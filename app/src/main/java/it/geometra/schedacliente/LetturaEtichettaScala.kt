package it.geometra.schedacliente

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.TimeUnit

/**
 * Legge con l'OCR del telefono (ML Kit, già usato per le bollette) il valore scritto accanto
 * alla barra blu di scala, di solito "10 metri" scritto in verticale lungo il bordo. Serve solo
 * a cogliere i casi in cui la barra rappresenta un valore diverso (es. "5 metri"): se non si
 * legge nulla di sensato si restituisce null e l'app assume 10 m.
 *
 * Bloccante: va chiamato su un thread in background.
 */
object LetturaEtichettaScala {

    private val RE_METRI = Regex("""(\d{1,3}(?:[.,]\d)?)\s*(?:m\b|mt\b|mt\.|metri)""", RegexOption.IGNORE_CASE)

    fun leggiMetri(bitmap: Bitmap, zona: Rect): Double? {
        val sicura = Rect(
            zona.left.coerceIn(0, bitmap.width - 1), zona.top.coerceIn(0, bitmap.height - 1),
            zona.right.coerceIn(1, bitmap.width), zona.bottom.coerceIn(1, bitmap.height)
        )
        if (sicura.width() < 4 || sicura.height() < 4) return null
        var ritaglio = Bitmap.createBitmap(bitmap, sicura.left, sicura.top, sicura.width(), sicura.height())
        // Testo piccolo: si ingrandisce un po' il ritaglio per aiutare l'OCR.
        val latoCorto = minOf(ritaglio.width, ritaglio.height)
        if (latoCorto < 120) {
            val f = 120f / latoCorto
            ritaglio = Bitmap.createScaledBitmap(ritaglio, (ritaglio.width * f).toInt(), (ritaglio.height * f).toInt(), true)
        }
        val ocr = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            // La scritta è spesso ruotata di 90° (lungo un bordo verticale): si provano gli orientamenti.
            for (rotazione in intArrayOf(270, 90, 0)) {
                val testo = try {
                    Tasks.await(ocr.process(InputImage.fromBitmap(ritaglio, rotazione)), 8, TimeUnit.SECONDS).text
                } catch (e: Exception) {
                    continue
                }
                val valore = RE_METRI.find(testo)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
                if (valore != null && valore in 1.0..200.0) return valore
            }
        } finally {
            ocr.close()
        }
        return null
    }
}
