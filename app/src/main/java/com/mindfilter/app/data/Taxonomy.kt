package com.mindfilter.app.data

/**
 * Hiérarchie Catégorie → Sous-catégorie → Thème, et lexique de détection associé.
 *
 * Chaque thème porte une liste de règles. Une règle est formée de « groupes » ;
 * elle se déclenche lorsque CHAQUE groupe contient au moins une expression présente
 * dans le texte. Les règles à un seul groupe sont de simples listes de mots-clés,
 * celles à plusieurs groupes décrivent des co-occurrences (« retrouvé » + « sans vie »).
 *
 * Syntaxe des expressions (insensible à la casse et aux accents) :
 *  - « crise cardiaque »  → expression exacte (mots entiers)
 *  - « cancer* »          → préfixe (cancer, cancers, cancéreux…)
 *  - « re:… »             → expression régulière sur le texte normalisé
 *
 * Poids : FORT = 4, MOYEN = 2, FAIBLE = 1. Le score d'un thème est la somme des
 * poids des règles déclenchées, comparée au seuil de la sensibilité choisie.
 */
data class Rule(val weight: Int, val groups: List<List<String>>)

data class Theme(val id: String, val name: String, val rules: List<Rule>)

data class SubCategory(val id: String, val name: String, val themes: List<Theme>)

data class Category(
    val id: String,
    val name: String,
    val description: String,
    val defaultEnabled: Boolean,
    val subCategories: List<SubCategory>,
) {
    val allThemes: List<Theme> get() = subCategories.flatMap { it.themes }
}

const val STRONG = 4
const val MEDIUM = 2
const val WEAK = 1

private fun s(vararg a: String) = Rule(STRONG, listOf(a.toList()))
private fun m(vararg a: String) = Rule(MEDIUM, listOf(a.toList()))
private fun w(vararg a: String) = Rule(WEAK, listOf(a.toList()))
private fun all(weight: Int, vararg groups: List<String>) = Rule(weight, groups.toList())
private fun t(id: String, name: String, vararg rules: Rule) = Theme(id, name, rules.toList())
private fun sub(id: String, name: String, vararg themes: Theme) = SubCategory(id, name, themes.toList())

// ---------------------------------------------------------------------------
// Groupes de mots partagés
// ---------------------------------------------------------------------------

private val DEATH = listOf(
    "mort", "morte", "morts", "mortes", "meurt", "meurent", "mourir", "mourant*",
    "décédé*", "décès", "décède", "sans vie", "inanimé*", "a perdu la vie",
    "ont perdu la vie", "perd la vie", "succombé*", "succombe", "tué", "tuée", "tués",
    "tuées", "a péri", "ont péri", "n'a pas survécu", "n'ont pas survécu",
    "s'est éteint", "s'est éteinte", "emporté par", "emportée par",
)
private val SUDDEN = listOf(
    "soudain*", "subit*", "subitement", "brutal", "brutale", "brutalement",
    "inattendu*", "foudroyé*", "sans prévenir", "du jour au lendemain",
    "s'est effondré*", "s'effondre", "malaise", "en pleine forme",
)
private val SLEEP = listOf(
    "sommeil", "en dormant", "dans son lit", "dans leur lit", "pendant la nuit",
    "durant la nuit", "au réveil", "dans la nuit",
)
private val LIFELESS = listOf(
    "sans vie", "inanimé*", "mort", "morte", "décédé*", "ne s'est pas réveillé*",
    "ne s'est jamais réveillé*", "ne se réveillera",
)
private val FOUND = listOf("retrouvé*", "découvert*")
private val YOUNG_STRICT = listOf(
    "re:\\b(1[89]|2[0-9]|3[0-9]) ans\\b", "jeune homme", "jeune femme", "jeune fille",
    "jeune adulte", "jeunes adultes", "jeune papa", "jeune maman", "adolescent*",
)
private val YOUNG_LOOSE = listOf(
    "jeune*", "sportif*", "sportive*", "footballeur*", "rugbyman", "cycliste*",
    "étudiant*", "étudiante*", "athlète*", "joueur*", "joueuse*", "lycéen*",
)
private val CHILD = listOf(
    "enfant*", "bébé*", "nourrisson*", "fillette*", "garçonnet*", "petite fille",
    "petit garçon", "collégien*", "écolier*", "re:\\b([1-9]|1[0-7]) ans\\b",
)
private val ACCIDENT = listOf(
    "accident*", "collision*", "carambolage*", "percuté*", "percute", "sortie de route",
    "fait un tonneau", "s'est encastré*", "s'encastre", "embardée", "heurté*",
    "perdu le contrôle", "choc frontal",
)
private val CELEBRITY = listOf(
    "célèbre", "star", "chanteur*", "chanteuse*", "acteur*", "actrice*", "comédien*",
    "comédienne*", "animateur*", "animatrice*", "icône", "légende", "humoriste*",
    "rappeur*", "influenceu*", "youtubeu*", "footballeur*", "ancien champion",
    "réalisateur*", "réalisatrice*", "écrivain*", "musicien*", "musicienne*",
)
private val PASSED_AWAY = listOf(
    "mort", "morte", "meurt", "décédé*", "décès", "s'est éteint*", "nous a quittés",
    "nous a quitté", "nous a quittée", "disparition", "rend l'âme", "a rendu l'âme",
)

/** Expressions retirées avant analyse (faux positifs courants). */
val EXCLUSIONS = listOf(
    "mort de rire", "morte de rire", "morts de rire", "crise de rire", "fou rire",
    "mdr", "ptdr", "tuer le temps", "tue le temps", "drama queen", "guerre des étoiles",
    "star wars", "cancer astro", "signe du cancer", "tue l'amour",
)

// ---------------------------------------------------------------------------
// Les dix catégories
// ---------------------------------------------------------------------------

