package it.geometra.schedacliente

import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Perizia di stima completa in formato Word (.docx), impaginata in modo professionale (v3.82):
 * copertina con fascia colorata e scheda dell'oggetto, indice, intestazione e piè di pagina con
 * "Pagina X di Y", titoli numerati, tabelle con riga di intestazione colorata e righe alternate,
 * riquadro del valore di stima, spazio per luogo/data/timbro/firma del perito.
 *
 * Il testo narrativo arriva dall'AI con la marcatura di [GeneratoreParereAi] ("# " oggetto,
 * "## " sezioni, "### " sottosezioni, "- " elenchi, tabelle con "| a | b |", "**grassetto**").
 * Le tabelle della CONSISTENZA e della VALUTAZIONE non le scrive l'AI: l'AI mette nel punto giusto
 * le righe [[CONSISTENZA]] e [[VALUTAZIONE]] e qui vengono sostituite con i numeri esatti calcolati
 * dall'app ([DatiPerizia]); se mancano, le tabelle sono aggiunte prima delle conclusioni.
 *
 * I punti "[DA VERIFICARE: …]" e "[DA COMPILARE]" sono evidenziati in giallo, così il tecnico li
 * trova subito. Lingua di correzione predefinita: italiano (it-IT).
 */
object EsportatorePerizia {

    private const val LARGHEZZA = 9070 // twip: A4 (11906) meno i margini di 2,5 cm
    private const val VERDE = "2E5339"
    private const val VERDE_CHIARO = "E9F2EA"
    private const val GRIGIO_BORDO = "B8C4BB"

    // ---------------------------------------------------------------------
    // Blocchi del testo dell'AI
    // ---------------------------------------------------------------------

    internal sealed class Blocco {
        class Titolo1(val testo: String) : Blocco()
        class Titolo2(val testo: String) : Blocco()
        class Paragrafo(val testo: String) : Blocco()
        class Elenco(val testo: String) : Blocco()
        class Tabella(val righe: List<List<String>>) : Blocco()
        class Segnaposto(val nome: String) : Blocco()
    }

    /** Titolo (prima riga "# ") e blocchi del resto del testo marcato. */
    internal fun analizza(testoMarcato: String): Pair<String?, List<Blocco>> {
        val righe = testoMarcato.replace("\r\n", "\n").lines()
        var titolo: String? = null
        val blocchi = ArrayList<Blocco>()
        val paragrafo = StringBuilder()
        fun chiudi() { if (paragrafo.isNotBlank()) blocchi += Blocco.Paragrafo(paragrafo.toString().trim()); paragrafo.setLength(0) }
        var i = 0
        while (i < righe.size) {
            val riga = righe[i].trim()
            when {
                riga.isEmpty() -> chiudi()
                riga.startsWith("|") -> {
                    chiudi()
                    val celle = ArrayList<List<String>>()
                    while (i < righe.size && righe[i].trim().startsWith("|")) {
                        val cs = righe[i].trim().trim('|').split('|').map { it.trim() }
                        // La riga di separazione "|---|---|" non è un dato.
                        if (!cs.all { it.matches(Regex(":?-{2,}:?")) }) celle += cs
                        i++
                    }
                    if (celle.isNotEmpty()) blocchi += Blocco.Tabella(celle)
                    continue
                }
                riga.startsWith("### ") -> { chiudi(); blocchi += Blocco.Titolo2(riga.removePrefix("### ").trim()) }
                riga.startsWith("## ") -> { chiudi(); blocchi += Blocco.Titolo1(riga.removePrefix("## ").trim()) }
                riga.startsWith("# ") -> { chiudi(); if (titolo == null) titolo = riga.removePrefix("# ").trim() else blocchi += Blocco.Titolo1(riga.removePrefix("# ").trim()) }
                riga.matches(Regex("\\[\\[[A-Z]+]]")) -> { chiudi(); blocchi += Blocco.Segnaposto(riga.trim('[', ']')) }
                riga.startsWith("- ") || riga.startsWith("* ") || riga.startsWith("• ") -> { chiudi(); blocchi += Blocco.Elenco(riga.substring(2).trim()) }
                else -> { if (paragrafo.isNotEmpty()) paragrafo.append(' '); paragrafo.append(riga) }
            }
            i++
        }
        chiudi()
        return titolo to blocchi
    }

