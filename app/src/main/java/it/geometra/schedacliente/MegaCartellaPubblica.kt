package it.geometra.schedacliente

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Lettura di una cartella PUBBLICA di Mega (mega.nz) a partire dal suo link condiviso
 * ("Ottieni link"), senza account, login o SDK nativo.
 *
 * Come funziona: il link contiene l'identificativo della cartella e, dopo il "#", la sua chiave
 * (16 byte). Il server di Mega restituisce l'elenco dei file con nomi e chiavi CIFRATI: tutto
 * viene decifrato qui sul telefono con quella chiave (AES), e allo stesso modo il contenuto dei
 * file scaricati (AES-CTR). La chiave non viene mai inviata a Mega.
 *
 * Usa l'interfaccia pubblica del sito di Mega (g.api.mega.co.nz), non documentata ufficialmente
 * ma stabile da anni: se Mega la cambiasse, le chiamate fallirebbero con un errore esplicito.
 *
 * Tutte le funzioni di rete sono bloccanti: vanno chiamate su un thread in background.
 */
object MegaCartellaPubblica {

    private const val URL_API = "https://g.api.mega.co.nz/cs"

    /** Dimensione massima di un singolo file scaricabile (oltre non avrebbe senso per l'AI). */
    const val DIMENSIONE_MAX_FILE = 30L * 1024 * 1024

    class LinkCartella(val handle: String, val chiave: ByteArray)

    class FileMega(
        val handle: String,
        val nome: String,
        /** Percorso relativo alla cartella condivisa, es. "Catasto/visura.pdf". */
        val percorso: String,
        val dimensione: Long,
        internal val chiaveAes: ByteArray,
        internal val iv: ByteArray,
        /** Handle della cartella che contiene il file (la radice per i file al primo livello). */
        val genitore: String = ""
    ) {
        val estensione: String get() = nome.substringAfterLast('.', "").lowercase()
    }

    class CartellaMega(
        val handle: String,
        val nome: String,
        /** Handle della cartella superiore; per la radice è vuoto. */
        val genitore: String,
        val percorso: String
    )

    /** Contenuto completo di una cartella condivisa: radice, sottocartelle e file. */
    class Contenuto(val radice: CartellaMega, val cartelle: List<CartellaMega>, val file: List<FileMega>) {
        fun sottocartelleDi(handle: String) = cartelle.filter { it.genitore == handle }.sortedBy { it.nome.lowercase() }
        fun fileIn(handle: String) = file.filter { it.genitore == handle }.sortedBy { it.nome.lowercase() }
        fun cartella(handle: String): CartellaMega? = if (handle == radice.handle) radice else cartelle.firstOrNull { it.handle == handle }

        /** Numero di file contenuti nella cartella e in tutte le sue sottocartelle. */
        fun fileTotaliIn(handle: String): Int =
            fileIn(handle).size + sottocartelleDi(handle).sumOf { fileTotaliIn(it.handle) }
    }

    class ErroreMega(messaggio: String) : IOException(messaggio)

    private val RE_LINK_NUOVO = Regex("""mega(?:\.co)?\.nz/folder/([A-Za-z0-9_-]{8})#([A-Za-z0-9_-]{22})""")
    private val RE_LINK_VECCHIO = Regex("""mega(?:\.co)?\.nz/#F!([A-Za-z0-9_-]{8})!([A-Za-z0-9_-]{22})""")

    /** Riconosce un link pubblico di cartella Mega (formato nuovo o vecchio); null se non valido. */
    fun analizzaLink(link: String): LinkCartella? {
        val testo = link.trim()
        val corrispondenza = RE_LINK_NUOVO.find(testo) ?: RE_LINK_VECCHIO.find(testo) ?: return null
        val chiave = base64UrlDecodifica(corrispondenza.groupValues[2]) ?: return null
        if (chiave.size != 16) return null
        return LinkCartella(corrispondenza.groupValues[1], chiave)
    }

    /** Elenca tutti i file (anche nelle sottocartelle) della cartella condivisa. */
    fun elencaFile(link: String): List<FileMega> = leggiContenuto(link).file

    /** Legge cartelle e file della cartella condivisa, per la navigazione cartella per cartella. */
    fun leggiContenuto(link: String): Contenuto {
        val cartella = analizzaLink(link) ?: throw ErroreMega("Link della cartella Mega non valido")
        val richiesta = JSONArray().put(JSONObject().put("a", "f").put("c", 1).put("ca", 1).put("r", 1))
        val risposta = chiamaApi(cartella.handle, richiesta)
        val nodi = risposta.optJSONArray("f") ?: throw ErroreMega("Risposta di Mega senza elenco file")
        return costruisciContenuto(nodi, cartella.chiave)
    }

