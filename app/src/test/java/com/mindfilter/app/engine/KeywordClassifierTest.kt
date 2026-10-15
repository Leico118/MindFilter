package com.mindfilter.app.engine

import com.mindfilter.app.data.CustomFilter
import com.mindfilter.app.data.Sensitivity
import com.mindfilter.app.data.TaxonomyIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class KeywordClassifierTest {

    private fun all(s: Sensitivity = Sensitivity.NORMAL) =
        KeywordClassifier(TaxonomyIndex.themesById.values, emptyList(), s)

    @Test
    fun normalisation() {
        assertEquals("l oms alerte sur une epidemie", TextNormalizer.normalize("L’OMS alerte sur une Épidémie !"))
    }

    @Test
    fun exempleDuCahierDesCharges() {
        val r = all().classify("Un sportif de 27 ans retrouvé sans vie pendant son sommeil.")
        assertNotNull(r)
    }

    @Test
    fun exempleDetecteSeulementParDecesSoudains() {
        val only = KeywordClassifier(listOf(TaxonomyIndex.themesById.getValue("sante.deces.soudain")), emptyList(), Sensitivity.LIGHT)
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
    fun filtrePersonnalise() {
        val f = CustomFilter(name = "Série", keywords = listOf("spoiler", "épisode final"), sensitivity = Sensitivity.NORMAL)
        val c = KeywordClassifier(emptyList(), listOf(f), Sensitivity.NORMAL)
        assertNotNull(c.classify("Attention SPOILER sur la fin de la saison"))
        assertNull(c.classify("Une belle journée à la plage"))
    }
}
