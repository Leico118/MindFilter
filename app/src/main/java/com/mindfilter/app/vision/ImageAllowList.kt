package com.mindfilter.app.vision

import android.content.Context

/**
 * Images signalées « pas à masquer » depuis le journal. Seule leur empreinte visuelle
 * (un nombre) est gardée, jamais l'image. Une image très proche (même photo, recadrée ou
 * redimensionnée) est aussi reconnue.
 */
object ImageAllowList {
    private const val PREFS = "mindfilter_learning"
    private const val KEY = "allowedImages"
    private const val MAX = 500

    @Volatile private var hashes: LongArray? = null

    /** Change à chaque ajout : les verdicts déjà rendus doivent être refaits. */
    @Volatile var revision = 0
        private set

    private fun load(ctx: Context): LongArray {
        hashes?.let { return it }
        val raw = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
        val arr = raw.split(',').mapNotNull { it.trim().toLongOrNull(16) ?: it.trim().toULongOrNull(16)?.toLong() }.toLongArray()
        hashes = arr
        return arr
    }

    fun isAllowed(ctx: Context, hash: Long): Boolean =
        load(ctx).any { java.lang.Long.bitCount(it xor hash) <= 6 }

    fun add(ctx: Context, hash: Long) {
        synchronized(this) {
            val list = load(ctx).toMutableList()
            if (list.any { it == hash }) return
            list.add(hash)
            val kept = list.takeLast(MAX)
            hashes = kept.toLongArray()
            ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY, kept.joinToString(",") { java.lang.Long.toHexString(it) })
                .apply()
            revision++
        }
    }

    fun count(ctx: Context): Int = load(ctx).size

    fun clear(ctx: Context) {
        synchronized(this) {
            hashes = LongArray(0)
            ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
            revision++
        }
    }
}
