package it.geometra.schedacliente

import android.graphics.Bitmap
import android.graphics.PointF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SuperficieViewTest {

    private fun vista(w: Int, h: Int): SuperficieView {
        val v = SuperficieView(RuntimeEnvironment.getApplication())
        v.bitmap = Bitmap.createBitmap(2000, 1500, Bitmap.Config.ARGB_8888)
        dimensiona(v, w, h)
        return v
    }

    private fun dimensiona(v: View, w: Int, h: Int) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
    }

    private fun zoom(v: SuperficieView) = SuperficieView::class.java.getDeclaredField("fattoreZoomCorrente").apply { isAccessible = true }.getFloat(v)

    private fun centroImmagine(v: SuperficieView): PointF =
        SuperficieView::class.java.getDeclaredMethod("schermoAImmagine", Float::class.java, Float::class.java)
            .apply { isAccessible = true }.invoke(v, v.width / 2f, v.height / 2f) as PointF

    @Test
    fun loZoomResta() {
        val v = vista(1080, 1500)
        v.zoomCentratoSu(PointF(1234f, 567f), 6f)
        // La barra delle istruzioni cambia altezza: la vista si rimpicciolisce.
        dimensiona(v, 1080, 1380)
        assertEquals(6f, zoom(v), 0.001f)
        val c = centroImmagine(v)
        assertEquals(1234f, c.x, 1f)
        assertEquals(567f, c.y, 1f)
    }

    @Test
    fun annullaRipristinaIPunti() {
        val v = vista(1080, 1500)
        val lista = mutableListOf(PointF(0f, 0f), PointF(100f, 0f), PointF(100f, 100f), PointF(0f, 100f))
        v.listaAttiva = lista
        v.modoTocco = SuperficieView.ModoTocco.SOLO_TRASCINA
        assertFalse(v.puoAnnullare)
        val registra = SuperficieView::class.java.getDeclaredMethod("registraStato").apply { isAccessible = true }
        registra.invoke(v); lista.removeAt(1)                 // rimozione vertice
        registra.invoke(v); lista.add(1, PointF(50f, -20f))   // aggiunta vertice
        assertTrue(v.puoAnnullare)
        assertTrue(v.annullaUltimaModifica())
        assertEquals(3, lista.size)
        assertTrue(v.annullaUltimaModifica())
        assertEquals(listOf(100f, 0f), listOf(lista[1].x, lista[1].y))
        assertFalse(v.annullaUltimaModifica())
    }

    @Test
    fun inVisualizzazioneUnDitoScorreSenzaAvvisi() {
        val v = vista(1080, 1500)
        v.avvisaTapNonConsentito = false
        var avvisi = 0
        v.onTapNonConsentito = { avvisi++ }
        v.zoomCentratoSu(PointF(1000f, 750f), 4f)
        val prima = centroImmagine(v)
        val t = SystemClock.uptimeMillis()
        v.onTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 500f, 500f, 0))
        v.onTouchEvent(MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_MOVE, 600f, 650f, 0))
        v.onTouchEvent(MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, 600f, 650f, 0))
        val dopo = centroImmagine(v)
        assertTrue("la vista deve essersi spostata", prima.x - dopo.x > 5f)
        assertEquals(0, avvisi)
    }
}
