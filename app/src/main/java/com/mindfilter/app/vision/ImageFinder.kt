package com.mindfilter.app.vision

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Trouve la zone « image » dans la capture d'une publication entière (nom, texte, photo,
 * boutons…), quand l'appli ne dit pas où est sa photo.
 *
 * Principe : une ligne de pixels d'une photo est « chargée » (couleurs variées ou différentes
 * du fond), alors qu'un paragraphe de texte alterne lignes de lettres et lignes vides. La plus
 * longue suite de lignes chargées, assez haute, est la photo.
 */
object ImageFinder {
    fun findImageBand(post: Bitmap): Rect? {
        val w = 64
        val h = (post.height * w / post.width.coerceAtLeast(1)).coerceIn(8, 400)
        val small = Bitmap.createScaledBitmap(post, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== post) small.recycle()
        return findBand(px, w, h)?.let { (top, bottom) ->
            val scale = post.height.toFloat() / h
            Rect(0, (top * scale).toInt(), post.width, ((bottom + 1) * scale).toInt().coerceAtMost(post.height))
        }
    }

    /** Lignes [début, fin] (incluses) de la bande image, à l'échelle réduite. */
    fun findBand(px: IntArray, w: Int, h: Int): Pair<Int, Int>? {
        val mean = FloatArray(h)
        val std = FloatArray(h)
        for (y in 0 until h) {
            var s = 0f
            var s2 = 0f
            for (x in 0 until w) {
                val p = px[y * w + x]
                val l = (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000f
                s += l
                s2 += l * l
            }
            mean[y] = s / w
            std[y] = sqrt((s2 / w - mean[y] * mean[y]).coerceAtLeast(0f))
        }
        // Couleur de fond = luminosité de ligne la plus fréquente (arrondie).
        val hist = IntArray(26)
        for (m in mean) hist[(m / 10).toInt().coerceIn(0, 25)]++
        val bg = hist.indices.maxByOrNull { hist[it] }!! * 10 + 5f
        val busy = BooleanArray(h) { std[it] > 10f || abs(mean[it] - bg) > 18f }

        // Plus longue suite de lignes chargées (petits trous de 2 lignes tolérés).
        var bestStart = -1
        var bestEnd = -1
        var start = -1
        var gap = 0
        for (y in 0..h) {
            val b = y < h && busy[y]
            if (b) {
                if (start < 0) start = y
                gap = 0
            } else if (start >= 0) {
                gap++
                if (gap > 2 || y == h) {
                    val end = y - gap
                    if (end - start > bestEnd - bestStart) { bestStart = start; bestEnd = end }
                    start = -1
                    gap = 0
                }
            }
        }
        if (bestStart < 0) return null
        // Assez haute pour être une photo (au moins 30 % de la largeur), pas une ligne de texte.
        if (bestEnd - bestStart + 1 < (w * 0.3f).toInt()) return null
        return bestStart to bestEnd
    }
}
