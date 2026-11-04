package com.mindfilter.app

import android.content.Context
import java.io.File

/**
 * Rapport du dernier plantage, gardé sur le téléphone (jamais envoyé) : il ne contient que
 * l'endroit du programme où l'erreur s'est produite, aucun contenu affiché à l'écran.
 * L'utilisateur peut le copier depuis l'accueil pour aider à corriger le problème.
 */
object CrashLog {
    private const val FILE = "dernier_plantage.txt"

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val version = try {
                    app.packageManager.getPackageInfo(app.packageName, 0).versionName
                } catch (_: Exception) {
                    "?"
                }
                val text = buildString {
                    append("MindFilter ").append(version).append(" · Android ").append(android.os.Build.VERSION.RELEASE)
                    append(" · ").append(android.os.Build.MODEL).append('\n')
                    append("Fil : ").append(t.name).append('\n')
                    append(e.stackTraceToString().take(6000))
                }
                File(app.filesDir, FILE).writeText(text)
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(t, e)
        }
    }

    /** Erreur rattrapée (le service a continué) : notée pour pouvoir la corriger. */
    fun record(ctx: Context, e: Throwable, where: String) {
        try {
            val app = ctx.applicationContext
            val version = try { app.packageManager.getPackageInfo(app.packageName, 0).versionName } catch (_: Exception) { "?" }
            File(app.filesDir, FILE).writeText(
                "MindFilter $version · Android ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.MODEL}\n" +
                    "Erreur évitée ($where)\n" + e.stackTraceToString().take(6000),
            )
        } catch (_: Throwable) {
        }
    }

    fun read(ctx: Context): String? = try {
        File(ctx.applicationContext.filesDir, FILE).takeIf { it.exists() }?.readText()
    } catch (_: Exception) {
        null
    }

    fun clear(ctx: Context) {
        try { File(ctx.applicationContext.filesDir, FILE).delete() } catch (_: Exception) {}
    }
}
