package com.mindfilter.app.vision

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File

/**
 * Lecture du texte écrit DANS une image (mèmes, captures d'articles, affiches…), en français
 * et en anglais, avec Tesseract, entièrement sur le téléphone.
 */
class TextReader(ctx: Context) {
    private var tess: TessBaseAPI? = null

    val available: Boolean get() = tess != null

    init {
        try {
            val base = File(ctx.filesDir, "tesseract")
            val dir = File(base, "tessdata").apply { mkdirs() }
            val langs = ArrayList<String>()
            for (lang in listOf("fra", "eng")) {
                val f = File(dir, "$lang.traineddata")
                if (!f.exists() || f.length() == 0L) {
                    try {
                        ctx.assets.open("tessdata/$lang.traineddata").use { input -> f.outputStream().use { input.copyTo(it) } }
                    } catch (e: Exception) {
                        f.delete()
                    }
                }
                if (f.exists() && f.length() > 0L) langs.add(lang)
            }
            if (langs.isNotEmpty()) {
                val t = TessBaseAPI()
                if (t.init(base.absolutePath, langs.joinToString("+"))) tess = t else t.recycle()
            }
        } catch (e: Throwable) {
            tess = null
        }
    }

    /** Texte lu (vide si rien de fiable). */
    fun read(bitmap: Bitmap): String {
        val t = tess ?: return ""
        // Agrandit les petites images : Tesseract lit mieux un texte assez grand.
        val src = if (bitmap.width < 700) {
            Bitmap.createScaledBitmap(bitmap, bitmap.width * 2, bitmap.height * 2, true)
        } else {
            bitmap
        }
        return try {
            t.setImage(src)
            val text = t.getUTF8Text().orEmpty()
            val conf = t.meanConfidence()
            t.clear()
            if (conf < 45) "" else text.replace(Regex("\\s+"), " ").trim()
        } catch (e: Throwable) {
            ""
        } finally {
            if (src !== bitmap) src.recycle()
        }
    }

    fun close() {
        tess?.recycle()
        tess = null
    }
}
