package it.geometra.schedacliente

import android.graphics.PointF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CorrezioniRevisioneTest {

    @Test
    fun idGeneratiInRapidaSuccessioneSonoTuttiDiversiECrescenti() {
        val ids = (1..10_000).map { IdUnivoci.nuovo() }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.zipWithNext().all { (a, b) -> b > a })
        assertTrue(ids.all { it > 0 })
    }

    @Test
    fun idNonRiusaUnoGiaPresenteInArchivio() {
        // Simula un record importato da un telefono con l'orologio avanti di qualche ms.
        val futuro = System.currentTimeMillis() + 5
        val esistenti = (futuro - 5..futuro + 5).toSet()
        repeat(20) { assertTrue(IdUnivoci.nuovo(esistenti) !in esistenti) }
    }

    @Test
    fun idGeneratiDaPiuThreadNonCollidono() {
        val risultati = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val thread = (1..8).map { Thread { repeat(2_000) { risultati.add(IdUnivoci.nuovo()) } } }
        thread.forEach { it.start() }; thread.forEach { it.join() }
        assertEquals(risultati.size, risultati.toSet().size)
    }

    @Test
    fun centroideDiListaVuotaNonEsplode() {
        val c = GeometriaUtils.centroide(emptyList())
        assertEquals(0f, c.x, 0f); assertEquals(0f, c.y, 0f)
    }

    @Test
    fun centroideNormaleInvariato() {
        val c = GeometriaUtils.centroide(listOf(PointF(0f, 0f), PointF(4f, 0f), PointF(4f, 2f), PointF(0f, 2f)))
        assertEquals(2f, c.x, 1e-6f); assertEquals(1f, c.y, 1e-6f)
    }

    @Test
    fun azzeramentiGiornataETotaleSonoIndipendenti() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        AvvistamentiFdmLocaliStore.aggiungi(ctx, "galli")
        AvvistamentiFdmLocaliStore.aggiungi(ctx, "celli")
        Thread.sleep(5)
        AvvistamentiFdmLocaliStore.azzeraGiornata(ctx)
        assertEquals(0, AvvistamentiFdmLocaliStore.vociGiornata(ctx).size)
        assertEquals(2, AvvistamentiFdmLocaliStore.vociTotale(ctx).size)
        Thread.sleep(5)
        AvvistamentiFdmLocaliStore.aggiungi(ctx, "mondiale")
        assertEquals(1, AvvistamentiFdmLocaliStore.vociGiornata(ctx).size)
        assertEquals(3, AvvistamentiFdmLocaliStore.vociTotale(ctx).size)
        Thread.sleep(5)
        AvvistamentiFdmLocaliStore.azzeraTotale(ctx)
        assertEquals(0, AvvistamentiFdmLocaliStore.vociTotale(ctx).size)
        assertEquals(1, AvvistamentiFdmLocaliStore.vociGiornata(ctx).size)
        // Le due voci non più visibili in nessun sondaggio sono state eliminate dal file.
        assertEquals(1, AvvistamentiFdmLocaliStore.leggiTutti(ctx).size)
    }
}
