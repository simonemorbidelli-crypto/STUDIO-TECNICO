package it.geometra.schedacliente

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class SegmentazioneTest {

    private fun area(p: List<PointF>): Double {
        var s = 0.0
        for (i in p.indices) { val a = p[i]; val b = p[(i + 1) % p.size]; s += a.x * b.y - b.x * a.y }
        return abs(s) / 2
    }

    /**
     * Pianta sintetica 1400×1000 (1 px = 1 cm, quindi 0,01 m/px), muri spessi 12 px:
     *  A: rettangolo interno 400×300 (x 112..512, y 112..412) = 12 m²
     *  B: a L a destra di A, collegata ad A da una porta aperta di 90 px
     *  C: piccolo vano 200×150 in basso a sinistra
     *  + una linea sottile di reticolo (2 px) che attraversa tutto, e scritte sottili.
     */
    private fun pianta(): Bitmap {
        val bmp = Bitmap.createBitmap(1400, 1000, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); c.drawColor(Color.WHITE)
        val muro = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
        fun r(l: Int, t: Int, rr: Int, b: Int) = c.drawRect(l.toFloat(), t.toFloat(), rr.toFloat(), b.toFloat(), muro)
        // perimetro esterno 100..1300 x 100..900
        r(100, 100, 1300, 112); r(100, 888, 1300, 900); r(100, 100, 112, 900); r(1288, 100, 1300, 900)
        // muro verticale tra A e B a x=512..524 con porta da y=200 a 290
        r(512, 112, 524, 200); r(512, 290, 524, 412)
        // muro orizzontale sotto A a y=412..424 (da 112 a 1000), poi la L di B scende a destra
        r(112, 412, 1000, 424)
        // muro verticale x=1000..1012 da 424 a 888: B è a L (sopra fino a 1288, sotto solo x>1012)
        r(1000, 424, 1012, 888)
        // vano C: muro orizzontale y=650..662 da 112 a 324 e verticale x=312..324 da 650 a 888
        r(112, 650, 324, 662); r(312, 650, 324, 888)
        // reticolo sottile e "scritte"
        val sottile = Paint().apply { color = Color.BLACK; strokeWidth = 2f }
        c.drawLine(0f, 300f, 1400f, 300f, sottile)
        c.drawLine(700f, 0f, 700f, 1000f, sottile)
        val testo = Paint().apply { color = Color.BLACK; textSize = 28f }
        c.drawText("CUCINA", 250f, 260f, testo)
        return bmp
    }

    @Test
    fun contorniEsatti() {
        val bmp = pianta()
        val seg = SegmentazionePlanimetria(bmp, Rect(0, 0, bmp.width, bmp.height), 0.01)
        assertTrue(seg.utilizzabile)
        val a = seg.poligonoDaPunto(PointF(300f, 350f))
        assertNotNull("vano A", a)
        println("A: ${a!!.size} vertici, area=${area(a)} " + a.joinToString { "(${it.x.toInt()},${it.y.toInt()})" })
        assertEquals(400.0 * 300.0, area(a), 400.0 * 300.0 * 0.06) // entro il 6% (porta)
        val b = seg.poligonoDaPunto(PointF(1150f, 700f))
        assertNotNull("vano B a L", b)
        println("B: ${b!!.size} vertici, area=${area(b)} " + b.joinToString { "(${it.x.toInt()},${it.y.toInt()})" })
        val areaB = (1288 - 524) * (412 - 112) + (1288 - 1012) * (888 - 412) // parte alta + gamba a destra
        assertEquals(areaB.toDouble(), area(b), areaB * 0.06)
        val cc = seg.poligonoDaPunto(PointF(200f, 780f))
        assertNotNull("vano C", cc)
        println("C: ${cc!!.size} vertici, area=${area(cc)}")
        assertEquals((312 - 112) * (888 - 662).toDouble(), area(cc), 200.0 * 226 * 0.08)
    }

    @Test
    fun vaniMancantiTrovati() {
        val bmp = pianta()
        val seg = SegmentazionePlanimetria(bmp, Rect(0, 0, bmp.width, bmp.height), 0.01)
        val a = seg.poligonoDaPunto(PointF(300f, 350f))!!
        val mancanti = seg.vaniChiusiMancanti(listOf(a))
        println("mancanti: ${mancanti.size} " + mancanti.map { area(it).toInt() })
        // B (L), C e il vano centrale in basso (tra C, A e B)
        assertTrue(mancanti.size >= 3)
    }

    @Test
    fun vanoApertoNonInventato() {
        // Un vano senza un lato (es. loggia aperta) non è chiuso: niente poligono.
        val bmp = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); c.drawColor(Color.WHITE)
        val muro = Paint().apply { color = Color.BLACK }
        c.drawRect(100f, 100f, 700f, 112f, muro); c.drawRect(100f, 100f, 112f, 500f, muro); c.drawRect(688f, 100f, 700f, 500f, muro)
        val seg = SegmentazionePlanimetria(bmp, Rect(0, 0, 800, 600), 0.01)
        assertNull(seg.poligonoDaPunto(PointF(400f, 300f)))
    }
}