val TAXONOMY: List<Category> = listOf(
    catSante(),
    catVirus(),
    catAccidents(),
    catViolence(),
    catGuerre(),
    catCatastrophes(),
    catDeces(),
    catEconomie(),
    catClimat(),
    catMenaces(),
)

private fun catSante(): Category =
    Category(
        "sante", "Santé et maladies", "Maladies graves, décès médicaux, urgences", true,
        listOf(
            sub(
                "sante.cardio", "Maladies cardiovasculaires",
                t(
                    "sante.cardio.crise", "Crises cardiaques",
                    s("crise cardiaque", "crises cardiaques", "attaque cardiaque", "accident cardiaque", "malaise cardiaque", "problème cardiaque"),
                    m("cardiaque*"),
                ),
                t(
                    "sante.cardio.arret", "Arrêts cardiaques",
                    s("arrêt cardiaque", "arrêt cardio-respiratoire", "arrêt cardiorespiratoire", "arrêt du cœur", "cœur s'est arrêté", "son cœur a lâché"),
                    m("défibrillateur*", "massage cardiaque"),
                ),
                t(
                    "sante.cardio.infarctus", "Infarctus",
                    s("infarctus", "crise coronarienne", "syndrome coronarien"),
                ),
                t(
                    "sante.cardio.avc", "AVC",
                    s("avc", "accident vasculaire cérébral", "accident vasculaire", "attaque cérébrale", "hémorragie cérébrale", "rupture d'anévrisme", "anévrisme*"),
                    m("thrombose*", "embolie*"),
                ),
                t(
                    "sante.cardio.mortsubite", "Morts subites",
                    s("mort subite", "morts subites", "mort subite du nourrisson"),
                    all(STRONG, SUDDEN, DEATH),
                ),
                t(
                    "sante.cardio.insuffisance", "Insuffisance cardiaque",
                    s("insuffisance cardiaque", "cardiopathie*", "myocardite*", "péricardite*", "arythmie*", "malformation cardiaque"),
                ),
            ),
            sub(
                "sante.graves", "Maladies graves",
                t(
                    "sante.graves.cancer", "Cancers",
                    s("cancer*", "leucémie*", "lymphome*", "métastase*", "chimiothérapie*", "chimio", "oncolog*", "carcinome*", "mélanome*", "sarcome*", "radiothérapie"),
                ),
                t(
                    "sante.graves.tumeur", "Tumeurs",
                    s("tumeur*", "glioblastome*", "neuroblastome*", "masse cancéreuse"),
                    m("tumoral*"),
                ),
                t(
                    "sante.graves.incurable", "Maladies incurables",
                    s("maladie incurable", "maladies incurables", "incurable*", "phase terminale", "stade terminal", "soins palliatifs", "quelques mois à vivre", "condamné par la maladie"),
                    m("maladie orpheline", "maladie rare", "maladies rares"),
                ),
                t(
                    "sante.graves.neuro", "Maladies neurologiques",
                    s("sclérose en plaques", "sclérose latérale", "maladie de charcot", "épilepsie", "maladie neurologique", "maladies neurologiques", "méningite*", "encéphalite*", "paralysie*"),
                    m("neurologique*"),
                ),
                t(
                    "sante.graves.degen", "Maladies dégénératives",
                    s("alzheimer", "parkinson", "huntington", "maladie dégénérative", "maladies dégénératives", "neurodégénérative*", "démence*", "myopathie*", "dystrophie musculaire"),
                ),
            ),
            sub(
                "sante.deces", "Décès médicaux",
                t(
                    "sante.deces.soudain", "Décès soudains",
                    s(
                        "décès soudain", "décès brutal", "mort brutale", "mort soudaine", "mort subite",
                        "décédé subitement", "décédée subitement", "décédé brutalement", "décédée brutalement",
                        "mort subitement", "morte subitement", "disparu brutalement", "disparue brutalement",
                    ),
                    all(STRONG, FOUND, listOf("sans vie", "inanimé*", "mort", "morte", "décédé*")),
                    all(STRONG, SUDDEN, DEATH),
                    all(MEDIUM, DEATH, listOf("à seulement", "n'avait que", "si jeune")),
                ),
                t(
                    "sante.deces.jeunes", "Décès de jeunes adultes",
                    all(STRONG, YOUNG_STRICT, DEATH),
                    all(MEDIUM, YOUNG_LOOSE, DEATH),
                ),
                t(
                    "sante.deces.sommeil", "Décès pendant le sommeil",
                    s("mort dans son sommeil", "morte dans son sommeil", "décédé dans son sommeil", "décédée dans son sommeil", "mort pendant son sommeil", "mort en dormant", "morte en dormant", "décédé en dormant"),
                    all(STRONG, SLEEP, LIFELESS),
                ),
                t(
                    "sante.deces.complications", "Complications médicales mortelles",
                    s("complications mortelles", "erreur médicale", "erreurs médicales", "choc septique", "défaillance multiviscérale", "choc anaphylactique"),
                    all(STRONG, listOf("complication*", "opération", "intervention chirurgicale", "anesthésie", "accouchement", "chirurgie"), DEATH),
                ),
            ),
            sub(
                "sante.urgences", "Urgences médicales",
                t(
                    "sante.urgences.hospit", "Hospitalisations graves",
                    s("état grave", "pronostic vital", "entre la vie et la mort", "état critique", "soins intensifs", "grièvement blessé*"),
                    m("hospitalisé*", "hospitalisation*", "urgences"),
                    w("hôpital", "hôpitaux", "chu"),
                ),
                t(
                    "sante.urgences.rea", "Réanimations",
                    s("réanimation", "réanimé*", "coma", "coma artificiel", "plongé dans le coma", "plongée dans le coma", "intubé*", "sous respirateur"),
                    w("respirateur*"),
                ),
                t(
                    "sante.urgences.malaise", "Malaises graves",
                    s("malaise grave", "grave malaise", "perte de connaissance", "perdu connaissance", "convulsion*"),
                    m("malaise*", "évanoui*", "effondré*"),
                ),
                t(
                    "sante.urgences.symptomes", "Symptômes alarmants",
                    s("symptômes alarmants", "symptôme alarmant", "signes qui doivent alerter", "ne négligez pas", "symptômes à ne pas ignorer", "signe avant-coureur", "signes avant-coureurs", "signal d'alarme"),
                    m("symptôme*", "douleur thoracique", "essoufflement*"),
                    w("douleur*"),
                ),
            ),
        ),
    )