    // ---------------------------------------------------------------------
    // Creazione del documento
    // ---------------------------------------------------------------------

    fun crea(
        destinazioneFile: File,
        dati: DatiPerizia?,
        cliente: Cliente?,
        testoMarcato: String,
        documentiUsati: List<String>,
        troncato: Boolean,
        nonLetti: List<String> = emptyList()
    ) {
        val (titoloAi, blocchiAi) = analizza(testoMarcato)
        val oggetto = titoloAi ?: "Perizia di stima" + (dati?.destinazione?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: "")
        val blocchi = completaConTabelle(blocchiAi, dati)

        val corpo = StringBuilder()
        corpo.append(copertina(oggetto, dati, cliente))
        corpo.append(indice(blocchi))
        corpo.append(contenuto(blocchi, dati))
        if (troncato) corpo.append(p("[DA VERIFICARE: il testo generato si è interrotto perché troppo lungo: completare o rigenerare con meno documenti]", "Nota"))
        corpo.append(firma())
        corpo.append(notaFinale(documentiUsati, nonLetti))

        val documento = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><w:body>$corpo<w:sectPr><w:headerReference w:type="default" r:id="rId4"/><w:footerReference w:type="default" r:id="rId5"/><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1418" w:right="1418" w:bottom="1418" w:left="1418" w:header="709" w:footer="709" w:gutter="0"/><w:titlePg/></w:sectPr></w:body></w:document>"""

        destinazioneFile.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(destinazioneFile)).use { zip ->
            fun voce(nome: String, contenuto: String) {
                zip.putNextEntry(ZipEntry(nome)); zip.write(contenuto.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            }
            voce("[Content_Types].xml", CONTENT_TYPES)
            voce("_rels/.rels", EsportatoreDocx.RELS)
            voce("word/_rels/document.xml.rels", DOCUMENT_RELS)
            voce("word/document.xml", documento)
            voce("word/styles.xml", STILI)
            voce("word/numbering.xml", EsportatoreDocx.NUMERAZIONE)
            voce("word/settings.xml", EsportatoreDocx.IMPOSTAZIONI)
            voce("word/header1.xml", intestazione(oggetto))
            voce("word/footer1.xml", PIE_DI_PAGINA)
            voce("docProps/core.xml", EsportatoreDocx.proprieta(oggetto))
        }
    }

    /** Se l'AI non ha messo i segnaposto delle tabelle di consistenza/valutazione, si aggiungono prima delle conclusioni. */
    internal fun completaConTabelle(blocchi: List<Blocco>, dati: DatiPerizia?): List<Blocco> {
        if (dati == null) return blocchi
        val haConsistenza = blocchi.any { it is Blocco.Segnaposto && it.nome == "CONSISTENZA" }
        val haValutazione = blocchi.any { it is Blocco.Segnaposto && it.nome == "VALUTAZIONE" }
        val daAggiungere = ArrayList<Blocco>()
        if (!haConsistenza && dati.righe.isNotEmpty()) { daAggiungere += Blocco.Titolo1("Consistenza e superficie commerciale"); daAggiungere += Blocco.Segnaposto("CONSISTENZA") }
        if (!haValutazione && dati.valoreMercato != null) { daAggiungere += Blocco.Titolo1("Valutazione"); daAggiungere += Blocco.Segnaposto("VALUTAZIONE") }
        if (daAggiungere.isEmpty()) return blocchi
        val posizione = blocchi.indexOfLast { it is Blocco.Titolo1 && Regex("conclusion|giudizio", RegexOption.IGNORE_CASE).containsMatchIn(it.testo) }
        return if (posizione >= 0) blocchi.subList(0, posizione) + daAggiungere + blocchi.subList(posizione, blocchi.size)
        else blocchi + daAggiungere
    }

