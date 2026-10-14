package it.geometra.schedacliente

import android.graphics.PointF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** DXF: il disegno deve aprirsi già inquadrato (limiti, vista corrente e viewport coerenti con i locali). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EsportatoreDxfVistaTest {
    private fun valore(righe: List<String>, variabile: String, codice: String): Double {
        val i = righe.indexOf(variabile)
        var j = i + 1
        while (righe[j] != codice) j += 2
        return righe[j + 1].toDouble()
    }

    @Test fun vistaIntestazioneEViewportInquadranoTuttoIlDisegno() {
        // Locale largo 20 m x alto 5 m (scala 0,01 m per pixel), lontano dall'origine.
        val verti = listOf(PointF(5000f, 3000f), PointF(7000f, 3000f), PointF(7000f, 3500f), PointF(5000f, 3500f))
        val dxf = EsportatoreDxf.genera(listOf(LocaleTracciato("Sala", verti, 100.0, emptyList())), 0.01, 0.01)
        File("/tmp/dxf_prova.dxf").writeBytes(dxf.toByteArray(Charsets.ISO_8859_1))
        val r = dxf.lines()
        val minX = valore(r, "\$EXTMIN", "10"); val maxX = valore(r, "\$EXTMAX", "10")
        assertTrue(minX < 50.0 && maxX > 70.0)
        assertEquals((minX + maxX) / 2, valore(r, "\$VIEWCTR", "10"), 1e-3)
        assertEquals(valore(r, "\$LIMMIN", "10"), minX, 1e-3)
        val ampiezza = maxX - minX
        // Il disegno è più largo che alto: l'altezza della vista deve farlo stare per intero in una finestra 16:10.
        assertTrue(valore(r, "\$VIEWSIZE", "40") >= ampiezza / 1.6 - 1e-6)
    }
}
