package com.mindfilter.app.vision

/**
 * Correspondance entre les objets reconnus par le modèle (noms anglais du jeu ImageNet) et
 * les thèmes de MindFilter. Seuls des objets clairement identifiables sont utilisés : le modèle
 * ne « comprend » pas une scène (il ne voit pas un accident, seulement une ambulance).
 */
object ImageLabels {
    class Rule(val themeId: String, val label: String, val minProbability: Float)

    private val SPIDERS = listOf(
        "black and gold garden spider", "barn spider", "garden spider", "black widow", "tarantula",
        "wolf spider", "european garden spider",
    )
    private val SNAKES = listOf(
        "thunder snake", "ringneck snake", "hognose snake", "green snake", "king snake", "garter snake",
        "water snake", "vine snake", "night snake", "boa constrictor", "rock python", "indian cobra",
        "green mamba", "sea snake", "horned viper", "diamondback", "sidewinder",
    )
    private val WEAPONS = listOf("assault rifle", "rifle", "revolver")
    // Retirés car trop souvent reconnus à tort : « military uniform » (n'importe quelle veste
    // sombre), « projectile » (feux d'artifice), « spider web » (grillages, dentelle),
    // « syringe » (stylos).
    private val WAR = listOf("tank", "missile", "warplane", "cannon", "half track")

    private val rules: Map<String, Rule> = buildMap {
        SPIDERS.forEach { put(it, Rule("horreur.phobies.araignees", "Araignées (image)", 0.45f)) }
        SNAKES.forEach { put(it, Rule("horreur.phobies.serpents", "Serpents (image)", 0.45f)) }
        put("cockroach", Rule("horreur.phobies.insectes", "Insectes (image)", 0.5f))
        WEAPONS.forEach { put(it, Rule("violence.collectives.fusillades", "Armes (image)", 0.55f)) }
        WAR.forEach { put(it, Rule("guerre.conflits.guerres", "Guerre (image)", 0.55f)) }
        put("volcano", Rule("catastrophes.geo.volcans", "Volcans (image)", 0.6f))
        put("ambulance", Rule("sante.urgences.hospit", "Urgences (image)", 0.6f))
        put("stretcher", Rule("sante.urgences.hospit", "Urgences (image)", 0.6f))
    }

    fun ruleFor(label: String): Rule? = rules[label.trim().lowercase()]

    /**
     * Probabilité minimale réellement exigée : le modèle d'objets se trompe souvent sur les
     * photos de personnes et les vidéos. On exige donc au moins 70 %.
     */
    const val MIN_CONFIDENCE = 0.70f
}
