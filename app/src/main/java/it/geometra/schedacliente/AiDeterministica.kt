package it.geometra.schedacliente

import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Invio delle richieste all'AI con temperatura 0, così che la stessa planimetria dia lo stesso
 * risultato a ogni prova (senza questo parametro il servizio usa il valore predefinito, 1,
 * che rende ogni risposta diversa). Se il modello rifiutasse il parametro, si riprova una volta
 * senza e non lo si invia più.
 */
object AiDeterministica {
    @Volatile
    var rifiutata = false

    /** Restituisce il codice HTTP e il testo della risposta. */
    fun invia(endpoint: String, chiaveApi: String, corpo: JSONObject): Pair<Int, String> {
        if (!rifiutata) corpo.put("temperature", 0) else corpo.remove("temperature")
        val (codice, testo) = chiamata(endpoint, chiaveApi, corpo)
        if (codice == 400 && !rifiutata && testo.contains("temperature", ignoreCase = true)) {
            rifiutata = true
            corpo.remove("temperature")
            return chiamata(endpoint, chiaveApi, corpo)
        }
        return codice to testo
    }

    private fun chiamata(endpoint: String, chiaveApi: String, corpo: JSONObject): Pair<Int, String> {
        val connessione = URL(endpoint).openConnection() as HttpURLConnection
        connessione.requestMethod = "POST"
        connessione.setRequestProperty("content-type", "application/json")
        connessione.setRequestProperty("x-api-key", chiaveApi)
        connessione.setRequestProperty("anthropic-version", "2023-06-01")
        connessione.doOutput = true
        connessione.connectTimeout = 20000
        connessione.readTimeout = 60000
        OutputStreamWriter(connessione.outputStream, Charsets.UTF_8).use { it.write(corpo.toString()) }
        val codice = connessione.responseCode
        val stream = if (codice in 200..299) connessione.inputStream else connessione.errorStream
        val testo = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        return codice to testo
    }
}
