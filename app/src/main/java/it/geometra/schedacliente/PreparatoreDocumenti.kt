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
 *  - Excel .xlsx/.xlsm, PowerPoint .pptx, OpenDocument (.odt/.ods/.odp), RTF, HTML: se ne estrae il testo.
 * Tutti gli altri file (vecchio .doc/.xls/.ppt, .dwg, .tif, archivi, .p7m, audio/video…) si possono
 * comunque spuntare: al momento della generazione vengono scartati e all'utente si dice quali sono
 * stati scartati e come convertirli ([suggerimento]).
 */
object PreparatoreDocumenti {

    enum class Tipo(val icona: String, val descrizione: String, val supportato: Boolean) {
        PDF("📕", "PDF", true),
        WORD("📝", "Word", true),
        IMMAGINE("🖼️", "Immagine", true),
        TESTO("📃", "Testo", true),
        FOGLIO("📊", "Foglio di calcolo", true),
        PRESENTAZIONE("📽️", "Presentazione", true),
        OPENDOCUMENT("📝", "OpenDocument", true),
        RTF("📝", "RTF", true),
        HTML("🌐", "Pagina web", true),
        NON_SUPPORTATO("🚫", "Non leggibile dall'AI", false)
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
        "txt", "csv", "tsv", "md", "json", "xml", "log", "ini", "xyz", "pts", "gml" -> Tipo.TESTO
        "xlsx", "xlsm" -> Tipo.FOGLIO
        "pptx" -> Tipo.PRESENTAZIONE
        "odt", "ods", "odp" -> Tipo.OPENDOCUMENT
        "rtf" -> Tipo.RTF
        "html", "htm" -> Tipo.HTML
        else -> Tipo.NON_SUPPORTATO
    }

    /** Motivo leggibile per cui un formato non è supportato, con il suggerimento per convertirlo. */
    fun motivoNonSupportato(nomeFile: String): String = when (nomeFile.substringAfterLast('.', "").lowercase()) {
        "doc", "dot" -> "Word vecchio formato (.doc): aprilo in Word e salvalo come .docx o PDF"
        "xls", "xlsb" -> "Excel vecchio formato: aprilo in Excel e salvalo come .xlsx, oppure esportalo in PDF"
        "ppt", "pps" -> "PowerPoint vecchio formato: salvalo come .pptx o PDF"
        "dwg", "dxf", "dwf" -> "Disegno CAD: esportalo in PDF (Stampa/Plotta su PDF dal programma di disegno)"
        "tif", "tiff" -> "Immagine TIFF: convertila in PDF o JPG (ad esempio dall'app Galleria o con \"Stampa su PDF\")"
        "zip", "rar", "7z", "tar", "gz" -> "Archivio compresso: estrai i file e scegli direttamente quelli utili"
        "p7m" -> "File firmato digitalmente (.p7m): usa il PDF originale oppure estrai il documento con il programma di firma"
        "eml", "msg" -> "Email: salvala come PDF oppure copia il testo in un file .txt"
        "mp3", "m4a", "wav", "mp4", "mov", "avi", "mkv", "3gp" -> "Audio/video non leggibile: se serve, trascrivilo in un file .txt"
        "shp", "dbf", "dat", "tfw", "pdw", "pregeo" -> "File tecnico/topografico: esporta un rapporto o un estratto in PDF"
        else -> "Formato non leggibile dall'AI: convertilo in PDF, Word (.docx) o immagine (JPG/PNG)"
    }

