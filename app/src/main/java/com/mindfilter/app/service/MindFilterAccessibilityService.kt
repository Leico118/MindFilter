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
        overlay = MaskOverlayManager(this) { pts -> replayDrag(pts) }

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
                    s.sensitivity != settings.sensitivity
                settings = s
                if (rebuild) {
                    workerHandler.post {
                        val themes = s.enabledThemes.mapNotNull { TaxonomyIndex.themesById[it] }
                        classifier = KeywordClassifier(themes, s.customFilters, s.sensitivity)
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
        if (event.packageName?.toString() == packageName) {
            // Ouverture de MindFilter : on retire les masques. Les autres événements
            // émis par nos propres masques sont ignorés.
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) requestScan(0)
            return
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                val pkg = event.packageName?.toString()
                if (pkg != null && pkg in s.monitoredPackages && overlay.count > 0 &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ) {
                    // Suivi immédiat du défilement, la position exacte est recalculée juste après.
                    val dy = event.scrollDeltaY
                    val dx = event.scrollDeltaX
                    if (dy != -1 && dy != 0 && kotlin.math.abs(dy) < 4000) overlay.offsetAll(0, -dy)
                    else if (dx != -1 && dx != 0 && kotlin.math.abs(dx) < 4000) overlay.offsetAll(-dx, 0)
                }
                requestScan(50)
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
                val created = overlay.render(targets, dark, touchable)
                if (created > 0) ServiceStatus.maskedThisSession.value += created
            }
            ServiceStatus.currentlyMasked.value = overlay.count
        }
    }

    private fun clearMasks() {
        main.post {
            if (::overlay.isInitialized) overlay.clear()
            ServiceStatus.currentlyMasked.value = 0
        }
    }

    /**
     * Rejoue sur l'application un glissement effectué sur un masque : les masques
     * deviennent transparents aux gestes le temps du geste, puis sont rétablis.
     * Un simple appui n'est jamais transmis.
     */
    private fun replayDrag(points: List<TouchPoint>) {
        if (points.size < 2 || replayingGesture) return
        val dm = resources.displayMetrics
        val maxX = dm.widthPixels - 1f
        val maxY = dm.heightPixels - 1f
        val path = Path()
        // Sous-échantillonnage : 40 points suffisent pour un tracé fidèle.
        val step = maxOf(1, points.size / 40)
        points.forEachIndexed { i, p ->
            if (i == 0 || i == points.lastIndex || i % step == 0) {
                val x = p.x.coerceIn(0f, maxX)
                val y = p.y.coerceIn(0f, maxY)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
        }
        val maxDuration = GestureDescription.getMaxGestureDuration()
        val duration = (points.last().t - points.first().t).coerceIn(40L, minOf(2500L, maxDuration))
        val gesture = try {
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
                .build()
        } catch (e: Exception) {
            return
        }
        replayingGesture = true
        overlay.setTouchable(false)
        main.postDelayed({
            val ok = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) = restoreTouch()
                override fun onCancelled(gestureDescription: GestureDescription?) = restoreTouch()
            }, main)
            if (!ok) restoreTouch()
        }, 24)
    }

    private fun restoreTouch() {
        main.postDelayed({
            replayingGesture = false
            if (::overlay.isInitialized) overlay.setTouchable(settings.touchMode == TouchMode.PROTECT)
            requestScan(0)
        }, 140)
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
