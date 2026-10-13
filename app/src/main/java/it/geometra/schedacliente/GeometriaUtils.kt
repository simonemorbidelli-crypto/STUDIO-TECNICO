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