    fun prepara(context: Context, nomeFile: String, dati: ByteArray): Blocco = when (tipoDi(nomeFile)) {
        Tipo.PDF -> preparaPdf(context, nomeFile, dati)
        Tipo.WORD -> {
            val testo = estraiTestoDocx(dati)
            if (testo.isBlank()) throw DocumentoNonLeggibile("\"$nomeFile\" non contiene testo leggibile (solo immagini?): esportalo in PDF")
            Blocco.Testo(nomeFile, limita(testo), Tipo.WORD)
        }
        Tipo.IMMAGINE -> Blocco.Immagine(nomeFile, preparaImmagine(nomeFile, dati))
        Tipo.TESTO -> Blocco.Testo(nomeFile, limita(decodificaTesto(dati)), Tipo.TESTO)
        Tipo.FOGLIO -> testoOCosa(nomeFile, Tipo.FOGLIO, "Il foglio non contiene testo o numeri leggibili") { estraiTestoXlsx(dati) }
        Tipo.PRESENTAZIONE -> testoOCosa(nomeFile, Tipo.PRESENTAZIONE, "La presentazione non contiene testo (solo immagini?): esportala in PDF") { estraiTestoPptx(dati) }
        Tipo.OPENDOCUMENT -> testoOCosa(nomeFile, Tipo.OPENDOCUMENT, "Il documento non contiene testo leggibile: esportalo in PDF") { estraiTestoOpenDocument(dati) }
        Tipo.RTF -> testoOCosa(nomeFile, Tipo.RTF, "Il file RTF non contiene testo leggibile: salvalo come .docx o PDF") { estraiTestoRtf(decodificaTesto(dati)) }
        Tipo.HTML -> testoOCosa(nomeFile, Tipo.HTML, "La pagina non contiene testo leggibile: salvala come PDF") { estraiTestoHtml(decodificaTesto(dati)) }
        Tipo.NON_SUPPORTATO -> throw DocumentoNonLeggibile(motivoNonSupportato(nomeFile))
    }

    private fun testoOCosa(nomeFile: String, tipo: Tipo, vuoto: String, estrai: () -> String): Blocco {
        val testo = try { estrai() } catch (e: DocumentoNonLeggibile) { throw DocumentoNonLeggibile("\"$nomeFile\": ${e.message}") } catch (e: Exception) {
            throw DocumentoNonLeggibile("\"$nomeFile\" è danneggiato o non si apre: riesportalo in PDF")
        }
        if (testo.isBlank()) throw DocumentoNonLeggibile("\"$nomeFile\": $vuoto")
        return Blocco.Testo(nomeFile, limita(testo), tipo)
    }

    private fun preparaPdf(context: Context, nomeFile: String, dati: ByteArray): Blocco.Pdf {
        if (dati.size < 5 || String(dati, 0, 5, StandardCharsets.ISO_8859_1) != "%PDF-") {
            throw DocumentoNonLeggibile("\"$nomeFile\" non è un PDF valido o è danneggiato: scaricalo o esportalo di nuovo in PDF")
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
                throw DocumentoNonLeggibile("\"$nomeFile\" è protetto da password: salvane una copia senza protezione (ad esempio con \"Stampa su PDF\")")
            } catch (e: IOException) {
                throw DocumentoNonLeggibile("\"$nomeFile\" è danneggiato o non leggibile: esportalo di nuovo in PDF")
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
            throw DocumentoNonLeggibile("Immagine \"$nomeFile\" non leggibile su questo telefono (formato non supportato?): convertila in JPG o PNG")
        }
        var campionamento = 1
        while (maxOf(limiti.outWidth, limiti.outHeight) / (campionamento * 2) >= LATO_MAX_IMMAGINE) campionamento *= 2
        val bitmap = BitmapFactory.decodeByteArray(dati, 0, dati.size, BitmapFactory.Options().apply {
            inSampleSize = campionamento
        }) ?: throw DocumentoNonLeggibile("Immagine \"$nomeFile\" non leggibile: convertila in JPG o PNG")
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

    // ---------------------------------------------------------------- altri formati

    private fun voceZip(dati: ByteArray, nome: String): ByteArray? =
        ZipInputStream(ByteArrayInputStream(dati)).use { zip ->
            generateSequence { zip.nextEntry }.firstOrNull { it.name == nome }?.let { zip.readBytes() }
        }

    private fun vociZip(dati: ByteArray, prefisso: String, suffisso: String): List<Pair<String, ByteArray>> =
        ZipInputStream(ByteArrayInputStream(dati)).use { zip ->
            generateSequence { zip.nextEntry }
                .filter { it.name.startsWith(prefisso) && it.name.endsWith(suffisso) }
                .map { it.name to zip.readBytes() }.toList()
        }

    private fun parser(xml: ByteArray) = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        setInput(ByteArrayInputStream(xml), "UTF-8")
    }

