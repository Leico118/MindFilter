package com.mindfilter.app.service

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.mindfilter.app.engine.ContentClassifier

/**
 * Diagnostic « Structure de l'écran » : enregistre comment l'appli ouverte est construite
 * (blocs, tailles, listes, ce qui a été masqué et pourquoi) pour comprendre un mauvais
 * masquage. AUCUN texte n'est enregistré : chaque texte est remplacé par sa longueur.
 * Le fichier est mis dans Téléchargements ; rien n'est envoyé.
 */
object ScreenDump {
    /** Jusqu'à quand le prochain écran d'une appli surveillée doit être enregistré. */
    @Volatile var requestedUntil = 0L
    @Volatile private var notBefore = 0L

    /** L'écran est enregistré 8 secondes après l'appui (le temps de revenir sur la page et
     *  que les masques « tardifs » apparaissent), au plus tard 40 secondes après. */
    fun request() {
        val now = System.currentTimeMillis()
        notBefore = now + 8_000
        requestedUntil = now + 40_000
    }

    fun wanted(): Boolean {
        val now = System.currentTimeMillis()
        return now in notBefore until requestedUntil
    }

    fun build(root: SNode, targets: List<MaskTarget>, classifier: ContentClassifier, pkg: String): String {
        val sb = StringBuilder()
        sb.append("Structure MindFilter · appli ").append(pkg).append('\n')
        sb.append("Légende : [S] défile · [O] hors écran · [LISTE] liste de lignes · txt=N longueur du texte (texte non enregistré)\n")
        sb.append("  MATCH=thème si le texte de ce bloc déclenche le masquage · IMG=image reconnue · FLAG=bloc marqué par une image\n\n")
        fun walk(n: SNode, depth: Int) {
            if (sb.length > 400_000) return
            repeat(depth) { sb.append("  ") }
            sb.append(n.className.substringAfterLast('.'))
            val b = n.bounds
            sb.append(" [").append(b.left).append(',').append(b.top).append(' ').append(b.width()).append('x').append(b.height()).append(']')
            if (n.scrollable) sb.append(" [S]")
            if (n.offscreen) sb.append(" [O]")
            if (ScreenScanner.isRowList(n)) sb.append(" [LISTE]")
            if (n.ownText.isNotBlank()) sb.append(" txt=").append(n.ownText.length)
            if (n.children.isNotEmpty()) sb.append(" tout=").append(n.fullText.length)
            if (n.fullText.isNotBlank()) {
                val m = try { classifier.classify(n.fullText) } catch (_: Throwable) { null }
                if (m != null) sb.append(" MATCH=").append(m.label)
            }
            n.imageFlag?.let { sb.append(" IMG=").append(it) }
            n.flagLabel?.let { sb.append(" FLAG=").append(it) }
            sb.append('\n')
            for (c in n.children) walk(c, depth + 1)
        }
        walk(root, 0)
        sb.append("\nMasques (").append(targets.size).append(") :\n")
        for (t in targets) {
            val r = t.rect
            sb.append("  [").append(r.left).append(',').append(r.top).append(' ').append(r.width()).append('x').append(r.height())
                .append("] ").append(t.label).append(if (t.kind == MaskTarget.KIND_TEXT) " (texte)" else " (image)").append('\n')
        }
        return sb.toString()
    }

    /** Enregistre le fichier dans Téléchargements ; renvoie son nom, ou null en cas d'échec. */
    fun save(ctx: Context, text: String): String? {
        val name = "MindFilter-structure-" + java.text.SimpleDateFormat("HHmmss", java.util.Locale.ROOT).format(java.util.Date()) + ".txt"
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/")
                }
                val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } ?: return null
            } else {
                java.io.File(ctx.getExternalFilesDir(null), name).writeText(text)
            }
            name
        } catch (_: Throwable) {
            null
        }
    }
}