private fun catVirus(): Category =
    Category(
        "virus", "Virus et épidémies", "Pandémies, virus émergents, infections", true,
        listOf(
            sub(
                "virus.epidemies", "Épidémies",
                t(
                    "virus.epidemies.pandemie", "Pandémies",
                    s("pandémie*", "pandémique*", "covid*", "coronavirus", "sras", "grippe espagnole", "confinement*", "reconfinement*"),
                    m("vague épidémique"),
                ),
                t(
                    "virus.epidemies.locales", "Épidémies locales",
                    s("épidémie*", "flambée de cas", "foyer épidémique", "foyer infectieux", "cluster*", "recrudescence*"),
                    m("épidémique*", "cas recensés", "cas confirmés"),
                ),
                t(
                    "virus.epidemies.propagation", "Propagation de maladies",
                    s("propagation du virus", "propagation de la maladie", "transmission interhumaine", "nombre de cas", "contamination*"),
                    m("propagation*", "contaminé*", "se propage*"),
                ),
                t(
                    "virus.epidemies.alertes", "Alertes sanitaires",
                    s("alerte sanitaire", "alertes sanitaires", "urgence sanitaire", "crise sanitaire", "risque sanitaire", "rappel conso*", "rappel de produit*", "l'oms alerte"),
                    m("sanitaire*", "listeria", "salmonelle*", "e coli"),
                ),
            ),
            sub(
                "virus.virus", "Virus",
                t(
                    "virus.virus.nouveaux", "Nouveaux virus",
                    s("nouveau virus", "nouveaux virus", "virus inconnu", "nouvelle maladie", "maladie mystérieuse", "maladie x", "pathogène inconnu"),
                    all(STRONG, listOf("virus", "pathogène*"), listOf("nouveau", "nouvelle", "inconnu*", "mystérieu*", "détecté*", "découvert*")),
                ),
                t(
                    "virus.virus.mutations", "Mutations virales",
                    s("variant*", "nouvelle souche", "souche mutante", "sous-variant*"),
                    all(STRONG, listOf("mutation*", "muté*", "muter"), listOf("virus", "viral*", "souche*", "grippe*", "covid*", "h5n1")),
                ),
                t(
                    "virus.virus.emergents", "Virus émergents",
                    s("virus émergent*", "maladie émergente", "maladies émergentes", "h5n1", "grippe aviaire", "variole du singe", "mpox", "ebola", "marburg", "nipah", "zika", "fièvre hémorragique"),
                ),
            ),
            sub(
                "virus.infections", "Infections",
                t(
                    "virus.infections.contagieuses", "Maladies contagieuses",
                    s("maladie contagieuse", "maladies contagieuses", "très contagieu*", "hautement contagieu*", "rougeole", "coqueluche", "tuberculose", "varicelle", "méningite*"),
                    m("contagieu*", "contagion*", "grippe"),
                ),
                t(
                    "virus.infections.graves", "Infections graves",
                    s("infection grave", "infections graves", "bactérie mangeuse de chair", "infection nosocomiale", "sepsis", "septicémie", "bactérie résistante", "superbactérie*", "staphylocoque doré", "streptocoque*", "choc toxique"),
                    m("infection*", "bactérie*"),
                ),
                t(
                    "virus.infections.tropicales", "Maladies tropicales",
                    s("paludisme", "malaria", "dengue", "choléra", "fièvre jaune", "chikungunya", "leishmaniose", "maladie tropicale", "maladies tropicales"),
                ),
            ),
        ),
    )

