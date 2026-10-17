package it.geometra.schedacliente

import android.graphics.PointF
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** v4.10: chiusura delle piccole rientranze (pilastro / cella di finestra) senza toccare gli spigoli veri. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ChiudiRientranzeTest {
    private fun p(x: Float, y: Float) = PointF(x, y)

    @Test
    fun rientranzaPiccolaVieneChiusa() {
        // Rettangolo 400x300 con una rientranza 140x50 sul lato sinistro (come blocco + cella accanto al muro).
        val v = listOf(p(0f, 0f), p(400f, 0f), p(400f, 300f), p(0f, 300f), p(0f, 200f), p(140f, 200f), p(140f, 150f), p(0f, 150f))
        val r = GeometriaUtils.chiudiRientranze(v, areaMax = 10000.0, profMax = 200.0)
        assertEquals(4, r.size)
        assertEquals(120000.0, GeometriaUtils.areaPoligono(r), 1.0)
    }

    @Test
    fun rientranzaGrandeNonVieneChiusa() {
        val v = listOf(p(0f, 0f), p(400f, 0f), p(400f, 300f), p(0f, 300f), p(0f, 200f), p(140f, 200f), p(140f, 150f), p(0f, 150f))
        val r = GeometriaUtils.chiudiRientranze(v, areaMax = 3000.0, profMax = 200.0)
        assertEquals(8, r.size)
    }

    @Test
    fun formaAElleNonVieneAlterata() {
        // L grande: l'angolo rientrante è un cambio di forma vero, oltre la soglia.
        val v = listOf(p(0f, 0f), p(400f, 0f), p(400f, 150f), p(200f, 150f), p(200f, 300f), p(0f, 300f))
        val r = GeometriaUtils.chiudiRientranze(v, areaMax = 5000.0, profMax = 100.0)
        assertEquals(6, r.size)
    }
}
