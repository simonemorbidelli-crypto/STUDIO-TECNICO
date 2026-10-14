package it.geometra.schedacliente

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64

/** WebDAV del NAS (v3.85): server di prova (stile Zyxel NAS326) dentro il test, in HTTP e in HTTPS con certificato proprio. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NasWebDavTest {
    private lateinit var radice: File
    private var server: MockWebServer? = null

    @Before fun prepara() {
        radice = createTempDir("nas")
        File(radice, "Pratiche/Rossi Mario/Catasto").mkdirs()
        File(radice, "Pratiche/Rossi Mario/visura catastale.pdf").writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        File(radice, "Pratiche/Rossi Mario/Catasto/planimetria è.txt").writeText("ciao")
        File(radice, "Pratiche/Bianchi Anna").mkdirs()
        File(radice, "Pratiche/@eaDir").mkdirs()
        File(radice, "Pratiche/Rossi Mario/Thumbs.db").writeText("x")
    }
    @After fun chiudi() { server?.shutdown(); radice.deleteRecursively() }

    private fun avvia(https: Boolean): Int {
        val m = MockWebServer()
        if (https) {
            val cert = HeldCertificate.Builder().commonName("nas-di-prova").addSubjectAlternativeName("127.0.0.1").build()
            m.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(), false)
        }
        m.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = gestisci(request) }
        m.start(java.net.InetAddress.getByName("127.0.0.1"), 0); server = m
        return m.port
    }

    private fun gestisci(x: RecordedRequest): MockResponse {
        val auth = x.getHeader("Authorization")
        if (auth != "Basic " + Base64.getEncoder().encodeToString("studio:segreta".toByteArray())) return MockResponse().setResponseCode(401)
        val path = URLDecoder.decode(x.path!!.replace("+", "%2B"), "UTF-8")
        val f = File(radice, path.removePrefix("/webdav/"))
        if (!path.startsWith("/webdav/") || !f.exists()) return MockResponse().setResponseCode(404)
        return when (x.method) {
            "GET" -> MockResponse().setBody(Buffer().write(f.readBytes()))
            "PROPFIND" -> {
                val voci = if (x.getHeader("Depth") == "0" || !f.isDirectory) listOf(f) else listOf(f) + f.listFiles()!!.toList()
                val sb = StringBuilder("""<?xml version="1.0" encoding="utf-8"?><D:multistatus xmlns:D="DAV:">""")
                for (v in voci) {
                    val rel = "/webdav/" + v.relativeTo(radice).path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") } + if (v.isDirectory) "/" else ""
                    sb.append("<D:response><D:href>$rel</D:href><D:propstat><D:prop>")
                    sb.append(if (v.isDirectory) "<D:resourcetype><D:collection/></D:resourcetype>" else "<D:resourcetype/><D:getcontentlength>${v.length()}</D:getcontentlength>")
                    sb.append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>")
                }
                sb.append("</D:multistatus>")
                MockResponse().setResponseCode(207).setHeader("Content-Type", "application/xml").setBody(sb.toString())
            }
            else -> MockResponse().setResponseCode(405)
        }
    }

    private fun accesso(porta: Int, https: Boolean, cartella: String = "", password: String = "segreta", impronta: String = "") =
        NasStudio.Accesso(NasStudio.analizzaPercorso((if (https) "https" else "http") + "://127.0.0.1:$porta/webdav/Pratiche" + cartella)!!, "studio", password, impronta = impronta)

    @Test fun percorsoWebDav() {
        val p = NasStudio.analizzaPercorso("https://studio.ddns.net:5003/webdav/Pratiche/Clienti 2026/")!!
        assertEquals(NasStudio.Percorso("studio.ddns.net", 5003, "Pratiche", "Clienti 2026", webdav = true, sicuro = true), p)
        assertEquals("https://studio.ddns.net:5003/webdav/Pratiche/Clienti 2026", p.toString())
        assertEquals(5002, NasStudio.analizzaPercorso("http://192.168.1.20:5002/webdav/Doc")!!.porta)
        assertEquals(5003, NasStudio.analizzaPercorso("https://nas/webdav/Doc")!!.porta)
        assertNull("manca la condivisione", NasStudio.analizzaPercorso("https://nas:5003/webdav"))
        assertNull("manca /webdav/", NasStudio.analizzaPercorso("https://nas:5003/Pratiche/Altro"))
    }

    @Test fun elencaLeggeEScaricaInHttp() {
        val porta = avvia(false); val a = accesso(porta, false)
        assertEquals(listOf("Bianchi Anna", "Rossi Mario"), NasStudio.elencaCartelle(a))   // senza @eaDir
        val c = NasStudio.leggiContenuto(a, "Rossi Mario")
        assertEquals(listOf("Catasto"), c.cartelle.map { it.nome })
        assertEquals(listOf("visura catastale.pdf"), c.file.map { it.nome })           // senza Thumbs.db
        assertEquals(5L, c.file[0].dimensione)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), NasStudio.scarica(a, "Rossi Mario", c.file[0]))
        val sotto = NasStudio.leggiLivello(a, "Rossi Mario", "Catasto")
        assertEquals("planimetria è.txt", sotto.file[0].nome)
        assertEquals("ciao", String(NasStudio.scarica(a, "Rossi Mario", sotto.file[0])))
        assertEquals(2, NasStudio.prova(a))
    }

    @Test fun errori() {
        val porta = avvia(false)
        try { NasStudio.prova(accesso(porta, false, password = "sbagliata")); fail() } catch (e: NasStudio.ErroreNas) { assertTrue(e.message!!, e.message!!.contains("password")) }
        try { NasStudio.prova(accesso(porta, false, cartella = "/Inesistente")); fail() } catch (e: NasStudio.ErroreNas) { assertTrue(e.cartellaNonTrovata) }
        server!!.shutdown()
        try { NasStudio.prova(accesso(porta, false)); fail() } catch (e: NasStudio.ErroreNas) { assertTrue(e.message!!, e.message!!.contains("raggiungibile")) }
    }

    @Test fun httpsConCertificatoProprioChiedeConfermaEPoiLoRicorda() {
        val porta = avvia(true)
        val impronta = try { NasStudio.prova(accesso(porta, true)); fail("doveva chiedere conferma"); "" } catch (e: NasStudio.ErroreNas) {
            assertNotNull(e.message, e.improntaDaConfermare); e.improntaDaConfermare!!
        }
        assertTrue(impronta.matches(Regex("([0-9A-F]{2}:){31}[0-9A-F]{2}")))
        assertEquals(2, NasStudio.prova(accesso(porta, true, impronta = impronta)))
        // Un certificato diverso da quello accettato viene rifiutato (anche se l'utente aveva accettato un altro).
        val sbagliata = impronta.replaceRange(0, 2, if (impronta.startsWith("00")) "11" else "00")
        try { NasStudio.prova(accesso(porta, true, impronta = sbagliata)); fail() } catch (e: NasStudio.ErroreNas) {
            assertNull(e.improntaDaConfermare); assertTrue(e.message!!, e.message!!.contains("diverso"))
        }
    }
}