    /** Scarica e decifra un file della cartella. */
    fun scarica(link: String, file: FileMega): ByteArray {
        val cartella = analizzaLink(link) ?: throw ErroreMega("Link della cartella Mega non valido")
        if (file.dimensione > DIMENSIONE_MAX_FILE) {
            throw ErroreMega("\"${file.nome}\" è troppo grande (oltre ${DIMENSIONE_MAX_FILE / 1024 / 1024} MB)")
        }
        if (file.dimensione == 0L) return ByteArray(0)
        // "ssl": 2 chiede un indirizzo di download https (Android blocca i download in chiaro http).
        val richiesta = JSONArray().put(
            JSONObject().put("a", "g").put("g", 1).put("ssl", 2).put("n", file.handle)
        )
        val risposta = chiamaApi(cartella.handle, richiesta)
        var url = risposta.optString("g").takeIf { it.isNotBlank() }
            ?: throw ErroreMega("Mega non ha fornito l'indirizzo di download di \"${file.nome}\"")
        if (url.startsWith("http://")) url = "https://" + url.removePrefix("http://")

        val cifrato = scaricaByte(url)
        return decifraContenuto(cifrato, file.chiaveAes, file.iv)
    }

    // ---------------------------------------------------------------------
    // Decifratura (separata dalla rete per poterla verificare con i test)
    // ---------------------------------------------------------------------

    internal fun costruisciElenco(nodi: JSONArray, chiaveCartella: ByteArray): List<FileMega> =
        costruisciContenuto(nodi, chiaveCartella).file.sortedBy { it.percorso.lowercase() }

    internal fun costruisciContenuto(nodi: JSONArray, chiaveCartella: ByteArray): Contenuto {
        class Nodo(val handle: String, val genitore: String, val tipo: Int, val nome: String?,
                   val dimensione: Long, val chiaveAes: ByteArray?, val iv: ByteArray?)

        val tutti = LinkedHashMap<String, Nodo>()
        var nomiIllegibili = 0
        for (i in 0 until nodi.length()) {
            val o = nodi.getJSONObject(i)
            val tipo = o.optInt("t", -1)
            if (tipo != 0 && tipo != 1) continue // solo file (0) e cartelle (1)
            val handle = o.optString("h")
            var chiaveAes: ByteArray? = null
            var iv: ByteArray? = null
            var nome: String? = null
            // Un nodo può avere più chiavi alternative: come fa megajs, si tiene quella che
            // decifra correttamente gli attributi (il nome del file).
            for (chiaveNodo in decifraChiaviNodo(o.optString("k"), chiaveCartella)) {
                var candidataAes: ByteArray? = null
                var candidatoIv: ByteArray? = null
                if (tipo == 0 && chiaveNodo.size == 32) {
                    candidataAes = ByteArray(16) { (chiaveNodo[it].toInt() xor chiaveNodo[it + 16].toInt()).toByte() }
                    candidatoIv = chiaveNodo.copyOfRange(16, 24) + ByteArray(8)
                } else if (tipo == 1 && chiaveNodo.size >= 16) {
                    candidataAes = chiaveNodo.copyOfRange(0, 16)
                }
                val candidatoNome = candidataAes?.let { decifraAttributi(o.optString("a"), it)?.optString("n") }
                if (candidatoNome != null) {
                    chiaveAes = candidataAes; iv = candidatoIv; nome = candidatoNome
                    break
                }
            }
            if (nome == null) nomiIllegibili++
            tutti[handle] = Nodo(handle, o.optString("p"), tipo, nome, o.optLong("s", 0L), chiaveAes, iv)
        }
        if (tutti.isNotEmpty() && nomiIllegibili == tutti.size) {
            throw ErroreMega("Impossibile decifrare la cartella: la chiave nel link (dopo il #) è sbagliata o incompleta")
        }

        // La radice è la cartella il cui genitore non fa parte dell'elenco: il suo nome non entra nel percorso.
        fun percorsoDi(nodo: Nodo): String {
            val parti = ArrayList<String>()
            var corrente: Nodo? = tutti[nodo.genitore]
            var guardia = 0
            while (corrente != null && tutti.containsKey(corrente.genitore) && guardia++ < 50) {
                parti.add(0, corrente.nome ?: "?")
                corrente = tutti[corrente.genitore]
            }
            parti.add(nodo.nome ?: "?")
            return parti.joinToString("/")
        }

        val nodoRadice = tutti.values.firstOrNull { it.tipo == 1 && !tutti.containsKey(it.genitore) }
            ?: throw ErroreMega("Nella risposta di Mega manca la cartella principale")
        val radice = CartellaMega(nodoRadice.handle, nodoRadice.nome ?: "Cartella condivisa", "", "")
        val cartelle = tutti.values
            .filter { it.tipo == 1 && it !== nodoRadice && it.nome != null }
            .map { CartellaMega(it.handle, it.nome!!, it.genitore, percorsoDi(it)) }
        val file = tutti.values
            .filter { it.tipo == 0 && it.nome != null && it.chiaveAes != null && it.iv != null }
            .map { FileMega(it.handle, it.nome!!, percorsoDi(it), it.dimensione, it.chiaveAes!!, it.iv!!, it.genitore) }
        return Contenuto(radice, cartelle, file)
    }

