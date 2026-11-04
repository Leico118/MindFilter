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
        // Option « Analyse des images » : marque les images déjà reconnues et liste les nouvelles.
        val vision = visionFor(s)
        val images = if (vision != null) {
            try {
                ScreenScanner.markImages(
                    snap, screen, maxHeight,
                    lookup = { vision.labelFor(it) },
                    precaution = { s.imagePrecaution && vision.precautionFor(it) },
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
        if (lastRootWindow != -1 && winId != lastRootWindow) main.post { armPageGuard() }
        lastRootWindow = winId
        checkPageGuard(targets, screen, winId)
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
            MaskStyle.AUTO, MaskStyle.GLASS, MaskStyle.COLOR -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        }
        val glass = s.maskStyle == MaskStyle.GLASS
        val hue = if (s.maskStyle == MaskStyle.COLOR) s.maskHue else null
        main.post { try { renderOnMain(targets, dark, glass, hue, snapAt, snapX, snapY) } catch (e: Throwable) { com.mindfilter.app.CrashLog.record(this, e, "affichage des masques") } }
    }

    private fun renderOnMain(
        targets: List<MaskTarget>,
        dark: Boolean,
        glass: Boolean,
        hue: Float?,
        snapAt: Long,
        snapX: Int,
        snapY: Int,
    ) {
        run {
            // Verre fait maison (Android 14+ : capture de l'appli seule, sans nos masques).
            val fake = glass && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
            overlay.setFakeGlass(fake)
            if (fake && targets.isNotEmpty()) startGlassSampler()
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

    // --- Verre fait maison ------------------------------------------------------------------
    private var glassSampling = false

    private fun startGlassSampler() {
        if (glassSampling) return
        glassSampling = true
        main.postDelayed(glassTick, 150)
    }

    /**
     * Environ une fois par seconde, quand la page est immobile : capture de l'appli seule
     * (jamais nos masques), réduite aussitôt en taches de couleur pour chaque masque « Verre ».
     * La capture reste en mémoire et est effacée tout de suite.
     */
    private val glassTick: Runnable = object : Runnable {
        override fun run() {
            val self: Runnable = this
            val s = settings
            if (s.maskStyle != MaskStyle.GLASS || !s.protectionEnabled || overlay.count == 0 ||
                Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE
            ) {
                glassSampling = false
                return
            }
            if (android.os.SystemClock.uptimeMillis() - lastMoveAt < 350 || replayingGesture) {
                main.postDelayed(this, 300)
                return
            }
            val root = try { rootInActiveWindow } catch (_: Exception) { null }
            if (root == null) {
                main.postDelayed(this, 1000)
                return
            }
            val origin = Rect().also { root.getBoundsInScreen(it) }
            try {
                takeScreenshotOfWindow(
                    root.windowId,
                    mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(result: ScreenshotResult) {
                            val hb = result.hardwareBuffer
                            try {
                                val wrapped = android.graphics.Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                                val soft = wrapped?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                                wrapped?.recycle()
                                if (soft != null) {
                                    overlay.applyBackdrop(soft, origin.left, origin.top)
                                    soft.recycle()
                                }
                            } catch (_: Throwable) {
                            } finally {
                                hb.close()
                            }
                            main.postDelayed(self, 1100)
                        }

                        override fun onFailure(errorCode: Int) {
                            main.postDelayed(self, 1500)
                        }
                    },
                )
            } catch (_: Throwable) {
                main.postDelayed(this, 2000)
            }
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

    // --- 2e protection : nouvel écran dont le contenu principal est masqué ----------------
    //
    // Toutes les applis ne signalent pas les appuis (Facebook, souvent). Si, juste après un
    // changement d'écran alors que des masques étaient affichés, le nouvel écran est surtout
    // fait de contenus masqués JAMAIS vus jusque-là, c'est qu'un contenu masqué vient d'être
    // ouvert : retour arrière. Un retour vers un écran déjà vu (contenus connus) ou une
    // recherche tapée au clavier ne déclenchent rien.

    @Volatile private var lastTypingAt = 0L
    @Volatile private var lastRootWindow = -1
    @Volatile private var pageGuardAt = 0L
    @Volatile private var lastAutoBack = 0L
    /** Masques affichés récemment (clé → moment) : pour reconnaître un écran déjà vu. */
    private val seenMaskKeys = object : LinkedHashMap<String, Long>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 400
    }
    /** Première apparition de chaque masque (même verrou que [seenMaskKeys]). */
    private val maskFirstSeen = object : LinkedHashMap<String, Long>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 400
    }
    @Volatile private var keysBeforeChange: Set<String> = emptySet()
    @Volatile private var textsJustBefore: Set<String> = emptySet()

    /** Appelé sur le fil principal quand l'écran change. */
    private fun armPageGuard() {
        // En mode « Bloqué », les appuis sur les masques sont déjà impossibles : cette
        // protection (moins précise) n'est utile qu'en mode « Protégé ».
        if (settings.touchMode != TouchMode.PROTECT) return
        val now = System.currentTimeMillis()
        if (overlay.count == 0 || now - lastTypingAt < 4000 || now - lastAutoBack < 3000) return
        // Seuls comptent les masques apparus AVANT le changement d'écran (plus de 400 ms) : la
        // nouvelle page a parfois déjà été lue quand Android signale le changement.
        val (before, justBefore) = synchronized(seenMaskKeys) {
            val old = seenMaskKeys.keys.filter { (maskFirstSeen[it] ?: now) < now - 400 }
            old.toHashSet() to old.filter { now - (seenMaskKeys[it] ?: 0L) < 5000 }.mapTo(HashSet()) { textPart(it) }
        }
        keysBeforeChange = before
        // Contenus masqués visibles juste avant le changement d'écran (les 5 dernières secondes).
        textsJustBefore = justBefore
        pageGuardAt = now
    }

    /** Appelé sur le fil d'analyse, après chaque lecture de l'écran. */
    /** Moment où chaque fenêtre (écran d'une appli) a été vue pour la première fois. */
    private val windowFirstSeen = object : LinkedHashMap<Int, Long>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Long>?) = size > 60
    }

    /** Partie « texte » de la clé d'un masque (identique pour le même contenu ailleurs). */
    private fun textPart(key: String) = key.substringAfter('|').substringBefore('#')

    private fun checkPageGuard(targets: List<MaskTarget>, screen: Rect, winId: Int) {
        val visible = targets.filter { it.kind != MaskTarget.KIND_PENDING && Rect.intersects(it.rect, it.clip) }
        val now = System.currentTimeMillis()
        val armedAt = pageGuardAt
        if (armedAt != 0L) {
            if (now - armedAt > 2500 || now - lastTypingAt < 4000) {
                pageGuardAt = 0L
            } else if (visible.isNotEmpty()) {
                val area = visible.sumOf { t ->
                    val r = Rect(t.rect)
                    if (r.intersect(t.clip)) r.width().toLong() * r.height() else 0L
                }
                val screenArea = (screen.width().toLong() * screen.height()).coerceAtLeast(1L)
                val allNew = visible.none { it.key in keysBeforeChange }
                // Nouvel écran (jamais vu avant l'appui) qui montre un contenu masqué juste avant
                // (même texte) : c'est ce contenu qui vient d'être ouvert. Un retour vers un écran
                // déjà vu (bouton retour) ne déclenche rien.
                val firstSeen = synchronized(windowFirstSeen) { windowFirstSeen[winId] } ?: now
                val newWindow = firstSeen >= armedAt - 500
                val sameContent = newWindow && visible.any { it.kind == MaskTarget.KIND_TEXT && textPart(it.key) in textsJustBefore }
                if (sameContent || (allNew && area * 4 >= screenArea)) {
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
