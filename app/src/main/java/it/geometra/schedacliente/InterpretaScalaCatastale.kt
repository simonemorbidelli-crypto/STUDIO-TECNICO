package it.geometra.schedacliente

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Legge, su una planimetria catastale scaricata dall'Agenzia delle Entrate,
 * SOLO il testo esplicito della scala numerica (es. "Scala 1:200") già
 * scritto sul disegno. Non misura mai la lunghezza della scala grafica a
 * barrette, perché quella misurazione richiederebbe di stimare i pixel
 * dell'immagine: se non trova un testo di scala leggibile, lo dichiara
 * invece di indovinare.
 */
object InterpretaScalaCatastale {

    private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
    private const val MODELLO = "claude-haiku-5-5"
    private const val LATO_MASSIMO_PX = 1568

    interface Callback {
        fun onSuccesso(denominatore: Int?, testoOriginale: String?, nota: String?)
        fun onErrore(messaggio: String)
    }

    fun analizza(chiaveApi: String, bitmap: Bitmap, callback: Callback) {
        val handler = Handler(Looper.getMainLooper())
        Thread {
            try {
                val base64Immagine = codificaBitmap(bitmap)
                val risultato = eseguiChiamata(chiaveApi, base64Immagine)
                val trovata = risultato.optBoolean("scala_trovata", false)
                val denominatore = if (trovata && !risultato.isNull("denominatore_scala")) {
                    risultato.optInt("denominatore_scala", -1).takeIf { it > 0 }
                } else null
                val testo = risultato.optString("testo_originale").ifBlank { null }
                val nota = risultato.optString("nota").ifBlank { null }
                handler.post { callback.onSuccesso(denominatore, testo, nota) }
            } catch (e: Exception) {
                handler.post { callback.onErrore(e.message ?: "Errore sconosciuto") }
            }
        }.start()
    }

    private fun eseguiChiamata(chiaveApi: String, base64Immagine: String): JSONObject {
        val promptSistema = """
            Sei un assistente tecnico che esamina planimetrie catastali italiane
            (scaricate dall'Agenzia delle Entrate / ex Catasto). Il tuo UNICO
            compito è trovare, se presente, il testo esplicito della scala
            numerica scritta sul disegno (es. "Scala 1:200", "Scala 1/100",
            "scala originale 1:500").

            REGOLE FONDAMENTALI:
            - Cerca SOLO un testo scritto con una scala numerica nel formato
              1:N oppure 1/N. Non calcolarla, non stimarla e non dedurla in
              altro modo.
            - NON usare mai una scala grafica a barrette/tacche (quella con un
              segmento disegnato e valori in metri lungo di esso) per stimare
              o calcolare un rapporto: misurare la lunghezza in pixel di quel
              segmento non è consentito, produrrebbe un numero inventato.
              Se trovi solo quel tipo di scala grafica e nessun testo
              numerico 1:N, considera la scala NON trovata.
            - Se il testo è leggibile ma ambiguo o parzialmente illeggibile,
              considera la scala NON trovata e spiegalo nella nota.

            Restituisci SOLO un oggetto JSON valido, senza markdown, senza
            testo prima o dopo, con questa struttura esatta:
            {
              "scala_trovata": true/false,
              "denominatore_scala": numero intero N (es. 200) oppure null,
              "testo_originale": "il testo esatto letto, es. Scala 1:200, oppure null",
              "nota": "breve spiegazione se non trovata o se ci sono dubbi, stringa vuota altrimenti"
            }
        """.trimIndent()

        val contenutoUtente = org.json.JSONArray()
        contenutoUtente.put(
            JSONObject().apply {
                put("type", "image")
                put("source", JSONObject().apply {
                    put("type", "base64")
                    put("media_type", "image/jpeg")
                    put("data", base64Immagine)
                })
            }
        )
        contenutoUtente.put(
            JSONObject().apply {
                put("type", "text")
                put("text", "Cerca il testo della scala numerica (1:N) scritto su questa planimetria catastale, seguendo esattamente le regole date.")
            }
        )

        val corpo = JSONObject().apply {
            put("model", MODELLO)
            put("max_tokens", 512)
            put("system", promptSistema)
            put("messages", org.json.JSONArray().put(
                JSONObject().apply {
                    put("role", "user")
                    put("content", contenutoUtente)
                }
            ))
        }

        val url = URL(ENDPOINT)
        val connessione = url.openConnection() as HttpURLConnection
        connessione.requestMethod = "POST"
        connessione.setRequestProperty("content-type", "application/json")
        connessione.setRequestProperty("x-api-key", chiaveApi)
        connessione.setRequestProperty("anthropic-version", "2023-06-01")
        connessione.doOutput = true
        connessione.connectTimeout = 20000
        connessione.readTimeout = 60000

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

        return JSONObject(estraiJson(testoModello.toString()))
    }

    private fun codificaBitmap(originale: Bitmap): String {
        val latoMaggiore = maxOf(originale.width, originale.height)
        val bitmapRidimensionato = if (latoMaggiore > LATO_MASSIMO_PX) {
            val scala = LATO_MASSIMO_PX.toFloat() / latoMaggiore
            Bitmap.createScaledBitmap(
                originale,
                (originale.width * scala).toInt(),
                (originale.height * scala).toInt(),
                true
            )
        } else {
            originale
        }
        val output = ByteArrayOutputStream()
        bitmapRidimensionato.compress(Bitmap.CompressFormat.JPEG, 90, output)
        return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
    }

    private fun estraiJson(testo: String): String {
        val inizio = testo.indexOf('{')
        val fine = testo.lastIndexOf('}')
        if (inizio == -1 || fine == -1 || fine < inizio) {
            throw IllegalStateException("Non sono riuscito a interpretare la risposta dell'AI")
        }
        return testo.substring(inizio, fine + 1)
    }
}
