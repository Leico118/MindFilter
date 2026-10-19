package com.mindfilter.app.vision

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Apprentissage depuis un dossier (par exemple sur un NAS, via le sélecteur de dossiers
 * d'Android). Chaque image est lue en mémoire, son empreinte est calculée, puis l'image est
 * oubliée : AUCUNE image n'est copiée ni gardée. L'accès au dossier n'est pas conservé : il
 * prend fin avec l'import.
 *
 * L'import continue même si on quitte l'écran ; il peut être annulé (ce qui a déjà été appris
 * est gardé).
 */
object FolderLearning {
    data class Progress(
        val running: Boolean = false,
        val found: Int = 0,
        val done: Int = 0,
        val learned: Int = 0,
        val message: String = "",
    )

    private const val MAX_IMAGES = 5000
    private const val MAX_DEPTH = 6

    private val _state = MutableStateFlow(Progress())
    val state: StateFlow<Progress> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    fun start(ctx: Context, tree: Uri, theme: String) {
        if (job?.isActive == true) return
        val app = ctx.applicationContext
        job = scope.launch { run(app, tree, theme) }
    }

    fun cancel() {
        job?.cancel()
        _state.value = _state.value.copy(running = false, message = "Import arrêté. Ce qui a été appris est gardé.")
    }

    private suspend fun run(app: Context, tree: Uri, theme: String) {
        val safe = theme.isEmpty()
        _state.value = Progress(running = true, message = "Recherche des images dans le dossier…")
        val uris = try {
            listImages(app, tree)
        } catch (e: Exception) {
            _state.value = Progress(message = "Dossier illisible : ${e.message ?: "erreur"}")
            return
        }
        if (uris.isEmpty()) {
            _state.value = Progress(message = "Aucune image trouvée dans ce dossier.")
            return
        }
        val emb = ImageEmbedder(app)
        if (!emb.available) {
            emb.close()
            _state.value = Progress(message = "Le modèle d'empreintes est absent de cette version.")
            return
        }
        var learned = 0
        val batch = ArrayList<FloatArray>(64)
        try {
            for ((i, u) in uris.withIndex()) {
                kotlin.coroutines.coroutineContext.ensureActive()
                val bmp = ImageLoading.load(app, u, maxSide = 640)
                if (bmp != null) {
                    emb.embed(bmp)?.let { batch.add(it); learned++ }
                    bmp.recycle()
                }
                if (batch.size >= 50) {
                    LearnedExamples.addAll(app, theme, safe, batch.toList())
                    batch.clear()
                }
                if (i % 5 == 0 || i == uris.lastIndex) {
                    _state.value = Progress(true, uris.size, i + 1, learned, "Apprentissage en cours…")
                }
            }
            _state.value = Progress(false, uris.size, uris.size, learned, "Terminé : $learned image(s) apprise(s). Aucune image n'a été gardée.")
        } finally {
            LearnedExamples.addAll(app, theme, safe, batch.toList())
            emb.close()
        }
    }

    /** Liste les images du dossier et de ses sous-dossiers (sans rien copier). */
    private fun listImages(ctx: Context, tree: Uri): List<Uri> {
        val out = ArrayList<Uri>()
        val root = DocumentsContract.getTreeDocumentId(tree)
        fun walk(docId: String, depth: Int) {
            if (depth > MAX_DEPTH || out.size >= MAX_IMAGES) return
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE)
            val subdirs = ArrayList<String>()
            ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext() && out.size < MAX_IMAGES) {
                    val id = c.getString(0) ?: continue
                    val mime = c.getString(1).orEmpty()
                    when {
                        mime == DocumentsContract.Document.MIME_TYPE_DIR -> subdirs.add(id)
                        mime.startsWith("image/") -> out.add(DocumentsContract.buildDocumentUriUsingTree(tree, id))
                    }
                }
            }
            for (d in subdirs) walk(d, depth + 1)
        }
        walk(root, 0)
        return out
    }
}
