package it.geometra.schedacliente

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import android.graphics.Rect
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Prova ad hoc: planimetria reale dell'utente (Terranuova Bracciolini, scheda 466330),
 * confrontata con le aree "vere" (dal DXF/PDF forniti dall'utente). Si esegue SOLO se è
 * presente la variabile PROVA_CHIAVE. Stampa, per ciascuno dei 4 metodi e per ciascun piano,
 * i locali trovati con la relativa area in mq, usando una scala calibrata sul vano W.C.
 * (area nota 4,70 mq) individuato dall'AI stessa nel modo "raff".
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class ProvaMorbidelliTest {

    private fun area(p: List<PointF>) = GeometriaUtils.areaPoligono(p)

    private class Esito(val nome: String, val dest: String, val finale: List<PointF>, val grezzo: List<PointF>, val misurato: Boolean)

    private fun verticiDaLati(lati: JSONArray, w: Float, h: Float, stima: List<PointF>): List<PointF>? {
        val r = ArrayList<Pair<PointF, PointF>>()
        for (k in 0 until lati.length()) {
            val o = lati.optJSONObject(k) ?: return null
            val d = o.optJSONObject("da") ?: return null; val a = o.optJSONObject("a") ?: return null
            r += PointF(d.getDouble("x").toFloat() * w, d.getDouble("y").toFloat() * h) to PointF(a.getDouble("x").toFloat() * w, a.getDouble("y").toFloat() * h)
        }
        val n = r.size; val tol = 0.06 * maxOf(w, h); val out = ArrayList<PointF>()
        for (k in 0 until n) {
            val r1 = r[(k - 1 + n) % n]; val r2 = r[k]
            val d1x = (r1.second.x - r1.first.x).toDouble(); val d1y = (r1.second.y - r1.first.y).toDouble()
            val d2x = (r2.second.x - r2.first.x).toDouble(); val d2y = (r2.second.y - r2.first.y).toDouble()
            val den = d1x * d2y - d1y * d2x
            val ref = PointF((r1.second.x + r2.first.x) / 2, (r1.second.y + r2.first.y) / 2)
            val vic = stima.minByOrNull { GeometriaUtils.distanza(it, ref) } ?: ref
            if (abs(den) < 1e-6 * (Math.hypot(d1x, d1y) * Math.hypot(d2x, d2y) + 1e-9)) { out += vic; continue }
            val t = ((r2.first.x - r1.first.x) * d2y - (r2.first.y - r1.first.y) * d2x) / den
            val p = PointF((r1.first.x + t * d1x).toFloat(), (r1.first.y + t * d1y).toFloat())
            out += if (GeometriaUtils.distanza(p, vic) <= tol) p else vic
        }
        return if (out.size >= 3) out else null
    }

    @Test
    fun provaPianiReali() {
        val chiave = System.getenv("PROVA_CHIAVE")
        assumeTrue("PROVA_CHIAVE assente: prova saltata", !chiave.isNullOrBlank())
        val modi = (System.getenv("PROVA_MODI") ?: "ess,raff,lato,chiudi").split(",").map { it.trim() }

        val ip = InterpretePlanimetria::class.java
        val cod = ip.getDeclaredMethod("codificaBitmap", Bitmap::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        val chiama = ip.getDeclaredMethod("eseguiChiamataVertici", String::class.java, String::class.java).apply { isAccessible = true }
        val istanza = ip.getField("INSTANCE").get(null)

        // mpp provvisorio per la prima passata "raff" (serve solo a dimensionare porte/aree
        // plausibili nella segmentazione a muri: non è critico che sia preciso al primo giro).
        var mpp = 0.017

        val piani = listOf("morbidelli_piano1" to "Piano II (sopra)", "morbidelli_piano0" to "Piano terra")

        for ((file, etichettaPiano) in piani) {
            println("\n================ $etichettaPiano ($file) ================")
            val bmp = BitmapFactory.decodeStream(javaClass.classLoader!!.getResourceAsStream("catasto/$file.png")).copy(Bitmap.Config.ARGB_8888, true)
            var mppPiano = mpp
            var calibrato = false

            for (modo in modi) {
                InterpretePlanimetria.soloSpigoli = modo == "ess"
                InterpretePlanimetria.latoPerLato = modo == "lato"
                val seg = AnalizzatoreVani(bmp, Rect(0, 0, bmp.width, bmp.height), mppPiano)
                val esiti = ArrayList<Esito>()
                try {
                    val b64 = cod.invoke(istanza, bmp, true) as String
                    val js = chiama.invoke(istanza, chiave, b64) as JSONObject
                    val locali = js.optJSONArray("locali") ?: JSONArray()
                    for (i in 0 until locali.length()) {
                        val l = locali.getJSONObject(i)
                        val nome = l.optString("nome")
                        val dest = l.optString("destinazione")
                        val vj = l.optJSONArray("vertici") ?: JSONArray()
                        var raw = (0 until vj.length()).map { PointF(vj.getJSONObject(it).getDouble("x").toFloat() * bmp.width, vj.getJSONObject(it).getDouble("y").toFloat() * bmp.height) }
                        if (modo == "lato" && raw.size >= 3) l.optJSONArray("lati")?.let { la -> verticiDaLati(la, bmp.width.toFloat(), bmp.height.toFloat(), raw)?.let { raw = it } }
                        if (raw.size < 3) continue
                        val pi = l.optJSONObject("punto_interno")?.let { PointF(it.getDouble("x").toFloat() * bmp.width, it.getDouble("y").toFloat() * bmp.height) }
                        val areaAi = area(raw)
                        var fin: List<PointF>? = null; var mis = false
                        for (s in listOfNotNull(pi, GeometriaUtils.centroide(raw))) {
                            val pol = seg.misura(s, areaAi)?.vertici ?: continue
                            if (area(pol) / areaAi !in 0.6..1.45) continue
                            fin = pol; mis = true; break
                        }
                        if (fin == null) fin = seg.agganciaAiMuri(raw)
                        if (modo == "ess") fin = GeometriaUtils.semplificaSpigoli(fin, 0.25 / (mppPiano * mppPiano))
                        if (modo == "chiudi") fin = GeometriaUtils.chiudiRientranze(fin, 0.6 / (mppPiano * mppPiano), 0.7 / mppPiano)
                        esiti += Esito(nome, dest, fin, raw, mis)
                    }
                } catch (e: Throwable) {
                    println("MODO=$modo errore: ${e.cause?.message ?: e.message}")
                    continue
                }

                // Calibrazione: dopo il primo modo riuscito, se c'è un vano "W.C." usa la sua area
                // nota (4,70 mq, dal rilievo dell'utente) per stimare la vera scala dell'immagine.
                if (!calibrato) {
                    val wc = esiti.firstOrNull { it.nome.contains("W.C", true) || it.dest.contains("bagno", true) || it.nome.contains("WC", true) }
                    if (wc != null) {
                        val areaPxWc = area(wc.finale)
                        if (areaPxWc > 0) {
                            mppPiano = sqrt(4.70 / areaPxWc)
                            calibrato = true
                            println("[calibrazione: W.C. trovato, area grezza=${"%.0f".format(areaPxWc)}px2 -> mpp=${"%.5f".format(mppPiano)} m/px]")
                        }
                    }
                }

                println("--- modo=$modo (${esiti.size} locali) ---")
                for (e in esiti.sortedByDescending { area(it.finale) }) {
                    val areaM2 = area(e.finale) * mppPiano * mppPiano
                    val label = listOf(e.nome, e.dest).filter { it.isNotBlank() }.joinToString(" / ").ifBlank { "(senza nome)" }
                    println("  $label: ${"%.2f".format(areaM2)} mq  (${e.finale.size}v, misurato-sui-muri=${e.misurato})")
                }
                val totale = esiti.sumOf { area(it.finale) * mppPiano * mppPiano }
                println("  TOTALE: ${"%.2f".format(totale)} mq")
            }
        }
    }
}
