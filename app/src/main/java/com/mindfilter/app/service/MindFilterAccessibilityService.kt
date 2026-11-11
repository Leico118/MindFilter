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
import android.view.accessibility.AccessibilityWindowInfo
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

    private val scanRunnable = Runnable {
        try {
            scan()
        } catch (e: Throwable) {
            scanPending = false
            com.mindfilter.app.CrashLog.record(this, e, "lecture de l'écran")
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        AppSettings.init(this)
        worker = HandlerThread("mindfilter-scan").also { it.start() }
        workerHandler = Handler(worker.looper)
        overlay = MaskOverlayManager(this, dragRelay)
        overlay.onShieldSwipe = { pts ->
            try { replaySwipe(pts) } catch (e: Throwable) { com.mindfilter.app.CrashLog.record(this, e, "défilement sur un masque") }
        }
        overlay.setShieldsEnabled(settings.touchMode == TouchMode.BLOCK)
        tracker = MaskTracker(
            frameMs = frameIntervalMs(),
            screenHeight = { resources.displayMetrics.heightPixels },
        ) { updates, lost, at ->
            main.post {
                try {
                    if (::overlay.isInitialized) overlay.applyTracked(updates, lost, at)
                } catch (e: Throwable) {
                    com.mindfilter.app.CrashLog.record(this, e, "suivi des masques")
                }
            }
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
                main.post { if (::overlay.isInitialized) overlay.setShieldsEnabled(s.touchMode == TouchMode.BLOCK) }
                if (filterChanged || !packageFilterApplied) applyPackageFilter(s)
                if (rebuild) {
                    // Les thèmes ont changé : les images déjà jugées doivent l'être à nouveau.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        (analyzer as? com.mindfilter.app.vision.ImageAnalyzer)?.reset()
                    }
                    workerHandler.post {
                        val themes = s.enabledThemes.mapNotNull { TaxonomyIndex.themesById[it] }
                        val v = TaxonomyIndex.vetoes(s.enabledThemes)
                        classifier = KeywordClassifier(themes, s.customFilters, s.restrictionLevel, s.exceptions, v.deselected, v.inactive)
                        requestScan(0)
                    }
                } else {
                    requestScan(0)
                }
                if (!s.protectionEnabled) clearMasks()
            }
        }
        ServiceStatus.connected.value = true
        // Vérifie de temps en temps s'il existe une nouvelle version (au plus toutes les 6 h).
        workerHandler.postDelayed(updateCheck, 30_000)
    }

    private val updateCheck: Runnable = object : Runnable {
        override fun run() {
            try {
                com.mindfilter.app.update.UpdateNotifier.checkIfDue(this@MindFilterAccessibilityService)
            } catch (_: Exception) {
            }
            if (::workerHandler.isInitialized) workerHandler.postDelayed(this, 60L * 60 * 1000)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Une erreur ici ne doit jamais arrêter le service (Android le couperait) : elle est notée.
        try {
            handleEvent(event)
        } catch (e: Throwable) {
            com.mindfilter.app.CrashLog.record(this, e, "événement")
        }
    }

    private fun handleEvent(event: AccessibilityEvent?) {
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
                // Appui récent sur un masque : un défilement l'annule (sauf si l'écran a changé).
                if (guardUntil != 0L && !checkGuard()) clearGuard()
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
                armPageGuard()
                requestScan(0)
            }
            // Saisie au clavier : seul le moment compte (le texte tapé n'est jamais lu).
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val editable = try { event.source?.isEditable == true } catch (e: Exception) { false }
                if (editable) lastTypingAt = System.currentTimeMillis()
                requestScan(16)
            }
            else -> {
                // Certaines applis (Facebook…) changent d'écran sans le signaler comme une
                // nouvelle fenêtre : on vérifie si l'élément touché a disparu.
                if (guardUntil != 0L) checkGuard()
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
        if (root == null || pkg == null || pkg == packageName || pkg !in s.monitoredPackages || !foregroundIsMonitored(root, s)) {
            enterStandby()
            return
        }
        foregroundOk = true
        val epoch = standbyEpoch
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
        // Option « Analyse des images » : marque les images déjà reconnues et liste les nouvelles.
        val vision = visionFor(s)
        val images = if (vision != null) {
            try {
                ScreenScanner.markImages(
                    snap, screen, maxHeight,
                    lookup = { vision.labelFor(it) },
                    // Plus aucune image n'est cachée « par précaution » pendant son analyse : seules
                    // les images reconnues sont masquées (les masques « Analyse… » gênaient trop).
                    precaution = { false },
                )
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
        val targets = try {
            ScreenScanner.findTargets(snap, c, screen, maxHeight)
        } catch (e: Exception) {
            emptyList()
        }
        // Changement d'écran que l'appli n'a pas signalé (Facebook…) : nouvelle fenêtre.
        val winId = root.windowId
        synchronized(windowFirstSeen) { if (!windowFirstSeen.containsKey(winId)) windowFirstSeen[winId] = System.currentTimeMillis() }
        val windowChanged = lastRootWindow != -1 && winId != lastRootWindow
        lastRootWindow = winId
        checkPageGuard(targets, screen, winId, windowChanged)
        // Diagnostic demandé dans « Pourquoi c'est masqué ? » : structure de cet écran (sans texte).
        if (ScreenDump.wanted()) {
            ScreenDump.requestedUntil = 0L
            val dump = ScreenDump.build(snap, targets, c, pkg)
            val saved = ScreenDump.save(this, dump)
            main.post {
                android.widget.Toast.makeText(
                    this,
                    if (saved != null) "MindFilter : structure enregistrée dans Téléchargements ($saved)" else "MindFilter : enregistrement impossible",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }
        // Journal « Pourquoi c'est masqué ? » : thème et mots repérés seulement (pas le texte).
        MaskLog.currentApp = pkg
        for (t in targets) {
            if (t.kind != MaskTarget.KIND_TEXT) continue
            if (!Rect.intersects(t.rect, t.clip)) continue
            MaskLog.logText(t.key, t.label, t.words)
        }
        if (vision != null) com.mindfilter.app.vision.VisionStats.candidates = images.size
        if (vision != null && images.isNotEmpty()) {
            vision.classifier = c
            vision.enabledThemes = s.enabledThemes
            vision.restrictionLevel = s.restrictionLevel
            vision.maskedAreas = targets.map { t -> Rect(t.rect).also { r -> if (!r.intersect(t.clip)) r.setEmpty() } }
            vision.request(images.map { com.mindfilter.app.vision.ImageAnalyzer.Candidate(it.key, it.node, it.bounds, it.zone, it.video) })
        }
        val dark = when (s.maskStyle) {
            MaskStyle.DARK -> true
            MaskStyle.LIGHT -> false
            MaskStyle.AUTO, MaskStyle.COLOR -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        }
        val hue = if (s.maskStyle == MaskStyle.COLOR) s.maskHue else null
        main.post {
            // L'utilisateur a quitté l'appli pendant l'analyse : ce résultat n'est plus affiché.
            if (epoch != standbyEpoch || !foregroundOk) return@post
            try { renderOnMain(targets, dark, hue, snapAt, snapX, snapY) } catch (e: Throwable) { com.mindfilter.app.CrashLog.record(this, e, "affichage des masques") }
        }
    }

    // ------------------------------------------------------------------------------
    // Veille hors des applis surveillées
    // ------------------------------------------------------------------------------
    //
    // MindFilter ne travaille QUE lorsqu'une appli cochée est réellement au premier plan :
    //  - l'appli active (celle que l'on touche) doit être cochée ;
    //  - aucune autre appli non cochée ne doit la recouvrir en partie (Gemini, une bulle de
    //    discussion, le volet des notifications, une fenêtre flottante…).
    // Sinon : veille complète, tous les masques sont retirés, aucune analyse n'est faite.

    /** Appli cochée au premier plan lors de la dernière vérification. */
    @Volatile private var foregroundOk = false
    /** Change à chaque mise en veille : les résultats d'analyse plus anciens sont ignorés. */
    @Volatile private var standbyEpoch = 0
    @Volatile private var lastWindowsCheckAt = 0L
    @Volatile private var lastWindowsVerdict = true
    /** Appli de chaque fenêtre (le numéro d'une fenêtre ne change pas tant qu'elle existe). */
    private val windowPackages = object : LinkedHashMap<Int, String>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, String>?) = size > 40
    }

    private fun enterStandby() {
        if (foregroundOk || ServiceStatus.currentlyMasked.value != 0) standbyEpoch++
        foregroundOk = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            (analyzer as? com.mindfilter.app.vision.ImageAnalyzer)?.cancelPending()
        }
        clearMasks()
    }

    private fun windowPackage(w: AccessibilityWindowInfo): String? {
        synchronized(windowPackages) { windowPackages[w.id] }?.let { return it }
        val p = try { w.root?.packageName?.toString() } catch (_: Exception) { null } ?: return null
        synchronized(windowPackages) { windowPackages[w.id] = p }
        return p
    }

    /**
     * L'appli cochée [root] est-elle vraiment visible, sans appli non cochée par-dessus ?
     * Vérifié au plus toutes les 90 ms (la liste des fenêtres demande un échange avec Android).
     */
    private fun foregroundIsMonitored(root: android.view.accessibility.AccessibilityNodeInfo, s: SettingsState, force: Boolean = false): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        if (!force && now - lastWindowsCheckAt < 90) return lastWindowsVerdict
        lastWindowsCheckAt = now
        val list = try { windows } catch (_: Exception) { null }
        if (list.isNullOrEmpty()) {
            lastWindowsVerdict = true
            return true
        }
        val mine = list.firstOrNull { it.id == root.windowId }
        if (mine == null) {
            lastWindowsVerdict = true
            return true
        }
        val area = Rect().also { mine.getBoundsInScreen(it) }
        val full = area.width().toLong() * area.height()
        if (full <= 0L) {
            lastWindowsVerdict = true
            return true
        }
        var ok = true
        for (w in list) {
            if (w.id == mine.id || w.layer <= mine.layer) continue
            val type = w.type
            if (type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY ||
                type == AccessibilityWindowInfo.TYPE_INPUT_METHOD ||
                type == AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && type == AccessibilityWindowInfo.TYPE_MAGNIFICATION_OVERLAY)
            ) continue
            val r = Rect().also { w.getBoundsInScreen(it) }
            if (!r.intersect(area)) continue
            val covered = r.width().toLong() * r.height()
            // Barre d'état, barre de navigation, petites bulles : ignorées. Au-delà, si la fenêtre
            // appartient à une appli non cochée (Gemini, Messenger…), MindFilter se met en veille.
            val app = type == AccessibilityWindowInfo.TYPE_APPLICATION
            if (covered < if (app) full / 6 else full / 3) continue
            val p = windowPackage(w)
            if (p != null && p in s.monitoredPackages) continue
            // Fenêtre système (volet des notifications, assistant…) : seulement si l'utilisateur
            // s'en sert (active) ou si c'est l'interface système. Certains téléphones gardent des
            // fenêtres système invisibles par-dessus tout : elles ne doivent pas tout bloquer.
            if (!app && !(w.isActive || w.isFocused || p == "com.android.systemui")) continue
            ok = false
            break
        }
        lastWindowsVerdict = ok
        return ok
    }

    private fun renderOnMain(
        targets: List<MaskTarget>,
        dark: Boolean,
        hue: Float?,
        snapAt: Long,
        snapX: Int,
        snapY: Int,
    ) {
        run {
            overlay.setHue(hue)
            if (!settings.protectionEnabled) {
                overlay.clear()
            } else if (!replayingGesture) {
                // Si la page a encore défilé pendant l'analyse, on décale d'autant les
                // nouvelles positions : les masques restent collés aux publications.
                val lateY = scrollAccumY.get() - snapY
                val lateX = scrollAccumX.get() - snapX
                val placed = if (lateX == 0 && lateY == 0) targets else targets.map { t ->
                    t.copy(rect = Rect(t.rect).apply { ScreenScanner.shiftMask(this, -lateX, -lateY, t.clip) })
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

    @Volatile private var analyzer: Any? = null

    /** Analyseur d'images, créé à la demande si l'option est activée (Android 11 et plus). */
    private fun visionFor(s: SettingsState): com.mindfilter.app.vision.ImageAnalyzer? {
        if (!s.imageAnalysis || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                (analyzer as? com.mindfilter.app.vision.ImageAnalyzer)?.quit()
            }
            analyzer = null
            return null
        }
        (analyzer as? com.mindfilter.app.vision.ImageAnalyzer)?.let { return it }
        val a = com.mindfilter.app.vision.ImageAnalyzer(this) { requestScan(0) }
        analyzer = a
        return a
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
     * Tant que des masques sont affichés, vérifie toutes les 120 ms que l'appli
     * surveillée est toujours au premier plan (Android ne nous prévient pas quand on
     * passe à une appli non surveillée). S'arrête dès qu'il n'y a plus de masque :
     * aucun coût le reste du temps.
     */
    private fun startExitWatch() {
        if (exitWatchRunning || !::workerHandler.isInitialized) return
        exitWatchRunning = true
        workerHandler.postDelayed(exitWatch, EXIT_WATCH_MS)
    }

    private val exitWatch: Runnable = object : Runnable {
        override fun run() {
            if (!this@MindFilterAccessibilityService::overlay.isInitialized || ServiceStatus.currentlyMasked.value == 0) {
                exitWatchRunning = false
                return
            }
            val root = try {
                rootInActiveWindow
            } catch (e: Exception) {
                null
            }
            val pkg = root?.packageName?.toString()
            val s = settings
            if (!s.protectionEnabled || root == null || pkg == null || pkg !in s.monitoredPackages ||
                !foregroundIsMonitored(root, s, force = true)
            ) {
                exitWatchRunning = false
                enterStandby()
            } else {
                workerHandler.postDelayed(this, EXIT_WATCH_MS)
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
    /** Élément touché sous un masque, et sa fenêtre : s'il disparaît, un écran s'est ouvert. */
    private var guardNode: android.view.accessibility.AccessibilityNodeInfo? = null
    private var guardWindow = -1

    private fun onClickEvent(event: AccessibilityEvent) {
        if (settings.touchMode == TouchMode.PASSTHROUGH) return
        val src = try {
            event.source
        } catch (e: Exception) {
            null
        } ?: return
        val r = Rect()
        src.getBoundsInScreen(r)
        if (r.isEmpty) return
        // hitsRecent : compte aussi les masques retirés il y a un instant (la page peut déjà
        // avoir commencé à changer quand Android nous signale l'appui).
        val inside = overlay.hitsRecent(r.centerX(), r.centerY()) ||
            (overlay.hitsRecent(r.left + r.width() / 4, r.top + r.height() / 4) &&
                overlay.hitsRecent(r.right - r.width() / 4, r.bottom - r.height() / 4))
        if (!inside) return
        guardUntil = System.currentTimeMillis() + 1500
        guardNode = src
        guardWindow = src.windowId
        // L'appui a peut-être déjà ouvert l'écran : vérification immédiate et un peu après.
        main.postDelayed({ if (guardUntil != 0L) checkGuard() }, 250)
        main.postDelayed({ if (guardUntil != 0L) checkGuard() }, 700)
    }

    /**
     * L'élément touché sous un masque a-t-il disparu (nouvel écran ouvert) ? Si oui, retour
     * arrière immédiat. Renvoie true si le retour a été fait.
     */
    private fun checkGuard(): Boolean {
        if (guardUntil == 0L) return false
        if (System.currentTimeMillis() > guardUntil) {
            clearGuard()
            return false
        }
        val n = guardNode ?: return false
        val gone = try {
            !n.refresh() || !n.isVisibleToUser ||
                (rootInActiveWindow?.windowId?.let { it != guardWindow } ?: false)
        } catch (e: Exception) {
            true
        }
        if (!gone) return false
        clearGuard()
        autoBack()
        return true
    }

    // --- 2e protection : ouverture d'un contenu masqué, pour TOUTES les applis --------------
    //
    // Android ne permet pas à une appli de voir où le doigt touche l'écran sans bloquer le
    // toucher, et beaucoup d'applis (Facebook, Reddit…) ne signalent pas les appuis. On
    // reconnaît donc l'ouverture d'un contenu masqué à ce qui se passe ENSUITE à l'écran :
    //  1. la page change : nouvelle fenêtre, signal de changement d'écran, ou bien tous les
    //     masques visibles disparaissent d'un coup sans que la page ait défilé ;
    //  2. dans les 2,5 secondes, la nouvelle page affiche un NOUVEAU contenu masqué lié à ce
    //     qui était masqué juste avant (mêmes mots repérés, même texte, même type d'image), ou
    //     une nouvelle fenêtre surtout faite de contenus masqués jamais vus ;
    //  → retour arrière. Revenir sur une page déjà vue (bouton retour) ou taper une recherche au
    //  clavier ne déclenchent rien.

    @Volatile private var lastTypingAt = 0L
    @Volatile private var lastRootWindow = -1
    @Volatile private var pageGuardAt = 0L
    @Volatile private var lastAutoBack = 0L
    /** Masques affichés récemment (clé → dernier moment vu). */
    private val seenMaskKeys = object : LinkedHashMap<String, Long>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 400
    }
    /** Première apparition de chaque masque (même verrou que [seenMaskKeys]). */
    private val maskFirstSeen = object : LinkedHashMap<String, Long>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 400
    }
    /** « Signature » de chaque masque vu (mots repérés, texte, type d'image). */
    private val maskSigs = object : LinkedHashMap<String, Set<String>>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Set<String>>?) = size > 400
    }
    @Volatile private var keysBeforeChange: Set<String> = emptySet()
    @Volatile private var sigsJustBefore: Set<String> = emptySet()
    /** Masques visibles lors de la lecture précédente (fil d'analyse uniquement). */
    private var prevVisibleKeys: Set<String> = emptySet()

    /** Moment où chaque fenêtre (écran d'une appli) a été vue pour la première fois. */
    private val windowFirstSeen = object : LinkedHashMap<Int, Long>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Long>?) = size > 60
    }

    /** Partie « texte » de la clé d'un masque (identique pour le même contenu ailleurs). */
    private fun textPart(key: String) = key.substringAfter('|').substringBefore('#')

    private fun sigsOf(t: MaskTarget): Set<String> =
        if (t.kind == MaskTarget.KIND_TEXT) {
            t.words.mapTo(HashSet()) { "w:" + it.lowercase() }.apply { add("t:" + textPart(t.key)) }
        } else {
            setOf("i:" + t.label)
        }

    /** Signal « l'écran a changé » (événement d'Android, depuis le fil principal). */
    private fun armPageGuard() {
        armNow(System.currentTimeMillis(), requireMasks = overlay.count > 0)
    }

    /**
     * Prépare la vérification : on retient ce qui était masqué JUSTE AVANT le changement
     * (masques apparus depuis plus de 400 ms et vus dans les 4 dernières secondes).
     */
    private fun armNow(now: Long, requireMasks: Boolean) {
        if (settings.touchMode != TouchMode.PROTECT) return
        if (!requireMasks || now - lastTypingAt < 4000 || now - lastAutoBack < 3000) return
        synchronized(seenMaskKeys) {
            val old = seenMaskKeys.keys.filter { (maskFirstSeen[it] ?: now) < now - 400 }
            keysBeforeChange = old.toHashSet()
            sigsJustBefore = old.filter { now - (seenMaskKeys[it] ?: 0L) < 4000 }
                .flatMapTo(HashSet()) { maskSigs[it] ?: emptySet() }
        }
        pageGuardAt = now
    }

    /** Appelé sur le fil d'analyse, après chaque lecture de l'écran. */
    private fun checkPageGuard(targets: List<MaskTarget>, screen: Rect, winId: Int, windowChanged: Boolean) {
        val visible = targets.filter { it.kind != MaskTarget.KIND_PENDING && Rect.intersects(it.rect, it.clip) }
        val now = System.currentTimeMillis()
        val keysNow = visible.mapTo(HashSet()) { it.key }
        // Changement de page sans signal : tous les masques d'avant ont disparu d'un coup, alors
        // que la page n'a pas défilé (ce n'est donc pas un simple défilement).
        val still = android.os.SystemClock.uptimeMillis() - lastMoveAt > 700
        val vanished = prevVisibleKeys.isNotEmpty() && keysNow.none { it in prevVisibleKeys } && still
        // (seulement si des masques étaient visibles juste avant : ouvrir une appli ou une page
        // sans rien de masqué ne déclenche jamais rien)
        if (windowChanged || vanished) armNow(now, requireMasks = prevVisibleKeys.isNotEmpty())
        prevVisibleKeys = keysNow

        val armedAt = pageGuardAt
        if (armedAt != 0L) {
            if (now - armedAt > 2500 || now - lastTypingAt < 4000) {
                pageGuardAt = 0L
            } else if (visible.isNotEmpty()) {
                val firstSeen = synchronized(windowFirstSeen) { windowFirstSeen[winId] } ?: now
                val newWindow = firstSeen >= armedAt - 500
                val fresh = visible.filter { it.key !in keysBeforeChange }
                // Nouveau contenu masqué lié à ce qui était masqué juste avant : c'est lui qui
                // vient d'être ouvert.
                val related = fresh.any { t -> sigsOf(t).any { it in sigsJustBefore } }
                // Nouvelle fenêtre faite surtout de contenus masqués jamais vus.
                val area = visible.sumOf { t ->
                    val r = Rect(t.rect)
                    if (r.intersect(t.clip)) r.width().toLong() * r.height() else 0L
                }
                val screenArea = (screen.width().toLong() * screen.height()).coerceAtLeast(1L)
                val heavy = newWindow && fresh.size == visible.size && area * 4 >= screenArea
                if (related || heavy) {
                    pageGuardAt = 0L
                    main.post { autoBack(if (newWindow) winId else -1) }
                    return
                }
            }
        }
        synchronized(seenMaskKeys) {
            for (t in visible) {
                seenMaskKeys[t.key] = now
                if (!maskFirstSeen.containsKey(t.key)) maskFirstSeen[t.key] = now
                maskSigs[t.key] = sigsOf(t)
            }
        }
    }

    /**
     * Un seul retour arrière à la fois : les deux protections (appui signalé, nouvel écran
     * masqué) peuvent repérer la même ouverture. Avant, elles revenaient chacune en arrière et
     * on se retrouvait deux écrans plus loin (ex. sur le fil au lieu de la recherche).
     */
    private fun autoBack(leaveWindow: Int = -1) {
        val now = System.currentTimeMillis()
        if (now - lastAutoBack < 3000) return
        lastAutoBack = now
        pageGuardAt = 0L
        clearGuard()
        // Petit délai : un « retour » envoyé pendant que le nouvel écran s'ouvre encore est
        // parfois ignoré par Android. Puis vérification : si l'écran ouvert est toujours là, on
        // recommence une fois.
        // Vérification seulement pour une vraie nouvelle fenêtre (pas pour un changement de page
        // à l'intérieur de la même fenêtre, où un 2e retour irait trop loin).
        val target = leaveWindow
        main.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 120)
        main.postDelayed({
            val still = try { rootInActiveWindow?.windowId } catch (_: Exception) { null }
            if (target != -1 && still == target) performGlobalAction(GLOBAL_ACTION_BACK)
        }, 900)
    }

    /** Rejoue sur l'appli un glissement fait sur un bouclier (mode « Bloqué »). */
    private fun replaySwipe(pts: List<TouchPoint>) {
        if (pts.size < 2) return
        val first = pts.first()
        val last = pts.last()
        val dur = (last.t - first.t).coerceIn(40L, 1500L)
        val path = Path()
        path.moveTo(first.x.coerceAtLeast(0f), first.y.coerceAtLeast(0f))
        // Au plus ~24 points : le tracé garde sa forme et sa vitesse finale (lancer).
        val step = maxOf(1, pts.size / 24)
        for (i in step until pts.size - 1 step step) path.lineTo(pts[i].x.coerceAtLeast(0f), pts[i].y.coerceAtLeast(0f))
        path.lineTo(last.x.coerceAtLeast(0f), last.y.coerceAtLeast(0f))
        // Les boucliers laissent passer le geste rejoué le temps qu'il se fasse.
        overlay.suspendShields(dur + 250)
        val gesture = try {
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, dur)).build()
        } catch (e: Exception) {
            return
        }
        replayingGesture = true
        val done = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) = finish()
            override fun onCancelled(gestureDescription: GestureDescription?) = finish()
            private fun finish() {
                replayingGesture = false
                if (overlay.count > 0) tracker.poke()
                requestScan(0)
            }
        }
        if (!dispatchGesture(gesture, done, null)) replayingGesture = false
    }

    private fun clearGuard() {
        guardUntil = 0L
        guardNode = null
        guardWindow = -1
    }

    private fun onWindowChanged() {
        if (guardUntil != 0L && System.currentTimeMillis() <= guardUntil) {
            clearGuard()
            // La publication masquée a été ouverte par un appui : on revient en arrière.
            autoBack()
        } else {
            clearGuard()
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
        /** Tant que des masques sont affichés, vérification de l'appli au premier plan. */
        const val EXIT_WATCH_MS = 120L
    }

    /** Rotation de l'écran, changement de taille (écran partagé…) : les positions ne valent plus. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        clearMasks()
        requestScan(150)
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            (analyzer as? com.mindfilter.app.vision.ImageAnalyzer)?.quit()
        }
        super.onDestroy()
    }
}
