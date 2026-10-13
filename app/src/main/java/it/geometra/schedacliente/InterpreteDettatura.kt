package it.geometra.schedacliente

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Invia il testo dettato dall'utente al servizio AI (Anthropic/Claude),
 * usando la chiave API personale salvata nelle Impostazioni, e ne estrae
 * i campi della scheda cliente in formato JSON.
 *
 * Non viene usata nessuna chiave dell'autore dell'app: ognuno paga solo il
 * proprio uso, con la propria chiave, salvata solo sul proprio telefono.
 */
object InterpreteDettatura {

    private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
    private const val MODELLO = "claude-haiku-5-5"

    private val CAMPI_TIPO_PRATICA = listOf(
        "Catasto (DOCFA/Tipo mappale)",
        "Urbanistica (CILA/SCIA/PdC)",
        "APE / SIERT",
        "IMU / Fiscale",
        "Perizia / Valutazione",
        "Altro"
    )

    interface Callback {
        fun onSuccesso(risultato: JSONObject)
        fun onErrore(messaggio: String)
    }

    fun interpreta(chiaveApi: String, testoDettato: String, callback: Callback) {
        val handler = Handler(Looper.getMainLooper())

        Thread {
            try {
                val risultato = eseguiChiamata(chiaveApi, testoDettato)
                handler.post { callback.onSuccesso(risultato) }
            } catch (e: Exception) {
                handler.post { callback.onErrore(e.message ?: "Errore sconosciuto") }
            }
        }.start()
    }

    private fun eseguiChiamata(chiaveApi: String, testoDettato: String): JSONObject {
        val promptSistema = """
            Sei un assistente che trascrive i dati dettati a voce da un geometra in una
            scheda cliente strutturata. Ricevi il testo dettato (italiano, dettatura libera,
            può contenere imprecisioni del riconoscimento vocale) e devi restituire SOLO un
            oggetto JSON valido, senza markdown, senza commenti, senza testo prima o dopo,
            con esattamente queste chiavi (stringa vuota "" se il dato non è presente):

            cognome, nome, codiceFiscale, partitaIva, telefono, email, indirizzo,
            provincia (nome provincia italiana, es. "Arezzo"), comune (comune italiano),
            tipoPratica (scegli il più adatto tra: ${CAMPI_TIPO_PRATICA.joinToString(" | ")}),
            numeroPratica, annoPratica (4 cifre), foglio, particella, subalterno, note.

            "cognome" è il cognome della persona o la ragione sociale se è un'azienda/ente.
            Se il dato non viene menzionato lascia il campo come stringa vuota, non inventare.
        """.trimIndent()

        val corpo = JSONObject().apply {
            put("model", MODELLO)
            put("max_tokens", 1024)
            put("system", promptSistema)
            put("messages", org.json.JSONArray().put(
                JSONObject().apply {
                    put("role", "user")
                    put("content", testoDettato)
                }
            ))
        }

        val url = URL(ENDPOINT)
        val connessione = url.openConnection() as HttpURLConnection
        if (connessione is HttpsURLConnection) {
            // nessuna configurazione particolare: usa il validatore TLS di sistema
        }
        connessione.requestMethod = "POST"
        connessione.setRequestProperty("content-type", "application/json")
        connessione.setRequestProperty("x-api-key", chiaveApi)
        connessione.setRequestProperty("anthropic-version", "2023-06-01")
        connessione.doOutput = true
        connessione.connectTimeout = 20000
        connessione.readTimeout = 30000

        OutputStreamWriter(connessione.outputStream, Charsets.UTF_8).use { scrittore ->
            scrittore.write(corpo.toString())
        }

        val codice = connessione.responseCode
        val stream = if (codice in 200..299) connessione.inputStream else connessione.errorStream
        val testoRisposta = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }

        if (codice !in 200..299) {
            val messaggioErrore = try {
                JSONObject(testoRisposta).optJSONObject("error")?.optString("message")
            } catch (e: Exception) {
                null
            } ?: "Il servizio AI ha risposto con un errore (codice $codice)"
            throw IllegalStateException(messaggioErrore)
        }

        val rispostaJson = JSONObject(testoRisposta)
        val contenuto = rispostaJson.optJSONArray("content")
            ?: throw IllegalStateException("Risposta AI senza contenuto")
        val testoModello = StringBuilder()
        for (i in 0 until contenuto.length()) {
            val blocco = contenuto.getJSONObject(i)
            if (blocco.optString("type") == "text") {
                testoModello.append(blocco.optString("text"))
            }
        }

        val testoPulito = estraiJson(testoModello.toString())
        return JSONObject(testoPulito)
    }

    /** Il modello a volte racchiude il JSON in un blocco ```json ... ```: lo ripulisce. */
    private fun estraiJson(testo: String): String {
        val inizio = testo.indexOf('{')
        val fine = testo.lastIndexOf('}')
        if (inizio == -1 || fine == -1 || fine < inizio) {
            throw IllegalStateException("Non sono riuscito a interpretare la risposta dell'AI")
        }
        return testo.substring(inizio, fine + 1)
    }
}
