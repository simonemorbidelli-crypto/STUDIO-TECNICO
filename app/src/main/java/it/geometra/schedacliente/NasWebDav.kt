package it.geometra.schedacliente

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import android.util.Base64
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import javax.xml.parsers.DocumentBuilderFactory

/** Una voce (cartella o file) letta da un archivio di rete, qualunque sia il protocollo (SMB o WebDAV). */
internal class VoceArchivio(val nome: String, val cartella: Boolean, val dimensione: Long, val nascosto: Boolean)

/** Operazioni minime che servono alla lettura delle pratiche. I percorsi sono relativi alla condivisione, con "\" come separatore. */
internal interface ArchivioRete {
    fun elenca(percorso: String): List<VoceArchivio>
    fun cartellaEsiste(percorso: String): Boolean
    fun leggi(percorso: String): ByteArray
}

/** Il certificato del NAS non è quello di un'autorità riconosciuta: [impronta] (SHA-256) serve all'utente per fidarsi consapevolmente. */
class CertificatoNonFidato(val impronta: String) : CertificateException("Certificato del NAS non riconosciuto (impronta $impronta)")

/**
 * Collegamento WebDAV (HTTP o HTTPS) a una cartella condivisa del NAS (v3.85), es. Zyxel NAS326:
 * https://indirizzo:5003/webdav/NomeCondivisione. Solo lettura (PROPFIND e GET).
 *
 * Il NAS usa di norma un certificato proprio, non riconosciuto da Android: alla prima connessione l'app
 * mostra l'impronta del certificato e, se l'utente conferma, la ricorda ([Accesso.impronta]); da quel
 * momento accetta SOLO quel certificato (se cambia, il collegamento si blocca e avvisa).
 */
internal class ArchivioWebDav(private val accesso: NasStudio.Accesso) : ArchivioRete {
    private val p = accesso.percorso

