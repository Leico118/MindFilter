package com.mindfilter.app.service

import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs

/**
 * Suivi en direct des publications masquées pendant le défilement.
 *
 * Android ne signale un défilement aux services d'accessibilité qu'environ toutes les
 * 100 ms (en cumulant le déplacement) : en s'y fiant, les masques avancent par à-coups
 * et « flottent » derrière les publications. Ici, au lieu d'attendre ces signaux, on
 * redemande directement à Android la position de chaque publication masquée, à chaque
 * image de l'écran (jusqu'à 120 fois par seconde), tant que la page bouge.
 *
 * Seule la POSITION est relue (aucun texte), sur un fil séparé de l'analyse.
 */
class MaskTracker(
    private val frameMs: Long,
    private val screenHeight: () -> Int,
    /** Appelé (depuis ce fil) avec les nouvelles zones et les masques perdus. */
    private val onUpdate: (updates: List<Pair<String, Rect>>, lost: List<String>, at: Long) -> Unit,
) {
    class Tracked(val key: String, val node: AccessibilityNodeInfo, val clip: Rect, val start: Rect)

    private val thread = HandlerThread("mindfilter-track").also { it.start() }
    private val handler = Handler(thread.looper)

    @Volatile private var targets: List<Tracked> = emptyList()
    @Volatile private var activeUntil = 0L
    @Volatile private var running = false

    /** Dernière position connue de chaque publication (utilisé uniquement sur ce fil). */
    private val last = HashMap<String, Rect>()

    fun setTargets(list: List<Tracked>) {
        targets = list
        handler.post {
            val keys = list.mapTo(HashSet()) { it.key }
            last.keys.retainAll(keys)
            for (t in list) last[t.key] = Rect(t.start)
        }
    }

    /** La page bouge : suivi actif pendant encore 450 ms. */
    fun poke() {
        activeUntil = SystemClock.uptimeMillis() + 450
        if (!running && targets.isNotEmpty()) {
            running = true
            handler.post(tick)
        }
    }

    fun quit() {
        targets = emptyList()
        thread.quitSafely()
    }

    private val tick: Runnable = object : Runnable {
        override fun run() {
            val start = SystemClock.uptimeMillis()
            val list = targets
            if (start > activeUntil || list.isEmpty()) {
                running = false
                return
            }
            val h = screenHeight().coerceAtLeast(1)
            // On ne suit que les masques visibles ou tout proches de l'écran (au plus 8) :
            // chaque lecture sollicite l'appli, inutile de la ralentir pour des masques lointains.
            val margin = h / 2
            val near = list.filter { t ->
                val r = last[t.key] ?: t.start
                r.bottom > t.clip.top - margin && r.top < t.clip.bottom + margin
            }.take(8)

            val fresh = ArrayList<Triple<Tracked, Rect, Int>>(near.size) // élément, position, déplacement
            val lost = ArrayList<String>()
            val r = Rect()
            for (t in near) {
                val ok = try {
                    t.node.refresh()
                } catch (e: Exception) {
                    false
                }
                if (!ok) {
                    lost.add(t.key)
                    continue
                }
                t.node.getBoundsInScreen(r)
                if (r.isEmpty) continue
                val prev = last[t.key] ?: t.start
                // Les deux bords bougent ensemble lors d'un défilement ; si l'un reste au bord de
                // la liste (publication coupée), on prend le déplacement de l'autre.
                val dTop = r.top - prev.top
                val dBottom = r.bottom - prev.bottom
                val delta = if (abs(dTop) >= abs(dBottom)) dTop else dBottom
                // Saut impossible en une image : l'élément a été réutilisé par l'appli pour une
                // autre publication. On arrête de le suivre (l'analyse suivante corrigera).
                if (abs(dTop) > h / 2 && abs(dBottom) > h / 2) {
                    lost.add(t.key)
                    continue
                }
                fresh.add(Triple(t, Rect(r), delta))
            }
            // Contrôle de cohérence : dans une liste, toutes les publications bougent pareil.
            if (fresh.size >= 3) {
                val sorted = fresh.map { it.third }.sorted()
                val median = sorted[sorted.size / 2]
                val tolerance = maxOf(150, h / 4)
                fresh.removeAll { (t, _, d) ->
                    val bad = abs(d - median) > tolerance
                    if (bad) lost.add(t.key)
                    bad
                }
            }
            val updates = ArrayList<Pair<String, Rect>>(fresh.size)
            for ((t, bounds, _) in fresh) {
                last[t.key] = bounds
                updates.add(t.key to ScreenScanner.maskRect(bounds, t.clip))
            }
            if (updates.isNotEmpty() || lost.isNotEmpty()) onUpdate(updates, lost, start)

            val spent = SystemClock.uptimeMillis() - start
            handler.postDelayed(this, (frameMs - spent).coerceAtLeast(1L))
        }
    }
}
