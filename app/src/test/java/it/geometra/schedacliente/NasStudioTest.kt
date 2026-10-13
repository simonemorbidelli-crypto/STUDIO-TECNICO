package it.geometra.schedacliente

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * NAS dello studio (v3.78).
 *
 * Le prove sul percorso e sulla ricerca della cartella del cliente girano sempre. Quelle di
 * collegamento richiedono un server SMB di prova (Samba configurato come il NAS326: solo SMB2,
 * utente e password) e si attivano con la variabile d'ambiente NAS_PROVA_PERCORSO, es.:
 *   NAS_PROVA_PERCORSO='\\127.0.0.1\Pratiche' NAS_PROVA_UTENTE=studio NAS_PROVA_PASSWORD='...'
 *   NAS_PROVA_DISCO=/srv/nas      (cartella sul disco del server, per confrontare i byte scaricati)
 * Senza queste variabili vengono saltate.
 */
class NasStudioTest {

    // ---------------------------------------------------------------- percorso

    @Test
    fun percorsoScrittoComeInEsploraRisorse() {
        val p = NasStudio.analizzaPercorso("""\\192.168.1.20\Pratiche\Clienti 2026""")!!
        assertEquals("192.168.1.20", p.host); assertEquals(445, p.porta)
        assertEquals("Pratiche", p.condivisione); assertEquals("Clienti 2026", p.cartella)
        assertEquals("""\\192.168.1.20\Pratiche\Clienti 2026""", p.toString())
    }

    @Test
    fun altriModiDiScrivereIlPercorso() {
        assertEquals(NasStudio.Percorso("nas", 445, "Pratiche", ""), NasStudio.analizzaPercorso("smb://nas/Pratiche/"))
        assertEquals(NasStudio.Percorso("192.168.1.20", 445, "Pratiche", "A\\B"), NasStudio.analizzaPercorso(" 192.168.1.20/Pratiche/A/B "))
        assertEquals(NasStudio.Percorso("10.0.0.5", 4450, "Doc", ""), NasStudio.analizzaPercorso("""\\10.0.0.5:4450\Doc"""))
        assertNull("serve anche la condivisione", NasStudio.analizzaPercorso("""\\192.168.1.20"""))
        assertNull(NasStudio.analizzaPercorso(""))
        assertNull(NasStudio.analizzaPercorso("""\\nas:99999\Doc"""))
    }

    // ---------------------------------------------------------------- ricerca cartella cliente

    private val cartelle = listOf(
        "Bianchi Luca - Via Roma 12", "De Santis Anna", "Lucà Niccolò", "ROSSI GIUSEPPE", "Rossi Mario",
        "Mario Rossi (vecchia)", "Rossini Paola", "Twiga Europe S.p.a."
    )

    @Test
    fun trovaLaCartellaDelCliente() {
        // Cognome + nome: solo le cartelle con entrambi, la più corta per prima.
        assertEquals(listOf("Rossi Mario", "Mario Rossi (vecchia)"), NasStudio.cartellePerCliente(cartelle, "Rossi", "Mario"))
        // Maiuscole e ordine non contano; "Rossini" non è "Rossi".
        assertEquals(listOf("ROSSI GIUSEPPE"), NasStudio.cartellePerCliente(cartelle, "rossi", "giuseppe"))
        // Accenti e trattini non contano.
        assertEquals(listOf("Lucà Niccolò"), NasStudio.cartellePerCliente(cartelle, "Luca", "Niccolo"))
        assertEquals(listOf("Bianchi Luca - Via Roma 12"), NasStudio.cartellePerCliente(cartelle, "Bianchi", "Luca"))
        // Cognome di due parole; ragione sociale senza nome.
        assertEquals(listOf("De Santis Anna"), NasStudio.cartellePerCliente(cartelle, "De Santis", "Anna"))
        assertEquals(listOf("Twiga Europe S.p.a."), NasStudio.cartellePerCliente(cartelle, "Twiga Europe S.p.a.", ""))
        // Nome non trovato in nessuna cartella: restano quelle col cognome, da far scegliere.
        assertEquals(listOf("Rossi Mario", "ROSSI GIUSEPPE", "Mario Rossi (vecchia)"), NasStudio.cartellePerCliente(cartelle, "Rossi", "Anna"))
        assertTrue(NasStudio.cartellePerCliente(cartelle, "Verdi", "Paolo").isEmpty())
        assertTrue(NasStudio.cartellePerCliente(cartelle, "", "").isEmpty())
    }

    // ---------------------------------------------------------------- collegamento al NAS di prova

