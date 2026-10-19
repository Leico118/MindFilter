package com.mindfilter.app.vision

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Calcule l'« empreinte » d'une image : une liste de nombres qui résume ce qu'elle montre
 * (formes, couleurs, style, sujet). Deux images qui se ressemblent ont des empreintes proches.
 * C'est la base de l'apprentissage par l'exemple. Tout est calculé sur le téléphone.
 *
 * Attention : un Interpreter ne doit servir qu'à un seul fil à la fois.
 */
class ImageEmbedder(ctx: Context) {
    private val interpreter: Interpreter?
    private val size: Int
    private val inputFloat: Boolean
    private val outputFloat: Boolean
    val dimension: Int

    val available: Boolean get() = interpreter != null

    init {
        var itp: Interpreter? = null
        var sz = 224
        var inF = true
        var outF = true
        var dim = 0
        try {
            val bytes = ctx.assets.open("vision/embedder.tflite").use { it.readBytes() }
            val buf = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
            buf.put(bytes)
            buf.rewind()
            itp = Interpreter(buf, Interpreter.Options().setNumThreads(2))
            val inT = itp.getInputTensor(0)
            sz = inT.shape()[1]
            inF = inT.dataType() == DataType.FLOAT32
            val outT = itp.getOutputTensor(0)
            outF = outT.dataType() == DataType.FLOAT32
            dim = outT.shape().last()
        } catch (e: Throwable) {
            itp?.close()
            itp = null
        }
        interpreter = itp
        size = sz
        inputFloat = inF
        outputFloat = outF
        dimension = dim
    }

    /** Empreinte normalisée (longueur 1), ou null si le modèle est absent. */
    fun embed(bitmap: Bitmap): FloatArray? {
        val itp = interpreter ?: return null
        val src = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
        val scaled = Bitmap.createScaledBitmap(src, size, size, true)
        val pixels = IntArray(size * size)
        scaled.getPixels(pixels, 0, size, 0, 0, size, size)
        if (scaled !== src) scaled.recycle()
        if (src !== bitmap) src.recycle()
        val input = ByteBuffer.allocateDirect(size * size * 3 * if (inputFloat) 4 else 1).order(ByteOrder.nativeOrder())
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            if (inputFloat) {
                input.putFloat(r / 255f); input.putFloat(g / 255f); input.putFloat(b / 255f)
            } else {
                input.put(r.toByte()); input.put(g.toByte()); input.put(b.toByte())
            }
        }
        input.rewind()
        val v = FloatArray(dimension)
        if (outputFloat) {
            val out = Array(1) { FloatArray(dimension) }
            itp.run(input, out)
            out[0].copyInto(v)
        } else {
            val out = Array(1) { ByteArray(dimension) }
            itp.run(input, out)
            for (i in 0 until dimension) v[i] = out[0][i].toFloat()
        }
        return normalize(v)
    }

    fun close() {
        interpreter?.close()
    }

    companion object {
        fun normalize(v: FloatArray): FloatArray {
            var n = 0f
            for (x in v) n += x * x
            val len = sqrt(n)
            if (len > 0f) for (i in v.indices) v[i] /= len
            return v
        }

        /** Ressemblance entre deux empreintes : 1 = identiques, 0 = sans rapport. */
        fun similarity(a: FloatArray, b: FloatArray): Float {
            if (a.size != b.size) return 0f
            var s = 0f
            for (i in a.indices) s += a[i] * b[i]
            return s
        }
    }
}
