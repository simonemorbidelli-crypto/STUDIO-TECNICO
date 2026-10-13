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

    /**
     * Cornice blu con angoli ad angolo retto e tacche ogni 146 px (10 m), SENZA barra "10 metri",
     * come nella planimetria che sbagliava (v3.80: il tratto andava da un angolo all'altro). Il tratto
     * deve andare dalla prima all'ultima TACCA della linea più lunga, non agli angoli.
     */
    @Test
    fun corniceConAngoliNonUsaGliAngoliComeTacche() {
        val w = 900; val h = 1300; val m = 30f; val passo = 146f; val primoOffset = 110f; val mezzaTacca = 28f
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        c.drawColor(android.graphics.Color.WHITE)
        val p = android.graphics.Paint().apply { color = android.graphics.Color.rgb(20, 20, 220); strokeWidth = 2f; isAntiAlias = false }
        c.drawRect(m, m, w - m, h - m, p.apply { style = android.graphics.Paint.Style.STROKE })
        var x = m + primoOffset
        while (x < w - m - 40) { c.drawLine(x, m - mezzaTacca, x, m + mezzaTacca, p); c.drawLine(x, h - m - mezzaTacca, x, h - m + mezzaTacca, p); x += passo }
        var y = m + primoOffset
        val tacche = ArrayList<Float>()
        while (y < h - m - 40) { c.drawLine(m - mezzaTacca, y, m + mezzaTacca, y, p); c.drawLine(w - m - mezzaTacca, y, w - m + mezzaTacca, y, p); tacche.add(y); y += passo }

        val r = RilevatoreScalaBlu.rileva(bmp)
        assertNotNull(r)
        r!!
        println("corniceConAngoli: ${r.descrizione} p1=${r.p1} p2=${r.p2} m=${r.metriTraPunti}")
        // Linea più lunga = verticale; estremi = prima e ultima tacca (non gli angoli a y=30 e y=1270).
        assertEquals(tacche.first().toDouble(), r.p1.y.toDouble(), 2.0)
        assertEquals(tacche.last().toDouble(), r.p2.y.toDouble(), 2.0)
        assertEquals(((tacche.size - 1) * 10).toDouble(), r.metriTraPunti, 0.001)
        assertEquals(10.0 / passo, r.metriPerPixel, 10.0 / passo * 0.005)
    }
}