    private fun accesso(percorso: String? = System.getenv("NAS_PROVA_PERCORSO"), password: String? = System.getenv("NAS_PROVA_PASSWORD")): NasStudio.Accesso {
        assumeTrue("NAS di prova non configurato: prova saltata", percorso != null)
        return NasStudio.Accesso(NasStudio.analizzaPercorso(percorso!!)!!, System.getenv("NAS_PROVA_UTENTE") ?: "", password ?: "")
    }

    private fun disco(relativo: String): java.io.File = java.io.File(System.getenv("NAS_PROVA_DISCO") ?: "/srv/nas", relativo)

    @Test
    fun elencaLeCartelleDeiClienti() {
        val elenco = NasStudio.elencaCartelle(accesso())
        assertTrue(elenco.toString(), elenco.containsAll(listOf("Rossi Mario", "Rossi Giuseppe", "Bianchi Luca - Via Roma 12", "De Santis Anna", "Lucà Niccolò")))
        assertFalse("le cartelle di sistema/nascoste non si mostrano", elenco.any { it.startsWith(".") })
        assertEquals("ordine alfabetico", elenco.sortedBy { it.lowercase() }, elenco)
        assertEquals(elenco.size, NasStudio.prova(accesso()))
    }

    @Test
    fun cartellaDellePraticheConSpaziNelNome() {
        val a = accesso()
        val archivio = a.copy(percorso = a.percorso.copy(condivisione = "Archivio", cartella = "Clienti 2026"))
        assertEquals(listOf("Verdi Paolo"), NasStudio.elencaCartelle(archivio))
    }

    @Test
    fun leggeLaCartellaDelClienteConSottocartelle() {
        val c = NasStudio.leggiContenuto(accesso(), "Rossi Mario")
        assertEquals("Rossi Mario", c.radice.nome)
        assertEquals(listOf("Catasto", "Corrispondenza", "Foto sopralluogo"), c.sottocartelleDi(c.radice.handle).map { it.nome })
        assertEquals("Thumbs.db e desktop.ini nascosti", listOf("note.txt"), c.fileIn(c.radice.handle).map { it.nome })
        val catasto = c.sottocartelleDi(c.radice.handle).first { it.nome == "Catasto" }
        assertEquals(listOf("planimetria.jpg", "visura catastale.pdf"), c.fileIn(catasto.handle).map { it.nome })
        assertEquals(5, c.fileTotaliIn(c.radice.handle))
        val visura = c.file.first { it.nome == "visura catastale.pdf" }
        assertEquals("Catasto/visura catastale.pdf", visura.percorso)
        assertEquals(disco("Pratiche/Rossi Mario/Catasto/visura catastale.pdf").length(), visura.dimensione)
        // Struttura coerente con la schermata (la cartella del cliente non ha "genitore").
        assertEquals("", c.radice.genitore)
        assertTrue(c.cartelle.all { it.genitore.isNotEmpty() })
    }

    @Test
    fun scaricaIFileUgualiAllOriginale() {
        val a = accesso()
        val c = NasStudio.leggiContenuto(a, "Rossi Mario")
        for (nome in listOf("visura catastale.pdf", "planimetria.jpg", "note.txt")) {
            val f = c.file.first { it.nome == nome }
            assertArrayEquals(nome, disco("Pratiche/Rossi Mario/${f.percorso}").readBytes(), NasStudio.scarica(a, "Rossi Mario", f))
        }
        // Nomi con accenti.
        val l = NasStudio.leggiContenuto(a, "Lucà Niccolò")
        val atto = l.file.single()
        assertEquals("Atti/atto di compravendita 2019.pdf", atto.percorso)
        assertArrayEquals(disco("Pratiche/Lucà Niccolò/Atti/atto di compravendita 2019.pdf").readBytes(), NasStudio.scarica(a, "Lucà Niccolò", atto))
    }

    @Test
    fun erroriComprensibili() {
        fun errore(blocco: () -> Unit): NasStudio.ErroreNas {
            try { blocco() } catch (e: NasStudio.ErroreNas) { return e }
            fail("atteso un errore"); throw AssertionError()
        }
        val a = accesso()
        assertEquals("Utente o password del NAS non corretti",
            errore { NasStudio.elencaCartelle(accesso(password = "sbagliata")) }.message)
        assertTrue(errore { NasStudio.elencaCartelle(a.copy(percorso = a.percorso.copy(condivisione = "NonEsiste"))) }.message!!
            .contains("non esiste la cartella condivisa \"NonEsiste\""))
        val manca = errore { NasStudio.leggiContenuto(a, "Cliente Inesistente") }
        assertTrue(manca.cartellaNonTrovata)
        val nonRaggiungibile = errore { NasStudio.elencaCartelle(a.copy(percorso = a.percorso.copy(porta = 4459))) }
        assertTrue(nonRaggiungibile.message, nonRaggiungibile.message!!.startsWith("NAS non raggiungibile"))
        assertNotNull(nonRaggiungibile.cause)
    }
}
