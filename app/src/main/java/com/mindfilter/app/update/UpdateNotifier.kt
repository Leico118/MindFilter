package com.mindfilter.app.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.mindfilter.app.R
import com.mindfilter.app.ui.MainActivity

/**
 * Prévient par une notification quand une nouvelle version de MindFilter est publiée.
 *
 * - Vérification au plus toutes les 6 heures (demande de la dernière version à GitHub,
 *   rien d'autre n'est envoyé), lancée par le service quand il tourne et à l'ouverture de l'appli.
 * - Une seule notification par version.
 * - Désactivable dans Réglages → Mise à jour.
 */
object UpdateNotifier {
    private const val PREFS = "mindfilter_updates"
    private const val CHANNEL = "updates"
    private const val NOTIF_ID = 4201
    const val EXTRA_OPEN_UPDATE = "open_update"
    const val INTERVAL_MS = 6L * 60 * 60 * 1000

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context) = prefs(ctx).getBoolean("enabled", true)

    fun setEnabled(ctx: Context, value: Boolean) {
        prefs(ctx).edit().putBoolean("enabled", value).apply()
    }

    /** Android 13+ : les notifications doivent être autorisées par la personne. */
    fun canNotify(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Déjà demandé une fois automatiquement ? (on ne harcèle pas) */
    fun askedOnce(ctx: Context) = prefs(ctx).getBoolean("asked", false)
    fun markAsked(ctx: Context) = prefs(ctx).edit().putBoolean("asked", true).apply()

    /**
     * Vérifie s'il est temps (toutes les 6 h) et notifie si besoin.
     * BLOQUANT (accès réseau) : à appeler hors du fil principal.
     */
    fun checkIfDue(ctx: Context, force: Boolean = false) {
        if (!isEnabled(ctx)) return
        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        if (!force && now - p.getLong("lastCheck", 0L) < INTERVAL_MS) return
        p.edit().putLong("lastCheck", now).apply()
        val latest = Updater.fetchLatestBlocking() ?: return
        val version = latest.first
        if (!Updater.isNewer(version, Updater.currentVersion(ctx))) return
        if (p.getString("notified", null) == version) return
        if (notify(ctx, version)) p.edit().putString("notified", version).apply()
    }

    private fun notify(ctx: Context, version: String): Boolean {
        if (!canNotify(ctx)) return false
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Mises à jour", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Prévient quand une nouvelle version de MindFilter est disponible"
                },
            )
        }
        val open = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_OPEN_UPDATE, true)
        val pi = PendingIntent.getActivity(
            ctx, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(ctx, CHANNEL)
        } else {
            android.app.Notification.Builder(ctx)
        }
        val n = builder
            .setSmallIcon(R.drawable.ic_stat_update)
            .setContentTitle("Mise à jour de MindFilter disponible")
            .setContentText("La version $version est prête. Touchez pour l'installer.")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        return try {
            nm.notify(NOTIF_ID, n)
            true
        } catch (e: SecurityException) {
            false
        }
    }
}
