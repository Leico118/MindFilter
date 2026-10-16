package com.mindfilter.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.res.Configuration
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import java.util.concurrent.atomic.AtomicInteger
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import com.mindfilter.app.data.AppSettings
import com.mindfilter.app.data.MaskStyle
import com.mindfilter.app.data.ServiceStatus
import com.mindfilter.app.data.SettingsState
import com.mindfilter.app.data.TaxonomyIndex
import com.mindfilter.app.data.TouchMode
import com.mindfilter.app.engine.KeywordClassifier
import com.mindfilter.app.overlay.MaskOverlayManager
import com.mindfilter.app.overlay.DragRelay
import com.mindfilter.app.overlay.TouchPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Cœur de MindFilter : lit le texte accessible des applications surveillées,
 * le compare aux thèmes choisis et pose des masques sur les publications détectées.
 *
 * - Les événements sont regroupés (anti-rebond) puis analysés sur un thread dédié.
 * - Rien n'est analysé hors des applications surveillées ni quand la protection est en pause.
 * - Aucun texte n'est stocké ni transmis.
 */
class MindFilterAccessibilityService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var worker: HandlerThread
    private lateinit var workerHandler: Handler
    private lateinit var overlay: MaskOverlayManager
    private lateinit var tracker: MaskTracker
    @Volatile private var lastMoveAt = 0L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile private var settings: SettingsState = AppSettings.state.value
    @Volatile private var classifier: KeywordClassifier? = null
    @Volatile private var scanPending = false
    @Volatile private var lastScanAt = 0L
    private var replayingGesture = false

    private val scanRunnable = Runnable { scan() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        AppSettings.init(this)
        worker = HandlerThread("mindfilter-scan").also { it.start() }
        workerHandler = Handler(worker.looper)
        overlay = MaskOverlayManager(this, dragRelay)
        tracker = MaskTracker(
            frameMs = frameIntervalMs(),
            screenHeight = { resources.displayMetrics.heightPixels },
        ) { updates, lost, at ->
            main.post { if (::overlay.isInitialized) overlay.applyTracked(updates, lost, at) }
        }

        // Après un redémarrage du téléphone, Android relance automatiquement ce service.
        // Si l'« activation automatique » est désactivée, la protection reste en pause.
        val boot = try {
            Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1)
        } catch (e: Exception) {
            -1
        }
        val previous = AppSettings.swapBootCount(boot)
        if (previous != Int.MIN_VALUE && previous != boot && !AppSettings.state.value.autoActivation) {
            AppSettings.setProtection(false)
        }

        scope.launch {
            AppSettings.state.collect { s ->
                val rebuild = classifier == null ||
                    s.enabledThemes != settings.enabledThemes ||
                    s.customFilters != settings.customFilters ||
                    s.restrictionLevel != settings.restrictionLevel ||
                    s.exceptions != settings.exceptions
                val filterChanged = s.monitoredPackages != settings.monitoredPackages ||
                    s.protectionEnabled != settings.protectionEnabled
                settings = s
                if (filterChanged || !packageFilterApplied) applyPackageFilter(s)
                if (rebuild) {
                    workerHandler.post {
                        val themes = s.enabledThemes.mapNotNull { TaxonomyIndex.themesById[it] }
                        classifier = KeywordClassifier(themes, s.customFilters, s.restrictionLevel, s.exceptions)
                        requestScan(0)
                    }
                } else {
                    requestScan(0)
                }
                if (!s.protectionEnabled) clearMasks()
            }
        }
        ServiceStatus.connected.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (!::overlay.isInitialized) return
        val s = settings
        if (!s.protectionEnabled) return
        // Normalement Android ne nous envoie déjà que les événements des applis cochées
        // (voir applyPackageFilter) ; ce test est une sécurité supplémentaire.
        val evPkg = event.packageName?.toString() ?: return
        if (evPkg !in s.monitoredPackages) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    // Suivi immédiat du défilement. Le décalage est aussi cumulé pour corriger
                    // les analyses faites pendant le mouvement (sinon les masques « reculent »).
                    val dy = event.scrollDeltaY
                    val dx = event.scrollDeltaX
                    if (dy != -1 && dy != 0 && kotlin.math.abs(dy) < 4000) {
                        scrollAccumY.addAndGet(dy)
                        if (overlay.count > 0) overlay.offsetAll(0, -dy)
                    } else if (dx != -1 && dx != 0 && kotlin.math.abs(dx) < 4000) {
                        scrollAccumX.addAndGet(dx)
                        if (overlay.count > 0) overlay.offsetAll(-dx, 0)
                    }
                }
                lastMoveAt = android.os.SystemClock.uptimeMillis()
                if (overlay.count > 0) tracker.poke()
                requestScan(0)
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED, AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> onClickEvent(event)
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                onWindowChanged()
                requestScan(0)
            }
            else -> {
                if (overlay.count > 0) tracker.poke()
                requestScan(16)
            }
        }
    }

    private fun requestScan(delayMs: Long) {
        if (!::workerHandler.isInitialized) return
        if (scanPending) return
        scanPending = true
        val sinceLast = System.currentTimeMillis() - lastScanAt
        // Pas plus d'une analyse toutes les ~33 ms (30 par seconde), mais la première
        // part immédiatement : un contenu qui apparaît est analysé sans attendre.
        val wait = maxOf(delayMs, MIN_INTERVAL_MS - sinceLast).coerceAtLeast(0L)
        workerHandler.postDelayed(scanRunnable, wait)
    }

    /** Exécuté sur le thread d'analyse. */
    private fun scan() {
        scanPending = false
        lastScanAt = System.currentTimeMillis()
        val s = settings
        val c = classifier
        if (!s.protectionEnabled || c == null || replayingGesture) return

        val root = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                getRootInActiveWindow(android.view.accessibility.AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_DEPTH_FIRST)
            } else {
                rootInActiveWindow
            }
        } catch (e: Exception) {
            null
        }
        val pkg = root?.packageName?.toString()
        if (root == null || pkg == null || pkg == packageName || pkg !in s.monitoredPackages) {
            clearMasks()
            return
        }
        if (c.isEmpty) {
            clearMasks()
            return
        }
        // Position du défilement au moment où l'écran est lu.
        val snapAt = android.os.SystemClock.uptimeMillis()
        val snapY = scrollAccumY.get()
        val snapX = scrollAccumX.get()
        val screen = Rect()
        root.getBoundsInScreen(screen)
        if (screen.isEmpty) {
            val dm = resources.displayMetrics
            screen.set(0, 0, dm.widthPixels, dm.heightPixels)
        }
        val snap = try {
            ScreenScanner.snapshot(root, screen)
        } catch (e: Exception) {
            null
        } ?: return

        val maxHeight = (screen.height() * 0.78f).toInt()
        val targets = try {
            ScreenScanner.findTargets(snap, c, screen, maxHeight)
        } catch (e: Exception) {
            emptyList()
        }
        val dark = when (s.maskStyle) {
            MaskStyle.DARK -> true
            MaskStyle.LIGHT -> false
            MaskStyle.AUTO -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        }
        main.post {
            if (!settings.protectionEnabled) {
                overlay.clear()
            } else if (!replayingGesture) {
                // Si la page a encore défilé pendant l'analyse, on décale d'autant les
                // nouvelles positions : les masques restent collés aux publications.
                val lateY = scrollAccumY.get() - snapY
                val lateX = scrollAccumX.get() - snapX
                val placed = if (lateX == 0 && lateY == 0) targets else targets.map { t ->
                    t.copy(rect = Rect(t.rect).apply { offset(-lateX, -lateY) })
                }
                val created = overlay.render(placed, dark, snapAt)
                // Les publications masquées sont ensuite suivies en direct pendant le défilement.
                tracker.setTargets(
                    placed.mapNotNull { t ->
                        val n = t.node ?: return@mapNotNull null
                        val start = Rect().also { n.getBoundsInScreen(it) }
                        MaskTracker.Tracked(t.key, n, t.clip, start)
                    },
                )
                // La page est en train de défiler : le suivi démarre tout de suite.
                if (android.os.SystemClock.uptimeMillis() - lastMoveAt < 300) tracker.poke()
                if (created > 0) ServiceStatus.maskedThisSession.value += created
                if (overlay.count > 0) startExitWatch()
            }
            ServiceStatus.currentlyMasked.value = overlay.count
        }
    }

    @Volatile private var packageFilterApplied = false
    @Volatile private var exitWatchRunning = false

    /**
     * Demande à Android de ne réveiller MindFilter QUE pour les applications cochées.
     * Dans toute autre appli (WhatsApp, jeux, réglages…), le service ne reçoit plus
     * aucun événement et reste totalement inactif. Protection en pause : on ne garde
     * que notre propre appli, donc aucun réveil non plus.
     */
    private fun applyPackageFilter(s: SettingsState) {
        val info = serviceInfo ?: return
        val pkgs = if (s.protectionEnabled) s.monitoredPackages + packageName else setOf(packageName)
        info.packageNames = pkgs.toTypedArray()
        try {
            serviceInfo = info
            packageFilterApplied = true
        } catch (_: Exception) {
        }
    }

    /**
     * Tant que des masques sont affichés, vérifie toutes les 400 ms que l'appli
     * surveillée est toujours au premier plan (Android ne nous prévient pas quand on
     * passe à une appli non surveillée). S'arrête dès qu'il n'y a plus de masque :
     * aucun coût le reste du temps.
     */
    private fun startExitWatch() {
        if (exitWatchRunning || !::workerHandler.isInitialized) return
        exitWatchRunning = true
        workerHandler.postDelayed(exitWatch, 400)
    }

    private val exitWatch: Runnable = object : Runnable {
        override fun run() {
            if (!this@MindFilterAccessibilityService::overlay.isInitialized || ServiceStatus.currentlyMasked.value == 0) {
                exitWatchRunning = false
                return
            }
            val pkg = try {
                rootInActiveWindow?.packageName?.toString()
            } catch (e: Exception) {
                null
            }
            val s = settings
            if (!s.protectionEnabled || pkg == null || pkg !in s.monitoredPackages) {
                exitWatchRunning = false
                clearMasks()
            } else {
                workerHandler.postDelayed(this, 400)
            }
        }
    }

    private fun clearMasks() {
        if (::tracker.isInitialized) tracker.setTargets(emptyList())
        main.post {
            if (::overlay.isInitialized) overlay.clear()
            ServiceStatus.currentlyMasked.value = 0
        }
    }

    // ------------------------------------------------------------------------------
    // Défilement natif + protection contre les appuis (mode « Protégé »)
    // ------------------------------------------------------------------------------
    //
    // Android annule tout geste envoyé par un service d'accessibilité dès que le doigt
    // touche l'écran : impossible de relayer un glissement en direct. Les masques laissent
    // donc passer TOUS les gestes (défilement 100 % natif), et en mode « Protégé », si un
    // appui atteint une publication masquée et ouvre un nouvel écran, MindFilter le referme
    // aussitôt (retour arrière).

    private val scrollAccumY = AtomicInteger(0)
    private val scrollAccumX = AtomicInteger(0)

    /** Aucun relais : les masques sont traversables. */
    private val dragRelay = object : DragRelay {
        override fun onDragStart(first: TouchPoint) {}
        override fun onDragMove(p: TouchPoint) {}
        override fun onDragEnd(last: TouchPoint) {}
    }

    /** Jusqu'à quand un nouvel écran ouvert doit être refermé (appui sur un masque). */
    private var guardUntil = 0L

    private fun onClickEvent(event: AccessibilityEvent) {
        if (settings.touchMode != TouchMode.PROTECT || overlay.count == 0) return
        val src = try {
            event.source
        } catch (e: Exception) {
            null
        } ?: return
        val r = Rect()
        src.getBoundsInScreen(r)
        if (r.isEmpty) return
        val inside = overlay.hits(r.centerX(), r.centerY()) ||
            (overlay.hits(r.left + r.width() / 4, r.top + r.height() / 4) &&
                overlay.hits(r.right - r.width() / 4, r.bottom - r.height() / 4))
        if (inside) guardUntil = System.currentTimeMillis() + 1500
    }

    private fun onWindowChanged() {
        if (guardUntil != 0L && System.currentTimeMillis() <= guardUntil) {
            guardUntil = 0L
            // La publication masquée a été ouverte par un appui : on revient en arrière.
            performGlobalAction(GLOBAL_ACTION_BACK)
        } else {
            guardUntil = 0L
        }
    }

    /** Durée d'une image de l'écran (6 à 16 ms selon la fréquence : 60, 90, 120, 165 Hz…). */
    @Suppress("DEPRECATION")
    private fun frameIntervalMs(): Long {
        val hz = try {
            val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
            wm.defaultDisplay.refreshRate
        } catch (e: Exception) {
            60f
        }
        // Au plus 120 lectures par seconde : au-delà, on charge l'appli sans gain visible.
        return (1000f / hz.coerceIn(30f, 120f)).toLong().coerceIn(8L, 33L)
    }

    private companion object {
        const val MIN_INTERVAL_MS = 33L
    }

    override fun onInterrupt() {
        clearMasks()
    }

    override fun onDestroy() {
        ServiceStatus.connected.value = false
        ServiceStatus.currentlyMasked.value = 0
        scope.cancel()
        if (::overlay.isInitialized) overlay.clear()
        if (::worker.isInitialized) worker.quitSafely()
        if (::tracker.isInitialized) tracker.quit()
        super.onDestroy()
    }
}