    // ---------------------------------------------------------------------
    // Copertina, indice, contenuto, firma
    // ---------------------------------------------------------------------

    private fun copertina(oggetto: String, dati: DatiPerizia?, cliente: Cliente?): String = buildString {
        append(p("", "Normal"))
        append(p("", "Normal"))
        // Fascia colorata con il titolo.
        append(tabellaRaw(listOf(
            riga(listOf(cella(
                p("PERIZIA DI STIMA", "CopertinaTitolo") + p("Valutazione del valore di mercato", "CopertinaSottotitolo"),
                LARGHEZZA, riempimento = VERDE
            )))
        ), bordi = false, griglia = listOf(LARGHEZZA)))
        append(p("", "Normal"))
        append(p(oggetto, "CopertinaOggetto"))
        append(p("", "Normal"))

        val dc = cliente?.let { EsportatoreDocx.datiPratica(it) }.orEmpty().toMap()
        val scheda = ArrayList<Pair<String, String>>()
        scheda += "Committente" to (dc["Committente"] ?: "[DA COMPILARE]")
        scheda += "Immobile" to (dc["Immobile"] ?: "[DA COMPILARE]")
        scheda += "Dati catastali" to (dc["Dati catastali"] ?: "[DA COMPILARE]")
        dati?.destinazione?.takeIf { it.isNotBlank() }?.let { scheda += "Destinazione d'uso" to it }
        dati?.takeIf { it.superficieArrotondata > 0 }?.let { scheda += "Superficie commerciale" to "${it.superficieArrotondata} mq" }
        dati?.valoreMercato?.let { scheda += "Valore di mercato stimato" to DatiPerizia.euro(it) }
        scheda += "Data della perizia" to SimpleDateFormat("dd/MM/yyyy", Locale.ITALY).format(Date())
        append(tabellaRaw(scheda.mapIndexed { i, (k, v) ->
            riga(listOf(
                cella(p(k, "EtichettaScheda"), 2600, riempimento = VERDE_CHIARO),
                cella(pRuns(v, "ValoreScheda"), LARGHEZZA - 2600, riempimento = if (i % 2 == 0) "FFFFFF" else "F6F9F6")
            ))
        }, bordi = true, griglia = listOf(2600, LARGHEZZA - 2600)))
        append(p("", "Normal"))
        append(p("", "Normal"))
        append(p("Perito estimatore", "EtichettaScheda"))
        append(p("[DA COMPILARE: nome, titolo, iscrizione all'albo]", "ValoreScheda"))
        append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
    }

    private fun indice(blocchi: List<Blocco>): String {
        val titoli = blocchi.filterIsInstance<Blocco.Titolo1>()
        if (titoli.size < 2) return ""
        return buildString {
            append(p("Indice", "IndiceTitolo"))
            titoli.forEachIndexed { i, t -> append(p("${i + 1}.  ${t.testo}", "IndiceVoce")) }
            append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
        }
    }

    private fun contenuto(blocchi: List<Blocco>, dati: DatiPerizia?): String = buildString {
        var n1 = 0; var n2 = 0
        blocchi.forEach { b ->
            when (b) {
                is Blocco.Titolo1 -> { n1++; n2 = 0; append(p("$n1.  ${b.testo}", "Heading1")) }
                is Blocco.Titolo2 -> { n2++; append(p("$n1.$n2  ${b.testo}", "Heading2")) }
                is Blocco.Paragrafo -> append(pRuns(b.testo, null))
                is Blocco.Elenco -> append(pRuns(b.testo, "ListParagraph", elenco = true))
                is Blocco.Tabella -> append(tabellaDati(b.righe))
                is Blocco.Segnaposto -> when (b.nome) {
                    "CONSISTENZA" -> append(tabellaConsistenza(dati))
                    "VALUTAZIONE" -> append(tabellaValutazione(dati))
                    else -> {}
                }
            }
        }
    }

