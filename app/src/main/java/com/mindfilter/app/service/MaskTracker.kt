package com.mindfilter.app.service

import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Suivi en direct des publications masquées pendant le défilement.
 *
 * Android ne signale un défilement aux services d'accessibilité qu'environ toutes les
 * 100 ms (en cumulant le déplacement) : en s'y fiant, les masques avancent par à-coups.
 * Ici, on lit nous-mêmes la position réelle des publications à chaque image de l'écran.
 *
 * Pour être le plus rapide possible :
 *  - à chaque image, on ne relit que 1 ou 2 publications « repères » par liste ; toutes les
 *    publications d'une même liste bougent ensemble, les autres masques suivent le même
 *    déplacement. Toutes les 4 images, on relit tout pour corriger les petits écarts ;
 *  - on mesure la vitesse du défilement et on ÉTIRE le masque dans le sens du mouvement
 *    d'autant que le contenu avance pendant le court délai de lecture et d'affichage :
 *    même pendant un défilement rapide, rien ne dépasse du masque. L'étirement disparaît
 *    dès que la page ralentit (aucun dépassement quand elle s'arrête).
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

    private class State(val raw: Rect, val mask: Rect)

    private class Motion {
        var vx = 0f // px par ms
        var vy = 0f
        var lastAt = 0L
        /** Étirement appliqué à l'image précédente (lissé pour éviter que le masque « pulse »). */
        var ex = 0f
        var ey = 0f
        /** Images consécutives où la lecture semblait aberrante (saut improbable). */
        var suspect = 0
    }

    private val thread = HandlerThread("mindfilter-track").also { it.start() }
    private val handler = Handler(thread.looper)

    @Volatile private var targets: List<Tracked> = emptyList()
    @Volatile private var activeUntil = 0L
    @Volatile private var running = false

    // Utilisés uniquement sur le fil de suivi.
    private val states = HashMap<String, State>()
    private val lostKeys = HashSet<String>()
    private val motions = HashMap<String, Motion>()
    private var tickCount = 0
    /** Une lecture douteuse a été écartée : tout relire à l'image suivante pour trancher. */
    private var forceFull = false

    fun setTargets(list: List<Tracked>) {
        targets = list
        handler.post {
            states.clear()
            lostKeys.clear()
            for (t in list) states[t.key] = State(Rect(t.start), ScreenScanner.maskRect(t.start, t.clip))
            val groups = list.mapTo(HashSet()) { clipKey(it.clip) }
            motions.keys.retainAll(groups)
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

    /** Étirement : augmente aussitôt (même sens), diminue progressivement. */
    private fun smoothStretch(current: Float, target: Float): Float {
        val grows = abs(target) >= abs(current) && (current == 0f || (target > 0f) == (current > 0f))
        val next = if (grows) target else current + (target - current) * 0.35f
        return if (abs(next) < 1f) 0f else next
    }

    private companion object {
        /** Déplacement (px) en dessous duquel on parle de micro-mouvement. */
        const val MICRO_PX = 2
        /** Vitesse (px/ms) en dessous de laquelle le masque n'est pas étiré. */
        const val MIN_STRETCH_SPEED = 0.25f
    }

    private fun clipKey(c: Rect) = "${c.left},${c.top},${c.right},${c.bottom}"

    private fun visibleArea(r: Rect, clip: Rect): Int {
        val v = Rect(r)
        return if (v.intersect(clip)) v.width() * v.height() else 0
    }

    private val tick: Runnable = object : Runnable {
        override fun run() {
            val start = SystemClock.uptimeMillis()
            val list = targets
            if (start > activeUntil || list.isEmpty()) {
                running = false
                // Fin du mouvement : dernière position SANS étirement, pour que le masque reprenne
                // exactement la taille de la publication (pas de saut à la prochaine analyse).
                val hadStretch = motions.values.any { it.ex != 0f || it.ey != 0f }
                motions.values.forEach { it.vx = 0f; it.vy = 0f; it.lastAt = 0L; it.ex = 0f; it.ey = 0f; it.suspect = 0 }
                if (hadStretch) {
                    val rest = list.filter { it.key !in lostKeys && states.containsKey(it.key) }
                        .map { it.key to Rect(states.getValue(it.key).mask) }
                    if (rest.isNotEmpty()) onUpdate(rest, emptyList(), start)
                }
                return
            }
            val h = screenHeight().coerceAtLeast(1)
            val fullPass = forceFull || tickCount++ % 4 == 0
            forceFull = false
            val margin = h / 2
            val lost = ArrayList<String>()
            val updates = ArrayList<Pair<String, Rect>>(list.size)
            var budget = 8 // lectures maximum par image, toutes listes confondues
            val r = Rect()

            for ((gKey, group) in list.groupBy { clipKey(it.clip) }) {
                val clip = group.first().clip
                val alive = group.filter { it.key !in lostKeys && states.containsKey(it.key) }
                if (alive.isEmpty()) continue
                val near = alive.filter { t ->
                    val s = states.getValue(t.key).raw
                    s.bottom > clip.top - margin && s.top < clip.bottom + margin
                }.sortedByDescending { visibleArea(states.getValue(it.key).raw, clip) }
                val toRead = (if (fullPass) near else near.take(2)).take(budget)
                budget -= toRead.size

                // 1. Lecture des positions réelles.
                val reads = ArrayList<Triple<Tracked, Rect, IntArray>>(toRead.size)
                for (t in toRead) {
                    val ok = try {
                        t.node.refresh()
                    } catch (e: Exception) {
                        false
                    }
                    if (!ok) {
                        lost.add(t.key); lostKeys.add(t.key)
                        continue
                    }
                    t.node.getBoundsInScreen(r)
                    if (r.isEmpty) continue
                    val prev = states.getValue(t.key).raw
                    val dTop = r.top - prev.top
                    val dBottom = r.bottom - prev.bottom
                    // Un bord collé au bord de la liste ne bouge pas : on prend l'autre.
                    val dy = if (abs(dTop) >= abs(dBottom)) dTop else dBottom
                    val dLeft = r.left - prev.left
                    val dRight = r.right - prev.right
                    val dx = if (abs(dLeft) >= abs(dRight)) dLeft else dRight
                    // Saut impossible en une image : élément réutilisé pour une autre publication.
                    if (abs(dTop) > h / 2 && abs(dBottom) > h / 2) {
                        lost.add(t.key); lostKeys.add(t.key)
                        continue
                    }
                    reads.add(Triple(t, Rect(r), intArrayOf(dx, dy)))
                }
                // Cohérence : dans une liste, toutes les publications bougent pareil.
                if (reads.size >= 3) {
                    val med = reads.map { it.third[1] }.sorted()[reads.size / 2]
                    val tol = maxOf(150, h / 4)
                    reads.removeAll { (t, _, d) ->
                        val bad = abs(d[1] - med) > tol
                        if (bad) { lost.add(t.key); lostKeys.add(t.key) }
                        bad
                    }
                }
                if (reads.isEmpty()) continue

                val m = motions.getOrPut(gKey) { Motion() }
                val dt = if (m.lastAt == 0L) 0L else start - m.lastAt
                // Peu de lectures (1 ou 2) : impossible de voter. Une lecture très éloignée du
                // mouvement attendu (élément recyclé pour une autre publication, mise en page en
                // cours…) est écartée : son masque suit simplement le mouvement de la liste, et tout
                // est relu à l'image suivante. Si TOUTES les lectures sautent deux images de suite,
                // c'est la liste entière qui a bougé (lancer, retour en haut) : on suit.
                if (reads.size < 3) {
                    val px = if (dt in 1L..120L) m.vx * dt else 0f
                    val py = if (dt in 1L..120L) m.vy * dt else 0f
                    val tol = maxOf(150, h / 4)
                    val odd = reads.filter { (_, _, d) -> abs(d[1] - py) > tol || abs(d[0] - px) > tol }
                    when {
                        odd.isEmpty() -> m.suspect = 0
                        odd.size < reads.size -> {
                            reads.removeAll(odd.toSet())
                            forceFull = true
                        }
                        ++m.suspect < 2 -> {
                            reads.clear()
                            forceFull = true
                        }
                        else -> m.suspect = 0
                    }
                } else {
                    m.suspect = 0
                }
                if (reads.isEmpty()) continue

                // 2. Déplacement commun de la liste (médiane) et vitesse.
                val mdx = reads.map { it.third[0] }.sorted()[reads.size / 2]
                val mdy = reads.map { it.third[1] }.sorted()[reads.size / 2]
                if (dt in 1L..120L) {
                    // Micro-mouvements (1 ou 2 px : doigt posé, ajustement de mise en page) : la
                    // position suit, mais ils ne comptent pas comme une vitesse (pas d'étirement).
                    val ix = if (abs(mdx) <= MICRO_PX) 0f else mdx.toFloat() / dt
                    val iy = if (abs(mdy) <= MICRO_PX) 0f else mdy.toFloat() / dt
                    m.vx = 0.6f * m.vx + 0.4f * ix
                    m.vy = 0.6f * m.vy + 0.4f * iy
                } else {
                    m.vx = 0f; m.vy = 0f
                }
                m.lastAt = start

                // 3. Nouvelles positions : exactes pour les publications relues, déplacement
                //    commun pour les autres.
                val readKeys = HashSet<String>()
                for ((t, bounds, _) in reads) {
                    readKeys.add(t.key)
                    val st = states.getValue(t.key)
                    st.raw.set(bounds)
                    st.mask.set(ScreenScanner.maskRect(bounds, clip))
                }
                if (mdx != 0 || mdy != 0) {
                    for (t in alive) {
                        if (t.key in readKeys || t.key in lostKeys) continue
                        val st = states.getValue(t.key)
                        st.raw.offset(mdx, mdy)
                        ScreenScanner.shiftMask(st.mask, mdx, mdy, clip)
                    }
                }

                // 4. Étirement dans le sens du mouvement : couvre ce que le contenu parcourt
                //    pendant la lecture et l'affichage du masque (environ une image).
                //    Lissé : il grandit tout de suite (rien ne dépasse) mais diminue en douceur, et
                //    il est nul quand la page bouge à peine : le masque ne « pulse » plus.
                val lead = (SystemClock.uptimeMillis() - start) + frameMs
                val cap = h / 4f
                m.ey = smoothStretch(m.ey, if (abs(m.vy) < MIN_STRETCH_SPEED) 0f else (m.vy * lead).coerceIn(-cap, cap))
                m.ex = smoothStretch(m.ex, if (abs(m.vx) < MIN_STRETCH_SPEED) 0f else (m.vx * lead).coerceIn(-cap, cap))
                val ey = m.ey.roundToInt()
                val ex = m.ex.roundToInt()
                for (t in alive) {
                    if (t.key in lostKeys) continue
                    val out = Rect(states.getValue(t.key).mask)
                    if (ey < 0) out.top += ey else out.bottom += ey
                    if (ex < 0) out.left += ex else out.right += ex
                    updates.add(t.key to out)
                }
            }
            if (updates.isNotEmpty() || lost.isNotEmpty()) onUpdate(updates, lost, start)

            val spent = SystemClock.uptimeMillis() - start
            handler.postDelayed(this, (frameMs - spent).coerceAtLeast(1L))
        }
    }
}
