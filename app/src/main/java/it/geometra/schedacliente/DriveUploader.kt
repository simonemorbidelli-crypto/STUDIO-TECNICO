package it.geometra.schedacliente

import android.content.Context
import com.google.android.gms.auth.GoogleAuthUtil
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Carica un file su Google Drive dell'utente e lo rende condivisibile con un link (chiunque
 * abbia il link può vederlo, senza bisogno di un account Google per aprirlo).
 *
 * Fatto "a mano" con chiamate HTTP dirette all'API Drive v3 invece di usare le librerie client
 * ufficiali di Google (google-api-client / google-api-services-drive): quelle librerie sono
 * pesanti e portano con sé molte dipendenze trasversali, per un'app che deve solo caricare
 * un'immagine ogni tanto bastano tre chiamate REST semplici.
 *
 * Usa il permesso "drive.file" (solo i file creati da questa app, non l'intero Drive
 * dell'utente): per questo serve che l'utente faccia il login Google la prima volta (vedi
 * [RuotaCaffeActivity]) e che il progetto Google Cloud associato abbia l'API Drive attiva e il
 * client OAuth Android configurato con il nome del pacchetto e l'impronta (SHA-1) corretti.
 *
 * ATTENZIONE: va chiamato sempre su un thread in background (mai sul thread principale): sia
 * [GoogleAuthUtil.getToken] sia le chiamate HTTP sono bloccanti.
 */
object DriveUploader {

    private const val SCOPE_DRIVE_FILE = "oauth2:https://www.googleapis.com/auth/drive.file"
    private const val URL_UPLOAD = "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart"
    private const val URL_FILES = "https://www.googleapis.com/drive/v3/files/"
    private const val CONFINE = "SchedaClienteFDM-----confine"

    /**
     * Carica [bytesPng] su Drive con nome [nomeFile], lo rende visibile a chiunque abbia il
     * link (solo visualizzazione) e restituisce quel link. Lancia un'eccezione se qualsiasi
     * passaggio fallisce (token mancante, rete assente, API Drive non attiva sul progetto...).
     *
     * [emailAccount] è l'email dell'account Google già autenticato (non l'oggetto
     * [android.accounts.Account]): su alcuni dispositivi quest'ultimo risulta null anche dopo
     * un login riuscito, mentre l'email è sempre disponibile e [GoogleAuthUtil.getToken] accetta
     * anche una stringa al posto dell'Account.
     */
    @Throws(IOException::class)
    fun caricaEContrassegnaCondivisibile(
        context: Context,
        emailAccount: String,
        nomeFile: String,
        bytesPng: ByteArray
    ): String {
        val token = GoogleAuthUtil.getToken(context, emailAccount, SCOPE_DRIVE_FILE)
        val idFile = creaFile(token, nomeFile, bytesPng)
        rendiCondivisibile(token, idFile)
        return leggiLinkCondivisione(token, idFile)
    }

    private fun creaFile(token: String, nomeFile: String, bytesPng: ByteArray): String {
        val metadati = JSONObject().apply {
            put("name", nomeFile)
            put("mimeType", "image/png")
        }

        val intestazioneParte1 = (
            "--$CONFINE\r\n" +
                "Content-Type: application/json; charset=UTF-8\r\n\r\n" +
                metadati.toString() + "\r\n" +
                "--$CONFINE\r\n" +
                "Content-Type: image/png\r\n\r\n"
            ).toByteArray(StandardCharsets.UTF_8)
        val chiusuraParte2 = "\r\n--$CONFINE--\r\n".toByteArray(StandardCharsets.UTF_8)

        val connessione = (URL(URL_UPLOAD).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "multipart/related; boundary=$CONFINE")
            connectTimeout = 20000
            readTimeout = 20000
        }

        try {
            connessione.outputStream.use { flusso: OutputStream ->
                flusso.write(intestazioneParte1)
                flusso.write(bytesPng)
                flusso.write(chiusuraParte2)
            }
            val risposta = leggiRisposta(connessione)
            return JSONObject(risposta).getString("id")
        } finally {
            connessione.disconnect()
        }
    }

    private fun rendiCondivisibile(token: String, idFile: String) {
        val corpo = JSONObject().apply {
            put("role", "reader")
            put("type", "anyone")
        }.toString().toByteArray(StandardCharsets.UTF_8)

        val connessione = (URL(URL_FILES + idFile + "/permissions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connectTimeout = 20000
            readTimeout = 20000
        }
        try {
            connessione.outputStream.use { it.write(corpo) }
            leggiRisposta(connessione)
        } finally {
            connessione.disconnect()
        }
    }

    private fun leggiLinkCondivisione(token: String, idFile: String): String {
        val connessione = (URL(URL_FILES + idFile + "?fields=webViewLink").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $token")
            connectTimeout = 20000
            readTimeout = 20000
        }
        try {
            val risposta = leggiRisposta(connessione)
            return JSONObject(risposta).getString("webViewLink")
        } finally {
            connessione.disconnect()
        }
    }

    /** Legge il corpo della risposta, da successo o da errore, e lancia IOException se il codice non è 2xx. */
    private fun leggiRisposta(connessione: HttpURLConnection): String {
        val codice = connessione.responseCode
        val flusso = if (codice in 200..299) connessione.inputStream else connessione.errorStream
        val corpo = flusso?.bufferedReader(StandardCharsets.UTF_8)?.use(BufferedReader::readText) ?: ""
        if (codice !in 200..299) {
            throw IOException("Drive ha risposto con errore $codice: $corpo")
        }
        return corpo
    }
}
