package com.mindfilter.app.vision

/**
 * Compteurs de l'analyse des images en direct, affichés dans le mode développeur pour
 * comprendre pourquoi une image est (ou n'est pas) masquée. Rien n'est enregistré.
 */
object VisionStats {
    @Volatile var candidates = 0          // images repérées à l'écran lors de la dernière analyse
    @Volatile var shotsOk = 0             // captures réussies
    @Volatile var shotsFailed = 0         // captures refusées par Android
    @Volatile var lastError = ""          // raison du dernier refus
    @Volatile var analyzed = 0            // images analysées
    @Volatile var hits = 0                // images reconnues comme à masquer
    @Volatile var lastLabel = ""          // dernière détection
    @Volatile var lastBest = ""           // meilleure ressemblance de la dernière image analysée
    @Volatile var models = ""             // modèles disponibles

    fun errorText(code: Int): String = when (code) {
        1 -> "erreur interne d'Android"
        2 -> "MindFilter n'a pas le droit de capturer l'écran : désactive puis réactive MindFilter dans l'accessibilité"
        3 -> "captures trop rapprochées (normal de temps en temps)"
        4 -> "écran invalide"
        5 -> "fenêtre invalide"
        6 -> "l'appli interdit les captures (contenu protégé)"
        else -> "code $code"
    }
}
