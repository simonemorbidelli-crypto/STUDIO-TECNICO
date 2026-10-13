package it.geometra.schedacliente

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PeriziaCompletaTest {

    private fun dati() = DatiPerizia(
        destinazione = "Abitazione", 
        righe = listOf(
            DatiPerizia.Riga("Appartamento", "Principale", 92.5, "100%", 92.5),
            DatiPerizia.Riga("Garage", "Accessorio", 20.0, "50%", 10.0)
        ),
        superficieCommerciale = 102.5, superficieArrotondata = 103, note = listOf("Muri esterni al 100%"),
        omiDescrizione = "Arezzo, B1, 1° sem. 2026", omiTipologia = "Abitazioni civili",
        omiMinimo = 1500.0, omiMassimo = 2000.0, valoreUnitario = 1750.0,
        correttivi = listOf(DatiPerizia.Correttivo("Piano terra", -5.0)),
        valoreMercato = 170_000.0, valoreArrotondato = 170_000.0
    )

    private val testoAi = """
        # Perizia di stima – Appartamento in Arezzo
        ## Premessa
        Incarico conferito da Rossi Mario. [DA VERIFICARE: data dell'incarico]
        ## Consistenza
        [[CONSISTENZA]]
        ## Storia edilizia
        - Licenza edilizia n. 12/1975
        - [DA COMPILARE: condono]
        | Titolo | Anno |
        |---|---|
        | Licenza | 1975 |
        ## Valutazione
        [[VALUTAZIONE]]
    """.trimIndent()

    @Test
    fun jsonRoundTrip() {
        val d = dati()
        val r = DatiPerizia.daJson(d.toJson())
        assertEquals(d, r)
        val senzaOmi = d.copy(omiMinimo = null, omiMassimo = null, valoreUnitario = null, valoreMercato = null, valoreArrotondato = null)
        assertEquals(senzaOmi, DatiPerizia.daJson(senzaOmi.toJson()))
    }

    @Test
    fun docxValidoConTabelleEvidenziatura() {
        val f = File.createTempFile("perizia", ".docx")
        EsportatorePerizia.crea(f, dati(), Cliente(cognome = "Rossi", nome = "Mario", codiceFiscale = "", partitaIva = "", telefono = "", email = "",
            indirizzo = "Via Roma 1", provincia = "AR", comune = "Arezzo", tipoPratica = "Perizia",
            numeroPratica = "12", annoPratica = "2026", foglio = "15", particella = "281", subalterno = "3", note = ""), testoAi, listOf("visura.pdf"), false, listOf("x.dwg"))
        ZipFile(f).use { z ->
            val nomi = z.entries().toList().map { it.name }
            assertTrue(nomi.containsAll(listOf("word/document.xml", "word/header1.xml", "word/footer1.xml", "word/styles.xml")))
            nomi.filter { it.endsWith(".xml") || it.endsWith(".rels") }.forEach { n ->
                val xml = z.getInputStream(z.getEntry(n)).readBytes()
                javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.inputStream())
            }
            val doc = z.getInputStream(z.getEntry("word/document.xml")).readBytes().toString(Charsets.UTF_8)
            assertTrue(doc.contains("<w:tbl>"))
            assertTrue(doc.contains("w:highlight"))
            assertTrue(doc.contains("Garage"))
            assertTrue(doc.contains("170.000"))
            assertTrue(!doc.contains("[[CONSISTENZA]]") && !doc.contains("[[VALUTAZIONE]]"))
            assertTrue(doc.contains("x.dwg"))
            val stili = z.getInputStream(z.getEntry("word/styles.xml")).readBytes().toString(Charsets.UTF_8)
            assertTrue(stili.contains("it-IT"))
            val foot = z.getInputStream(z.getEntry("word/footer1.xml")).readBytes().toString(Charsets.UTF_8)
            assertTrue(foot.contains("NUMPAGES"))
        }
        f.delete()
    }

    @Test
    fun segnaposto_mancanti_aggiunti_in_fondo() {
        val f = File.createTempFile("perizia2", ".docx")
        EsportatorePerizia.crea(f, dati(), null, "# T\n## Descrizione\nTesto.", emptyList(), false)
        ZipFile(f).use { z ->
            val doc = z.getInputStream(z.getEntry("word/document.xml")).readBytes().toString(Charsets.UTF_8)
            assertTrue(doc.contains("Appartamento"))
        }
        f.delete()
    }

    @Test
    fun istruzioniContengonoDatiRichiestaENonLetti() {
        val s = GeneratoreParereAi.istruzioniPerizia(null, dati(), "Descrivi bene il garage", listOf("visura.pdf"), listOf("x.dwg"))
        assertTrue(s.contains("Descrivi bene il garage"))
        assertTrue(s.contains("visura.pdf"))
        assertTrue(s.contains("x.dwg"))
        assertTrue(s.contains("170"))
    }
}
