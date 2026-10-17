package it.geometra.schedacliente

import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Funzioni geometriche di base per il tracciamento manuale delle aree dei
 * locali: distanza tra due punti e area di un poligono qualsiasi (anche
 * irregolare) con la formula di Gauss (shoelace). La formula di Gauss dà lo
 * stesso risultato esatto che si otterrebbe scomponendo il poligono in
 * triangoli e sommando le aree con la formula di Erone, ma in un unico
 * calcolo diretto, senza arrotondamenti intermedi.
 */
object GeometriaUtils {

    fun distanza(a: PointF, b: PointF): Double =
        hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble())

    fun areaPoligono(vertici: List<PointF>): Double {
        if (vertici.size < 3) return 0.0
        var somma = 0.0
        for (i in vertici.indices) {
            val p1 = vertici[i]
            val p2 = vertici[(i + 1) % vertici.size]
            somma += p1.x.toDouble() * p2.y.toDouble() - p2.x.toDouble() * p1.y.toDouble()
        }
        return abs(somma) / 2.0
    }

    /**
     * Modalità "essenziale": toglie i particolari del contorno (nicchie, piccole rientranze,
     * sporgenze, spessori di porte) lasciando solo gli spigoli principali. Elimina uno alla volta
     * il vertice che toglie meno area (triangolo formato col vertice precedente e successivo)
     * finché quell'area resta sotto [areaMinima] (stessa unità del poligono, al quadrato).
     * Non scende mai sotto 4 vertici se il poligono ne aveva almeno 4 (3 se ne aveva 3).
     */
    fun semplificaSpigoli(vertici: List<PointF>, areaMinima: Double): List<PointF> {
        if (vertici.size <= 4) return vertici
        val v = vertici.toMutableList()
        while (v.size > 4) {
            var migliore = -1
            var minArea = Double.MAX_VALUE
            for (i in v.indices) {
                val a = v[(i - 1 + v.size) % v.size]
                val b = v[i]
                val c = v[(i + 1) % v.size]
                val t = abs((b.x - a.x).toDouble() * (c.y - a.y) - (c.x - a.x).toDouble() * (b.y - a.y)) / 2.0
                if (t < minArea) { minArea = t; migliore = i }
            }
            if (migliore < 0 || minArea >= areaMinima) break
            v.removeAt(migliore)
        }
        return v
    }

    /**
     * Chiude le piccole rientranze del contorno (pilastri, spalle di muro, cellette di finestra,
     * nicchie) che il tracciato aggira lasciandole fuori dal vano. Per ogni tratto di 1-6 vertici
     * consecutivi prova a sostituirlo con il segmento che unisce i due vertici ai suoi estremi;
     * accetta solo se l'area AUMENTA (si riempie una rientranza, non si taglia uno spigolo vero),
     * l'aumento è al massimo [areaMax], nessun vertice tolto dista più di [profMax] dal segmento e
     * il nuovo contorno non si incrocia. Ripete finché trova rientranze da chiudere; non scende
     * sotto i 4 vertici. Gli spigoli veri del vano restano dove sono.
     */
    fun chiudiRientranze(vertici: List<PointF>, areaMax: Double, profMax: Double): List<PointF> {
        var p = vertici.toMutableList()
        if (p.size < 5) return p
        var cambiato = true
        while (cambiato && p.size > 4) {
            cambiato = false
            val n = p.size
            val area0 = areaPoligono(p)
            var migliore: List<PointF>? = null
            var deltaMigliore = Double.MAX_VALUE
            for (a in 0 until n) {
                for (m in 1..minOf(6, n - 4)) {
                    val b = (a + m + 1) % n
                    val pa = p[a]; val pb = p[b]
                    val tolti = (1..m).map { p[(a + it) % n] }
                    val nuovo = mutableListOf<PointF>()
                    var k = b
                    while (true) { nuovo.add(p[k]); if (k == a) break; k = (k + 1) % n }
                    val delta = areaPoligono(nuovo) - area0
                    if (delta <= 1e-9 || delta > areaMax) continue
                    val lung = distanza(pa, pb)
                    if (lung < 1e-9) continue
                    val maxDist = tolti.maxOf { t ->
                        abs((pb.x - pa.x).toDouble() * (pa.y - t.y) - (pa.x - t.x).toDouble() * (pb.y - pa.y)) / lung
                    }
                    if (maxDist > profMax) continue
                    // Mai scorciatoie in diagonale: il segmento che chiude la rientranza deve proseguire una
                    // parete già esistente, cioè essere parallelo (entro ~10°) al lato che entra in [a] o a
                    // quello che esce da [b]. Un semplice gradino o un angolo tagliato resta com'è.
                    val prima = p[(a - 1 + n) % n]
                    val dopo = p[(b + 1) % n]
                    fun parallelo(x1: PointF, y1: PointF): Boolean {
                        val lx = (x1.x - y1.x).toDouble(); val ly = (x1.y - y1.y).toDouble()
                        val l = kotlin.math.hypot(lx, ly)
                        if (l < 1e-9) return false
                        val cx = (pb.x - pa.x).toDouble(); val cy = (pb.y - pa.y).toDouble()
                        return abs(lx * cx + ly * cy) / (l * lung) >= 0.985
                    }
                    if (!parallelo(prima, pa) && !parallelo(pb, dopo)) continue
                    // Rientranza piccola anche rispetto al vano (al massimo l'8% dell'area).
                    if (delta > 0.08 * area0) continue
                    if (!contornoSemplice(nuovo)) continue
                    if (delta < deltaMigliore) { deltaMigliore = delta; migliore = nuovo }
                }
            }
            if (migliore != null) { p = migliore.toMutableList(); cambiato = true }
        }
        // Vertici rimasti allineati sui due vicini (la rientranza chiusa li ha messi sulla stessa retta).
        var i = 0
        while (p.size > 4 && i < p.size) {
            val a = p[(i - 1 + p.size) % p.size]; val b = p[i]; val c = p[(i + 1) % p.size]
            val croce = abs((b.x - a.x).toDouble() * (c.y - a.y) - (c.x - a.x).toDouble() * (b.y - a.y))
            val lungAC = distanza(a, c)
            if (lungAC > 1e-9 && croce / lungAC < 0.5) { p.removeAt(i); i = 0 } else i++
        }
        return p
    }

    /** Vero se nessun lato del poligono ne incrocia un altro non adiacente. */
    fun contornoSemplice(v: List<PointF>): Boolean {
        val n = v.size
        if (n < 4) return true
        fun incrocia(a: PointF, b: PointF, c: PointF, d: PointF): Boolean {
            fun o(p: PointF, q: PointF, r: PointF) =
                (q.x - p.x).toDouble() * (r.y - p.y) - (q.y - p.y).toDouble() * (r.x - p.x)
            val o1 = o(a, b, c); val o2 = o(a, b, d); val o3 = o(c, d, a); val o4 = o(c, d, b)
            return o1 * o2 < 0 && o3 * o4 < 0
        }
        for (i in 0 until n) for (j in i + 1 until n) {
            if (j == i + 1 || (i == 0 && j == n - 1)) continue
            if (incrocia(v[i], v[(i + 1) % n], v[j], v[(j + 1) % n])) return false
        }
        return true
    }

    /** Lunghezza di ciascun lato del poligono, nello stesso ordine dei vertici. */
    fun latiPoligono(vertici: List<PointF>): List<Double> {
        if (vertici.size < 2) return emptyList()
        return vertici.indices.map { i ->
            distanza(vertici[i], vertici[(i + 1) % vertici.size])
        }
    }

    /** Vero se [punto] cade dentro il poligono [vertici] (ray casting). Usato per capire su quale locale già confermato è caduto un tocco. */
    fun puntoDentroPoligono(punto: PointF, vertici: List<PointF>): Boolean {
        if (vertici.size < 3) return false
        var dentro = false
        var j = vertici.size - 1
        for (i in vertici.indices) {
            val vi = vertici[i]
            val vj = vertici[j]
            if ((vi.y > punto.y) != (vj.y > punto.y)) {
                val xIntersezione = vi.x + (punto.y - vi.y) / (vj.y - vi.y) * (vj.x - vi.x)
                if (punto.x < xIntersezione) dentro = !dentro
            }
            j = i
        }
        return dentro
    }

    fun centroide(vertici: List<PointF>): PointF {
        // Lista vuota: si evita la divisione per zero (che darebbe NaN) e si restituisce l'origine.
        if (vertici.isEmpty()) return PointF(0f, 0f)
        var x = 0f
        var y = 0f
        vertici.forEach { x += it.x; y += it.y }
        return PointF(x / vertici.size, y / vertici.size)
    }
}
