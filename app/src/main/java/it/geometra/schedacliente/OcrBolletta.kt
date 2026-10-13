package it.geometra.schedacliente

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale

/** Dati letti automaticamente dal testo OCR di una bolletta: ciascun campo è null se non trovato. */
data class DatiEstrattiBolletta(
    val importoTotale: Double?,
    val consumoTotaleKwh: Double?
)

/**
 * Lettura automatica (OCR) dei dati principali di una bolletta elettrica, a partire dalle foto o
 * dai PDF allegati: riconoscimento testo on-device con Google ML Kit Text Recognition (il testo
 * viene elaborato sul telefono, non inviato a nessun server; il modello di riconoscimento viene
 * scaricato una sola volta tramite Google Play Services al primo utilizzo, poi funziona anche
 * offline), poi un'analisi con semplici pattern/parole chiave per individuare importo totale e
 * consumo totale in kWh.
 *
 * IMPORTANTE (vedi anche BollettaEnergiaFormActivity): quanto estratto qui serve solo a
 * pre-compilare il form, mai a salvare direttamente — l'utente deve sempre poter correggere, e le
 * letture del contatore a valle dello studio non vengono mai cercate qui: non compaiono sulla
 * bolletta del proprietario, vanno sempre inserite a mano.
 */
object OcrBolletta {

    /** Riconosce il testo in [bitmap] e lo passa a [onSuccesso]; eventuali errori a [onErrore]. */
    fun riconosciTesto(bitmap: Bitmap, onSuccesso: (String) -> Unit, onErrore: (Exception) -> Unit) {
        val immagine = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(immagine)
            .addOnSuccessListener { risultato -> onSuccesso(risultato.text) }
            .addOnFailureListener { e -> onErrore(e) }
    }