private fun catAccidents(): Category =
    Category(
        "accidents", "Accidents", "Route, transports, domicile, travail", true,
        listOf(
            sub(
                "accidents.route", "Accidents routiers",
                t(
                    "accidents.route.voitures", "Voitures",
                    s("accident de la route", "accidents de la route", "accident de voiture", "accident routier", "accidents routiers", "drame de la route"),
                    all(STRONG, ACCIDENT, listOf("voiture*", "véhicule*", "automobil*", "conducteur*", "conductrice*", "chauffard*", "route", "autoroute", "nationale", "départementale", "carrefour")),
                    w("accident*"),
                ),
                t(
                    "accidents.route.motos", "Motos",
                    s("accident de moto", "motard tué", "motard décédé", "motarde tuée", "accident de scooter"),
                    all(STRONG, ACCIDENT + listOf("chute", "tombé"), listOf("moto*", "motard*", "motarde*", "scooter*", "deux-roues", "deux roues", "motocycliste*")),
                ),
                t(
                    "accidents.route.camions", "Camions",
                    s("accident de camion", "accident de bus", "accident d'autocar", "accident de car"),
                    all(STRONG, ACCIDENT, listOf("camion*", "poids lourd*", "semi-remorque*", "routier*", "bus", "autocar*", "car scolaire")),
                ),
                t(
                    "accidents.route.pietons", "Piétons",
                    s("piéton tué", "piétonne tuée", "piéton renversé", "piétonne renversée", "piéton fauché", "piétonne fauchée"),
                    all(STRONG, ACCIDENT + listOf("renversé*", "fauché*", "happé*"), listOf("piéton*", "piétonne*", "passage piéton*", "passage clouté", "trottoir")),
                ),
                t(
                    "accidents.route.mortelles", "Collisions mortelles",
                    s("collision mortelle", "collisions mortelles", "accident mortel", "accidents mortels", "collision frontale"),
                    all(STRONG, ACCIDENT, DEATH),
                ),
            ),
            sub(
                "accidents.transport", "Accidents de transport",
                t(
                    "accidents.transport.avions", "Avions",
                    s("crash aérien", "accident d'avion", "catastrophe aérienne", "s'est écrasé", "crash d'avion", "crash d'hélicoptère", "accident d'hélicoptère"),
                    all(STRONG, listOf("avion*", "vol", "appareil", "hélicoptère*", "boeing", "airbus", "aéroport"), listOf("crash*", "écrasé*", "abîmé en mer", "disparu des radars", "atterrissage d'urgence", "catastrophe", "accident*", "turbulences")),
                ),
                t(
                    "accidents.transport.trains", "Trains",
                    s("accident ferroviaire", "catastrophe ferroviaire", "déraillement*", "déraillé*", "collision ferroviaire"),
                    all(STRONG, listOf("train*", "tgv", "ter", "rer", "métro", "tramway"), listOf("collision*", "accident*", "percuté*", "happé*", "heurté*")),
                ),
                t(
                    "accidents.transport.bateaux", "Bateaux",
                    s("naufrage*", "chaviré*", "chavire", "accident maritime", "catastrophe maritime", "migrants noyés"),
                    all(STRONG, listOf("bateau*", "navire*", "ferry", "embarcation*", "paquebot*", "chalutier*"), listOf("coulé", "sombré", "accident*", "disparu*", "collision*")),
                ),
            ),
            sub(
                "accidents.domestiques", "Accidents domestiques",
                t(
                    "accidents.domestiques.chutes", "Chutes",
                    s("chute mortelle", "chute grave", "chute dans l'escalier", "tombé du balcon", "tombée du balcon", "défenestr*", "tombé d'une fenêtre", "tombée d'une fenêtre", "chute d'un balcon"),
                    all(STRONG, listOf("chute", "tombé*", "chuté"), listOf("balcon", "fenêtre", "escalier*", "échelle", "toit", "étage*")),
                ),
                t(
                    "accidents.domestiques.incendies", "Incendies",
                    s("incendie domestique", "incendie d'appartement", "incendie de maison", "incendie d'habitation", "incendie mortel", "incendie meurtrier", "monoxyde de carbone", "intoxication au monoxyde"),
                    all(STRONG, listOf("incendie*", "feu", "flammes", "brûlé*"), listOf("appartement*", "maison*", "logement*", "immeuble*", "habitation*", "pavillon*", "domicile")),
                ),
                t(
                    "accidents.domestiques.electrocution", "Électrocutions",
                    s("électrocut*", "électrisé*", "foudroyé*", "décharge électrique"),
                ),
            ),
            sub(
                "accidents.travail", "Accidents professionnels",
                t(
                    "accidents.travail.chantiers", "Chantiers",
                    s("accident de chantier", "chute d'un échafaudage"),
                    all(STRONG, listOf("chantier*", "échafaudage*", "grue*", "btp", "ouvrier*"), listOf("accident*", "chute", "tombé*", "écrasé*", "enseveli*", "mort", "tué", "décédé*", "blessé*")),
                ),
                t(
                    "accidents.travail.industries", "Industries",
                    s("explosion d'usine", "accident industriel", "catastrophe industrielle", "site seveso", "explosion dans une usine", "fuite de gaz", "fuite toxique", "incendie d'usine"),
                    all(STRONG, listOf("usine*", "raffinerie*", "entrepôt*", "site industriel", "centrale"), listOf("explosion*", "incendie*", "accident*", "fuite*", "effondr*")),
                ),
                t(
                    "accidents.travail.mortels", "Accidents mortels du travail",
                    s("accident du travail", "accidents du travail", "accident de travail", "mort au travail", "morts au travail", "sur son lieu de travail"),
                    all(STRONG, listOf("salarié*", "ouvrier*", "employé*", "travailleur*", "agriculteur*"), DEATH),
                ),
            ),
        ),
    )

