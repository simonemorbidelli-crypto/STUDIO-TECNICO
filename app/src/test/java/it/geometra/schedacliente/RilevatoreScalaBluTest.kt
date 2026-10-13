package it.geometra.schedacliente

import android.graphics.BitmapFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Planimetria catastale reale (PLN SISTER, A3 842,745 × 1191,82 pt) resa con pdfium come fa
 * Android: barra blu "10 metri" e tacche lunghe/distanziate 141,732 pt (= 10 m).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class RilevatoreScalaBluTest {

    private fun carica(nome: String) = javaClass.classLoader!!.getResourceAsStream(nome).use { BitmapFactory.decodeStream(it) }

    private fun verifica(nome: String, larghezzaPaginaPt: Double = 842.745) {
        val bmp = carica(nome)
        val pxPerPt = bmp.width / larghezzaPaginaPt
        val atteso = 10.0 / (141.732 * pxPerPt)
        val r = RilevatoreScalaBlu.rileva(bmp)
        assertNotNull("scala non trovata in $nome", r)
        println("$nome: ${r!!.fonte} ${r.descrizione} m/px=${r.metriPerPixel} atteso=$atteso errore=${(r.metriPerPixel / atteso - 1) * 100}%")
        assertEquals("scala in $nome", atteso, r.metriPerPixel, atteso * 0.005) // entro lo 0,5%
    }

    @Test fun pdfAltaRisoluzione() = verifica("pln_4000.png")
    @Test fun pdfRisoluzioneBassa() = verifica("pln_2200.png")
    @Test fun screenshotCompresso() = verifica("pln_foto.jpg")

    @Test
    fun immagineSenzaBluNonInventaNulla() {
        val bmp = android.graphics.Bitmap.createBitmap(1200, 1600, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        c.drawColor(android.graphics.Color.WHITE)
        val p = android.graphics.Paint().apply { color = android.graphics.Color.BLACK; strokeWidth = 3f }
        c.drawLine(50f, 50f, 50f, 1500f, p)
        assertNull(RilevatoreScalaBlu.rileva(bmp))
    }
}
