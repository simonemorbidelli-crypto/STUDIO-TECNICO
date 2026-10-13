package it.geometra.schedacliente

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Lettura di tutti i tipi di file (v3.79): estrazione del testo e suggerimenti per i file scartati. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FormatiFileTest {

    private fun zip(vararg voci: Pair<String, String>): ByteArray {
        val u = ByteArrayOutputStream()
        ZipOutputStream(u).use { z -> voci.forEach { (n, c) -> z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry() } }
        return u.toByteArray()
    }

    @Test
    fun excelXlsx() {
        val dati = zip(
            "xl/workbook.xml" to """<workbook><sheets><sheet name="Superfici" sheetId="1"/><sheet name="Note" sheetId="2"/></sheets></workbook>""",
            "xl/sharedStrings.xml" to """<sst><si><t>Vano</t></si><si><t>Soggiorno</t></si><si><r><t>Mq</t></r></si></sst>""",
            "xl/worksheets/sheet1.xml" to """<worksheet><sheetData><row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>2</v></c></row>
                <row r="2"><c r="A2" t="s"><v>1</v></c><c r="B2"><v>24.5</v></c></row><row r="3"><c r="A3"/></row></sheetData></worksheet>""",
            "xl/worksheets/sheet2.xml" to """<worksheet><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>ok</t></is></c></row></sheetData></worksheet>"""
        )
        val b = PreparatoreDocumenti.prepara(RuntimeEnvironment.getApplication(), "superfici.xlsx", dati) as PreparatoreDocumenti.Blocco.Testo
        assertEquals("--- Foglio: Superfici ---\nVano | Mq\nSoggiorno | 24.5\n--- Foglio: Note ---\nok", b.testo)
        assertEquals(PreparatoreDocumenti.Tipo.FOGLIO, b.origine)
    }

    @Test
    fun powerPointOpenDocumentRtfHtml() {
        val pptx = zip(
            "ppt/slides/slide2.xml" to """<p:sld><a:p><a:r><a:t>Seconda</a:t></a:r></a:p></p:sld>""",
            "ppt/slides/slide1.xml" to """<p:sld><a:p><a:r><a:t>Prima</a:t></a:r></a:p></p:sld>"""
        )
        assertEquals("--- Diapositiva 1 ---\nPrima\n--- Diapositiva 2 ---\nSeconda", PreparatoreDocumenti.estraiTestoPptx(pptx))

        val odt = zip("content.xml" to """<office:document-content><text:p>Titolo</text:p><text:p>Riga<text:tab/>due</text:p></office:document-content>""")
        assertEquals("Titolo\nRiga\tdue", PreparatoreDocumenti.estraiTestoOpenDocument(odt))

        val rtf = """{\rtf1\ansi{\fonttbl{\f0 Arial;}}{\*\generator Word;}\pard Perizia di stima\par Citt\'e0 di Arezzo \b grassetto\b0\par}"""
        assertEquals("Perizia di stima\nCittà di Arezzo grassetto", PreparatoreDocumenti.estraiTestoRtf(rtf).replace(Regex("\\s*\n\\s*"), "\n"))

        val html = PreparatoreDocumenti.estraiTestoHtml("<html><style>p{}</style><body><h1>Visura</h1><p>Foglio&nbsp;15</p><script>x()</script></body></html>")
        assertTrue(html, html.contains("Visura") && html.contains("Foglio") && !html.contains("x()") && !html.contains("p{}"))
    }

    @Test
    fun fileDanneggiatoDaUnMessaggioNonUnCrash() {
        try {
            PreparatoreDocumenti.prepara(RuntimeEnvironment.getApplication(), "rotto.xlsx", "non è uno zip".toByteArray())
            fail("atteso errore")
        } catch (e: PreparatoreDocumenti.DocumentoNonLeggibile) {
            assertTrue(e.message, e.message!!.contains("rotto.xlsx"))
        }
    }

    @Test
    fun formatiNonLeggibiliHannoUnSuggerimento() {
        for (n in listOf("a.doc", "b.xls", "c.ppt", "d.dwg", "e.tif", "f.zip", "g.p7m", "h.eml", "i.mp4", "l.xyzq")) {
            assertFalse(n, PreparatoreDocumenti.tipoDi(n).supportato.let { it && n != "l.xyzq" && n != "a.doc" && n != "b.xls" && n != "c.ppt" && n != "d.dwg" && n != "e.tif" && n != "f.zip" && n != "g.p7m" && n != "h.eml" && n != "i.mp4" })
            assertTrue(n, PreparatoreDocumenti.motivoNonSupportato(n).length > 25)
        }
        assertTrue(PreparatoreDocumenti.motivoNonSupportato("x.dwg").contains("PDF"))
        assertTrue(PreparatoreDocumenti.motivoNonSupportato("x.xls").contains(".xlsx"))
        assertTrue(PreparatoreDocumenti.motivoNonSupportato("x.qualcosa").contains("PDF"))
        for (n in listOf("a.xlsx", "a.pptx", "a.odt", "a.ods", "a.rtf", "a.html", "a.json", "a.pdf", "a.docx", "a.jpg")) assertTrue(n, PreparatoreDocumenti.tipoDi(n).supportato)
    }
}