private fun catViolence(): Category =
    Category(
        "violence", "Violence et criminalité", "Agressions, homicides, crimes", true,
        listOf(
            sub(
                "violence.agressions", "Agressions",
                t(
                    "violence.agressions.physiques", "Agressions physiques",
                    s("agression*", "agressé*", "roué de coups", "rouée de coups", "passé à tabac", "tabassé*", "lynché*", "lynchage", "violemment frappé*", "frappé à mort", "coups et blessures", "violences conjugales", "violences volontaires"),
                    m("frappé*", "battu à mort", "violence*"),
                ),
                t(
                    "violence.agressions.sexuelles", "Agressions sexuelles",
                    s("agression sexuelle", "agressions sexuelles", "viol", "viols", "violé*", "violeur*", "attouchement*", "abus sexuel*", "pédocriminel*", "pédophil*", "inceste*", "harcèlement sexuel"),
                ),
                t(
                    "violence.agressions.couteau", "Attaques au couteau",
                    s("coup de couteau", "coups de couteau", "attaque au couteau", "poignardé*", "poignarder", "arme blanche", "égorgé*"),
                    m("couteau*", "machette*"),
                ),
            ),
            sub(
                "violence.homicides", "Homicides",
                t(
                    "violence.homicides.meurtres", "Meurtres",
                    s("meurtre*", "meurtrier*", "homicide*", "tué par balle", "tuée par balle", "abattu*", "abattue*", "retrouvé mort", "retrouvée morte", "corps sans vie", "cadavre*", "féminicide*", "tueur*"),
                    all(STRONG, listOf("tué", "tuée", "tués"), listOf("de plusieurs coups", "balle*", "par son", "par sa", "par un", "par une")),
                ),
                t(
                    "violence.homicides.assassinats", "Assassinats",
                    s("assassinat*", "assassiné*", "assassin*", "exécuté*", "exécution sommaire", "règlement de comptes", "tueur à gages"),
                ),
                t(
                    "violence.homicides.familiaux", "Drames familiaux",
                    s("drame familial", "drames familiaux", "tué sa femme", "tué son mari", "tué ses enfants", "tué sa mère", "tué son père", "tué sa compagne", "tué son ex", "infanticide*", "parricide*", "matricide*"),
                ),
            ),
            sub(
                "violence.crime", "Criminalité",
                t(
                    "violence.crime.enlevements", "Enlèvements",
                    s("enlèvement*", "enlevé*", "enlevée*", "kidnapp*", "séquestr*", "rapt", "alerte enlèvement"),
                ),
                t(
                    "violence.crime.otages", "Prises d'otages",
                    s("prise d'otage*", "prises d'otage*", "otage*", "retenu en otage", "retenus en otage", "preneur d'otage*", "forcené*"),
                ),
                t(
                    "violence.crime.braquages", "Braquages violents",
                    s("braquage*", "braqué*", "braqueur*", "vol à main armée", "attaque à main armée", "hold-up", "car-jacking", "carjacking", "home-jacking", "home jacking"),
                ),
            ),
            sub(
                "violence.collectives", "Violences collectives",
                t(
                    "violence.collectives.fusillades", "Fusillades",
                    s("fusillade*", "coups de feu", "tireur*", "tuerie*", "échange de tirs", "fusil d'assaut"),
                    m("arme à feu", "armes à feu", "balles"),
                ),
                t(
                    "violence.collectives.emeutes", "Émeutes",
                    s("émeute*", "émeutier*", "violences urbaines", "guérilla urbaine", "pillage*", "saccag*", "casseurs"),
                    m("affrontement*", "heurts", "dégénère"),
                ),
                t(
                    "violence.collectives.masse", "Attaques de masse",
                    s("attaque de masse", "tuerie de masse", "tueries de masse", "massacre*", "carnage", "voiture bélier", "voiture-bélier", "dans la foule", "bain de sang", "nombreuses victimes"),
                ),
            ),
        ),
    )

private fun catGuerre(): Category =
    Category(
        "guerre", "Guerres et terrorisme", "Conflits armés, attentats, menaces militaires", true,
        listOf(
            sub(
                "guerre.conflits", "Conflits armés",
                t(
                    "guerre.conflits.guerres", "Guerres",
                    s("guerre", "guerres", "conflit armé", "conflits armés", "ligne de front", "combats", "offensive*", "contre-offensive*", "champ de bataille", "belligérant*", "cessez-le-feu"),
                    m("armée*", "soldat*", "militaire*", "troupes"),
                ),
                t(
                    "guerre.conflits.invasions", "Invasions",
                    s("invasion*", "envahi*", "annexion*", "annexé*", "incursion*", "occupation militaire", "territoires occupés"),
                ),
                t(
                    "guerre.conflits.bombardements", "Bombardements",
                    s("bombardement*", "bombardé*", "frappes aériennes", "frappe aérienne", "frappe de missile*", "frappes de missile*", "missile*", "roquette*", "obus", "pilonnage*", "pilonné*", "raid aérien", "raids aériens"),
                    m("drone*", "explosion*"),
                ),
                t(
                    "guerre.conflits.civils", "Victimes civiles",
                    s("victimes civiles", "civils tués", "morts civils", "pertes civiles", "crime de guerre", "crimes de guerre", "génocide*", "nettoyage ethnique"),
                    all(STRONG, listOf("civils", "civil", "enfants", "population*"), listOf("tués", "morts", "victimes", "bombardé*", "massacré*")),
                ),
            ),
            sub(
                "guerre.terrorisme", "Terrorisme",
                t(
                    "guerre.terrorisme.attentats", "Attentats",
                    s("attentat*", "kamikaze*", "explosion meurtrière", "attaque terroriste", "attaques terroristes", "acte terroriste", "engin explosif", "colis piégé", "voiture piégée", "djihadiste*", "jihadiste*", "terroriste*"),
                    m("bombe*"),
                ),
                t(
                    "guerre.terrorisme.menaces", "Menaces terroristes",
                    s("menace terroriste", "menaces terroristes", "risque terroriste", "projet d'attentat", "attentat déjoué", "complot terroriste", "radicalisé*", "fiché s", "daech", "état islamique"),
                    m("islamiste*"),
                ),
                t(
                    "guerre.terrorisme.alertes", "Alertes terroristes",
                    s("alerte attentat", "urgence attentat", "vigipirate", "alerte terroriste", "alertes terroristes", "alerte à la bombe", "colis suspect"),
                ),
            ),
            sub(
                "guerre.militaire", "Menaces militaires",
                t(
                    "guerre.militaire.nucleaire", "Menaces nucléaires",
                    s("menace nucléaire", "arme nucléaire", "armes nucléaires", "bombe atomique", "guerre nucléaire", "frappe nucléaire", "arsenal nucléaire", "dissuasion nucléaire", "ogive*", "missile balistique", "missiles balistiques", "essai nucléaire", "hiver nucléaire"),
                    m("nucléaire*"),
                ),
                t(
                    "guerre.militaire.mobilisations", "Mobilisations",
                    s("mobilisation générale", "mobilisation militaire", "mobilisation partielle", "conscription", "appel sous les drapeaux", "réservistes", "service militaire obligatoire"),
                ),
                t(
                    "guerre.militaire.escalades", "Escalades militaires",
                    s("escalade militaire", "escalade des tensions", "tensions militaires", "troisième guerre mondiale", "3e guerre mondiale", "guerre mondiale", "ultimatum", "manœuvres militaires", "déploiement de troupes"),
                    m("escalade", "riposte*", "représailles", "otan"),
                ),
            ),
        ),
    )

