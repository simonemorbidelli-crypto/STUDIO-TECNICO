package it.geometra.schedacliente

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Disegna l'immagine di riepilogo del registro locale degli avvistamenti FDM, con la stessa
 * grafica del sondaggio condiviso su claude.ai (sfondo chiaro, schede bianche arrotondate,
 * grande numero color ocra, "chip" colorati per categoria, registro a righe). Contiene:
 * sondaggio della giornata, sondaggio totale e ultimi avvistamenti. Solo icone, mai i nomi
 * delle categorie per esteso. Pensata per essere caricata su Drive (vedi [DriveUploader]).
 *
 * Si disegna su una tela volutamente più alta del necessario e alla fine si ritaglia
 * all'altezza effettivamente usata: così l'immagine non può mai tagliare il contenuto,
 * qualunque sia il numero di voci.
 */
object RiepilogoFdmImmagine {

    private const val LARGHEZZA = 1080
    private const val ALTEZZA_MAX = 4200
    private const val MARGINE = 56f
    private const val MAX_VOCI_REGISTRO = 10

    fun eDiOggi(ts: Long): Boolean {
        val oggi = Calendar.getInstance()
        val c = Calendar.getInstance().apply { timeInMillis = ts }
        return c.get(Calendar.YEAR) == oggi.get(Calendar.YEAR) &&
            c.get(Calendar.DAY_OF_YEAR) == oggi.get(Calendar.DAY_OF_YEAR)
    }

    private val tipoPesante: Typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
    private val tipoMono: Typeface = Typeface.MONOSPACE
    private val tipoMonoGrassetto: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

