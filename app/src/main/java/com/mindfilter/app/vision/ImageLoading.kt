package com.mindfilter.app.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri

/** Chargement d'images en mémoire, à taille réduite. Rien n'est copié ni enregistré. */
object ImageLoading {
    fun load(ctx: Context, uri: Uri, maxSide: Int = 1280): Bitmap? = try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        var sample = 1
        while (opts.outWidth / (sample * 2) >= maxSide || opts.outHeight / (sample * 2) >= maxSide) sample *= 2
        val real = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, real) }
    } catch (e: Throwable) {
        null
    }

    /** Image retournée comme dans un miroir (exemple supplémentaire pour l'apprentissage). */
    fun mirror(b: Bitmap): Bitmap = Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { preScale(-1f, 1f) }, true)
}
