package it.geometra.schedacliente

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.ByteBuffer
import java.util.zip.ZipInputStream

/**
 * Prepara i file scaricati dalla cartella Mega per la richiesta all'AI:
 *  - PDF: passati così come sono (l'API li legge pagina per pagina, testo + immagine della
 *    pagina, quindi anche visure e planimetrie scansionate); qui si controlla solo che siano
 *    PDF validi, non protetti da password, e se ne contano le pagine (limite dell'API).
 *  - Word .docx: se ne estrae il testo (il .docx è un archivio zip con il testo in XML).
 *  - Immagini: ridimensionate (lato lungo max 1568 px, come consigliato per la vision) e
 *    ricompresse in JPEG.
 *  - Testo semplice (.txt, .csv, .md): letto così com'è.
 * Gli altri formati (es. vecchio .doc, .xls, .dwg, archivi) non sono supportati.
 */
object PreparatoreDocumenti {

    enum class Tipo(val icona: String, val descrizione: String, val supportato: Boolean) {
        PDF("📕", "PDF", true),
        WORD("📝", "Word", true),
        IMMAGINE("🖼️", "Immagine", true),
        TESTO("📃", "Testo", true),
        NON_SUPPORTATO("🚫", "Formato non supportato", false)
    }

    sealed class Blocco(val nome: String) {
        class Pdf(nome: String, val dati: ByteArray, val pagine: Int) : Blocco(nome)
        class Testo(nome: String, val testo: String, val origine: Tipo) : Blocco(nome)
        class Immagine(nome: String, val jpeg: ByteArray) : Blocco(nome)
    }

    class DocumentoNonLeggibile(messaggio: String) : IOException(messaggio)

    private const val LATO_MAX_IMMAGINE = 1568
    private const val CARATTERI_MAX_TESTO = 150_000

    fun tipoDi(nomeFile: String): Tipo = when (nomeFile.substringAfterLast('.', "").lowercase()) {
        "pdf" -> Tipo.PDF
        "docx" -> Tipo.WORD
        "jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif" -> Tipo.IMMAGINE
        "txt", "csv", "md" -> Tipo.TESTO
        else -> Tipo.NON_SUPPORTATO
    }

    /** Motivo leggibile per cui un formato non è supportato, mostrato accanto al file. */
    fun motivoNonSupportato(nomeFile: String): String = when (nomeFile.substringAfterLast('.', "").lowercase()) {
        "doc" -> "Word vecchio formato (.doc): salvalo come .docx o PDF"
        "xls", "xlsx" -> "Foglio Excel non supportato: esportalo in PDF"
        "dwg", "dxf" -> "Disegno CAD non supportato: esportalo in PDF"
        "zip", "rar", "7z" -> "Archivio compresso non supportato"
        "p7m" -> "File firmato digitalmente (.p7m): usa il PDF originale"
        else -> "Formato non supportato"
    }

    fun prepara(context: Context, nomeFile: String, dati: ByteArray): Blocco = when (tipoDi(nomeFile)) {
        Tipo.PDF -> preparaPdf(context, nomeFile, dati)
        Tipo.WORD -> {
            val testo = estraiTestoDocx(dati)
            if (testo.isBlank()) throw DocumentoNonLeggibile("\"$nomeFile\" non contiene testo leggibile")
            Blocco.Testo(nomeFile, limita(testo), Tipo.WORD)
        }
        Tipo.IMMAGINE -> Blocco.Immagine(nomeFile, preparaImmagine(nomeFile, dati))
        Tipo.TESTO -> Blocco.Testo(nomeFile, limita(decodificaTesto(dati)), Tipo.TESTO)
        Tipo.NON_SUPPORTATO -> throw DocumentoNonLeggibile(motivoNonSupportato(nomeFile))
    }

