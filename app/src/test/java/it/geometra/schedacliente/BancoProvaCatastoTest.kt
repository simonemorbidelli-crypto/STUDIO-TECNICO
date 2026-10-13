package it.geometra.schedacliente

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import org.json.JSONArray
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * Banco di prova su planimetrie catastali reali (ritagli delle piante dai PDF, resi a 4000 px
 * come nell'app). Per ogni vano: punto interno e area approssimata (come li darebbe l'AI).
 * Produce in build/anteprime/banco_<caso>_<modo>.png il risultato grafico:
 *  rosso = contorno misurato sui muri, blu = forma AI riportata sui muri, verde = vano chiuso non indicato.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class BancoProvaCatastoTest {

    private fun area(p: List<PointF>): Double {
        var s = 0.0
        for (i in p.indices) { val a = p[i]; val b = p[(i + 1) % p.size]; s += a.x * b.y - b.x * a.y }
        return abs(s) / 2
    }

    @Test
    fun bancoDiProva() {
        val testo = javaClass.classLoader!!.getResourceAsStream("catasto/casi.json")?.bufferedReader()?.readText() ?: return
        val casi = JSONArray(testo)
        val filtro = System.getenv("CASO")
        var precisi = 0; var agganciati = 0; var falliti = 0
        for (c in 0 until casi.length()) {
            val caso = casi.getJSONObject(c)
            val id = caso.getString("id")
            if (filtro != null && filtro.isNotBlank() && !id.startsWith(filtro)) continue
            val bmp = BitmapFactory.decodeStream(javaClass.classLoader!!.getResourceAsStream("catasto/$id.png"))
                .copy(Bitmap.Config.ARGB_8888, true)
            val vani = caso.getJSONArray("vani")
            for (modo in listOf("scala", "noscala")) {
                val mpp = if (modo == "scala") caso.getDouble("mpp") else null
                val seg = AnalizzatoreVani(bmp, Rect(0, 0, bmp.width, bmp.height), mpp)
                println("BANCO $id/$modo principale: ${seg.principale.descrizioneTipo()}")
                val out = bmp.copy(Bitmap.Config.ARGB_8888, true); val cv = Canvas(out)
                val pen = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 4f; isAntiAlias = true }
                val punti = Paint().apply { color = Color.MAGENTA; style = Paint.Style.FILL }
                fun disegna(p: List<PointF>, col: Int) {
                    pen.color = col
                    for (i in p.indices) { val a = p[i]; val b = p[(i + 1) % p.size]; cv.drawLine(a.x, a.y, b.x, b.y, pen) }
                    p.forEach { cv.drawCircle(it.x, it.y, 5f, punti) }
                }
                if (modo == "scala" && System.getenv("MASCHERE") != null) {
                    java.io.File("build/anteprime").mkdirs()
                    java.io.FileOutputStream("build/anteprime/maschera_${id}_pieni.png").use { seg.pieni.mascheraDiagnosi(seg.pieni.raggioPorte).compress(Bitmap.CompressFormat.PNG, 100, it) }
                    java.io.FileOutputStream("build/anteprime/maschera_${id}_linee.png").use { seg.linee.mascheraDiagnosi(seg.linee.raggioPorte).compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
                val trovati = ArrayList<List<PointF>>()
                for (v in 0 until vani.length()) {
                    val vano = vani.getJSONObject(v)
                    val seme = PointF(vano.getDouble("x").toFloat(), vano.getDouble("y").toFloat())
                    val attesa = vano.getDouble("area")
                    val esito = seg.misura(seme, attesa)
                    val pol = esito?.vertici
                    val nome = vano.getString("nome")
                    if (pol != null && area(pol) / attesa in 0.6..1.45) {
                        disegna(pol, Color.RED); trovati += pol
                        if (modo == "scala") precisi++
                        println("BANCO $id/$modo $nome: OK[${esito.metodo}] ${pol.size} vertici, area/attesa=${"%.2f".format(area(pol) / attesa)}")
                    } else {
                        // come l'app: stima AI (qui un rettangolo attorno al seme) riportata sui muri
                        val lato = Math.sqrt(attesa).toFloat() / 2
                        val ai = listOf(PointF(seme.x - lato, seme.y - lato), PointF(seme.x + lato, seme.y - lato),
                            PointF(seme.x + lato, seme.y + lato), PointF(seme.x - lato, seme.y + lato))
                        val agg = seg.agganciaAiMuri(ai)
                        disegna(agg, Color.BLUE); trovati += agg
                        if (modo == "scala") { if (pol == null) falliti++ else agganciati++ }
                        SegmentazionePlanimetria.DEBUG = modo == "scala" && System.getenv("DEBUGVANI") != null
                        println("BANCO $id/$modo $nome DETTAGLIO: ${seg.dettaglio(seme, attesa)}")
                        SegmentazionePlanimetria.DEBUG = false
                        println("BANCO $id/$modo $nome: ${if (pol == null) "NULL" else "SCARTATO area/attesa=${"%.2f".format(area(pol) / attesa)}"} -> aggancio")
                    }
                    cv.drawCircle(seme.x, seme.y, 7f, Paint().apply { color = Color.rgb(255, 140, 0) })
                }
                println("BANCO $id/$modo vittorie: ${seg.vittorie} -> ${seg.descrizioneDisegno()}")
                seg.vaniChiusiMancanti(trovati).forEach {
                    disegna(it, Color.rgb(0, 160, 0))
                    println("BANCO $id/$modo mancante: ${it.size} vertici, area=${area(it).toInt()}")
                }
                java.io.File("build/anteprime").mkdirs()
                java.io.FileOutputStream("build/anteprime/banco_${id}_$modo.png").use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        println("BANCO TOTALE (con scala): precisi=$precisi agganciati=$agganciati falliti=$falliti")
    }

    /** Stessa pianta (t6a) disegnata sopra carta millimetrata nera: i vani devono uscire uguali. */
    @Test
    fun cartaMillimetrata() {
        val testo = javaClass.classLoader!!.getResourceAsStream("catasto/casi.json")?.bufferedReader()?.readText() ?: return
        val casi = JSONArray(testo)
        val caso = (0 until casi.length()).map { casi.getJSONObject(it) }.first { it.getString("id") == "t6a" }
        val orig = BitmapFactory.decodeStream(javaClass.classLoader!!.getResourceAsStream("catasto/t6a.png")).copy(Bitmap.Config.ARGB_8888, true)
        val bmp = orig.copy(Bitmap.Config.ARGB_8888, true)
        val cv = Canvas(bmp)
        val fine = Paint().apply { color = Color.rgb(40, 40, 40); strokeWidth = 1.5f }
        val spessa = Paint().apply { color = Color.rgb(30, 30, 30); strokeWidth = 2.5f }
        // 1 mm ≈ 9,5 px (tavola A3 resa a 4000 px): linea ogni mm, più marcata ogni 5 mm
        var k = 0; var x = 0f
        while (x < bmp.width) { cv.drawLine(x, 0f, x, bmp.height.toFloat(), if (k % 5 == 0) spessa else fine); x += 9.5f; k++ }
        k = 0; var y = 0f
        while (y < bmp.height) { cv.drawLine(0f, y, bmp.width.toFloat(), y, if (k % 5 == 0) spessa else fine); y += 9.5f; k++ }
        // ridisegna il disegno originale sopra (inchiostro nero del disegno resta)
        val px = IntArray(orig.width * orig.height); orig.getPixels(px, 0, orig.width, 0, 0, orig.width, orig.height)
        val pg = IntArray(px.size); bmp.getPixels(pg, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        for (i in px.indices) if (Color.red(px[i]) < 128) pg[i] = Color.BLACK
        bmp.setPixels(pg, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val seg = AnalizzatoreVani(bmp, Rect(0, 0, bmp.width, bmp.height), caso.getDouble("mpp"))
        println("MM pieni: ${seg.pieni.descrizioneTipo()}")
        println("MM linee: ${seg.linee.descrizioneTipo()}")
        val vani = caso.getJSONArray("vani")
        val out = bmp.copy(Bitmap.Config.ARGB_8888, true); val c2 = Canvas(out)
        val pen = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.RED }
        var ok = 0
        for (v in 0 until vani.length()) {
            val vano = vani.getJSONObject(v)
            val e = seg.misura(PointF(vano.getDouble("x").toFloat(), vano.getDouble("y").toFloat()), vano.getDouble("area"))
            println("MM ${vano.getString("nome")}: ${e?.metodo} ${e?.vertici?.size} r=${e?.let { "%.2f".format(area(it.vertici) / vano.getDouble("area")) }}")
            if (e != null) { ok++; val p = e.vertici; for (i in p.indices) { val a = p[i]; val b = p[(i + 1) % p.size]; c2.drawLine(a.x, a.y, b.x, b.y, pen) } }
        }
        println("MM trovati $ok su ${vani.length()}")
        java.io.FileOutputStream("build/anteprime/banco_millimetrata.png").use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
