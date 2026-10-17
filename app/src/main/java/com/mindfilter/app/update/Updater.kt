package com.mindfilter.app.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Mise à jour de MindFilter depuis les versions publiées sur GitHub.
 * Ne s'exécute que lorsque l'utilisateur appuie sur le bouton : aucune vérification
 * en arrière-plan, et rien d'autre que la demande de version n'est envoyé.
 */
object Updater {
    private const val API = "https://api.github.com/repos/Leico118/MindFilter/releases/latest"
    const val FALLBACK_APK = "https://github.com/Leico118/MindFilter/releases/latest/download/MindFilter.apk"

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val version: String, val apkUrl: String) : State
        data class Downloading(val version: String, val progress: Float) : State
        data class ReadyToInstall(val version: String) : State
        data class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    fun currentVersion(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (_: Exception) { "?" }

    /** Compare « 0.2.10 » et « 0.2.9 » nombre par nombre. */
    fun isNewer(remote: String, local: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").split('.', '-', ' ').mapNotNull { it.toIntOrNull() }
        val r = parts(remote); val l = parts(local)
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }; val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "MindFilter-Android")
        }

    /** Dernière version publiée (numéro, lien de l'APK), ou null si indisponible. BLOQUANT. */
    fun fetchLatestBlocking(): Pair<String, String>? = try {
        val c = open(API).apply { setRequestProperty("Accept", "application/vnd.github+json") }
        if (c.responseCode != 200) {
            null
        } else {
            val json = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val tag = json.optString("tag_name").removePrefix("v")
            var apk = FALLBACK_APK
            val assets = json.optJSONArray("assets")
            if (assets != null) for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name") == "MindFilter.apk") apk = a.optString("browser_download_url", apk)
            }
            if (tag.isBlank()) null else tag to apk
        }
    } catch (e: Exception) {
        null
    }

    suspend fun check(ctx: Context) {
        _state.value = State.Checking
        _state.value = withContext(Dispatchers.IO) {
            try {
                val c = open(API).apply { setRequestProperty("Accept", "application/vnd.github+json") }
                val code = c.responseCode
                if (code != 200) return@withContext State.Error("GitHub ne répond pas (code $code). Réessayez plus tard.")
                val json = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                val tag = json.optString("tag_name").removePrefix("v")
                var apk = FALLBACK_APK
                val assets = json.optJSONArray("assets")
                if (assets != null) for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name") == "MindFilter.apk") apk = a.optString("browser_download_url", apk)
                }
                if (tag.isNotBlank() && isNewer(tag, currentVersion(ctx))) State.Available(tag, apk) else State.UpToDate
            } catch (_: java.io.IOException) {
                State.Error("Pas de connexion Internet. Vérifiez le Wi-Fi ou les données mobiles.")
            } catch (e: Exception) {
                State.Error("Vérification impossible : ${e.message ?: "erreur inconnue"}")
            }
        }
    }

    private fun apkFile(ctx: Context) = File(File(ctx.cacheDir, "updates").apply { mkdirs() }, "MindFilter-update.apk")

    suspend fun download(ctx: Context, version: String, url: String) {
        _state.value = State.Downloading(version, 0f)
        _state.value = withContext(Dispatchers.IO) {
            try {
                val c = open(url)
                if (c.responseCode != 200) return@withContext State.Error("Téléchargement refusé (code ${c.responseCode}).")
                val total = c.contentLengthLong
                val out = apkFile(ctx)
                c.inputStream.use { input ->
                    out.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n)
                            done += n
                            if (total > 0) _state.value = State.Downloading(version, done.toFloat() / total)
                        }
                    }
                }
                State.ReadyToInstall(version)
            } catch (_: java.io.IOException) {
                State.Error("Le téléchargement a été interrompu. Vérifiez la connexion et réessayez.")
            } catch (e: Exception) {
                State.Error("Téléchargement impossible : ${e.message ?: "erreur inconnue"}")
            }
        }
    }

    /** Android doit autoriser MindFilter à installer des applis (demandé une seule fois). */
    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(ctx: Context) {
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: ActivityNotFoundException) {
            ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Ouvre l'installateur d'Android : c'est lui qui demande la confirmation finale. */
    fun install(ctx: Context) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.updates", apkFile(ctx))
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(i) } catch (_: ActivityNotFoundException) { openInBrowser(ctx) }
    }

    /** Solution de secours : téléchargement par le navigateur. */
    fun openInBrowser(ctx: Context) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(FALLBACK_APK)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) { }
    }

    fun reset() { _state.value = State.Idle }
}