    /**
     * Decodifica un'immagine (foto della bolletta) da [uri], limitandone il lato maggiore a
     * [latoMassimo] px e applicando la rotazione corretta in base al tag EXIF "Orientation": una
     * fotocamera di un telefono ha il sensore orizzontale, quindi una foto scattata in verticale
     * viene salvata "sdraiata" con la sola informazione di come ruotarla per vederla giusta.
     * BitmapFactory non applica questa rotazione da solo: senza [applicaRotazioneExif] la foto
     * verrebbe mostrata e letta dall'OCR ruotata di 90°, con testo storto e difficile da riconoscere.
     */
    fun decodificaImmagine(context: Context, uri: Uri, latoMassimo: Int = 2200): Bitmap? {
        return try {
            val opzioniMisura = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opzioniMisura) }
            var inSampleSize = 1
            val latoOriginale = maxOf(opzioniMisura.outWidth, opzioniMisura.outHeight)
            while (latoOriginale / inSampleSize > latoMassimo) inSampleSize *= 2
            val opzioniDecodifica = BitmapFactory.Options().apply { this.inSampleSize = inSampleSize }
            val bitmapGrezzo = context.contentResolver.openInputStream(uri)
                ?.use { BitmapFactory.decodeStream(it, null, opzioniDecodifica) }
                ?: return null
            applicaRotazioneExif(context, uri, bitmapGrezzo)
        } catch (e: Exception) {
            null
        }
    }

    /** Legge il tag EXIF "Orientation" di [uri] e ruota [bitmap] di conseguenza, se necessario. */
    private fun applicaRotazioneExif(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        val gradi = try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                when (ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        } catch (e: Exception) {
            0f
        }
        if (gradi == 0f) return bitmap
        return try {
            val matrice = Matrix().apply { postRotate(gradi) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrice, true)
        } catch (e: Exception) {
            bitmap
        }
    }

    /**
     * Rasterizza la prima pagina di un PDF come bitmap: ML Kit Text Recognition lavora solo su
     * immagini, non direttamente su un PDF, quindi un allegato PDF va prima trasformato in
     * immagine (si assume che l'importo/consumo compaiano nella prima pagina, vero per tutte le
     * bollette Enel/fornitori viste finora). Un PDF non ha orientamento EXIF: le pagine sono già
     * disegnate diritte da [PdfRenderer].
     */
    fun renderizzaPrimaPaginaPdf(context: Context, uri: Uri): Bitmap? {
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        return try {
            pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
            renderer = PdfRenderer(pfd)
            if (renderer.pageCount == 0) return null
            val pagina = renderer.openPage(0)
            val scala = (2200f / pagina.width).coerceAtMost(8f)
            val larghezzaPx = (pagina.width * scala).toInt().coerceAtLeast(1)
            val altezzaPx = (pagina.height * scala).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(larghezzaPx, altezzaPx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.WHITE)
            pagina.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            pagina.close()
            bmp
        } catch (e: Exception) {
            null
        } finally {
            renderer?.close()
            pfd?.close()
        }
    }

    /**
     * Rasterizza fino a [maxPagine] pagine di un PDF (tutte, se sono di meno): una bolletta può
     * avere il totale nella prima pagina e il dettaglio dei consumi in una pagina successiva (o
     * viceversa), quindi per l'OCR non basta guardare solo la prima pagina come fa
     * [renderizzaPrimaPaginaPdf] (usata solo per la miniatura di anteprima, dove una pagina basta).
     * Se una singola pagina non si riesce a rasterizzare viene saltata, non blocca le altre.
     */
    fun renderizzaPaginePdf(context: Context, uri: Uri, maxPagine: Int = 8): List<Bitmap> {
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        val pagine = mutableListOf<Bitmap>()
        try {
            pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return pagine
            renderer = PdfRenderer(pfd)
            val numeroPagine = minOf(renderer.pageCount, maxPagine)
            for (indice in 0 until numeroPagine) {
                val pagina = renderer.openPage(indice)
                try {
                    val scala = (2200f / pagina.width).coerceAtMost(8f)
                    val larghezzaPx = (pagina.width * scala).toInt().coerceAtLeast(1)
                    val altezzaPx = (pagina.height * scala).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(larghezzaPx, altezzaPx, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bmp)
                    canvas.drawColor(Color.WHITE)
                    pagina.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    pagine.add(bmp)
                } catch (e: Exception) {
                    // Pagina saltata, si prova a continuare con le successive.
                } finally {
                    pagina.close()
                }
            }
        } catch (e: Exception) {
            // Si restituisce quanto rasterizzato finora (anche nessuna pagina).
        } finally {
            renderer?.close()
            pfd?.close()
        }
        return pagine
    }

    private val regexImporto = Regex("""(\d{1,3}(?:\.\d{3})*,\d{2})""")
    // Un consumo in kWh può comparire sia come "411,05 kWh" sia, come fa spesso il riepilogo delle
    // bollette ("TOTALE kWh 411,054000"), con l'unità di misura PRIMA del numero: servono entrambi
    // i versi, altrimenti il secondo formato non viene mai trovato.
    private val regexConsumo = Regex("""(\d+(?:[.,]\d+)?)\s*kwh""", RegexOption.IGNORE_CASE)
    private val regexConsumoInverso = Regex("""kwh\D{0,6}?(\d+(?:[.,]\d+)?)""", RegexOption.IGNORE_CASE)

    private val paroleTotaleForti = listOf(
        "totale da pagare", "importo totale", "totale bolletta", "totale fattura", "totale documento",
        "totale dovuto", "importo dovuto", "spesa totale", "totale da versare", "totale avviso di pagamento"
    )

    /**
     * Analizza il testo riconosciuto dall'OCR (il testo di tutte le foto/pagine allegate, unito in
     * un unico blocco) cercando importo totale e consumo totale in kWh. Non è un parser
     * "intelligente", ma un primo aiuto da verificare sempre a mano (vedi nota in testa al file).
     * Ogni campo non individuato resta null, e il form lo lascia semplicemente vuoto da compilare
     * a mano.
     *
     * Non si basa sull'ordine delle righe: un conto rasterizzato e poi letto dall'OCR può mettere
     * l'etichetta ("Totale da pagare") e il relativo importo su blocchi di testo che l'OCR
     * restituisce in un ordine qualsiasi (non necessariamente l'ordine di lettura "naturale" del
     * documento, specie se erano in colonne/riquadri separati) — e vale per qualunque fornitore,
     * non solo per il layout di una bolletta specifica. Per questo si cerca, fra tutti i numeri
     * trovati nel testo, quello più VICINO (in caratteri, non in righe) a una delle parole chiave,
     * in qualunque verso compaia rispetto ad essa: è una stima più robusta del semplice "la riga
     * dopo" o "la riga prima".
     */
    fun analizzaTesto(testo: String): DatiEstrattiBolletta {
        return DatiEstrattiBolletta(trovaImportoTotale(testo), trovaConsumoTotale(testo))
    }

    private fun trovaImportoTotale(testo: String): Double? {
        val minuscolo = testo.lowercase(Locale.ITALY)
        val candidati = regexImporto.findAll(testo).toList()
        if (candidati.isEmpty()) return null

        // 1) Importo più vicino a una delle parole chiave "forti" (più specifiche, meno ambigue).
        val posizioniForti = paroleTotaleForti.flatMap { tutteLePosizioni(minuscolo, it) }
        trovaPiuVicino(candidati, posizioniForti) { parseImportoItaliano(it.value) }?.let { return it }

        // 2) Importo più vicino a una parola "total" generica, scartando quelle vicine a "kwh"
        //    (per non confondersi con un totale di consumo, non di spesa).
        val posizioniGeneriche = tutteLePosizioni(minuscolo, "total").filter { posizione ->
            val intorno = minuscolo.substring(maxOf(0, posizione - 15), minOf(minuscolo.length, posizione + 25))
            !intorno.contains("kwh")
        }
        trovaPiuVicino(candidati, posizioniGeneriche) { parseImportoItaliano(it.value) }?.let { return it }

        // 3) Ultima spiaggia: l'importo più alto trovato nel testo, che su una bolletta è quasi
        //    sempre il totale complessivo (tutte le voci parziali sono più basse).
        return candidati.mapNotNull { parseImportoItaliano(it.value) }.maxOrNull()
    }

    private fun trovaConsumoTotale(testo: String): Double? {
        val minuscolo = testo.lowercase(Locale.ITALY)
        val candidati = (regexConsumo.findAll(testo).map { it.range.first to it.groupValues[1] } +
            regexConsumoInverso.findAll(testo).map { it.range.first to it.groupValues[1] }).toList()
        if (candidati.isEmpty()) return null

        // 1) Consumo più vicino a una dicitura "totale ... kWh" (es. "TOTALE kWh 411,054000"): il
        //    dato più specifico e affidabile, quando il documento lo riporta esplicitamente.
        val posizioniTotaleKwh = tutteLePosizioni(minuscolo, "total").filter { posizione ->
            val intorno = minuscolo.substring(maxOf(0, posizione - 10), minOf(minuscolo.length, posizione + 30))
            intorno.contains("kwh")
        }
        trovaPiuVicinoCoppia(candidati, posizioniTotaleKwh)?.let { return it }

        // 2) Consumo più vicino a una parola "consum" (consumo/consumi).
        val posizioniConsum = tutteLePosizioni(minuscolo, "consum")
        trovaPiuVicinoCoppia(candidati, posizioniConsum)?.let { return it }

        // 3) Ultima spiaggia: il consumo più alto trovato (le singole fasce/periodi sono sempre
        //    inferiori al totale).
        return candidati.mapNotNull { parseNumeroItaliano(it.second) }.maxOrNull()
    }

    /** Fra tutte le posizioni in [posizioniChiave], trova il candidato più vicino e lo converte con [estrai]. */
    private fun <T> trovaPiuVicino(candidati: List<MatchResult>, posizioniChiave: List<Int>, estrai: (MatchResult) -> T?): T? {
        if (posizioniChiave.isEmpty()) return null
        val migliore = candidati.minByOrNull { candidato -> posizioniChiave.minOf { kotlin.math.abs(candidato.range.first - it) } }
            ?: return null
        return estrai(migliore)
    }

    private fun trovaPiuVicinoCoppia(candidati: List<Pair<Int, String>>, posizioniChiave: List<Int>): Double? {
        if (posizioniChiave.isEmpty() || candidati.isEmpty()) return null
        val migliore = candidati.minByOrNull { (posizione, _) -> posizioniChiave.minOf { kotlin.math.abs(posizione - it) } }
            ?: return null
        return parseNumeroItaliano(migliore.second)
    }

    /** Tutte le posizioni (indici di carattere) in cui [sottostringa] compare in [testo]. */
    private fun tutteLePosizioni(testo: String, sottostringa: String): List<Int> {
        val posizioni = mutableListOf<Int>()
        var indice = testo.indexOf(sottostringa)
        while (indice >= 0) {
            posizioni.add(indice)
            indice = testo.indexOf(sottostringa, indice + 1)
        }
        return posizioni
    }

    /** Converte un importo in formato italiano ("1.234,56" o "123,45") in Double. */
    private fun parseImportoItaliano(s: String): Double? =
        s.trim().replace(".", "").replace(",", ".").toDoubleOrNull()

    /** Come [parseImportoItaliano], ma per un numero che potrebbe non avere separatore delle migliaia. */
    private fun parseNumeroItaliano(s: String): Double? {
        val pulito = s.trim()
        return if (pulito.contains(",")) {
            pulito.replace(".", "").replace(",", ".").toDoubleOrNull()
        } else {
            pulito.toDoubleOrNull()
        }
    }
}