    fun genera(
        context: Context,
        nomeBar: String?,
        vociOggi: List<AvvistamentiFdmLocaliStore.Avvistamento>,
        voci: List<AvvistamentiFdmLocaliStore.Avvistamento>,
        recenti: List<AvvistamentiFdmLocaliStore.Avvistamento>
    ): ByteArray {
        contesto = context.applicationContext
        val ultimeVoci = recenti.sortedByDescending { it.ts }.take(MAX_VOCI_REGISTRO)

        val bitmap = Bitmap.createBitmap(LARGHEZZA, ALTEZZA_MAX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(StileFdm.SFONDO_PAGINA)

        val larghezzaUtile = LARGHEZZA - 2 * MARGINE
        var y = 64f

        // --- Intestazione (come l'header del sondaggio condiviso) ---
        val paintEyebrow = testo(26f, StileFdm.TESTO_TENUE, tipoMono).apply { letterSpacing = 0.12f }
        y += 26f
        canvas.drawText("SONDAGGIO FDM · REGISTRO LOCALE", MARGINE, y, paintEyebrow)

        val paintTitolo = testo(84f, StileFdm.TESTO, tipoPesante)
        y += 96f
        canvas.drawText("Avvistamenti FDM", MARGINE, y, paintTitolo)

        val paintSub = testo(32f, StileFdm.TESTO_TENUE)
        val paintSubForte = testo(32f, StileFdm.TESTO, Typeface.DEFAULT_BOLD)
        y += 58f
        val prefisso = "Oggi siamo andati da: "
        canvas.drawText(prefisso, MARGINE, y, paintSub)
        canvas.drawText(
            nomeBar ?: "non ancora scelto",
            MARGINE + paintSub.measureText(prefisso), y, paintSubForte
        )
        y += 46f
        val dataOggi = SimpleDateFormat("EEEE d MMMM yyyy · HH:mm", Locale.ITALY).format(Date())
        canvas.drawText(dataOggi.replaceFirstChar { it.uppercase() }, MARGINE, y, paintSub)
        y += 44f

        // --- Sondaggio della giornata ---
        y = disegnaSchedaTotale(
            canvas, y, larghezzaUtile, "SONDAGGIO DELLA GIORNATA", vociOggi,
            if (vociOggi.size == 1) "avvistamento oggi" else "avvistamenti oggi"
        )
        y += 32f

        // --- Sondaggio totale ---
        y = disegnaSchedaTotale(
            canvas, y, larghezzaUtile, "SONDAGGIO TOTALE", voci,
            if (voci.size == 1) "avvistamento totale" else "avvistamenti totali"
        )
        y += 52f

        // --- Ultimi avvistamenti (come il "Registro" del sondaggio condiviso) ---
        val paintH2 = testo(40f, StileFdm.TESTO, Typeface.DEFAULT_BOLD)
        y += 40f
        canvas.drawText("Ultimi avvistamenti", MARGINE, y, paintH2)
        y += 26f

        if (ultimeVoci.isEmpty()) {
            val paintVuoto = testo(30f, StileFdm.TESTO_TENUE).apply { textAlign = Paint.Align.CENTER }
            y += 60f
            canvas.drawText("Nessun avvistamento ancora.", LARGHEZZA / 2f, y, paintVuoto)
            y += 30f
        } else {
            val formattoOra = SimpleDateFormat("EEE dd/MM · HH:mm", Locale.ITALY)
            val paintMeta = testo(30f, StileFdm.TESTO_TENUE, tipoMono)
            val altezzaRiga = 104f
            // Colonna degli orari allineata: parte dopo il chip più largo (quello dei due galli).
            val colonnaOrari = AvvistamentiFdmLocaliStore.CANDIDATI.maxOf { larghezzaChip(it, null) }
            ultimeVoci.forEach { v ->
                val riga = RectF(MARGINE, y, LARGHEZZA - MARGINE, y + altezzaRiga)
                disegnaScheda(canvas, riga, 28f)
                // Pillole tutte della stessa larghezza (quella dei due galli, la più larga).
                disegnaChip(canvas, MARGINE + 26f, riga.centerY(), v.luogo, null, colonnaOrari)
                canvas.drawText(
                    formattoOra.format(Date(v.ts)),
                    MARGINE + 26f + colonnaOrari + 26f, riga.centerY() + 11f, paintMeta
                )
                y += altezzaRiga + 16f
            }
        }

        // --- Piè di pagina ---
        val paintPiede = testo(22f, StileFdm.TESTO_TENUE, tipoMono).apply { textAlign = Paint.Align.CENTER }
        y += 34f
        canvas.drawText("Generato da SchedaCliente · solo su questo telefono", LARGHEZZA / 2f, y, paintPiede)
        y += 48f

        val altezzaFinale = y.toInt().coerceIn(400, ALTEZZA_MAX)
        val ritagliata = Bitmap.createBitmap(bitmap, 0, 0, LARGHEZZA, altezzaFinale)

        val stream = ByteArrayOutputStream()
        ritagliata.compress(Bitmap.CompressFormat.PNG, 100, stream)
        if (ritagliata !== bitmap) ritagliata.recycle()
        bitmap.recycle()
        return stream.toByteArray()
    }

    /**
     * Scheda bianca con etichetta, grande numero ocra, didascalia e chip per categoria (come la
     * sezione "tally" del sondaggio condiviso). Restituisce la y del bordo inferiore della scheda.
     */
    private fun disegnaSchedaTotale(
        canvas: Canvas,
        yInizio: Float,
        larghezzaUtile: Float,
        etichetta: String,
        voci: List<AvvistamentiFdmLocaliStore.Avvistamento>,
        didascalia: String
    ): Float {
        val paddingInterno = 40f
        val altezzaChip = 80f
        val spazioChip = 16f

        // Disposizione dei chip su una o più righe, centrate.
        val chip = AvvistamentiFdmLocaliStore.CANDIDATI.map { c ->
            Triple(c, voci.count { it.luogo == c }, larghezzaChip(c, voci.count { it.luogo == c }))
        }
        val righe = mutableListOf<MutableList<Triple<String, Int, Float>>>()
        var larghezzaRiga = 0f
        val larghezzaMaxRiga = larghezzaUtile - 2 * paddingInterno
        chip.forEach { elemento ->
            val aggiunta = elemento.third + if (righe.lastOrNull().isNullOrEmpty()) 0f else spazioChip
            if (righe.isEmpty() || larghezzaRiga + aggiunta > larghezzaMaxRiga) {
                righe.add(mutableListOf(elemento))
                larghezzaRiga = elemento.third
            } else {
                righe.last().add(elemento)
                larghezzaRiga += aggiunta
            }
        }

        val altezzaScheda = paddingInterno + 26f + 28f + 150f + 18f + 34f + 34f +
            righe.size * altezzaChip + (righe.size - 1) * spazioChip + paddingInterno
        val rett = RectF(MARGINE, yInizio, MARGINE + larghezzaUtile, yInizio + altezzaScheda)
        disegnaScheda(canvas, rett, 38f)

        val cx = rett.centerX()
        var y = yInizio + paddingInterno + 26f
        canvas.drawText(etichetta, cx, y, testo(26f, StileFdm.TESTO_TENUE, tipoMono).apply {
            letterSpacing = 0.12f; textAlign = Paint.Align.CENTER
        })
        y += 28f + 150f
        canvas.drawText(voci.size.toString(), cx, y, testo(170f, StileFdm.ACCENTO, tipoPesante).apply {
            textAlign = Paint.Align.CENTER
        })
        y += 18f + 34f
        canvas.drawText(didascalia, cx, y, testo(32f, StileFdm.TESTO_TENUE).apply {
            textAlign = Paint.Align.CENTER
        })
        y += 34f

        righe.forEach { riga ->
            val totale = riga.sumOf { it.third.toDouble() }.toFloat() + spazioChip * (riga.size - 1)
            var x = cx - totale / 2f
            val centroY = y + altezzaChip / 2f
            riga.forEach { (c, conteggio, larghezza) ->
                disegnaChip(canvas, x, centroY, c, conteggio)
                x += larghezza + spazioChip
            }
            y += altezzaChip + spazioChip
        }
        return rett.bottom
    }

    private fun paintEmoji() = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 40f; color = StileFdm.TESTO }
    private fun paintConteggio(c: String) = testo(34f, StileFdm.COLORE_TESTO[c] ?: StileFdm.TESTO, tipoMonoGrassetto)