private fun catCatastrophes(): Category =
    Category(
        "catastrophes", "Catastrophes naturelles", "Séismes, tempêtes, incendies, pollutions", true,
        listOf(
            sub(
                "catastrophes.geo", "Catastrophes géologiques",
                t("catastrophes.geo.seismes", "Séismes", s("séisme*", "tremblement de terre", "tremblements de terre", "secousse sismique", "secousses", "sismique*", "répliques"), m("magnitude")),
                t("catastrophes.geo.tsunamis", "Tsunamis", s("tsunami*", "raz-de-marée", "raz de marée", "vague géante", "vagues géantes")),
                t("catastrophes.geo.volcans", "Éruptions volcaniques", s("éruption volcanique", "éruptions volcaniques", "volcan*", "coulée de lave", "nuage de cendres"), m("éruption*", "lave")),
                t("catastrophes.geo.glissements", "Glissements de terrain", s("glissement de terrain", "glissements de terrain", "coulée de boue", "coulées de boue", "éboulement*", "avalanche*", "effondrement de terrain")),
            ),
            sub(
                "catastrophes.meteo", "Catastrophes météorologiques",
                t("catastrophes.meteo.ouragans", "Ouragans", s("ouragan*", "cyclone*", "typhon*")),
                t("catastrophes.meteo.tornades", "Tornades", s("tornade*", "supercellule*"), m("trombe*")),
                t("catastrophes.meteo.inondations", "Inondations", s("inondation*", "inondé*", "crue*", "submersion", "submergé*", "montée des eaux", "pluies diluviennes", "déluge")),
                t("catastrophes.meteo.tempetes", "Tempêtes", s("tempête*", "vents violents", "orage violent", "orages violents", "grêle*", "alerte météo", "vigilance rouge", "vigilance orange", "bourrasque*"), m("rafales")),
            ),
            sub(
                "catastrophes.incendies", "Incendies",
                t(
                    "catastrophes.incendies.forets", "Feux de forêt",
                    s("feu de forêt", "feux de forêt", "incendie de forêt", "incendies de forêt", "méga-feu*", "mégafeu*", "feux de végétation", "feu de végétation", "hectares brûlés", "hectares partis en fumée"),
                    all(STRONG, listOf("incendie*", "feu", "feux", "flammes"), listOf("forêt*", "hectares", "végétation", "maquis", "pinède*", "garrigue")),
                ),
                t(
                    "catastrophes.incendies.majeurs", "Incendies majeurs",
                    s("incendie géant", "incendie majeur", "incendie gigantesque", "gigantesque incendie", "violent incendie", "immense incendie", "ravagé par les flammes", "ravagé par un incendie", "incendie meurtrier", "brasier*", "partis en fumée", "en proie aux flammes"),
                    m("incendie*"),
                ),
            ),
            sub(
                "catastrophes.env", "Catastrophes environnementales",
                t("catastrophes.env.pollutions", "Pollutions majeures", s("pollution majeure", "marée noire", "nappe de pétrole", "fuite de pétrole", "pollution chimique", "eau contaminée", "pfas", "polluants éternels", "nuage toxique", "pic de pollution", "pollution massive")),
                t("catastrophes.env.accidents", "Accidents écologiques", s("catastrophe écologique", "désastre écologique", "catastrophe environnementale", "accident écologique", "fuite radioactive", "catastrophe nucléaire", "tchernobyl", "fukushima", "mortalité massive", "poissons morts", "algues vertes")),
            ),
        ),
    )

private fun catDeces(): Category =
    Category(
        "deces", "Décès et drames humains", "Décès, drames familiaux, disparitions, deuils", true,
        listOf(
            sub(
                "deces.deces", "Décès",
                t(
                    "deces.deces.celebrites", "Décès de célébrités",
                    all(STRONG, CELEBRITY, PASSED_AWAY),
                    s("deuil national", "hommage posthume", "s'est éteint", "s'est éteinte", "nous a quittés", "nécrologie*", "obsèques", "funérailles", "in memoriam"),
                ),
                t(
                    "deces.deces.jeunes", "Décès de jeunes personnes",
                    all(STRONG, YOUNG_STRICT, DEATH),
                    all(MEDIUM, YOUNG_LOOSE, DEATH),
                ),
                t(
                    "deces.deces.inattendues", "Morts inattendues",
                    s("mort inattendue", "décès inattendu", "disparu trop tôt", "partie trop tôt", "parti trop tôt", "en pleine jeunesse", "mort mystérieuse", "mort inexpliquée", "décès inexpliqué"),
                    all(STRONG, SUDDEN, DEATH),
                    all(STRONG, FOUND, listOf("sans vie", "inanimé*", "mort", "morte")),
                ),
            ),
            sub(
                "deces.familles", "Drames familiaux",
                t(
                    "deces.familles.enfants", "Décès d'enfants",
                    s("mort d'un enfant", "décès d'un enfant", "perte d'un enfant", "mort d'un bébé", "décès d'un bébé", "mort subite du nourrisson", "enfant noyé", "fillette tuée", "garçonnet tué", "bébé secoué"),
                    all(STRONG, CHILD, DEATH + listOf("noyé*", "noyade*")),
                ),
                t(
                    "deces.familles.drames", "Drames familiaux",
                    s("drame familial", "drames familiaux", "tragédie familiale", "drame conjugal", "famille endeuillée", "famille décimée", "infanticide*", "féminicide*"),
                ),
                t(
                    "deces.familles.tragiques", "Disparitions tragiques",
                    s("disparition tragique", "disparu tragiquement", "disparue tragiquement", "tragique disparition", "emporté par les vagues", "emportée par les vagues", "noyade*", "noyé*", "noyée*", "corps retrouvé", "corps a été retrouvé", "corps sans vie"),
                ),
            ),
            sub(
                "deces.disparitions", "Disparitions",
                t(
                    "deces.disparitions.disparus", "Personnes disparues",
                    s("disparu depuis", "disparue depuis", "porté disparu", "portée disparue", "portés disparus", "avis de recherche", "appel à témoins", "toujours introuvable", "personne disparue", "personnes disparues", "alerte enlèvement"),
                    m("introuvable*"),
                ),
                t(
                    "deces.disparitions.inquietantes", "Disparitions inquiétantes",
                    s("disparition inquiétante", "disparitions inquiétantes", "disparition inexpliquée", "sans nouvelles de", "aucune nouvelle de", "plus donné signe de vie", "battue*"),
                    m("fouilles", "ratissage"),
                ),
            ),
            sub(
                "deces.temoignages", "Témoignages",
                t(
                    "deces.temoignages.deuil", "Récits de deuil",
                    s("deuil", "endeuillé*", "mon père est mort", "ma mère est morte", "j'ai perdu mon", "j'ai perdu ma", "j'ai perdu mes", "perte d'un proche", "repose en paix", "tu nous manques", "depuis ton départ", "dernier adieu", "veillée funèbre", "enterrement", "cimetière"),
                    m("rip"),
                ),
                t(
                    "deces.temoignages.tragiques", "Histoires personnelles tragiques",
                    s("histoire tragique", "récit bouleversant", "témoignage bouleversant", "histoire bouleversante", "destin tragique", "calvaire", "descente aux enfers", "a tout perdu", "cagnotte pour"),
                    m("drame*", "tragique*", "tragédie*"),
                ),
            ),
        ),
    )

