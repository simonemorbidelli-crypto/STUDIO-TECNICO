package it.geometra.schedacliente

import it.geometra.schedacliente.ConformitaCalcolo.Esito
import it.geometra.schedacliente.ConformitaCalcolo.EsitoCampo
import it.geometra.schedacliente.ConformitaCalcolo.Fonte
import it.geometra.schedacliente.ConformitaCalcolo.TipoCampo
import it.geometra.schedacliente.ConformitaCalcolo.Vano
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ConformitaCalcoloTest {

    @Test
    fun fasceDiTolleranzaComma1bis() {
        val f = ConformitaCalcolo::percentualeTolleranza
        assertEquals(2.0, f(650.0, true), 0.0)
        assertEquals(3.0, f(400.0, true), 0.0)
        assertEquals(4.0, f(150.0, true), 0.0)
        assertEquals(5.0, f(80.0, true), 0.0)
        assertEquals(6.0, f(45.0, true), 0.0)
        // dopo il 24/5/2024 (o non indicato) vale sempre il 2% fisso
        assertEquals(2.0, f(45.0, false), 0.0)
        assertEquals(2.0, f(null, true), 0.0)
    }

    @Test
    fun alLimiteDiFasciaSiApplicaLaPiuPrudente() {
        assertEquals(4.0, ConformitaCalcolo.percentualeTolleranza(100.0, true), 0.0)   // tra 4% e 5%
        assertEquals(5.0, ConformitaCalcolo.percentualeTolleranza(60.0, true), 0.0)    // tra 5% e 6%
        assertEquals(3.0, ConformitaCalcolo.percentualeTolleranza(300.0, true), 0.0)   // tra 3% e 4%
        assertEquals(2.0, ConformitaCalcolo.percentualeTolleranza(500.0, true), 0.0)   // tra 2% e 3%
        assertTrue(ConformitaCalcolo.alLimiteDiFascia(100.0))
    }

    @Test
    fun abbinamentoPerNomeIgnoraAccentiEMaiuscole() {
        val r = ConformitaCalcolo.confrontaVani(
            listOf(Vano("Cucina", 10.0), Vano("W.C.", 3.0), Vano("Studio", 12.0)),
            listOf(Vano("CUCINA ", 10.2), Vano("wc", 3.0), Vano("Ripostiglio", 2.0))
        )
        // "W.C." e "wc" si normalizzano in modo diverso ("w c" / "wc"): restano separati e segnalati
        assertTrue(r.any { it.nome == "Cucina" && it.deltaAreaM2 != null })
        assertTrue(r.any { it.soloInRiferimento && it.nome == "Studio" })
        assertTrue(r.any { it.soloInMisurato && it.nome == "Ripostiglio" })
    }

    @Test
    fun titoloVsRilievo_entroEOltreSoglia() {
        val titolo = listOf(Vano("Soggiorno", 25.0, 2.70, abitabile = true), Vano("Camera", 15.0, 2.70, abitabile = true))
        val entro = listOf(Vano("Soggiorno", 25.4, 2.70, true), Vano("Camera", 14.8, 2.70, true))
        val r = ConformitaCalcolo.confronta(Fonte.TITOLO, titolo, Fonte.RILIEVO, entro, lavoriConclusiEntro24052024 = false)
        val sup = r.parametri.first { it.nome.startsWith("Superficie") }
        assertEquals(2.0, sup.tolleranzaPerc!!, 0.0)
        assertEquals(Esito.ENTRO_SOGLIA, sup.esito)   // 40,0 -> 40,2 = +0,5%
        val fuori = listOf(Vano("Soggiorno", 27.0, 2.70, true), Vano("Camera", 15.0, 2.70, true))
        val r2 = ConformitaCalcolo.confronta(Fonte.TITOLO, titolo, Fonte.RILIEVO, fuori, false)
        assertEquals(Esito.OLTRE_SOGLIA, r2.parametri.first { it.nome.startsWith("Superficie") }.esito)   // +5%
        // stessa differenza, ma unità <60 mq con lavori ante 24/5/2024: soglia 6%
        val piccolo = listOf(Vano("Monolocale", 40.0)); val misPiccolo = listOf(Vano("Monolocale", 42.0))
        val r3 = ConformitaCalcolo.confronta(Fonte.TITOLO, piccolo, Fonte.RILIEVO, misPiccolo, true)
        assertEquals(6.0, r3.parametri.first().tolleranzaPerc!!, 0.0)
        assertEquals(Esito.ENTRO_SOGLIA, r3.parametri.first().esito)   // +5%
    }

    @Test
    fun minimoDiLeggeSempre2PercentAncheConFasciaAlta() {
        // unità < 60 mq, lavori ante 24/5/2024: per l'altezza di progetto vale il 6%, per il minimo di legge solo il 2%
        val titolo = listOf(Vano("Camera", 12.0, 2.75, abitabile = true))
        val rilievo = listOf(Vano("Camera", 12.0, 2.60, abitabile = true))   // -5,45% su 2,75; < 2,646
        val r = ConformitaCalcolo.confronta(Fonte.TITOLO, titolo, Fonte.RILIEVO, rilievo, true)
        assertEquals(Esito.ENTRO_SOGLIA, r.parametri.first { it.nome.startsWith("Altezza –") }.esito)           // 6%
        assertEquals(Esito.OLTRE_SOGLIA, r.parametri.first { it.nome.startsWith("Altezza minima") }.esito)      // < 2,646
        val ok = ConformitaCalcolo.confronta(Fonte.TITOLO, titolo, Fonte.RILIEVO, listOf(Vano("Camera", 12.0, 2.65, true)), true)
        assertEquals(Esito.ENTRO_SOGLIA, ok.parametri.first { it.nome.startsWith("Altezza minima") }.esito)     // >= 2,646
    }

    @Test
    fun catastoNonApplicaSogliaEdilizia() {
        val r = ConformitaCalcolo.confronta(Fonte.CATASTO, listOf(Vano("Cucina", 10.0)), Fonte.RILIEVO, listOf(Vano("Cucina", 11.0)), true)
        val p = r.parametri.first()
        assertNull(p.tolleranzaPerc)
        assertEquals(Esito.DIFFERENZA, p.esito)
        assertTrue(r.avvisi.any { it.contains("non applica soglie") })
    }

    @Test
    fun titoloVsCatasto() {
        val r = ConformitaCalcolo.confronta(Fonte.TITOLO, listOf(Vano("Cucina", 10.0), Vano("Bagno", 5.0)),
            Fonte.CATASTO, listOf(Vano("Cucina", 10.0)), false)
        assertTrue(r.avvisi.any { it.contains("Bagno") })
        assertEquals(Esito.UGUALE, r.parametri.first().esito)
    }

    @Test
    fun attoVsVisura() {
        val campi = listOf(
            ConformitaCalcolo.Campo("Foglio", TipoCampo.NUMERO, "015", "15"),
            ConformitaCalcolo.Campo("Particella", TipoCampo.NUMERO, "281", "282"),
            ConformitaCalcolo.Campo("Categoria", TipoCampo.CATEGORIA, "A/2", "a 2"),
            ConformitaCalcolo.Campo("Rendita", TipoCampo.NUMERO, "€ 1.234,50", "1234,5"),
            ConformitaCalcolo.Campo("Intestatari", TipoCampo.PERSONE, "Rossi Mario\nBianchi Anna", "Anna Bianchi; Mario Rossi"),
            ConformitaCalcolo.Campo("Classe", TipoCampo.TESTO, "", "3")
        )
        val e = ConformitaCalcolo.confrontaCampi(campi).map { it.esito }
        assertEquals(listOf(EsitoCampo.UGUALE, EsitoCampo.DIVERSO, EsitoCampo.UGUALE, EsitoCampo.UGUALE, EsitoCampo.UGUALE, EsitoCampo.MANCANTE), e)
    }

    @Test
    fun jsonRoundTripEriepilogo() {
        val d = DatiConformita(
            mapOf(Fonte.TITOLO to listOf(Vano("Cucina", 10.0, 2.7, true))), true, 80.0,
            ConformitaCalcolo.campiBase().map { if (it.etichetta == "Foglio") it.copy(atto = "15", visura = "15") else it }
        )
        val r = DatiConformita.daJson(d.toJson())
        assertEquals(d.vani, r.vani); assertEquals(true, r.lavoriEntro24052024); assertEquals(80.0, r.superficieUtileAssentitaMq!!, 0.0)
        assertEquals("15", r.campi.first { it.etichetta == "Foglio" }.atto)
        val ris = ConformitaCalcolo.confronta(Fonte.TITOLO, listOf(Vano("Cucina", 10.0)), Fonte.RILIEVO, listOf(Vano("Cucina", 10.1)), false)
        val txt = ConformitaCalcolo.riepilogo("Verifica", ris)
        assertTrue(txt.contains("non una dichiarazione di conformità"))
        assertTrue(!txt.contains("è conforme"))
    }
}