    private fun preparaPdf(context: Context, nomeFile: String, dati: ByteArray): Blocco.Pdf {
        if (dati.size < 5 || String(dati, 0, 5, StandardCharsets.ISO_8859_1) != "%PDF-") {
            throw DocumentoNonLeggibile("\"$nomeFile\" non è un PDF valido")
        }
        // PdfRenderer serve un file: copia temporanea nella cache, cancellata subito dopo.
        val temporaneo = File.createTempFile("parere_", ".pdf", context.cacheDir)
        try {
            temporaneo.writeBytes(dati)
            val pagine = try {
                ParcelFileDescriptor.open(temporaneo, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    PdfRenderer(pfd).use { it.pageCount }
                }
            } catch (e: SecurityException) {
                throw DocumentoNonLeggibile("\"$nomeFile\" è protetto da password")
            } catch (e: IOException) {
                throw DocumentoNonLeggibile("\"$nomeFile\" è danneggiato o non leggibile")
            }
            return Blocco.Pdf(nomeFile, dati, pagine)
        } finally {
            temporaneo.delete()
        }
    }

    private fun preparaImmagine(nomeFile: String, dati: ByteArray): ByteArray {
        val limiti = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(dati, 0, dati.size, limiti)
        if (limiti.outWidth <= 0 || limiti.outHeight <= 0) {
            throw DocumentoNonLeggibile("Immagine \"$nomeFile\" non leggibile su questo telefono")
        }
        var campionamento = 1
        while (maxOf(limiti.outWidth, limiti.outHeight) / (campionamento * 2) >= LATO_MAX_IMMAGINE) campionamento *= 2
        val bitmap = BitmapFactory.decodeByteArray(dati, 0, dati.size, BitmapFactory.Options().apply {
            inSampleSize = campionamento
        }) ?: throw DocumentoNonLeggibile("Immagine \"$nomeFile\" non leggibile")
        val lato = maxOf(bitmap.width, bitmap.height)
        val finale = if (lato > LATO_MAX_IMMAGINE) {
            val scala = LATO_MAX_IMMAGINE.toFloat() / lato
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scala).toInt(), (bitmap.height * scala).toInt(), true)
        } else bitmap
        val uscita = ByteArrayOutputStream()
        finale.compress(Bitmap.CompressFormat.JPEG, 85, uscita)
        if (finale !== bitmap) finale.recycle()
        bitmap.recycle()
        return uscita.toByteArray()
    }

    /**
     * Testo di un .docx: legge word/document.xml (paragrafi, tabulazioni, a capo, tabelle con
     * celle separate da " | ").
     */
    fun estraiTestoDocx(dati: ByteArray): String {
        val xml = ZipInputStream(ByteArrayInputStream(dati)).use { zip ->
            generateSequence { zip.nextEntry }.firstOrNull { it.name == "word/document.xml" }
                ?: throw DocumentoNonLeggibile("File Word non valido (manca il contenuto)")
            zip.readBytes()
        }
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(ByteArrayInputStream(xml), "UTF-8")
        }
        val testo = StringBuilder()
        var dentroTesto = false
        while (true) {
            when (parser.next()) {
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.START_TAG -> when (parser.name) {
                    "w:t" -> dentroTesto = true
                    "w:tab" -> testo.append('\t')
                    "w:br", "w:cr" -> testo.append('\n')
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "w:t" -> dentroTesto = false
                    "w:p" -> testo.append('\n')
                    "w:tc" -> { if (testo.endsWith("\n")) testo.setLength(testo.length - 1); testo.append(" | ") }
                    "w:tr" -> testo.append('\n')
                }
                XmlPullParser.TEXT -> if (dentroTesto) testo.append(parser.text)
            }
        }
        return testo.toString().replace(Regex("\n{3,}"), "\n\n").trim()
    }

    /** UTF-8 se valido, altrimenti Windows-1252 (tipico dei .txt salvati da vecchi programmi). */
    private fun decodificaTesto(dati: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(dati)).toString()
    } catch (e: CharacterCodingException) {
        String(dati, charset("windows-1252"))
    }.removePrefix("﻿")

    private fun limita(testo: String): String =
        if (testo.length <= CARATTERI_MAX_TESTO) testo
        else testo.take(CARATTERI_MAX_TESTO) + "\n[… testo troncato: documento troppo lungo …]"
}