    private fun firma(): String = buildString {
        append(p("", "Normal"))
        append(tabellaRaw(listOf(riga(listOf(
            cella(p("Luogo e data", "EtichettaScheda") + p("______________________", "ValoreScheda"), LARGHEZZA / 2),
            cella(p("Il perito estimatore", "FirmaCentro") + p("(timbro e firma)", "FirmaCentro") + p("______________________", "FirmaCentro"), LARGHEZZA / 2)
        ))), bordi = false, griglia = listOf(LARGHEZZA / 2, LARGHEZZA / 2)))
    }

    private fun notaFinale(documentiUsati: List<String>, nonLetti: List<String>): String {
        val data = SimpleDateFormat("dd/MM/yyyy", Locale.ITALY).format(Date())
        return p("", "Normal") + p(
            "Bozza redatta il $data con l'ausilio di intelligenza artificiale sulla base dei documenti: " +
                (if (documentiUsati.isEmpty()) "nessuno (solo i dati inseriti)" else documentiUsati.joinToString(", ")) +
                ". Da verificare integralmente e completare a cura del perito prima dell'uso; rimuovere questa nota nella versione definitiva." +
                (if (nonLetti.isNotEmpty()) " File scelti ma NON esaminati (non leggibili dall'AI): " + nonLetti.joinToString(", ") + "." else ""),
            "Nota"
        )
    }

    // ---------------------------------------------------------------------
    // Tabelle
    // ---------------------------------------------------------------------

    /** Tabella scritta dall'AI: prima riga = intestazione; larghezze in proporzione al contenuto. */
    private fun tabellaDati(righe: List<List<String>>): String {
        val colonne = righe.maxOf { it.size }
        val pesi = (0 until colonne).map { c -> righe.maxOf { (it.getOrNull(c) ?: "").length }.coerceIn(8, 45) }
        val larghezze = proporzioni(pesi)
        return tabellaConIntestazione(righe.map { r -> (0 until colonne).map { r.getOrNull(it) ?: "" } }, larghezze, totale = false) + p("", "Spazio")
    }

    private fun tabellaConsistenza(dati: DatiPerizia?): String {
        if (dati == null || dati.righe.isEmpty()) return p("[DA COMPILARE: consistenza dell'unità]", null)
        val righe = ArrayList<List<String>>()
        righe += listOf("Locale", "Categoria", "Sup. lorda (mq)", "Criterio di ragguaglio", "Sup. ragguagliata (mq)")
        dati.righe.forEach { righe += listOf(it.nome, it.categoria, PeriziaCalcolo.fmt(it.mqLordi), it.regola, PeriziaCalcolo.fmt(it.mqRagguagliati)) }
        righe += listOf("Superficie commerciale", "", "", "", "${PeriziaCalcolo.fmt(dati.superficieCommerciale)} → ${dati.superficieArrotondata} mq")
        val t = tabellaConIntestazione(righe, proporzioni(listOf(20, 24, 12, 26, 14)), totale = true, destra = setOf(2, 4))
        val note = dati.note.joinToString("") { p("Nota: $it", "Nota") }
        return t + note + p("", "Spazio")
    }

    private fun tabellaValutazione(dati: DatiPerizia?): String {
        if (dati == null || dati.valoreMercato == null) return p("[DA COMPILARE: valutazione]", null)
        val righe = ArrayList<List<String>>()
        righe += listOf("Elemento", "Valore")
        righe += listOf("Superficie commerciale adottata", "${dati.superficieArrotondata} mq")
        if (dati.omiDescrizione.isNotBlank()) {
            righe += listOf("Quotazione OMI di riferimento", dati.omiDescrizione + (if (dati.omiTipologia.isNotBlank()) " — ${dati.omiTipologia}" else ""))
            if (dati.omiMinimo != null && dati.omiMassimo != null)
                righe += listOf("Intervallo OMI (€/mq)", "${PeriziaCalcolo.fmt(dati.omiMinimo)} – ${PeriziaCalcolo.fmt(dati.omiMassimo)}")
        }
        dati.valoreUnitario?.let { righe += listOf("Valore unitario adottato", "${PeriziaCalcolo.fmt(it)} €/mq") }
        dati.correttivi.filter { it.percentuale != 0.0 }.forEach {
            righe += listOf("Correttivo: ${it.descrizione.ifBlank { "—" }}", (if (it.percentuale > 0) "+" else "") + PeriziaCalcolo.fmt(it.percentuale) + "%")
        }
        righe += listOf("Valore di mercato", DatiPerizia.euro(dati.valoreMercato))
        dati.valoreArrotondato?.let { righe += listOf("Valore di mercato arrotondato", DatiPerizia.euro(it)) }
        return tabellaConIntestazione(righe, listOf(4200, LARGHEZZA - 4200), totale = true, destra = setOf(1), ultimeEvidenziate = if (dati.valoreArrotondato != null) 2 else 1) + p("", "Spazio")
    }

