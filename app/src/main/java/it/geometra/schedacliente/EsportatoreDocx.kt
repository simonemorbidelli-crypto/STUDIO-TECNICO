package it.geometra.schedacliente

import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Crea un documento Word (.docx) modificabile dalla bozza generata dall'AI, senza librerie:
 * un .docx è un archivio zip di file XML (formato Office Open XML).
 *
 * - Lingua di correzione predefinita: italiano (it-IT), impostata negli stili del documento.
 * - A4, margini 2,5 cm, Calibri 11, testo giustificato, stili Word veri (Titolo, Titolo 1-3,
 *   elenchi puntati) così il documento si rifinisce comodamente in Word.
 * - In testa i dati della pratica dalla scheda cliente, in fondo lo spazio per luogo/data e
 *   firma del tecnico e una nota che ricorda che è una bozza da verificare.
 *
 * Marcatura in ingresso (quella chiesta all'AI): "# " titolo, "## "/"### " sezioni,
 * "- " o "* " elenchi, "**testo**" grassetto, righe vuote tra i paragrafi.
 */
object EsportatoreDocx {

    const val MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

    fun crea(
        destinazione: File,
        cliente: Cliente?,
        tipo: GeneratoreParereAi.TipoDocumento,
        testoMarcato: String,
        documentiUsati: List<String>,
        troncato: Boolean
    ) {
        val corpo = StringBuilder()
        val righe = testoMarcato.replace("\r\n", "\n").lines()

        // Titolo: la prima riga "# ..." dell'AI, altrimenti il tipo di documento.
        val indiceTitolo = righe.indexOfFirst { it.trim().startsWith("# ") }
        val titolo = if (indiceTitolo >= 0) righe[indiceTitolo].trim().removePrefix("# ").trim() else tipo.etichetta
        corpo.append(paragrafo(titolo, stile = "Title"))

        // Riquadro dati della pratica (dalla scheda cliente; da compilare se aperto senza scheda).
        val dati = if (cliente != null) datiPratica(cliente)
        else listOf("Committente" to "[DA COMPILARE]", "Immobile" to "[DA COMPILARE]", "Dati catastali" to "[DA COMPILARE]")
        dati.forEach { (etichetta, valore) ->
            corpo.append("<w:p><w:pPr><w:pStyle w:val=\"DatiPratica\"/></w:pPr>")
            corpo.append(run("$etichetta: ", grassetto = true)).append(runConGrassetto(valore)).append("</w:p>")
        }
        corpo.append(paragrafo("", stile = "Normal"))

        // Corpo del documento.
        val paragrafoInCorso = StringBuilder()
        fun chiudiParagrafo() {
            if (paragrafoInCorso.isNotBlank()) corpo.append(paragrafo(paragrafoInCorso.toString().trim(), stile = null))
            paragrafoInCorso.setLength(0)
        }
        righe.forEachIndexed { i, rigaOriginale ->
            if (i == indiceTitolo) return@forEachIndexed
            val riga = rigaOriginale.trim()
            when {
                riga.isEmpty() -> chiudiParagrafo()
                riga.startsWith("### ") -> { chiudiParagrafo(); corpo.append(paragrafo(riga.removePrefix("### "), "Heading3")) }
                riga.startsWith("## ") -> { chiudiParagrafo(); corpo.append(paragrafo(riga.removePrefix("## "), "Heading2")) }
                riga.startsWith("# ") -> { chiudiParagrafo(); corpo.append(paragrafo(riga.removePrefix("# "), "Heading1")) }
                riga.startsWith("- ") || riga.startsWith("* ") || riga.startsWith("• ") -> {
                    chiudiParagrafo(); corpo.append(paragrafo(riga.substring(2).trim(), "ListParagraph", elenco = true))
                }
                else -> { if (paragrafoInCorso.isNotEmpty()) paragrafoInCorso.append(' '); paragrafoInCorso.append(riga) }
            }
        }
        chiudiParagrafo()

        if (troncato) {
            corpo.append(paragrafo("[DA VERIFICARE: il testo generato si è interrotto perché troppo lungo: completare o rigenerare con meno documenti]", "Nota"))
        }

        // Luogo, data e firma.
        corpo.append(paragrafo("", stile = "Normal"))
        corpo.append(paragrafo("Luogo e data ____________________", stile = "Normal"))
        corpo.append(paragrafo("", stile = "Normal"))
        corpo.append(paragrafo("Il tecnico", stile = "Firma"))
        corpo.append(paragrafo("______________________________", stile = "Firma"))

        // Nota finale sulla natura del documento.
        val data = SimpleDateFormat("dd/MM/yyyy", Locale.ITALY).format(Date())
        corpo.append(paragrafo("", stile = "Normal"))
        corpo.append(paragrafo(
            "Bozza redatta il $data con l'ausilio di intelligenza artificiale sulla base dei documenti: " +
                documentiUsati.joinToString(", ") + ". Da verificare integralmente e completare a cura del tecnico prima dell'uso; " +
                "rimuovere questa nota nella versione definitiva.",
            stile = "Nota"
        ))

        val documento = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><w:body>$corpo<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1418" w:right="1418" w:bottom="1418" w:left="1418" w:header="709" w:footer="709" w:gutter="0"/></w:sectPr></w:body></w:document>"""

        destinazione.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(destinazione)).use { zip ->
            fun voce(nome: String, contenuto: String) {
                zip.putNextEntry(ZipEntry(nome))
                zip.write(contenuto.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            voce("[Content_Types].xml", CONTENT_TYPES)
            voce("_rels/.rels", RELS)
            voce("word/_rels/document.xml.rels", DOCUMENT_RELS)
            voce("word/document.xml", documento)
            voce("word/styles.xml", STILI)
            voce("word/numbering.xml", NUMERAZIONE)
            voce("word/settings.xml", IMPOSTAZIONI)
            voce("docProps/core.xml", proprieta(titolo))
        }
    }

    internal fun datiPratica(cliente: Cliente): List<Pair<String, String>> {
        val dati = mutableListOf("Committente" to cliente.nomeCompleto())
        val luogo = listOf(
            cliente.indirizzo,
            listOf(cliente.comune, cliente.provincia.takeIf { it.isNotBlank() }?.let { "($it)" })
                .filter { !it.isNullOrBlank() }.joinToString(" ")
        ).filter { it.isNotBlank() }.joinToString(", ")
        if (luogo.isNotBlank()) dati += "Immobile" to luogo
        val catasto = listOfNotNull(
            cliente.foglio.takeIf { it.isNotBlank() }?.let { "Foglio $it" },
            cliente.particella.takeIf { it.isNotBlank() }?.let { "Particella $it" },
            cliente.subalterno.takeIf { it.isNotBlank() }?.let { "Sub. $it" }
        )
        if (catasto.isNotEmpty()) dati += "Dati catastali" to catasto.joinToString(", ")
        val pratica = listOf(cliente.tipoPratica, cliente.praticaFormattata().takeIf { it.isNotBlank() }?.let { "n. $it" })
            .filter { !it.isNullOrBlank() }.joinToString(" ")
        if (pratica.isNotBlank()) dati += "Pratica" to pratica
        return dati
    }

    // ---------------------------------------------------------------------
    // XML
    // ---------------------------------------------------------------------

    private fun paragrafo(testo: String, stile: String?, elenco: Boolean = false): String {
        val proprieta = buildString {
            if (stile != null || elenco) {
                append("<w:pPr>")
                if (stile != null) append("<w:pStyle w:val=\"$stile\"/>")
                if (elenco) append("<w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"1\"/></w:numPr>")
                append("</w:pPr>")
            }
        }
        return "<w:p>$proprieta${runConGrassetto(testo)}</w:p>"
    }

    /** Divide il testo sui "**" e alterna testo normale e grassetto. */
    internal fun runConGrassetto(testo: String): String {
        if (testo.isEmpty()) return ""
        val parti = testo.split("**").toMutableList()
        // Numero dispari di "**" (parti pari): l'ultimo "**" è spaiato e resta come testo.
        if (parti.size % 2 == 0) {
            val ultima = parti.removeAt(parti.size - 1)
            parti[parti.size - 1] = parti[parti.size - 1] + "**" + ultima
        }
        return parti.mapIndexed { i, parte ->
            if (parte.isEmpty()) "" else run(parte, grassetto = i % 2 == 1)
        }.joinToString("")
    }

    private fun run(testo: String, grassetto: Boolean): String {
        val rPr = if (grassetto) "<w:rPr><w:b/></w:rPr>" else ""
        return "<w:r>$rPr<w:t xml:space=\"preserve\">${escape(testo)}</w:t></w:r>"
    }

    internal fun escape(testo: String): String = buildString {
        testo.forEach { c ->
            when {
                c == '&' -> append("&amp;")
                c == '<' -> append("&lt;")
                c == '>' -> append("&gt;")
                c == '"' -> append("&quot;")
                // Caratteri di controllo non ammessi in XML 1.0: scartati.
                c < ' ' && c != '\t' -> {}
                else -> append(c)
            }
        }
    }

    private fun proprieta(titolo: String): String {
        val adesso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(Date())
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"><dc:title>${escape(titolo)}</dc:title><dc:language>it-IT</dc:language><dc:creator>SchedaCliente</dc:creator><dcterms:created xsi:type="dcterms:W3CDTF">$adesso</dcterms:created></cp:coreProperties>"""
    }

    private const val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/><Override PartName="/word/numbering.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml"/><Override PartName="/word/settings.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml"/><Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/></Types>"""

    private const val RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/></Relationships>"""

    private const val DOCUMENT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering" Target="numbering.xml"/><Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/settings" Target="settings.xml"/></Relationships>"""

    private const val IMPOSTAZIONI = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:settings xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:defaultTabStop w:val="708"/><w:hyphenationZone w:val="283"/><w:characterSpacingControl w:val="doNotCompress"/><w:themeFontLang w:val="it-IT"/><w:decimalSymbol w:val=","/><w:listSeparator w:val=";"/></w:settings>"""

    /** Stili: lingua predefinita it-IT (correzione ortografica in italiano), Calibri 11, giustificato. */
    private const val STILI = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:eastAsia="Calibri" w:cs="Calibri"/><w:sz w:val="22"/><w:szCs w:val="22"/><w:lang w:val="it-IT" w:eastAsia="it-IT" w:bidi="ar-SA"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after="120" w:line="288" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults><w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:qFormat/><w:pPr><w:jc w:val="both"/></w:pPr></w:style><w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/><w:pPr><w:jc w:val="center"/><w:spacing w:after="240"/></w:pPr><w:rPr><w:b/><w:caps/><w:sz w:val="32"/><w:szCs w:val="32"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/><w:pPr><w:keepNext/><w:spacing w:before="240" w:after="120"/><w:jc w:val="left"/><w:outlineLvl w:val="0"/></w:pPr><w:rPr><w:b/><w:sz w:val="28"/><w:szCs w:val="28"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="Heading2"><w:name w:val="heading 2"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/><w:pPr><w:keepNext/><w:spacing w:before="240" w:after="80"/><w:jc w:val="left"/><w:outlineLvl w:val="1"/></w:pPr><w:rPr><w:b/><w:caps/><w:color w:val="2E5339"/><w:sz w:val="24"/><w:szCs w:val="24"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="Heading3"><w:name w:val="heading 3"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/><w:pPr><w:keepNext/><w:spacing w:before="160" w:after="60"/><w:jc w:val="left"/><w:outlineLvl w:val="2"/></w:pPr><w:rPr><w:b/><w:i/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="ListParagraph"><w:name w:val="List Paragraph"/><w:basedOn w:val="Normal"/><w:qFormat/><w:pPr><w:spacing w:after="60"/><w:ind w:left="720"/></w:pPr></w:style><w:style w:type="paragraph" w:customStyle="1" w:styleId="DatiPratica"><w:name w:val="Dati pratica"/><w:basedOn w:val="Normal"/><w:pPr><w:spacing w:after="40"/><w:jc w:val="left"/></w:pPr></w:style><w:style w:type="paragraph" w:customStyle="1" w:styleId="Firma"><w:name w:val="Firma"/><w:basedOn w:val="Normal"/><w:pPr><w:spacing w:after="0"/><w:ind w:left="5103"/><w:jc w:val="center"/></w:pPr></w:style><w:style w:type="paragraph" w:customStyle="1" w:styleId="Nota"><w:name w:val="Nota"/><w:basedOn w:val="Normal"/><w:rPr><w:i/><w:color w:val="7A7A7A"/><w:sz w:val="18"/><w:szCs w:val="18"/></w:rPr></w:style></w:styles>"""

    private const val NUMERAZIONE = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:numbering xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:abstractNum w:abstractNumId="0"><w:multiLevelType w:val="singleLevel"/><w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="bullet"/><w:lvlText w:val="•"/><w:lvlJc w:val="left"/><w:pPr><w:ind w:left="720" w:hanging="360"/></w:pPr></w:lvl></w:abstractNum><w:num w:numId="1"><w:abstractNumId w:val="0"/></w:num></w:numbering>"""
}