    private val client: OkHttpClient by lazy {
        val b = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).callTimeout(180, TimeUnit.SECONDS)
            .followRedirects(false)
        if (p.sicuro) {
            val predefinito = predefinito()
            val tm = object : X509TrustManager {
                override fun checkClientTrusted(c: Array<X509Certificate>, a: String) = predefinito.checkClientTrusted(c, a)
                override fun getAcceptedIssuers(): Array<X509Certificate> = predefinito.acceptedIssuers
                override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {
                    val fp = impronta(c[0])
                    if (accesso.impronta.isNotEmpty()) {
                        if (!fp.equals(accesso.impronta, ignoreCase = true))
                            throw CertificateException("Il certificato del NAS è diverso da quello che avevi accettato (nuova impronta $fp)")
                        return
                    }
                    try { predefinito.checkServerTrusted(c, a) } catch (e: CertificateException) { throw CertificatoNonFidato(fp) }
                }
            }
            val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(tm), SecureRandom()) }
            b.sslSocketFactory(ctx.socketFactory, tm)
            // Con un certificato accettato dall'utente (impronta) il controllo è l'impronta stessa, non il nome dell'host.
            if (accesso.impronta.isNotEmpty()) b.hostnameVerifier { _, _ -> true }
        }
        b.build()
    }

    private fun predefinito(): X509TrustManager {
        val f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); f.init(null as java.security.KeyStore?)
        return f.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private fun url(percorso: String): String {
        val segmenti = (listOf("webdav", p.condivisione) + percorso.split('\\').filter { it.isNotEmpty() })
        val path = segmenti.joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        return (if (p.sicuro) "https://" else "http://") + p.host + ":" + p.porta + "/" + path
    }

    private val autorizzazione: String by lazy {
        "Basic " + Base64.encodeToString("${accesso.utente}:${accesso.password}".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    private fun richiesta(metodo: String, percorso: String, corpo: String? = null, profondita: String? = null): Request {
        val b = Request.Builder().url(url(percorso)).header("Authorization", autorizzazione)
        if (profondita != null) b.header("Depth", profondita)
        val rb = if (corpo != null) corpo.toRequestBody("application/xml; charset=utf-8".toMediaType()) else if (metodo == "GET") null else "".toRequestBody(null)
        return b.method(metodo, rb).build()
    }

    private fun controlla(codice: Int, percorso: String) {
        when (codice) {
            401 -> throw NasStudio.ErroreNas("Utente o password del NAS non corretti")
            403 -> throw NasStudio.ErroreNas("L'utente del NAS non ha il permesso di leggere questa cartella (o WebDAV non è attivo per lui)")
            404 -> throw NasStudio.ErroreNas("Cartella o file non trovati sul NAS (rinominati o spostati? il nome della condivisione è giusto?)", cartellaNonTrovata = true)
            in 300..399 -> throw NasStudio.ErroreNas("Il NAS ha risposto con un reindirizzamento (codice $codice): controlla indirizzo e porta (HTTPS = 5003, HTTP = 5002)")
        }
    }

    private val CORPO_PROPFIND = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/></d:prop></d:propfind>"""

    override fun elenca(percorso: String): List<VoceArchivio> {
        client.newCall(richiesta("PROPFIND", percorso, CORPO_PROPFIND, "1")).execute().use { r ->
            controlla(r.code, percorso)
            if (r.code != 207 && r.code != 200) throw NasStudio.ErroreNas("Il NAS ha risposto con un errore (codice ${r.code})")
            val xml = r.body?.bytes() ?: ByteArray(0)
            return interpretaElenco(xml, URI(url(percorso)).path)
        }
    }

    override fun cartellaEsiste(percorso: String): Boolean {
        client.newCall(richiesta("PROPFIND", percorso, CORPO_PROPFIND, "0")).execute().use { r ->
            if (r.code == 404) return false
            controlla(r.code, percorso)
            return true
        }
    }

    override fun leggi(percorso: String): ByteArray {
        client.newCall(richiesta("GET", percorso)).execute().use { r ->
            controlla(r.code, percorso)
            if (!r.isSuccessful) throw NasStudio.ErroreNas("Il NAS ha risposto con un errore (codice ${r.code})")
            return r.body?.bytes() ?: ByteArray(0)
        }
    }

    companion object {
        fun impronta(c: X509Certificate): String =
            MessageDigest.getInstance("SHA-256").digest(c.encoded).joinToString(":") { "%02X".format(it) }

        private fun decodifica(href: String): String {
            val h = href.trim()
            return try { URI(h).path } catch (e: Exception) { try { URI(h.replace(" ", "%20")).path } catch (e2: Exception) { h } } ?: h
        }

        /** Voci contenute nella cartella (esclusa la cartella stessa, [percorsoCartella] = percorso URL già codificato). */
        fun interpretaElenco(xml: ByteArray, percorsoCartella: String): List<VoceArchivio> {
            val f = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            val doc = f.newDocumentBuilder().parse(xml.inputStream())
            val risposte = doc.getElementsByTagNameNS("DAV:", "response")
            val self = decodifica(percorsoCartella).trimEnd('/')
            val out = ArrayList<VoceArchivio>()
            for (i in 0 until risposte.length) {
                val r = risposte.item(i) as org.w3c.dom.Element
                fun testo(nome: String) = r.getElementsByTagNameNS("DAV:", nome).item(0)?.textContent?.trim().orEmpty()
                val path = decodifica(testo("href")).trimEnd('/')
                if (path == self || path.isEmpty()) continue
                val eCartella = r.getElementsByTagNameNS("DAV:", "collection").length > 0
                val nome = path.substringAfterLast('/')
                if (nome.isEmpty()) continue
                out += VoceArchivio(nome, eCartella, testo("getcontentlength").toLongOrNull() ?: 0L, nascosto = false)
            }
            return out
        }
    }
}