    private fun proporzioni(pesi: List<Int>): List<Int> {
        val somma = pesi.sum().toDouble()
        val w = pesi.map { maxOf(900, (it / somma * LARGHEZZA).toInt()) }.toMutableList()
        // Ricalcola per far tornare il totale a LARGHEZZA.
        val eccesso = w.sum() - LARGHEZZA
        w[w.indices.maxByOrNull { w[it] }!!] -= eccesso
        return w
    }

    private val NUMERICO = Regex("^[€\\d.,\\s%+\\-−→]+(mq|m²|€/mq)?$")

    /** @param totale l'ultima riga è un totale (grassetto, riquadro colorato); @param ultimeEvidenziate quante righe finali evidenziare. */
    private fun tabellaConIntestazione(
        righe: List<List<String>>, larghezze: List<Int>, totale: Boolean, destra: Set<Int> = emptySet(), ultimeEvidenziate: Int = 1
    ): String {
        val colonne = larghezze.size
        val xml = righe.mapIndexed { r, celleRiga ->
            val intestazione = r == 0
            val evidenziata = totale && r >= righe.size - ultimeEvidenziate
            val sfondo = when {
                intestazione -> VERDE
                evidenziata -> "D5E6D8"
                r % 2 == 0 -> "F4F8F4"
                else -> "FFFFFF"
            }
            riga(
                (0 until colonne).map { c ->
                    val testo = celleRiga.getOrElse(c) { "" }
                    val allineaDestra = !intestazione && (c in destra || (destra.isEmpty() && testo.isNotEmpty() && NUMERICO.matches(testo)))
                    cella(
                        pCella(testo, intestazione, evidenziata, allineaDestra || (intestazione && c in destra)),
                        larghezze[c], riempimento = sfondo
                    )
                },
                intestazioneRipetuta = intestazione
            )
        }
        return tabellaRaw(xml, bordi = true, griglia = larghezze)
    }