    private fun numeroFinale(nome: String) = Regex("(\\d+)\\.xml$").find(nome)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    /** Excel .xlsx: ogni foglio, riga per riga, celle separate da " | " (valori già calcolati). */
    fun estraiTestoXlsx(dati: ByteArray): String {
        val condivise = ArrayList<String>()
        voceZip(dati, "xl/sharedStrings.xml")?.let { xml ->
            val p = parser(xml); var dentroT = false; var corrente: StringBuilder? = null
            while (true) when (p.next()) {
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.START_TAG -> if (p.name == "si") corrente = StringBuilder() else if (p.name == "t") dentroT = true
                XmlPullParser.END_TAG -> if (p.name == "t") dentroT = false else if (p.name == "si") { condivise += corrente.toString(); corrente = null }
                XmlPullParser.TEXT -> if (dentroT) corrente?.append(p.text)
            }
        }
        val nomiFogli = ArrayList<String>()
        voceZip(dati, "xl/workbook.xml")?.let { xml ->
            val p = parser(xml)
            while (p.next() != XmlPullParser.END_DOCUMENT) if (p.eventType == XmlPullParser.START_TAG && p.name == "sheet") nomiFogli += p.getAttributeValue(null, "name") ?: ""
        }
        val fogli = vociZip(dati, "xl/worksheets/", ".xml").sortedBy { numeroFinale(it.first) }
        if (fogli.isEmpty()) throw DocumentoNonLeggibile("File Excel non valido (nessun foglio)")
        val out = StringBuilder()
        fogli.forEachIndexed { i, (_, xml) ->
            out.append("--- Foglio: ").append(nomiFogli.getOrNull(i) ?: "${i + 1}").append(" ---\n")
            val p = parser(xml)
            val riga = ArrayList<String>()
            var tipoCella = ""; var dentroV = false; var dentroT = false; var valore = StringBuilder()
            while (true) when (p.next()) {
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.START_TAG -> when (p.name) {
                    "row" -> riga.clear()
                    "c" -> { tipoCella = p.getAttributeValue(null, "t") ?: ""; valore = StringBuilder() }
                    "v" -> dentroV = true
                    "t" -> dentroT = true
                }
                XmlPullParser.END_TAG -> when (p.name) {
                    "v" -> dentroV = false
                    "t" -> dentroT = false
                    "c" -> {
                        val v = valore.toString()
                        riga += if (tipoCella == "s") condivise.getOrNull(v.toIntOrNull() ?: -1) ?: "" else v
                    }
                    "row" -> if (riga.any { it.isNotBlank() }) out.append(riga.joinToString(" | ").trimEnd(' ', '|')).append('\n')
                }
                XmlPullParser.TEXT -> if (dentroV || dentroT) valore.append(p.text)
            }
        }
        return out.toString().trim().takeIf { it.lines().any { l -> !l.startsWith("---") && l.isNotBlank() } } ?: ""
    }

    /** PowerPoint .pptx: il testo di ogni diapositiva, in ordine. */
    fun estraiTestoPptx(dati: ByteArray): String {
        val diapositive = vociZip(dati, "ppt/slides/slide", ".xml").sortedBy { numeroFinale(it.first) }
        if (diapositive.isEmpty()) throw DocumentoNonLeggibile("File PowerPoint non valido (nessuna diapositiva)")
        val out = StringBuilder()
        diapositive.forEachIndexed { i, (_, xml) ->
            out.append("--- Diapositiva ${i + 1} ---\n")
            val p = parser(xml); var dentro = false
            while (true) when (p.next()) {
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.START_TAG -> if (p.name == "a:t") dentro = true
                XmlPullParser.END_TAG -> if (p.name == "a:t") dentro = false else if (p.name == "a:p") out.append('\n')
                XmlPullParser.TEXT -> if (dentro) out.append(p.text)
            }
        }
        val testo = out.toString().replace(Regex("\n{3,}"), "\n\n").trim()
        return if (testo.lines().all { it.startsWith("---") || it.isBlank() }) "" else testo
    }