private fun catEconomie(): Category =
    Category(
        "economie", "Économie et finances", "Crises, emploi, coût de la vie, précarité", false,
        listOf(
            sub(
                "economie.crises", "Crises économiques",
                t("economie.crises.recessions", "Récessions", s("récession*", "dépression économique", "crise économique", "effondrement économique", "croissance négative", "stagflation", "ralentissement économique")),
                t("economie.crises.financieres", "Crises financières", s("crise financière", "krach*", "effondrement des marchés", "chute des marchés", "panique boursière", "bulle financière", "défaut de paiement"), m("dévisse", "plonge en bourse")),
                t("economie.crises.banques", "Faillites bancaires", s("faillite bancaire", "faillites bancaires", "banque en faillite", "ruée sur les banques", "bank run", "banqueroute")),
            ),
            sub(
                "economie.emploi", "Emploi",
                t("economie.emploi.licenciements", "Licenciements", s("licenciement*", "licencié*", "licenciée*", "plan social", "plans sociaux", "suppression de postes", "suppressions de postes", "suppression d'emplois", "suppressions d'emplois", "postes supprimés", "emplois supprimés", "plan de départs")),
                t("economie.emploi.fermetures", "Fermetures d'entreprises", s("fermeture d'usine", "fermeture de l'usine", "fermeture du site", "fermetures d'usines", "clé sous la porte", "liquidation judiciaire", "redressement judiciaire", "dépôt de bilan", "faillite*", "cessation d'activité", "fermeture définitive")),
                t("economie.emploi.chomage", "Chômage", s("chômage", "chômeur*", "chômeuse*", "demandeurs d'emploi", "perte d'emploi", "perdu son emploi", "sans emploi")),
            ),
            sub(
                "economie.vie", "Coût de la vie",
                t("economie.vie.inflation", "Inflation", s("inflation", "hyperinflation", "indice des prix", "pouvoir d'achat")),
                t("economie.vie.prix", "Hausse des prix", s("hausse des prix", "flambée des prix", "envolée des prix", "prix s'envolent", "prix flambent", "augmentation des prix", "hausse du prix", "prix de l'énergie", "prix de l'essence", "prix du carburant", "facture d'électricité", "factures d'électricité", "hausse des tarifs", "explosion des prix")),
                t("economie.vie.logement", "Crise du logement", s("crise du logement", "mal-logement", "mal logés", "pénurie de logements", "hausse des loyers", "loyers flambent", "sans-abri", "sans abri", "sdf", "sans domicile")),
            ),
            sub(
                "economie.precarite", "Précarité",
                t("economie.precarite.dettes", "Surendettement", s("surendettement", "surendetté*", "croule sous les dettes", "crédit revolving", "huissier*"), m("dettes", "endetté*")),
                t("economie.precarite.expulsions", "Expulsions", s("expulsion locative", "expulsions locatives", "expulsé de son logement", "expulsée de son logement", "mis à la rue", "jeté à la rue", "trêve hivernale", "avis d'expulsion"), m("expulsion*", "expulsé*")),
                t("economie.precarite.pauvrete", "Pauvreté", s("pauvreté", "précarité", "seuil de pauvreté", "banque alimentaire", "restos du cœur", "aide alimentaire", "misère", "fins de mois difficiles"), m("précaire*")),
            ),
        ),
    )