    private fun tabellaRaw(righe: List<String>, bordi: Boolean, griglia: List<Int>): String {
        val bordo = if (bordi) "<w:tblBorders><w:top w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"$GRIGIO_BORDO\"/><w:left w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"$GRIGIO_BORDO\"/><w:bottom w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"$GRIGIO_BORDO\"/><w:right w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"$GRIGIO_BORDO\"/><w:insideH w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"$GRIGIO_BORDO\"/><w:insideV w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"$GRIGIO_BORDO\"/></w:tblBorders>" else ""
        return "<w:tbl><w:tblPr><w:tblW w:w=\"$LARGHEZZA\" w:type=\"dxa\"/>$bordo<w:tblLayout w:type=\"fixed\"/><w:tblCellMar><w:top w:w=\"60\" w:type=\"dxa\"/><w:left w:w=\"100\" w:type=\"dxa\"/><w:bottom w:w=\"60\" w:type=\"dxa\"/><w:right w:w=\"100\" w:type=\"dxa\"/></w:tblCellMar></w:tblPr><w:tblGrid>${griglia.joinToString("") { "<w:gridCol w:w=\"$it\"/>" }}</w:tblGrid>${righe.joinToString("")}</w:tbl>"
    }

    private fun riga(celle: List<String>, intestazioneRipetuta: Boolean = false): String =
        "<w:tr><w:trPr><w:cantSplit/>${if (intestazioneRipetuta) "<w:tblHeader/>" else ""}</w:trPr>${celle.joinToString("")}</w:tr>"

    private fun cella(contenutoXml: String, larghezza: Int, riempimento: String? = null): String =
        "<w:tc><w:tcPr><w:tcW w:w=\"$larghezza\" w:type=\"dxa\"/>" +
            (if (riempimento != null) "<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"$riempimento\"/>" else "") +
            "<w:vAlign w:val=\"center\"/></w:tcPr>$contenutoXml</w:tc>"

    private fun pCella(testo: String, intestazione: Boolean, grassetto: Boolean, destra: Boolean): String {
        val jc = if (destra) "<w:jc w:val=\"right\"/>" else "<w:jc w:val=\"left\"/>"
        val colore = if (intestazione) "<w:color w:val=\"FFFFFF\"/>" else ""
        val b = if (intestazione || grassetto) "<w:b/>" else ""
        val corpo = runsEvidenziati(testo, b + colore)
        return "<w:p><w:pPr><w:pStyle w:val=\"TestoTabella\"/>$jc</w:pPr>$corpo</w:p>"
    }

    // ---------------------------------------------------------------------
    // Paragrafi e testo
    // ---------------------------------------------------------------------

    private fun p(testo: String, stile: String?): String = EsportatoreDocx.paragrafo(testo, stile)

    /** Paragrafo con grassetto "**" e punti "[DA VERIFICARE…]" evidenziati. */
    private fun pRuns(testo: String, stile: String?, elenco: Boolean = false): String {
        val pPr = buildString {
            if (stile != null || elenco) {
                append("<w:pPr>")
                if (stile != null) append("<w:pStyle w:val=\"$stile\"/>")
                if (elenco) append("<w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"1\"/></w:numPr>")
                append("</w:pPr>")
            }
        }
        return "<w:p>$pPr${runsEvidenziati(testo, "")}</w:p>"
    }

    private val DA_VERIFICARE = Regex("\\[DA (?:VERIFICARE|COMPILARE)[^\\]]*]")

    /** Run con grassetto ("**") e sfondo giallo sui segnaposto da verificare/compilare; [proprieta] = proprietà extra di ogni run. */
    internal fun runsEvidenziati(testo: String, proprieta: String): String {
        if (testo.isEmpty()) return ""
        val out = StringBuilder()
        var ultimo = 0
        fun parte(t: String, evidenzia: Boolean) {
            if (t.isEmpty()) return
            val pezzi = t.split("**").toMutableList()
            if (pezzi.size % 2 == 0) { val u = pezzi.removeAt(pezzi.size - 1); pezzi[pezzi.size - 1] = pezzi[pezzi.size - 1] + "**" + u }
            pezzi.forEachIndexed { i, s ->
                if (s.isEmpty()) return@forEachIndexed
                val grassetto = if (i % 2 == 1 && !proprieta.contains("<w:b/>")) "<w:b/>" else ""
                val ev = if (evidenzia) "<w:highlight w:val=\"yellow\"/>" else ""
                out.append("<w:r><w:rPr>$proprieta$grassetto$ev</w:rPr><w:t xml:space=\"preserve\">${EsportatoreDocx.escape(s)}</w:t></w:r>")
            }
        }
        DA_VERIFICARE.findAll(testo).forEach { m ->
            parte(testo.substring(ultimo, m.range.first), false)
            parte(m.value, true)
            ultimo = m.range.last + 1
        }
        parte(testo.substring(ultimo), false)
        return out.toString()
    }

    // ---------------------------------------------------------------------
    // Parti fisse del pacchetto
    // ---------------------------------------------------------------------

    private fun intestazione(oggetto: String): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:hdr xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:p><w:pPr><w:pStyle w:val="Intestazione"/></w:pPr><w:r><w:t xml:space="preserve">${EsportatoreDocx.escape((if (oggetto.startsWith("Perizia", true)) oggetto else "Perizia di stima — $oggetto").take(100))}</w:t></w:r></w:p></w:hdr>"""

    private const val PIE_DI_PAGINA = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:ftr xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:p><w:pPr><w:pStyle w:val="PiePagina"/></w:pPr><w:r><w:t xml:space="preserve">Pagina </w:t></w:r><w:r><w:fldChar w:fldCharType="begin"/></w:r><w:r><w:instrText xml:space="preserve"> PAGE </w:instrText></w:r><w:r><w:fldChar w:fldCharType="separate"/></w:r><w:r><w:t>1</w:t></w:r><w:r><w:fldChar w:fldCharType="end"/></w:r><w:r><w:t xml:space="preserve"> di </w:t></w:r><w:r><w:fldChar w:fldCharType="begin"/></w:r><w:r><w:instrText xml:space="preserve"> NUMPAGES </w:instrText></w:r><w:r><w:fldChar w:fldCharType="separate"/></w:r><w:r><w:t>1</w:t></w:r><w:r><w:fldChar w:fldCharType="end"/></w:r></w:p></w:ftr>"""

    private const val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/><Override PartName="/word/numbering.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml"/><Override PartName="/word/settings.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml"/><Override PartName="/word/header1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml"/><Override PartName="/word/footer1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml"/><Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/></Types>"""

    private const val DOCUMENT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering" Target="numbering.xml"/><Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/settings" Target="settings.xml"/><Relationship Id="rId4" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/header" Target="header1.xml"/><Relationship Id="rId5" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/footer" Target="footer1.xml"/></Relationships>"""

    /** Stili: it-IT, Calibri 10,5, titoli verdi numerati, tabelle compatte, copertina. */
    private const val STILI = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:eastAsia="Calibri" w:cs="Calibri"/><w:sz w:val="21"/><w:szCs w:val="21"/><w:lang w:val="it-IT" w:eastAsia="it-IT" w:bidi="ar-SA"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after="120" w:line="276" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults><w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:qFormat/><w:pPr><w:jc w:val="both"/></w:pPr></w:style><w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/><w:pPr><w:keepNext/><w:pBdr><w:bottom w:val="single" w:sz="8" w:space="2" w:color="2E5339"/></w:pBdr><w:spacing w:before="360" w:after="140"/><w:jc w:val="left"/><w:outlineLvl w:val="0"/></w:pPr><w:rPr><w:b/><w:color w:val="2E5339"/><w:sz w:val="28"/><w:szCs w:val="28"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="Heading2"><w:name w:val="heading 2"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/><w:pPr><w:keepNext/><w:spacing w:before="220" w:after="80"/><w:jc w:val="left"/><w:outlineLvl w:val="1"/></w:pPr><w:rPr><w:b/><w:color w:val="1E2A22"/><w:sz w:val="24"/><w:szCs w:val="24"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="ListParagraph"><w:name w:val="List Paragraph"/><w:basedOn w:val="Normal"/><w:qFormat/><w:pPr><w:spacing w:after="60"/><w:ind w:left="720"/></w:pPr></w:style><w:style w:type="paragraph" w:customStyle="1" w:styleId="Nota"><w:name w:val="Nota"/><w:basedOn w:val="Normal"/><w:pPr><w:spacing w:after="60"/></w:pPr><w:rPr><w:i/><w:color w:val="6B6B6B"/><w:sz w:val="18"/><w:szCs w:val="18"/></w:rPr></w:style><w:style w:type="paragraph" w:customStyle="1" w:styleId="Spazio"><w:name w:val="Spazio"/><w:basedOn w:val="Normal"/><w:pPr><w:spacing w:after="60" w:line="120" w:lineRule="exact"/></w:pPr></w:style><w:style w:type="paragraph" w:customStyle="1" w:styleId="TestoTabella"><w:name w:val="Testo tabella"/><w:basedOn w:val="Normal"/><w:pPr><w:spacing w:before="0" w:after="0" w:line="252" w:lineRule="auto"/><w:jc w:val="left"/></w:pPr><w:rPr><w:sz w:val="19"/><w:szCs w:val="19"/></w:rPr></w:style><w:style w:type="paragraph" w:customStyle="1" w:styleId="CopertinaTitolo"><w:name w:val="Copertina titolo"/><w:basedOn w:val="Normal"/><w:pPr><w:spacing w:before="480" w:after="120"/><w:jc w:val="center"/></w:pPr><w:rPr><w:b/><w:color w:val="FFFFFF"/><w:spacing w:val="40"/><w:sz w:val="56"/><w:szCs w:val="56"/></w:rPr></w:style><w:style w:type="paragraph" w:customStyle="1" w:styleId="CopertinaSottotitolo"><w:name w:val="Copertina sottotitolo"/><w:basedOn w:val="Normal"/><w:pPr><w:spacing w:before="0" w:after="480"/><w:jc w:val="center"/></w:pPr><w:rPr><w:color w:val="DCE9DE"/><w:sz w:val="28"/><w:szCs w:val="28"/></w:rPr></w:style><w:style w:type="paragraph" w:customStyle="1" w:styleId="CopertinaOggetto"><w:name w:val="Copertina oggetto"/><w:basedOn w:val="Normal"/><w:pPr><w:spacing w:before="120" w:after="120"/><w:jc w:val="center"/></w:pPr><w:rPr><w:b/><w:color w:val="1E2A22"/><w:sz w:val="32"/><w:szCs w:val="32"/></w:rPr></w:style><w:style w:type="paragraph" w:customStyle="1" w:styleId="EtichettaScheda"><w:name w:val="Etichetta scheda"/><w:basedOn w:val="Normal"/><w:pPr><w:spacing w:before="40" w:after="40"/><w:jc w:val="left"/></w:pPr><w:rPr><w:b/><w:color w:val="2E5339"/></w:rPr></w:style><w:style w:type="paragraph" w:customStyle="1" w:styleId="ValoreScheda"><w:name w:val="Valore scheda"/><w:basedOn w:val="Normal"/><w:pPr><w:spacing w:before="40" w:after="40"/><w:jc w:val="left"/></w:pPr></w:style><w:style w:type="paragraph" w:customStyle="1" w:styleId="FirmaCentro"><w:name w:val="Firma"/><w:basedOn w:val="Normal"/><w:pPr><w:spacing w:before="0" w:after="60"/><w:jc w:val="center"/></w:pPr></w:style><w:style w:type="paragraph" w:customStyle="1" w:styleId="IndiceTitolo"><w:name w:val="Indice titolo"/><w:basedOn w:val="Normal"/><w:pPr><w:spacing w:before="0" w:after="200"/><w:jc w:val="left"/></w:pPr><w:rPr><w:b/><w:color w:val="2E5339"/><w:sz w:val="32"/><w:szCs w:val="32"/></w:rPr></w:style><w:style w:type="paragraph" w:customStyle="1" w:styleId="IndiceVoce"><w:name w:val="Indice voce"/><w:basedOn w:val="Normal"/><w:pPr><w:spacing w:after="80"/><w:ind w:left="284"/><w:jc w:val="left"/></w:pPr></w:style><w:style w:type="paragraph" w:styleId="Intestazione"><w:name w:val="header"/><w:basedOn w:val="Normal"/><w:pPr><w:pBdr><w:bottom w:val="single" w:sz="4" w:space="3" w:color="B8C4BB"/></w:pBdr><w:jc w:val="right"/></w:pPr><w:rPr><w:color w:val="6B6B6B"/><w:sz w:val="17"/><w:szCs w:val="17"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="PiePagina"><w:name w:val="footer"/><w:basedOn w:val="Normal"/><w:pPr><w:jc w:val="center"/></w:pPr><w:rPr><w:color w:val="6B6B6B"/><w:sz w:val="17"/><w:szCs w:val="17"/></w:rPr></w:style></w:styles>"""
}
