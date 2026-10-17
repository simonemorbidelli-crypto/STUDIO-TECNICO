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
