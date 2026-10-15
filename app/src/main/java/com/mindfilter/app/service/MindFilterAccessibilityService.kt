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
                requestScan(60)
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> requestScan(30)
            else -> requestScan(110)
        }
    }

    private fun requestScan(delayMs: Long) {
        if (!::workerHandler.isInitialized) return
        if (scanPending) return
        scanPending = true
        val sinceLast = System.currentTimeMillis() - lastScanAt
        val wait = maxOf(delayMs, 70L - sinceLast).coerceAtLeast(0L)
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
            rootInActiveWindow
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
        val snapY = scrollAccumY.get()
        val snapX = scrollAccumX.get()
        val screen = Rect()
        root.getBoundsInScreen(screen)
        if (screen.isEmpty) {
            val dm = resources.displayMetrics
            screen.set(0, 0, dm.widthPixels, dm.heightPixels)
        }
        val snap = try {
            ScreenScanner.snapshot(root)
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
        val touchable = s.touchMode == TouchMode.PROTECT
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
                val created = overlay.render(placed, dark, touchable)
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
        main.post {
            if (::overlay.isInitialized) overlay.clear()
            ServiceStatus.currentlyMasked.value = 0
        }
    }

    // ------------------------------------------------------------------------------
    // Relais du défilement en direct (mode « Protégé »)
    // ------------------------------------------------------------------------------
    //
    // Un appui sur un masque est absorbé (la publication ne peut pas s'ouvrir). Dès que
    // le doigt GLISSE, les masques deviennent transparents aux gestes et le mouvement est
    // retransmis à l'application par petits morceaux successifs (« gestes continus »
    // d'Android), au fil du mouvement : la page défile sous le doigt, presque sans délai.

    private val scrollAccumY = AtomicInteger(0)
    private val scrollAccumX = AtomicInteger(0)

    private val dragRelay = object : DragRelay {
        override fun onDragStart(first: TouchPoint) = streamStart(first)
        override fun onDragMove(p: TouchPoint) = streamMove(p)
        override fun onDragEnd(last: TouchPoint) = streamEnd(last)
    }

    private var streamActive = false
    private var streamInFlight = false
    private var streamEnding = false
    private var streamFinishing = false
    private var streamLast: TouchPoint? = null
    private var streamStroke: GestureDescription.StrokeDescription? = null
    private val streamPending = ArrayList<TouchPoint>()

    private fun streamStart(first: TouchPoint) {
        if (streamActive) return
        streamActive = true
        streamEnding = false
        streamFinishing = false
        streamInFlight = false
        streamStroke = null
        streamLast = first
        streamPending.clear()
        replayingGesture = true
        overlay.setTouchable(false)
        // Laisse au système le temps d'appliquer la transparence des masques.
        main.postDelayed({ pumpStream() }, 16)
    }

    private fun streamMove(p: TouchPoint) {
        if (!streamActive) return
        streamPending.add(p)
        if (!streamInFlight) pumpStream()
    }

    private fun streamEnd(last: TouchPoint) {
        if (!streamActive) return
        streamPending.add(last)
        streamEnding = true
        if (!streamInFlight) pumpStream()
    }

    private fun pumpStream() {
        if (!streamActive || streamInFlight) return
        val from = streamLast ?: return
        if (streamPending.isEmpty()) {
            if (!streamEnding) return
            streamPending.add(TouchPoint(from.x, from.y + 1f, from.t + 8))
        }
        val dm = resources.displayMetrics
        val maxX = dm.widthPixels - 1f
        val maxY = dm.heightPixels - 1f
        val path = Path().apply { moveTo(from.x.coerceIn(0f, maxX), from.y.coerceIn(0f, maxY)) }
        var moved = false
        for (p in streamPending) {
            val x = p.x.coerceIn(0f, maxX)
            val y = p.y.coerceIn(0f, maxY)
            if (x != from.x || y != from.y) moved = true
            path.lineTo(x, y)
        }
        if (!moved) {
            if (!streamEnding) {
                streamPending.clear()
                return
            }
            path.lineTo(from.x.coerceIn(0f, maxX), (from.y + 1f).coerceIn(0f, maxY))
        }
        val to = streamPending.last()
        val duration = (to.t - from.t).coerceIn(8L, 250L)
        val willContinue = !streamEnding
        val stroke = try {
            streamStroke?.continueStroke(path, 0, duration, willContinue)
                ?: GestureDescription.StrokeDescription(path, 0, duration, willContinue)
        } catch (e: Exception) {
            finishStream()
            return
        }
        streamLast = to
        streamPending.clear()
        streamStroke = stroke
        streamFinishing = !willContinue
        streamInFlight = true
        val ok = try {
            dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    streamInFlight = false
                    if (streamFinishing) finishStream() else pumpStream()
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    streamInFlight = false
                    finishStream()
                }
            }, main)
        } catch (e: Exception) {
            false
        }
        if (!ok) {
            streamInFlight = false
            finishStream()
        }
    }

    private fun finishStream() {
        streamActive = false
        streamStroke = null
        streamPending.clear()
        main.postDelayed({
            replayingGesture = false
            if (::overlay.isInitialized) overlay.setTouchable(settings.touchMode == TouchMode.PROTECT)
            requestScan(0)
        }, 120)
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
        super.onDestroy()
    }
}
