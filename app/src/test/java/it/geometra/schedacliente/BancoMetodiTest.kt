package it.geometra.schedacliente

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import org.json.JSONArray
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.atan2

/**
 * v4.13: confronto, sulle planimetrie reali del banco di prova, dei trattamenti applicati al contorno
 * misurato sui muri: nessuno (raffinato), essenziale (semplificazione spigoli), chiudi-rientranze.
 * Per ogni vano misura vertici, variazione d'area, lati in diagonale e validità del contorno.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class BancoMetodiTest {
    private fun area(p: List<PointF>) = GeometriaUtils.areaPoligono(p)

    private fun diagonali(p: List<PointF>): Int {
        var n = 0
        for (i in p.indices) {
            val a = p[i]; val b = p[(i + 1) % p.size]
            val l = GeometriaUtils.distanza(a, b)
            if (l < 15) continue
            val ang = Math.toDegrees(atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble()))
            val m = ((abs(ang) % 90) .let { if (it > 45) 90 - it else it })
            if (m > 8) n++
        }
        return n
    }

    @Test
    fun confrontoMetodi() {
        val testo = javaClass.classLoader!!.getResourceAsStream("catasto/casi.json")!!.bufferedReader().readText()
        val casi = JSONArray(testo)
        data class Riga(var n: Int = 0, var vert: Int = 0, var diag: Int = 0, var dArea: Double = 0.0, var maxDArea: Double = 0.0, var nonSemplici: Int = 0, var cambiati: Int = 0)
        val tot = linkedMapOf("misurato (raffinato)" to Riga(), "essenziale" to Riga(), "chiudi rientranze" to Riga())
        for (c in 0 until casi.length()) {
            val caso = casi.getJSONObject(c); val id = caso.getString("id")
            val bmp = BitmapFactory.decodeStream(javaClass.classLoader!!.getResourceAsStream("catasto/$id.png")).copy(Bitmap.Config.ARGB_8888, true)
            val mpp = caso.getDouble("mpp")
            val seg = AnalizzatoreVani(bmp, Rect(0, 0, bmp.width, bmp.height), mpp)
            val out = bmp.copy(Bitmap.Config.ARGB_8888, true); val cv = Canvas(out)
            val pen = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 4f; isAntiAlias = true }
            fun disegna(p: List<PointF>, col: Int, w: Float) {
                pen.color = col; pen.strokeWidth = w
                for (i in p.indices) { val a = p[i]; val b = p[(i + 1) % p.size]; cv.drawLine(a.x, a.y, b.x, b.y, pen) }
            }
            val vani = caso.getJSONArray("vani")
            for (v in 0 until vani.length()) {
                val vano = vani.getJSONObject(v)
                val attesa = vano.getDouble("area")
                val pol = seg.misura(PointF(vano.getDouble("x").toFloat(), vano.getDouble("y").toFloat()), attesa)?.vertici ?: continue
                if (area(pol) / attesa !in 0.6..1.45) continue
                val a0 = area(pol)
                val essenziale = GeometriaUtils.semplificaSpigoli(pol, 0.25 / (mpp * mpp))
                val chiuso = GeometriaUtils.chiudiRientranze(pol, 0.6 / (mpp * mpp), 0.7 / mpp)
                for ((nome, p) in listOf("misurato (raffinato)" to pol, "essenziale" to essenziale, "chiudi rientranze" to chiuso)) {
                    val r = tot[nome]!!
                    r.n++; r.vert += p.size; r.diag += diagonali(p)
                    val d = (area(p) - a0) / a0 * 100
                    r.dArea += d; r.maxDArea = maxOf(r.maxDArea, abs(d))
                    if (!GeometriaUtils.contornoSemplice(p)) r.nonSemplici++
                    if (p.size != pol.size) r.cambiati++
                }
                disegna(pol, Color.RED, 6f); disegna(chiuso, Color.rgb(0, 150, 0), 3f); disegna(essenziale, Color.BLUE, 3f)
            }
            java.io.File("build/anteprime").mkdirs()
            java.io.FileOutputStream("build/anteprime/metodi_$id.png").use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        for ((k, r) in tot) println("METODI $k: vani=${r.n} vertici/vano=${"%.2f".format(r.vert.toDouble() / r.n)} lati-diagonali=${r.diag} dArea-medio=${"%.2f".format(r.dArea / r.n)}% dArea-max=${"%.2f".format(r.maxDArea)}% contorni-non-semplici=${r.nonSemplici} contorni-modificati=${r.cambiati}")
        assertTrue(tot["chiudi rientranze"]!!.nonSemplici == 0)
    }
}
