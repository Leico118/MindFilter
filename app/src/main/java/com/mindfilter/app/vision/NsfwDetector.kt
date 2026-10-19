package com.mindfilter.app.vision

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Détecteur de contenus pour adultes (nudité, pornographie) : modèle « open_nsfw » de Yahoo,
 * entraîné sur des millions d'images. Renvoie une probabilité de 0 (sans problème) à 1
 * (contenu pour adultes). Tout est calculé sur le téléphone, aucune image n'est envoyée.
 *
 * Préparation identique à la version Android d'origine : image 256×256, centre 224×224,
 * couleurs dans l'ordre bleu-vert-rouge moins la moyenne VGG.
 */
class NsfwDetector(ctx: Context) {
    private val interpreter: Interpreter? = try {
        val bytes = ctx.assets.open("vision/nsfw.tflite").use { it.readBytes() }
        val buf = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        buf.put(bytes)
        buf.rewind()
        Interpreter(buf, Interpreter.Options().setNumThreads(2))
    } catch (e: Throwable) {
        null
    }

    val available: Boolean get() = interpreter != null

    /** Probabilité « contenu pour adultes » (0 à 1), ou -1 si le modèle est absent. */
    fun score(bitmap: Bitmap): Float {
        val itp = interpreter ?: return -1f
        val scaled = Bitmap.createScaledBitmap(bitmap, 256, 256, true)
        val px = IntArray(SIZE * SIZE)
        scaled.getPixels(px, 0, SIZE, 16, 16, SIZE, SIZE)
        if (scaled !== bitmap) scaled.recycle()
        val input = ByteBuffer.allocateDirect(SIZE * SIZE * 3 * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (c in px) {
            input.putFloat((c and 0xFF) - 103.939f)
            input.putFloat(((c shr 8) and 0xFF) - 116.779f)
            input.putFloat(((c shr 16) and 0xFF) - 123.68f)
        }
        input.rewind()
        val out = Array(1) { FloatArray(2) }
        return try {
            itp.run(input, out)
            out[0][1]
        } catch (e: Throwable) {
            -1f
        }
    }

    fun close() {
        interpreter?.close()
    }

    companion object {
        private const val SIZE = 224

        /** Seuil selon le niveau de filtrage choisi (plus strict = masque plus tôt). */
        fun thresholdFor(level: Int): Float = when {
            level <= 2 -> 0.88f
            level <= 4 -> 0.80f
            level <= 6 -> 0.70f
            level <= 8 -> 0.60f
            else -> 0.50f
        }
    }
}
