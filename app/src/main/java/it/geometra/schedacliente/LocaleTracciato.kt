package it.geometra.schedacliente

import android.graphics.PointF

/** Un locale tracciato a mano sulla planimetria: vertici in pixel dell'immagine originale, area e lati già convertiti in metri. */
data class LocaleTracciato(
    val nome: String,
    val verticiPx: List<PointF>,
    val areaM2: Double,
    val latiM: List<Double>
)
