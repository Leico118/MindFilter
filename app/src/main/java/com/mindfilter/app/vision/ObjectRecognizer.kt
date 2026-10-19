package com.mindfilter.app.vision

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reconnaissance d'objets dans une image (modèle MobileNet, 1000 sortes d'objets), entièrement
 * sur le téléphone. Renvoie les objets les plus probables avec leur probabilité (0 à 1).
 */
class ObjectRecognizer(ctx: Context) {
    private val interpreter: Interpreter?
    private val labels: List<String>
    private val size: Int
    private val inputFloat: Boolean
    private val outputFloat: Boolean
    private val outputCount: Int

    val available: Boolean get() = interpreter != null

    init {
        var itp: Interpreter? = null
        var lab: List<String> = emptyList()
        var sz = 224
        var inF = false
        var outF = false
        var n = 0
        try {
            val bytes = ctx.assets.open("vision/mobilenet.tflite").use { it.readBytes() }
            val buf = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
            buf.put(bytes)
            buf.rewind()
            itp = Interpreter(buf, Interpreter.Options().setNumThreads(2))
            lab = ctx.assets.open("vision/labels.txt").bufferedReader().readLines()
            val inT = itp.getInputTensor(0)
            sz = inT.shape()[1]
            inF = inT.dataType() == DataType.FLOAT32
            val outT = itp.getOutputTensor(0)
            outF = outT.dataType() == DataType.FLOAT32
            n = outT.shape()[1]
        } catch (e: Throwable) {
            itp?.close()
            itp = null
        }
        interpreter = itp
        labels = lab
        size = sz
        inputFloat = inF
        outputFloat = outF
        outputCount = n
    }

    fun recognize(bitmap: Bitmap, top: Int = 5): List<Pair<String, Float>> {
        val itp = interpreter ?: return emptyList()
        val scaled = Bitmap.createScaledBitmap(bitmap, size, size, true)
        val pixels = IntArray(size * size)
        scaled.getPixels(pixels, 0, size, 0, 0, size, size)
        if (scaled !== bitmap) scaled.recycle()
        val input = ByteBuffer.allocateDirect(size * size * 3 * if (inputFloat) 4 else 1).order(ByteOrder.nativeOrder())
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            if (inputFloat) {
                input.putFloat((r - 127.5f) / 127.5f)
                input.putFloat((g - 127.5f) / 127.5f)
                input.putFloat((b - 127.5f) / 127.5f)
            } else {
                input.put(r.toByte()); input.put(g.toByte()); input.put(b.toByte())
            }
        }
        input.rewind()
        val probs = FloatArray(outputCount)
        if (outputFloat) {
            val out = Array(1) { FloatArray(outputCount) }
            itp.run(input, out)
            out[0].copyInto(probs)
        } else {
            val out = Array(1) { ByteArray(outputCount) }
            itp.run(input, out)
            for (i in 0 until outputCount) probs[i] = (out[0][i].toInt() and 0xFF) / 255f
        }
        return probs.indices.sortedByDescending { probs[it] }.take(top)
            .map { (labels.getOrNull(it) ?: "?").lowercase() to probs[it] }
    }

    fun close() {
        interpreter?.close()
    }
}
