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
     * Objets qui montrent qu'une image est anodine : jouets et constructions (LEGO…), paysages,
     * monuments, plats, pages de texte. Utilisés comme « deuxième avis » pour éviter les fausses
     * alertes (une construction LEGO prise pour un contenu pour adultes, un char LEGO pris pour
     * la guerre, des dunes prises pour de la peau…).
     */
    private val SAFE_SCENES = setOf(
        // Jouets, jeux, constructions, dessins
        "toyshop", "jigsaw puzzle", "comic book", "crossword puzzle", "pinwheel", "teddy", "teddy bear",
        "carousel", "puck", "packet", "carton", "crate", "shoji", "chain mail", "lego",
        // Paysages
        "alp", "valley", "cliff", "promontory", "sandbar", "seashore", "lakeside", "coral reef",
        "geyser", "mountain tent", "dock", "boathouse", "breakwater", "dam",
        // Monuments, bâtiments
        "church", "castle", "palace", "monastery", "mosque", "dome", "bell cote", "library",
        "planetarium", "triumphal arch", "suspension bridge", "steel arch bridge", "viaduct", "pier",
        "beacon", "obelisk", "stupa", "cinema", "restaurant", "barn", "greenhouse", "patio",
        // Plats, nourriture
        "plate", "pizza", "cheeseburger", "trifle", "ice cream", "bakery", "grocery store",
        "confectionery", "carbonara", "guacamole", "potpie", "burrito", "espresso",
        // Écrans et textes
        "web site", "menu", "book jacket", "envelope", "scoreboard", "monitor", "screen",
    )

    fun isSafeScene(label: String): Boolean = label.trim().lowercase() in SAFE_SCENES

    /** Probabilité cumulée des objets « anodins » parmi les objets les plus probables. */
    fun safeSceneScore(top: List<Pair<String, Float>>): Float =
        top.filter { isSafeScene(it.first) }.sumOf { it.second.toDouble() }.toFloat()

    /** Une ressemblance ou un objet reconnu sont ignorés au-delà de ce score « anodin ». */
    const val SCENE_VETO = 0.30f

    /** Le détecteur de nudité est ignoré au-delà de ce score « anodin » (sauf certitude). */
    const val SCENE_VETO_STRONG = 0.45f

    /** L'objet reconnu doit devancer le 2e objet le plus probable d'au moins 25 points. */
    const val MIN_LEAD = 0.25f

    /**
     * Probabilité minimale réellement exigée : le modèle d'objets se trompe souvent sur les
     * photos de personnes et les vidéos. On exige donc au moins 70 %.
     */
    const val MIN_CONFIDENCE = 0.70f
}