    /** OpenDocument (.odt/.ods/.odp): testo di content.xml. */
    fun estraiTestoOpenDocument(dati: ByteArray): String {
        val xml = voceZip(dati, "content.xml") ?: throw DocumentoNonLeggibile("File OpenDocument non valido (manca il contenuto)")
        val p = parser(xml); val out = StringBuilder()
        while (true) when (p.next()) {
            XmlPullParser.END_DOCUMENT -> break
            XmlPullParser.START_TAG -> when (p.name) {
                "text:tab" -> out.append('\t')
                "text:line-break" -> out.append('\n')
                "text:s" -> out.append(' ')
            }
            XmlPullParser.END_TAG -> when (p.name) {
                "text:p", "text:h" -> out.append('\n')
                "table:table-cell" -> { if (out.endsWith("\n")) out.setLength(out.length - 1); out.append(" | ") }
                "table:table-row" -> out.append('\n')
            }
            XmlPullParser.TEXT -> out.append(p.text)
        }
        return out.toString().replace(Regex("(\\s*\\|\\s*)+\n"), "\n").replace(Regex("\n{3,}"), "\n\n").trim()
    }

    /** RTF: toglie comandi e gruppi di servizio, tiene testo, a capo e lettere accentate. */
    fun estraiTestoRtf(rtf: String): String {
        val out = StringBuilder()
        var i = 0; var livello = 0; var saltaFinoA = -1
        val cp = charset("windows-1252")
        while (i < rtf.length) {
            val c = rtf[i]
            if (saltaFinoA >= 0) { // dentro un gruppo da ignorare
                when (c) { '{' -> livello++; '}' -> { livello--; if (livello < saltaFinoA) saltaFinoA = -1 }; '\\' -> i++ }
                i++; continue
            }
            when (c) {
                '{' -> { livello++; if (rtf.startsWith("{\\*", i) || Regex("^\\{\\\\(fonttbl|colortbl|stylesheet|info|pict|header|footer)").containsMatchIn(rtf.substring(i, minOf(rtf.length, i + 14)))) saltaFinoA = livello }
                '}' -> livello--
                '\\' -> {
                    i++
                    if (i >= rtf.length) break
                    val n = rtf[i]
                    when {
                        n == '\'' && i + 2 < rtf.length -> { rtf.substring(i + 1, i + 3).toIntOrNull(16)?.let { out.append(String(byteArrayOf(it.toByte()), cp)) }; i += 2 }
                        n.isLetter() -> {
                            val inizio = i
                            while (i < rtf.length && rtf[i].isLetter()) i++
                            val parola = rtf.substring(inizio, i)
                            if (i < rtf.length && (rtf[i] == '-' || rtf[i].isDigit())) { i++; while (i < rtf.length && rtf[i].isDigit()) i++ }
                            if (i < rtf.length && rtf[i] == ' ') i++
                            when (parola) { "par", "line" -> out.append('\n'); "tab" -> out.append('\t'); "cell" -> out.append(" | "); "row" -> out.append('\n') }
                            continue
                        }
                        n == '\\' || n == '{' || n == '}' -> out.append(n)
                    }
                }
                '\r', '\n' -> {}
                else -> out.append(c)
            }
            i++
        }
        return out.toString().replace(Regex("\n{3,}"), "\n\n").trim()
    }

    /** HTML: testo visibile (senza tag, script e stili). */
    fun estraiTestoHtml(html: String): String {
        val pulito = html.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</tr>|</h\\d>|</li>"), "\n")
        return android.text.Html.fromHtml(pulito, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
            .replace(Regex("[ \t]+"), " ").replace(Regex("\n\\s*\n+"), "\n\n").trim()
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
