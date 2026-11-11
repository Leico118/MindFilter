package com.mindfilter.app.engine

import com.mindfilter.app.data.CustomFilter
import com.mindfilter.app.data.Sensitivity
import com.mindfilter.app.data.TaxonomyIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class KeywordClassifierTest {

    private fun all(level: Int = 5) =
        KeywordClassifier(TaxonomyIndex.themesById.values, emptyList(), level)

    @Test
    fun normalisation() {
        assertEquals("l oms alerte sur une epidemie", TextNormalizer.normalize("L’OMS alerte sur une Épidémie !"))
    }

    @Test
    fun exempleDuCahierDesCharges() {
        assertNotNull(all().classify("Un sportif de 27 ans retrouvé sans vie pendant son sommeil."))
    }

    @Test
    fun exempleDetecteSeulementParDecesSoudains() {
        val only = KeywordClassifier(listOf(TaxonomyIndex.themesById.getValue("sante.deces.soudain")), emptyList(), 3)
        assertNotNull(only.classify("Un sportif de 27 ans retrouvé sans vie pendant son sommeil."))
    }

    @Test
    fun detectionsSimples() {
        val c = all()
        assertNotNull(c.classify("Séisme de magnitude 7 au Japon : des dizaines de victimes"))
        assertNotNull(c.classify("Il meurt d'un infarctus à 45 ans"))
        assertNotNull(c.classify("Collision frontale sur l'A7, deux morts"))
        assertNotNull(c.classify("Attaque au couteau dans une gare"))
        assertNotNull(c.classify("Nouvelle vague de covid : les hôpitaux saturés"))
    }

    @Test
    fun pasDeFauxPositifsEvidents() {
        val c = all()
        assertNull(c.classify("Recette facile de tarte aux pommes pour le dimanche"))
        assertNull(c.classify("J'étais mort de rire devant cette vidéo de chat"))
        assertNull(c.classify("Les plus beaux couchers de soleil de l'été"))
    }

    @Test
    fun humourEtJeuxVideoNeSontPasMasques() {
        for (level in 1..7) {
            val c = all(level)
            assertNull("cran $level", c.classify("Cette légende m'a fait mort de rire 😂😂 · J'aime · Commenter"))
            assertNull("cran $level", c.classify("Le chat qui tombe du canapé 🤣 je suis mort"))
            assertNull("cran $level", c.classify("Assassin's Creed Shadows : le nouveau trailer est enfin disponible"))
            assertNull("cran $level", c.classify("Retrouvé ce matin mon vieux pull préféré ! Sinon, la série Mort sur le Nil est top"))
        }
    }

    @Test
    fun vraiesInformationsToujoursMasquees() {
        for (level in 1..9) {
            val c = all(level)
            assertNotNull("cran $level", c.classify("Attentat déjoué à Lyon : deux suspects interpellés"))
            assertNotNull("cran $level", c.classify("Il meurt d'un infarctus à 45 ans"))
        }
    }

    @Test
    fun filtrePersonnalise() {
        val f = CustomFilter(name = "Série", keywords = listOf("spoiler", "épisode final"), sensitivity = Sensitivity.NORMAL)
        val c = KeywordClassifier(emptyList(), listOf(f), 7)
        assertNotNull(c.classify("Attention SPOILER sur la fin de la saison"))
        assertNull(c.classify("Une belle journée à la plage"))
    }

    // --- 0.9.2 : contexte et thèmes décochés -------------------------------------------------

    private fun choice(ids: Set<String>, level: Int = 5): KeywordClassifier {
        val v = TaxonomyIndex.vetoes(ids)
        return KeywordClassifier(ids.mapNotNull { TaxonomyIndex.themesById[it] }, emptyList(), level, emptyList(), v.deselected, v.inactive)
    }

    private val demonsOn = TaxonomyIndex.defaultEnabledThemeIds + TaxonomyIndex.allThemeIds.filter { it.startsWith("horreur.surnaturel") }

    @Test
    fun creationEtLoisirNeSontPasMasques() {
        for (level in 1..7) {
            val c = choice(demonsOn, level)
            assertNull("cran $level", c.classify("Mon cosplay de démon sexy pour la convention ce week-end #cosplay"))
            assertNull("cran $level", c.classify("Figurine LEGO du démon de Diablo, 3000 pièces, superbe maquette"))
            assertNull("cran $level", c.classify("Mon chat est un vrai petit démon ce matin"))
        }
        assertNotNull(choice(demonsOn).classify("Exorcisme : un prêtre raconte la possession démoniaque d'une adolescente"))
    }

    @Test
    fun histoireNestPasUneActualite() {
        val c = choice(TaxonomyIndex.allThemeIds)
        assertNull(c.classify("En 1912, le Titanic coulait : plus de 1500 morts. Retour sur l'histoire du naufrage"))
        assertNotNull(c.classify("Naufrage au large de Lampedusa : au moins 40 morts"))
    }

    @Test
    fun themeDecocheEmporteLeThemeVoisin() {
        val sansCelebrites = TaxonomyIndex.defaultEnabledThemeIds - "deces.deces.celebrites"
        val c = choice(sansCelebrites)
        val hommage = "RIP Matthew Perry, repose en paix, l'acteur de Friends restera une légende"
        assertNull(c.classify(hommage))
        assertNotNull(c.vetoHit(hommage))
        // Coché : masqué.
        assertNotNull(choice(TaxonomyIndex.defaultEnabledThemeIds).classify(hommage))
        // Un sujet coché nettement plus fort reste masqué.
        assertNotNull(c.classify("L'horreur d'un attentat à la gare de Lyon : 3 morts"))
    }
}
