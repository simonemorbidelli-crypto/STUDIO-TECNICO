package it.geometra.schedacliente

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ParereMegaTest {

    /** Cartella Mega simulata generata da uno script Node e già verificata con megajs (stessi nomi e contenuti). */
    private fun fixture() = JSONObject(javaClass.classLoader!!.getResource("mega_cartella_prova.json").readText())

    @Test
    fun leggeLaCartellaComeMegajs() {
        val f = fixture()
        val link = MegaCartellaPubblica.analizzaLink(f.getString("link"))
        assertNotNull(link)
        val file = MegaCartellaPubblica.costruisciElenco(f.getJSONArray("nodes"), link!!.chiave)
        assertEquals(listOf("Catasto/visura catastale.pdf", "nota è àccentata.txt"), file.map { it.percorso })
        val attesi = f.getJSONArray("expected")
        for (i in 0 until attesi.length()) {
            val atteso = attesi.getJSONObject(i)
            val trovato = file.single { it.handle == atteso.getString("h") }
            assertEquals(atteso.getString("name"), trovato.nome)
            val cifrato = android.util.Base64.decode(f.getJSONObject("encrypted").getString(trovato.handle), android.util.Base64.DEFAULT)
            val chiaro = MegaCartellaPubblica.decifraContenuto(cifrato, trovato.chiaveAes, trovato.iv)
            assertArrayEquals(android.util.Base64.decode(atteso.getString("plainB64"), android.util.Base64.DEFAULT), chiaro)
            assertEquals(chiaro.size.toLong(), trovato.dimensione)
        }
    }

    @Test
    fun chiaveSbagliataDaErroreChiaro() {
        val f = fixture()
        try {
            MegaCartellaPubblica.costruisciElenco(f.getJSONArray("nodes"), ByteArray(16) { 7 })
            fail("doveva fallire")
        } catch (e: MegaCartellaPubblica.ErroreMega) {
            assertTrue(e.message!!.contains("chiave"))
        }
    }

    @Test
    fun riconosceILink() {
        val k = "AbCdEfGhIjKlMnOpQrStUv"
        assertEquals("H1h2H3h4", MegaCartellaPubblica.analizzaLink("https://mega.nz/folder/H1h2H3h4#$k")?.handle)
        assertEquals("H1h2H3h4", MegaCartellaPubblica.analizzaLink("  https://mega.nz/folder/H1h2H3h4#$k/folder/sotto123 ")?.handle)
        assertEquals("H1h2H3h4", MegaCartellaPubblica.analizzaLink("https://mega.nz/#F!H1h2H3h4!$k")?.handle)
        assertEquals("H1h2H3h4", MegaCartellaPubblica.analizzaLink("Ecco il link: https://mega.co.nz/folder/H1h2H3h4#$k grazie")?.handle)
        assertNull(MegaCartellaPubblica.analizzaLink("https://mega.nz/folder/H1h2H3h4"))          // senza chiave
        assertNull(MegaCartellaPubblica.analizzaLink("https://mega.nz/file/H1h2H3h4#$k"))         // link di un file, non di cartella
        assertNull(MegaCartellaPubblica.analizzaLink("https://drive.google.com/drive/folders/abc"))
    }

    private fun clienteProva() = Cliente(
        cognome = "Rossi", nome = "Mario", codiceFiscale = "RSSMRA80A01A390X", partitaIva = "",
        telefono = "333 1234567", email = "mario@example.com", indirizzo = "Via Roma 1",
        provincia = "AR", comune = "Castelfranco Piandiscò", tipoPratica = "Visura", numeroPratica = "12",
        annoPratica = "2026", foglio = "15", particella = "281", subalterno = "3", note = "",
        linkMega = "https://mega.nz/folder/H1h2H3h4#AbCdEfGhIjKlMnOpQrStUv"
    )

    @Test
    fun linkMegaSalvatoNellaScheda() {
        val c = clienteProva()
        assertEquals(c, Cliente.fromJson(c.toJson()))
        val vecchia = c.toJson().apply { remove("linkMega") }
        assertEquals("", Cliente.fromJson(vecchia).linkMega)
    }

    @Test
    fun allAiSoloIDatiUtili() {
        val testo = GeneratoreParereAi.istruzioniUtente(clienteProva(), GeneratoreParereAi.TipoDocumento.PARERE, "verifica conformità", listOf("visura.pdf"))
        assertTrue(testo.contains("Foglio 15, Particella 281, Sub. 3"))
        assertTrue(testo.contains("verifica conformità"))
        assertFalse(testo.contains("333 1234567"))
        assertFalse(testo.contains("mario@example.com"))
        assertFalse(testo.contains("RSSMRA80A01A390X"))
    }

    @Test
    fun docxGeneratoSiRilegge() {
        val bozza = """
            # Parere tecnico sulla conformità catastale
            ## Premessa
            Il sottoscritto ha esaminato la **visura** e la planimetria (fonte: visura.pdf).
            Seconda riga dello stesso paragrafo con caratteri speciali: & < > "virgolette".

            ## Conclusioni
            - Prima voce
            - Seconda voce con **grassetto**
            Testo con ** asterischi spaiati.
        """.trimIndent()
        val uscita = File(System.getProperty("user.dir"), "build/anteprime/bozza_prova.docx")
        EsportatoreDocx.crea(uscita, clienteProva(), GeneratoreParereAi.TipoDocumento.PARERE, bozza, listOf("visura.pdf"), false)
        val testo = PreparatoreDocumenti.estraiTestoDocx(uscita.readBytes())
        assertTrue(testo.contains("Parere tecnico sulla conformità catastale"))
        assertTrue(testo.contains("Committente: Rossi Mario"))
        assertTrue(testo.contains("Il sottoscritto ha esaminato la visura e la planimetria (fonte: visura.pdf). Seconda riga"))
        assertTrue(testo.contains("& < > \"virgolette\""))
        assertTrue(testo.contains("Seconda voce con grassetto"))
        assertTrue(testo.contains("Testo con ** asterischi spaiati."))
        assertTrue(testo.contains("Il tecnico"))
        // Il titolo "# ..." non deve comparire due volte.
        assertEquals(1, Regex("Parere tecnico sulla conformità catastale").findAll(testo).count())
    }

    @Test
    fun tipiDiFile() {
        assertEquals(PreparatoreDocumenti.Tipo.PDF, PreparatoreDocumenti.tipoDi("Visura.PDF"))
        assertEquals(PreparatoreDocumenti.Tipo.WORD, PreparatoreDocumenti.tipoDi("relazione.docx"))
        assertEquals(PreparatoreDocumenti.Tipo.IMMAGINE, PreparatoreDocumenti.tipoDi("foto.jpeg"))
        assertEquals(PreparatoreDocumenti.Tipo.NON_SUPPORTATO, PreparatoreDocumenti.tipoDi("vecchio.doc"))
        assertTrue(PreparatoreDocumenti.motivoNonSupportato("vecchio.doc").contains(".docx"))
    }

    @Test
    fun strutturaACartelle() {
        val f = fixture()
        val link = MegaCartellaPubblica.analizzaLink(f.getString("link"))!!
        val c = MegaCartellaPubblica.costruisciContenuto(f.getJSONArray("nodes"), link.chiave)
        assertEquals("Pratica Rossi", c.radice.nome)
        assertEquals(listOf("Catasto"), c.sottocartelleDi(c.radice.handle).map { it.nome })
        assertEquals(listOf("nota è àccentata.txt"), c.fileIn(c.radice.handle).map { it.nome })
        val catasto = c.sottocartelleDi(c.radice.handle).single()
        assertEquals(listOf("visura catastale.pdf"), c.fileIn(catasto.handle).map { it.nome })
        assertEquals(2, c.fileTotaliIn(c.radice.handle))
        assertEquals(c.radice.handle, catasto.genitore)
    }

    @Test
    fun senzaSchedaClienteLAiRicavaIDatiDaiDocumenti() {
        val testo = GeneratoreParereAi.istruzioniUtente(null, GeneratoreParereAi.TipoDocumento.RELAZIONE, "", listOf("a.pdf"))
        assertTrue(testo.contains("Non c'è una scheda cliente"))
        assertTrue(testo.contains("Struttura suggerita"))
        val uscita = File(System.getProperty("user.dir"), "build/anteprime/bozza_senza_cliente.docx")
        EsportatoreDocx.crea(uscita, null, GeneratoreParereAi.TipoDocumento.RELAZIONE, "# Relazione\nTesto.", listOf("a.pdf"), false)
        assertTrue(PreparatoreDocumenti.estraiTestoDocx(uscita.readBytes()).contains("Committente: [DA COMPILARE]"))
    }
}
