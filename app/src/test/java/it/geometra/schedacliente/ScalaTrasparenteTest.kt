package it.geometra.schedacliente

import android.graphics.Paint
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ScalaTrasparenteTest {
    private fun paint(v: SuperficieView, nome: String): Paint {
        val f = SuperficieView::class.java.getDeclaredField(nome); f.isAccessible = true
        return f.get(v) as Paint
    }

    @Test
    fun lineaEPallinoDellaScalaSonoSemitrasparenti() {
        val v = SuperficieView(RuntimeEnvironment.getApplication(), null)
        listOf("paintScalaEvidenziata", "paintScalaEvidenziataBordo", "paintScalaEvidenziataPunti", "paintScalaEvidenziataPuntiBordo").forEach {
            val alpha = paint(v, it).color ushr 24
            assertTrue("$it deve essere trasparente (alpha=$alpha)", alpha in 1..200)
        }
    }

    @Test
    fun immagineDisegnataConFiltroBilineare() {
        val v = SuperficieView(RuntimeEnvironment.getApplication(), null)
        assertTrue(paint(v, "paintImmagine").isFilterBitmap)
    }
}
