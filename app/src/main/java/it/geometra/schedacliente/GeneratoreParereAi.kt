package it.geometra.schedacliente

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * Chiede all'AI (API Anthropic, stessa chiave delle altre funzioni dell'app) di redigere la
 * bozza di un parere o di una relazione tecnica a partire dai documenti selezionati nella
 * cartella Mega della pratica e dai dati della scheda cliente.
 *
 * Restituisce testo con una marcatura minima (# titolo, ## sezioni, - elenchi, **grassetto**),
 * che [EsportatoreDocx] trasforma in un documento Word.
 *
 * Bloccante (rete): va chiamato su un thread in background.
 */
object GeneratoreParereAi {

    private const val ENDPOINT = "https://api.anthropic.com/v1/messages"

    /** Modello più capace di quello delle altre funzioni: un parere su più documenti richiede più qualità di scrittura. */
    const val MODELLO = "claude-sonnet-5-5"
    private const val MAX_TOKEN_RISPOSTA = 8000

    /** Limiti dell'API sull'intera richiesta (vedi documentazione "PDF support"). */
    const val PAGINE_PDF_MAX = 100
    const val BYTE_RICHIESTA_MAX = 32L * 1024 * 1024

    enum class TipoDocumento(val etichetta: String) {
        PARERE("Parere tecnico"),
        RELAZIONE("Relazione tecnica")
    }

    class Risultato(val testo: String, val troncato: Boolean)

    private val ISTRUZIONI_SISTEMA = """
        Sei l'assistente di un geometra libero professionista che opera in Toscana. Redigi bozze di
        pareri e relazioni tecniche in italiano, con il registro formale, sobrio e preciso tipico di
        un geometra: terminologia catastale, urbanistica ed edilizia corretta, frasi chiare,
        nessun tono commerciale.

        Regole inderogabili:
        - Basati SOLO sui documenti forniti e sui dati della scheda cliente. Non inventare dati
          (superfici, consistenze, rendite, date, protocolli, nomi, titoli edilizi).
        - Se un dato necessario manca, è illeggibile o i documenti sono in contrasto, scrivi
          [DA VERIFICARE: spiegazione] nel punto esatto in cui servirebbe.
        - Per i dati rilevanti indica tra parentesi il documento da cui provengono, ad esempio
          (fonte: visura.pdf).
        - Riferimenti normativi: citali solo se compaiono nei documenti, oppure se sei certo che
          siano pertinenti; in quel caso aggiungi sempre [DA VERIFICARE: vigenza], perché la
          normativa può essere cambiata dopo il tuo aggiornamento.
        - Non firmare e non inserire nome, timbro o dati del tecnico: li aggiunge lui.

        Formato di uscita: solo testo con questa marcatura minima, nient'altro (niente tabelle,
        niente altro markdown, niente commenti prima o dopo il documento):
        # Titolo del documento (una sola riga, la prima)
        ## Titolo di sezione
        ### Titolo di sottosezione
        - voce di elenco
        **grassetto** solo per pochi termini chiave
        Paragrafi separati da una riga vuota.
    """.trimIndent()

    fun genera(
        context: Context,
        cliente: Cliente?,
        tipo: TipoDocumento,
        richiestaUtente: String,
        blocchi: List<PreparatoreDocumenti.Blocco>,
        nonLetti: List<String> = emptyList()
    ): Risultato {
        val chiaveApi = ChiaveApiStore.leggiChiave(context)
        if (chiaveApi.isBlank()) {
            throw IllegalStateException("Chiave API Anthropic non impostata: inseriscila nelle Impostazioni dell'app")
        }

        val contenuto = JSONArray()
        blocchi.forEach { blocco ->
            when (blocco) {
                is PreparatoreDocumenti.Blocco.Pdf -> contenuto.put(testo("Documento PDF: ${blocco.nome}")).put(JSONObject().apply {
                    put("type", "document")
                    // "title" ha una lunghezza massima: nomi lunghi accorciati (il nome intero è nel testo sopra).
                    put("title", blocco.nome.take(100))
                    put("source", JSONObject().apply {
                        put("type", "base64")
                        put("media_type", "application/pdf")
                        put("data", Base64.encodeToString(blocco.dati, Base64.NO_WRAP))
                    })
                })
                is PreparatoreDocumenti.Blocco.Immagine -> {
                    contenuto.put(testo("Immagine: ${blocco.nome}"))
                    contenuto.put(JSONObject().apply {
                        put("type", "image")
                        put("source", JSONObject().apply {
                            put("type", "base64")
                            put("media_type", "image/jpeg")
                            put("data", Base64.encodeToString(blocco.jpeg, Base64.NO_WRAP))
                        })
                    })
                }
                is PreparatoreDocumenti.Blocco.Testo -> contenuto.put(testo(
                    "=== Documento: ${blocco.nome} (${blocco.origine.descrizione}) ===\n${blocco.testo}\n=== Fine ${blocco.nome} ==="
                ))
            }
        }
        contenuto.put(testo(istruzioniUtente(cliente, tipo, richiestaUtente, blocchi.map { it.nome }, nonLetti)))

        val corpo = JSONObject().apply {
            put("model", MODELLO)
            put("max_tokens", MAX_TOKEN_RISPOSTA)
            put("system", ISTRUZIONI_SISTEMA)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", contenuto)
            }))
        }
        return invia(chiaveApi, corpo.toString())
    }

    private fun testo(t: String) = JSONObject().put("type", "text").put("text", t)

    /** Solo i dati della scheda utili al documento (niente telefono, email, codice fiscale). */
    internal fun istruzioniUtente(
        cliente: Cliente?,
        tipo: TipoDocumento,
        richiestaUtente: String,
        nomiDocumenti: List<String>,
        nonLetti: List<String> = emptyList()
    ): String = buildString {
        appendLine("Redigi la bozza di un ${tipo.etichetta.lowercase()} basandoti sui documenti allegati sopra (${nomiDocumenti.joinToString(", ")}).")
        if (nonLetti.isNotEmpty()) {
            appendLine("Il tecnico aveva scelto anche questi file, che NON sono stati letti (formato non supportato o illeggibili): ${nonLetti.joinToString(", ")}. " +
                "Non dedurne il contenuto; dove le loro informazioni sarebbero state necessarie scrivi [DA VERIFICARE] e segnala nella bozza che non sono stati esaminati.")
        }
        appendLine()
        if (cliente == null) {
            // Aperto dal menu con un link libero: nessuna scheda cliente associata.
            appendLine("Non c'è una scheda cliente associata: ricava committente, immobile e dati catastali solo dai documenti; se non risultano, usa [DA VERIFICARE].")
        } else {
            appendDatiCliente(cliente)
        }
        appendLine()
        appendIstruzioniFinali(tipo, richiestaUtente)
    }

    private fun StringBuilder.appendDatiCliente(cliente: Cliente) {
        appendLine("Dati dalla scheda cliente:")
        appendLine("- Committente: ${cliente.nomeCompleto()}")
        val luogo = listOf(
            cliente.indirizzo,
            listOf(cliente.comune, cliente.provincia.takeIf { it.isNotBlank() }?.let { "($it)" })
                .filter { !it.isNullOrBlank() }.joinToString(" ")
        ).filter { it.isNotBlank() }.joinToString(", ")
        if (luogo.isNotBlank()) appendLine("- Immobile: $luogo")
        val catasto = listOfNotNull(
            cliente.foglio.takeIf { it.isNotBlank() }?.let { "Foglio $it" },
            cliente.particella.takeIf { it.isNotBlank() }?.let { "Particella $it" },
            cliente.subalterno.takeIf { it.isNotBlank() }?.let { "Sub. $it" }
        )
        if (catasto.isNotEmpty()) appendLine("- Riferimenti catastali: ${catasto.joinToString(", ")}")
        if (cliente.tipoPratica.isNotBlank()) appendLine("- Tipo pratica: ${cliente.tipoPratica}")
        if (cliente.praticaFormattata().isNotBlank()) appendLine("- Pratica n.: ${cliente.praticaFormattata()}")
        if (cliente.note.isNotBlank()) appendLine("- Note della scheda: ${cliente.note}")
    }

    private fun StringBuilder.appendIstruzioniFinali(tipo: TipoDocumento, richiestaUtente: String) {
        if (richiestaUtente.isNotBlank()) {
            appendLine("Cosa chiede il tecnico: $richiestaUtente")
            appendLine()
        }
        appendLine("Struttura suggerita (adattala al caso, omettendo le sezioni senza contenuto):")
        when (tipo) {
            TipoDocumento.PARERE -> appendLine(
                "Oggetto e premessa; Documentazione esaminata; Dati dell'immobile; Analisi e valutazioni; Conclusioni; Limiti del parere e verifiche consigliate."
            )
            TipoDocumento.RELAZIONE -> appendLine(
                "Premessa e incarico; Identificazione dell'immobile e dati catastali; Documentazione esaminata; Descrizione e verifiche svolte; Conclusioni."
            )
        }
    }

    private fun invia(chiaveApi: String, corpo: String): Risultato {
        val connessione = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("content-type", "application/json")
            setRequestProperty("x-api-key", chiaveApi)
            setRequestProperty("anthropic-version", "2023-06-01")
            connectTimeout = 30000
            // Un documento lungo con il modello più capace può richiedere qualche minuto.
            readTimeout = 300000
        }
        try {
            OutputStreamWriter(connessione.outputStream, Charsets.UTF_8).use { it.write(corpo) }
            val codice = connessione.responseCode
            val testoRisposta = (if (codice in 200..299) connessione.inputStream else connessione.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (codice !in 200..299) {
                val messaggio = try {
                    JSONObject(testoRisposta).optJSONObject("error")?.optString("message")
                } catch (e: Exception) { null }
                throw IllegalStateException(when (codice) {
                    401 -> "Chiave API non valida: controllala nelle Impostazioni"
                    413 -> "Documenti troppo grandi per una sola richiesta: selezionane meno"
                    429 -> "Troppe richieste in poco tempo: riprova tra qualche minuto"
                    529, 503 -> "Il servizio AI è sovraccarico in questo momento: riprova tra poco"
                    else -> messaggio ?: "Il servizio AI ha risposto con un errore (codice $codice)"
                })
            }
            val json = JSONObject(testoRisposta)
            val blocchi = json.optJSONArray("content") ?: throw IllegalStateException("Risposta AI senza contenuto")
            val testo = StringBuilder()
            for (i in 0 until blocchi.length()) {
                val b = blocchi.getJSONObject(i)
                if (b.optString("type") == "text") testo.append(b.optString("text"))
            }
            if (testo.isBlank()) throw IllegalStateException("L'AI non ha restituito testo")
            return Risultato(testo.toString().trim(), json.optString("stop_reason") == "max_tokens")
        } catch (e: SocketTimeoutException) {
            throw IOException("Il servizio AI non ha risposto in tempo: riprova, magari con meno documenti")
        } finally {
            connessione.disconnect()
        }
    }
}
