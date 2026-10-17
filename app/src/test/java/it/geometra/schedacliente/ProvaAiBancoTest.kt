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

/**
 * Prova con l'AI vera sul banco di prova. Si esegue SOLO se è presente la variabile d'ambiente
 * PROVA_CHIAVE (chiave API): senza, il test viene saltato. Variabili: PROVA_CASI (es. "t1a,t6a"),
 * PROVA_MODI (raff,ess,lato,chiudi), PROVA_RIP (ripetizioni, default 1).
 * Per ogni caso e modo: chiama la stessa richiesta dell'app per i vertici, misura sui muri come l'app e
 * confronta con i vani del banco (area attesa approssimata).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class ProvaAiBancoTest {
    private fun area(p: List<PointF>) = GeometriaUtils.areaPoligono(p)

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

    private class Esito(val finale: List<PointF>, val grezzo: List<PointF>, val misurato: Boolean)

    @Test
    fun provaConAi() {
        val chiave = System.getenv("PROVA_CHIAVE")
        assumeTrue("PROVA_CHIAVE assente: prova con AI saltata", !chiave.isNullOrBlank())
        val casiSel = (System.getenv("PROVA_CASI") ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val modi = (System.getenv("PROVA_MODI") ?: "raff").split(",").map { it.trim() }
        val rip = (System.getenv("PROVA_RIP") ?: "1").toInt()
        val casi = JSONArray(javaClass.classLoader!!.getResourceAsStream("catasto/casi.json")!!.bufferedReader().readText())
        val ip = InterpretePlanimetria::class.java
        val cod = ip.getDeclaredMethod("codificaBitmap", Bitmap::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        val chiama = ip.getDeclaredMethod("eseguiChiamataVertici", String::class.java, String::class.java).apply { isAccessible = true }
        val istanza = ip.getField("INSTANCE").get(null)

        for (modo in modi) {
            InterpretePlanimetria.soloSpigoli = modo == "ess"
            InterpretePlanimetria.latoPerLato = modo == "lato"
            var nVani = 0; var trovati = 0; var extra = 0; var sumRawErr = 0.0; var sumFinErr = 0.0; var nMis = 0; var nErr = 0; var nTr = 0; var sumRawTr = 0.0; var sumFinTr = 0.0
            val stab = ArrayList<Double>()
            for (c in 0 until casi.length()) {
                val caso = casi.getJSONObject(c); val id = caso.getString("id")
                if (casiSel.isNotEmpty() && id !in casiSel) continue
                val bmp = BitmapFactory.decodeStream(javaClass.classLoader!!.getResourceAsStream("catasto/$id.png")).copy(Bitmap.Config.ARGB_8888, true)
                val seg = AnalizzatoreVani(bmp, Rect(0, 0, bmp.width, bmp.height), caso.getDouble("mpp"))
                val mpp = caso.getDouble("mpp")
                val vani = caso.getJSONArray("vani")
                val perRip = ArrayList<List<Esito>>()
                for (r in 0 until rip) {
                    val esiti = ArrayList<Esito>()
                    try {
                        val b64 = cod.invoke(istanza, bmp, true) as String
                        val js = chiama.invoke(istanza, chiave, b64) as JSONObject
                        val locali = js.optJSONArray("locali") ?: JSONArray()
                        for (i in 0 until locali.length()) {
                            val l = locali.getJSONObject(i)
                            val vj = l.optJSONArray("vertici") ?: JSONArray()
                            var raw = (0 until vj.length()).map { PointF(vj.getJSONObject(it).getDouble("x").toFloat() * bmp.width, vj.getJSONObject(it).getDouble("y").toFloat() * bmp.height) }
                            if (modo == "lato" && raw.size >= 3) l.optJSONArray("lati")?.let { la -> verticiDaLati(la, bmp.width.toFloat(), bmp.height.toFloat(), raw)?.let { raw = it } }
                            val pi = l.optJSONObject("punto_interno")?.let { PointF(it.getDouble("x").toFloat() * bmp.width, it.getDouble("y").toFloat() * bmp.height) }
                            val areaAi = if (raw.size >= 3) area(raw) else null
                            var fin: List<PointF>? = null; var mis = false
                            for (s in listOfNotNull(pi, if (raw.size >= 3) GeometriaUtils.centroide(raw) else null)) {
                                val pol = seg.misura(s, areaAi)?.vertici ?: continue
                                if (areaAi != null && area(pol) / areaAi !in 0.6..1.45) continue
                                fin = pol; mis = true; break
                            }
                            if (fin == null && raw.size >= 3) fin = seg.agganciaAiMuri(raw)
                            if (fin == null) continue
                            if (modo == "ess") fin = GeometriaUtils.semplificaSpigoli(fin, 0.25 / (mpp * mpp))
                            if (modo == "chiudi") fin = GeometriaUtils.chiudiRientranze(fin, 0.6 / (mpp * mpp), 0.7 / mpp)
                            esiti += Esito(fin, raw, mis)
                        }
                    } catch (e: Throwable) { nErr++; println("PROVA $id/$modo errore: ${e.cause?.message ?: e.message}") }
                    perRip += esiti
                }
                for (v in 0 until vani.length()) {
                    val vano = vani.getJSONObject(v); val attesa = vano.getDouble("area")
                    val seme = PointF(vano.getDouble("x").toFloat(), vano.getDouble("y").toFloat())
                    nVani++
                    val aree = ArrayList<Double>()
                    for ((k, esiti) in perRip.withIndex()) {
                        val e = esiti.firstOrNull { it.finale.size >= 3 && GeometriaUtils.puntoDentroPoligono(seme, it.finale) } ?: continue
                        if (k == 0) {
                            trovati++; sumFinErr += abs(area(e.finale) / attesa - 1)
                            if (e.grezzo.size >= 3) sumRawErr += abs(area(e.grezzo) / attesa - 1) else sumRawErr += 1.0
                            if (e.misurato) nMis++
                        }
                        if (k == 0) {
                            // riferimento "muri": contorno misurato sui pixel del disegno dal seme del banco
                            val tr = seg.misura(seme, attesa)?.vertici
                            if (tr != null && area(tr) / attesa in 0.6..1.45 && e.grezzo.size >= 3) {
                                nTr++; sumRawTr += abs(area(e.grezzo) / area(tr) - 1); sumFinTr += abs(area(e.finale) / area(tr) - 1)
                            }
                        }
                        aree += area(e.finale)
                    }
                    if (aree.size == rip && rip > 1) stab += (aree.max() - aree.min()) / aree.average() * 100
                }
                if (perRip.isNotEmpty()) extra += maxOf(0, perRip[0].size - vani.length())
            }
            println("PROVA modo=$modo: vani=$nVani trovati=$trovati (${if (nVani > 0) 100 * trovati / nVani else 0}%) misurati-sui-muri=$nMis " +
                "errore-area-AI-grezzo=${"%.1f".format(100 * sumRawErr / maxOf(1, trovati))}% errore-area-finale=${"%.1f".format(100 * sumFinErr / maxOf(1, trovati))}% " +
                "rispetto-ai-muri(n=$nTr): AI-grezzo=${"%.1f".format(100 * sumRawTr / maxOf(1, nTr))}% finale=${"%.1f".format(100 * sumFinTr / maxOf(1, nTr))}% locali-in-piu=$extra errori-chiamata=$nErr" +
                (if (stab.isNotEmpty()) " instabilita-area-media=${"%.1f".format(stab.average())}% max=${"%.1f".format(stab.max())}%" else ""))
        }
    }
}