private fun catClimat(): Category =
    Category(
        "climat", "Climat et environnement", "Réchauffement, biodiversité, ressources", false,
        listOf(
            sub(
                "climat.rechauffement", "Réchauffement climatique",
                t("climat.rechauffement.projections", "Projections alarmantes", s("point de non-retour", "point de bascule", "emballement climatique", "catastrophe climatique", "effondrement climatique", "urgence climatique", "giec", "planète invivable", "invivable*", "scénario catastrophe", "collapsologie"), m("réchauffement climatique", "dérèglement climatique", "changement climatique")),
                t("climat.rechauffement.records", "Records de température", s("record de chaleur", "records de chaleur", "record de température", "records de température", "canicule*", "vague de chaleur", "dôme de chaleur", "température record", "températures record*", "jamais aussi chaud", "année la plus chaude", "mois le plus chaud", "fournaise")),
                t("climat.rechauffement.consequences", "Conséquences graves", s("fonte des glaces", "fonte des glaciers", "montée des eaux", "niveau de la mer", "niveau des océans", "réfugiés climatiques", "désertification", "acidification des océans", "blanchissement des coraux", "permafrost", "pergélisol"), m("sécheresse*")),
            ),
            sub(
                "climat.env", "Environnement",
                t("climat.env.extinction", "Extinction d'espèces", s("extinction*", "espèce menacée", "espèces menacées", "en voie de disparition", "disparition des espèces", "effondrement de la biodiversité", "sixième extinction", "déclin des insectes", "disparition des abeilles", "liste rouge")),
                t("climat.env.deforestation", "Déforestation", s("déforestation", "forêts détruites", "coupes rases", "coupe rase", "abattage massif", "forêt primaire")),
                t("climat.env.ecosystemes", "Destruction des écosystèmes", s("destruction des écosystèmes", "écosystème détruit", "écosystèmes menacés", "écocide*", "effondrement des écosystèmes", "zones mortes", "biodiversité menacée", "continent de plastique", "microplastiques")),
            ),
            sub(
                "climat.ressources", "Ressources",
                t("climat.ressources.eau", "Pénuries d'eau", s("pénurie d'eau", "pénuries d'eau", "manque d'eau", "stress hydrique", "restrictions d'eau", "nappes phréatiques", "eau rationnée", "coupures d'eau", "guerre de l'eau"), m("sécheresse*")),
                t("climat.ressources.alimentaire", "Crises alimentaires", s("crise alimentaire", "crises alimentaires", "famine*", "insécurité alimentaire", "pénurie alimentaire", "pénuries alimentaires", "malnutrition", "récoltes détruites", "mauvaises récoltes")),
                t("climat.ressources.epuisement", "Épuisement des ressources", s("épuisement des ressources", "jour du dépassement", "pic pétrolier", "ressources s'épuisent", "surexploitation", "fin du pétrole"), m("raréfaction")),
            ),
        ),
    )

private fun catMenaces(): Category =
    Category(
        "menaces", "Menaces et situations anxiogènes", "Insécurité, pannes, alertes, titres alarmistes", false,
        listOf(
            sub(
                "menaces.insecurite", "Insécurité",
                t("menaces.insecurite.cambriolages", "Cambriolages", s("cambriolage*", "cambriolé*", "cambrioleur*", "vol par effraction", "effraction*")),
                t("menaces.insecurite.intrusions", "Intrusions", s("intrusion*", "intrus", "s'est introduit*", "home-jacking", "home jacking", "squatteur*", "squatté*", "violation de domicile")),
                t("menaces.insecurite.personnelles", "Menaces personnelles", s("menaces de mort", "menace de mort", "menacé de mort", "menacée de mort", "harcèlement", "harcelé*", "harceleur*", "chantage", "cyberharcèlement", "sextorsion")),
            ),
            sub(
                "menaces.tech", "Risques technologiques",
                t("menaces.tech.cyber", "Cyberattaques majeures", s("cyberattaque*", "piratage*", "piraté*", "hacker*", "rançongiciel*", "ransomware", "fuite de données", "données dérobées", "données volées", "vol de données", "faille de sécurité", "attaque informatique", "cybercriminel*")),
                t("menaces.tech.pannes", "Pannes électriques massives", s("black-out", "blackout", "panne géante", "panne d'électricité", "pannes d'électricité", "panne générale", "coupure de courant", "coupures de courant", "délestage*", "privés d'électricité", "privés de courant", "panne de courant")),
                t("menaces.tech.reseaux", "Effondrements de réseaux", s("panne mondiale", "panne informatique mondiale", "panne internet", "internet coupé", "panne de réseau", "coupure internet", "effondrement du réseau", "réseau en panne")),
            ),
            sub(
                "menaces.collectives", "Menaces collectives",
                t("menaces.collectives.penuries", "Pénuries", s("pénurie*", "rupture de stock", "ruptures de stock", "rayons vides", "rationnement*", "rationné*", "stocks épuisés")),
                t("menaces.collectives.evacuations", "Évacuations", s("évacuation*", "évacué*", "évacuer", "ordre d'évacuation", "confinez-vous", "mise à l'abri", "périmètre de sécurité")),
                t("menaces.collectives.alertes", "Alertes de sécurité", s("alerte de sécurité", "alerte sécurité", "fr-alert", "fr alert", "alerte maximale", "état d'urgence", "état d'alerte", "alerte rouge", "alerte à la population", "niveau d'alerte"), m("sirènes")),
            ),
            sub(
                "menaces.alarmistes", "Informations alarmistes",
                t("menaces.alarmistes.titres", "Titres catastrophistes", s("catastrophe*", "catastrophique*", "apocalypse*", "apocalyptique*", "chaos", "cauchemar", "terrifiant*", "effrayant*", "horreur", "le pire est à venir", "sans précédent"), m("dramatique*", "alarmant*", "inquiétant*", "glaçant*", "panique")),
                t("menaces.alarmistes.rumeurs", "Rumeurs anxiogènes", s("on nous cache", "ce qu'on ne vous dit pas", "ne veulent pas que vous sachiez", "théorie du complot", "partagez avant suppression", "partagez massivement", "faites tourner"), m("rumeur*", "complot*")),
                t("menaces.alarmistes.predictions", "Prédictions alarmantes", s("prophétie*", "prédit la fin", "fin du monde", "prédit une catastrophe", "nostradamus", "baba vanga", "selon une prophétie", "pire scénario", "scénario du pire"), m("prédiction*")),
            ),
        ),
    )

/** Accès rapide par identifiant. */
object TaxonomyIndex {
    val themesById: Map<String, Theme> = TAXONOMY.flatMap { it.allThemes }.associateBy { it.id }
    val categoryById: Map<String, Category> = TAXONOMY.associateBy { it.id }
    val allThemeIds: Set<String> = themesById.keys
    val defaultEnabledThemeIds: Set<String> =
        TAXONOMY.filter { it.defaultEnabled }.flatMap { it.allThemes }.map { it.id }.toSet()

    fun themeName(id: String): String = themesById[id]?.name ?: id
}