    /** Lato dell'icona disegnata (uomo dietro le sbarre) usata al posto dell'emoji per "celli". */
    private const val LATO_ICONA_CELLI = 50f

    /** Contesto per caricare l'icona vettoriale di "celli"; impostato all'inizio di [genera]. */
    private var contesto: Context? = null

    private fun larghezzaIcona(c: String): Float =
        if (c == "celli") LATO_ICONA_CELLI else paintEmoji().measureText(StileFdm.ICONE[c] ?: "•")

    private fun disegnaIcona(canvas: Canvas, c: String, x: Float, centroY: Float) {
        if (c == "celli") {
            val d = contesto?.let { androidx.core.content.ContextCompat.getDrawable(it, R.drawable.ic_fdm_celli) }
            if (d != null) {
                val l = LATO_ICONA_CELLI
                d.setBounds(x.toInt(), (centroY - l / 2f).toInt(), (x + l).toInt(), (centroY + l / 2f).toInt())
                d.draw(canvas)
                return
            }
        }
        canvas.drawText(StileFdm.ICONE[c] ?: "•", x, centroY + 14f, paintEmoji())
    }

    private fun larghezzaChip(c: String, conteggio: Int?): Float {
        val emoji = larghezzaIcona(c)
        val numero = if (conteggio != null) 12f + paintConteggio(c).measureText(conteggio.toString()) else 0f
        return 26f + emoji + numero + 26f
    }

    /** Chip arrotondato (pillola) con icona ed eventuale conteggio; restituisce la sua larghezza. */
    private fun disegnaChip(
        canvas: Canvas, x: Float, centroY: Float, c: String, conteggio: Int?, larghezzaFissa: Float? = null
    ): Float {
        val larghezza = larghezzaFissa ?: larghezzaChip(c, conteggio)
        val altezza = 76f
        val rett = RectF(x, centroY - altezza / 2f, x + larghezza, centroY + altezza / 2f)
        val paintSfondo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = StileFdm.COLORE_SFONDO[c] ?: StileFdm.SFONDO_PAGINA
        }
        canvas.drawRoundRect(rett, altezza / 2f, altezza / 2f, paintSfondo)

        // Con larghezza fissa (registro) l'icona va centrata nella pillola.
        val xIcona = if (larghezzaFissa != null) x + (larghezza - larghezzaIcona(c)) / 2f else x + 26f
        disegnaIcona(canvas, c, xIcona, centroY)
        if (conteggio != null) {
            canvas.drawText(
                conteggio.toString(),
                x + 26f + larghezzaIcona(c) + 12f, centroY + 12f, paintConteggio(c)
            )
        }
        return larghezza
    }

    /** Scheda bianca con bordo sottile, come le "card" del sondaggio condiviso. */
    private fun disegnaScheda(canvas: Canvas, rett: RectF, raggio: Float) {
        canvas.drawRoundRect(rett, raggio, raggio, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = StileFdm.SUPERFICIE
        })
        canvas.drawRoundRect(rett, raggio, raggio, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
            color = StileFdm.BORDO
        })
    }

    private fun testo(dimensione: Float, colore: Int, tipo: Typeface = Typeface.DEFAULT) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = dimensione
            color = colore
            typeface = tipo
        }
}