    /**
     * Il campo "k" è "proprietario:chiaveCifrata", eventualmente più coppie separate da "/":
     * restituisce tutte le chiavi decifrabili (AES-ECB con la chiave della cartella).
     */
    internal fun decifraChiaviNodo(campoK: String, chiaveCartella: ByteArray): List<ByteArray> {
        if (campoK.isBlank()) return emptyList()
        return campoK.split('/').mapNotNull { coppia ->
            val cifrata = base64UrlDecodifica(coppia.substringAfterLast(':')) ?: return@mapNotNull null
            // Le chiavi più lunghe di 32 byte sono cifrate RSA (cartelle non pubbliche): non usabili qui.
            if (cifrata.isEmpty() || cifrata.size > 32 || cifrata.size % 16 != 0) return@mapNotNull null
            try {
                Cipher.getInstance("AES/ECB/NoPadding").run {
                    init(Cipher.DECRYPT_MODE, SecretKeySpec(chiaveCartella, "AES"))
                    doFinal(cifrata)
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    /** Attributi del nodo: AES-CBC con IV nullo, testo "MEGA{...json...}" seguito da zeri. */
    internal fun decifraAttributi(campoA: String, chiaveAes: ByteArray): JSONObject? {
        var dati = base64UrlDecodifica(campoA) ?: return null
        if (dati.isEmpty()) return null
        if (dati.size % 16 != 0) dati += ByteArray(16 - dati.size % 16)
        return try {
            val chiaro = Cipher.getInstance("AES/CBC/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(chiaveAes, "AES"), IvParameterSpec(ByteArray(16)))
                doFinal(dati)
            }
            val testo = String(chiaro, StandardCharsets.UTF_8).trimEnd('\u0000')
            if (!testo.startsWith("MEGA{")) return null
            JSONObject(testo.substring(4, testo.lastIndexOf('}') + 1))
        } catch (e: Exception) {
            null
        }
    }

    internal fun decifraContenuto(cifrato: ByteArray, chiaveAes: ByteArray, iv: ByteArray): ByteArray =
        Cipher.getInstance("AES/CTR/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(chiaveAes, "AES"), IvParameterSpec(iv))
            doFinal(cifrato)
        }

    internal fun base64UrlDecodifica(testo: String): ByteArray? = try {
        Base64.decode(testo.trim(), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    } catch (e: IllegalArgumentException) {
        null
    }

    // ---------------------------------------------------------------------
    // Rete
    // ---------------------------------------------------------------------

    private fun chiamaApi(handleCartella: String, richiesta: JSONArray): JSONObject {
        var tentativo = 0
        while (true) {
            val id = (Math.random() * 1_000_000_000).toLong()
            val connessione = (URL("$URL_API?id=$id&n=$handleCartella").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 20000
                readTimeout = 60000
            }
            try {
                connessione.outputStream.use { it.write(richiesta.toString().toByteArray(StandardCharsets.UTF_8)) }
                val codice = connessione.responseCode
                val corpo = (if (codice in 200..299) connessione.inputStream else connessione.errorStream)
                    ?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty().trim()
                if (codice !in 200..299) throw ErroreMega("Mega ha risposto con errore HTTP $codice")

                // Risposta: un numero (errore globale) oppure un array con un elemento per comando,
                // che a sua volta è un oggetto (successo) o un numero negativo (errore del comando).
                val elemento: Any = if (corpo.startsWith("[")) JSONArray(corpo).get(0) else corpo.toIntOrNull() ?: corpo
                when (elemento) {
                    is JSONObject -> return elemento
                    is Int -> {
                        if (elemento == -3 && tentativo++ < 3) { Thread.sleep(1500L * tentativo); continue }
                        throw ErroreMega(messaggioErrore(elemento))
                    }
                    else -> throw ErroreMega("Risposta di Mega non riconosciuta")
                }
            } finally {
                connessione.disconnect()
            }
        }
    }

    private fun messaggioErrore(codice: Int): String = when (codice) {
        -2 -> "Richiesta non valida per Mega (link non corretto?)"
        -3 -> "Mega è momentaneamente occupato: riprova tra poco"
        -9 -> "Cartella non trovata: il link potrebbe essere stato rimosso o non essere più condiviso"
        -11 -> "Accesso negato alla cartella Mega"
        -16 -> "Cartella bloccata da Mega"
        -17 -> "Superato il limite di traffico di Mega: riprova più tardi"
        -18 -> "Mega temporaneamente non disponibile: riprova tra poco"
        else -> "Errore da Mega (codice $codice)"
    }

    private fun scaricaByte(url: String): ByteArray {
        val connessione = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000
            readTimeout = 120000
        }
        try {
            if (connessione.responseCode !in 200..299) {
                throw ErroreMega("Download da Mega non riuscito (HTTP ${connessione.responseCode})")
            }
            val uscita = ByteArrayOutputStream()
            connessione.inputStream.use { ingresso ->
                val buffer = ByteArray(64 * 1024)
                var totale = 0L
                while (true) {
                    val letti = ingresso.read(buffer)
                    if (letti < 0) break
                    totale += letti
                    if (totale > DIMENSIONE_MAX_FILE) throw ErroreMega("File troppo grande")
                    uscita.write(buffer, 0, letti)
                }
            }
            return uscita.toByteArray()
        } finally {
            connessione.disconnect()
        }
    }
}
