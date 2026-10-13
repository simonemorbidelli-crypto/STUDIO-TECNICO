package it.geometra.schedacliente

import it.geometra.schedacliente.PeriziaCalcolo.Categoria.*
import it.geometra.schedacliente.PeriziaCalcolo.Destinazione
import it.geometra.schedacliente.PeriziaCalcolo.Voce
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PeriziaTest {

    private val appartamento = listOf(
        Voce("Soggiorno", 20.0, PRINCIPALE), Voce("Camera", 14.0, PRINCIPALE), Voce("Cucina", 10.0, PRINCIPALE),
        Voce("Bagno", 5.0, ACCESSORIO_DIRETTO), Voce("Ingresso", 4.0, ACCESSORIO_DIRETTO),
        Voce("Balcone", 30.0, BALCONE_COMUNICANTE), Voce("Cantina", 8.0, INDIRETTO_NON_COMUNICANTE),
        Voce("Giardino", 100.0, AREA_SCOPERTA)
    )

    @Test
    fun abitazioneCalcolataAMano() {
        // S = 44 + 9 = 53; balcone 25×30% + 5×10% = 8; cantina 8×25% = 2;
        // giardino 53×10% + 47×2% = 6,24 → totale 69,24 → 69 mq
        val r = PeriziaCalcolo.calcola(Destinazione.ABITAZIONE, appartamento)
        assertEquals(69.24, r.superficieCommerciale, 1e-9)
        assertEquals(69L, r.superficieArrotondata)
        assertEquals(8.0, r.righe.single { it.voce.nome == "Balcone" }.mqRagguagliati, 1e-9)
        assertTrue(r.note.isEmpty())
    }

    @Test
    fun limiteDel50PerCento() {
        val voci = appartamento.map { if (it.nome == "Giardino") it.copy(mq = 1000.0) else it }
        // pertinenze = 8 + 2 + (5,3 + 947×2% = 24,24) = 34,24 > 26,5 (50% di 53) → ridotte a 26,5
        val r = PeriziaCalcolo.calcola(Destinazione.ABITAZIONE, voci)
        assertEquals(53.0 + 26.5, r.superficieCommerciale, 1e-9)
        assertTrue(r.note.any { it.contains("50%") })
    }

    @Test
    fun negozioEMuri() {
        val voci = listOf(Voce("Negozio", 50.0, PRINCIPALE), Voce("Retro", 20.0, ACCESSORIO_DIRETTO), Voce("Area", 30.0, AREA_SCOPERTA))
        assertEquals(50 + 10 + 6.0, PeriziaCalcolo.calcola(Destinazione.NEGOZIO, voci).superficieCommerciale, 1e-9)
        // con +10% di muri sui coperti: 55 + 11 + 6
        assertEquals(55 + 11 + 6.0, PeriziaCalcolo.calcola(Destinazione.NEGOZIO, voci, 10.0).superficieCommerciale, 1e-9)
    }

    @Test
    fun villa() {
        val voci = listOf(Voce("Vani", 100.0, PRINCIPALE), Voce("Parco", 800.0, AREA_SCOPERTA))
        // conta solo oltre 5×100 = 500: 100×10% + 200×2% = 14
        assertEquals(114.0, PeriziaCalcolo.calcola(Destinazione.ABITAZIONE, voci, villa = true).superficieCommerciale, 1e-9)
        // senza regola villa: 100×10% + 700×2% = 24
        assertEquals(124.0, PeriziaCalcolo.calcola(Destinazione.ABITAZIONE, voci).superficieCommerciale, 1e-9)
    }

    @Test
    fun valoreECorrettivi() {
        assertEquals(69 * 1375 * 0.9, PeriziaCalcolo.valoreDiMercato(69.0, 1375.0, listOf(-10.0)), 1e-6)
        assertEquals(1.1 * 0.95, PeriziaCalcolo.fattoreCorrettivi(listOf(10.0, -5.0)), 1e-12)
    }

    @Test
    fun categorieDalNome() {
        assertEquals(ACCESSORIO_DIRETTO, PeriziaCalcolo.categoriaDaNome("Bagno 2"))
        assertEquals(ACCESSORIO_DIRETTO, PeriziaCalcolo.categoriaDaNome("DISIMPEGNO"))
        assertEquals(BALCONE_COMUNICANTE, PeriziaCalcolo.categoriaDaNome("Terrazzo"))
        assertEquals(INDIRETTO_NON_COMUNICANTE, PeriziaCalcolo.categoriaDaNome("Cantina"))
        assertEquals(AREA_SCOPERTA, PeriziaCalcolo.categoriaDaNome("Resede"))
        assertEquals(PRINCIPALE, PeriziaCalcolo.categoriaDaNome("Camera"))
    }

    private fun risorsa(n: String) = javaClass.classLoader!!.getResource(n).readText()

    @Test
    fun paginePubblicheOmi() {
        val province = OmiConsultazione.opzioni(risorsa("omi_province.html"), "pr")
        assertTrue(province.any { it.codice == "AR" })
        val comuni = OmiConsultazione.opzioni(risorsa("omi_comuni.html"), "co")
        assertTrue(comuni.any { it.codice == "F656" && it.descrizione == "MONTEVARCHI" })
        assertTrue(comuni.any { it.descrizione == "CASTELFRANCO PIANDISCO'" })
        assertEquals("20252", OmiConsultazione.opzioni(risorsa("omi_comuni.html"), "anno_semestre").first().codice)
        val zone = OmiConsultazione.opzioni(risorsa("omi_zone.html"), "linkzonastrada")
        assertEquals("AR00000623", zone[1].codice)
        assertTrue(zone[1].descrizione.startsWith("C1/Semicentrale/"))
        val valori = OmiConsultazione.analizzaTabella(risorsa("omi_valori.html"))
        val civili = valori.first { it.tipologia == "Abitazioni civili" && it.stato == "NORMALE" }
        assertEquals(1200.0, civili.minimo, 0.0)
        assertEquals(1550.0, civili.massimo, 0.0)
        assertEquals(1375.0, civili.medio, 0.0)
        assertEquals("L", civili.superficie)
    }
}
