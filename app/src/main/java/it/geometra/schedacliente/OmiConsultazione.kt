package it.geometra.schedacliente

import android.text.Html
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Consultazione delle quotazioni OMI (Osservatorio del Mercato Immobiliare dell'Agenzia delle
 * Entrate) tramite il servizio PUBBLICO e gratuito di consultazione sul sito dell'Agenzia
 * (www1.agenziaentrate.gov.it/servizi/Consultazione), senza login: le stesse pagine che si usano
 * dal browser, passo per passo (provincia → comune e semestre → zona → destinazione → valori).
 *
 * Il servizio non è un'interfaccia documentata per le app: se l'Agenzia cambiasse le pagine, le
 * funzioni restituirebbero un errore e nella perizia resta sempre possibile inserire il valore
 * €/mq a mano. Tutte le funzioni sono bloccanti (rete): da chiamare in background.
 */
object OmiConsultazione {

    private const val BASE = "https://www1.agenziaentrate.gov.it/servizi/Consultazione/"

    data class Voce(val codice: String, val descrizione: String) {
        override fun toString() = descrizione
    }

    /** Zona OMI: es. codice "C1", fascia "Semicentrale", descrizione delle vie comprese. */
    data class Zona(val link: String, val codice: String, val fascia: String, val descrizione: String) {
        override fun toString() = "$codice · $fascia · $descrizione"
    }

    data class Quotazione(
        val tipologia: String,
        val stato: String,
        val minimo: Double,
        val massimo: Double,
        /** "L" = valori riferiti alla superficie lorda, "N" = netta. */
        val superficie: String
    ) {
        val medio: Double get() = (minimo + massimo) / 2.0
    }

    class ErroreOmi(messaggio: String) : IOException(messaggio)

    fun province(): List<Voce> {
        val html = richiesta("ricerca.htm", null)
        return opzioni(html, "pr").ifEmpty { throw ErroreOmi("Elenco province OMI non trovato") }
    }

    /** Semestri disponibili (dal più recente) e comuni della provincia. */
    fun semestriEComuni(provincia: String): Pair<List<Voce>, List<Voce>> {
        val html = richiesta("ricerca.htm", mapOf("level" to "1", "lingua" to "IT", "pr" to provincia))
        val semestri = opzioni(html, "anno_semestre")
        val comuni = opzioni(html, "co")
        if (comuni.isEmpty()) throw ErroreOmi("Elenco comuni OMI non trovato per la provincia $provincia")
        return semestri to comuni
    }

    fun zone(provincia: String, semestre: String, comune: String): List<Zona> {
        val html = richiesta(
            "ricerca.htm",
            mapOf("level" to "2", "lingua" to "IT", "pr" to provincia, "anno_semestre" to semestre, "co" to comune)
        )
        val zone = opzioni(html, "linkzonastrada").map { o ->
            // Testo opzione: "C1/Semicentrale/ZONA PESTELLO, CIMITERO, ..." (può essere troncato con "...").
            val parti = o.descrizione.split('/', limit = 3)
            Zona(o.codice, parti.getOrElse(0) { "" }, parti.getOrElse(1) { "" }, parti.getOrElse(2) { o.descrizione })
        }
        if (zone.isEmpty()) throw ErroreOmi("Nessuna zona OMI trovata per questo comune e semestre")
        return zone
    }

    /** @param utilizzo "Residenziale", "Commerciale", "Terziaria" o "Produttiva". */
    fun quotazioni(provincia: String, semestre: String, comune: String, zona: Zona, utilizzo: String): List<Quotazione> {
        val html = richiesta(
            "risultato.php",
            mapOf(
                "pr" to provincia, "co" to comune, "anno_semestre" to semestre,
                "linkzona" to zona.link, "idstrada" to "",
                "fasciazona" to "${zona.fascia}/${zona.descrizione}", "codzona" to zona.codice,
                "lingua" to "IT", "level" to "4", "utilizzo" to utilizzo, "bt1" to "Mostra valori"
            )
        )
        return analizzaTabella(html)
    }

    // ---------------------------------------------------------------------

    internal fun analizzaTabella(html: String): List<Quotazione> {
        val righe = Regex("<tr[^>]*>(.*?)</tr>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).findAll(html)
        return righe.mapNotNull { r ->
            val celle = Regex("<t[hd][^>]*>(.*?)</t[hd]>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                .findAll(r.groupValues[1]).map { testo(it.groupValues[1]) }.toList()
            // Righe dati: tipologia, stato, min, max, L/N, loc min, loc max, L/N
            if (celle.size < 5) return@mapNotNull null
            val min = numero(celle[2]) ?: return@mapNotNull null
            val max = numero(celle[3]) ?: return@mapNotNull null
            Quotazione(celle[0], celle[1], min, max, celle[4])
        }.toList()
    }

    internal fun opzioni(html: String, nomeSelect: String): List<Voce> {
        val select = Regex("""<select[^>]*name="$nomeSelect"[^>]*>(.*?)</select>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .find(html)?.groupValues?.get(1)
        // Alcune pagine non chiudono le option: si prende il testo fino al tag successivo.
        val corpo = select ?: Regex("""<select[^>]*name="$nomeSelect"[^>]*>(.*)""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .find(html)?.groupValues?.get(1)?.substringBefore("</select") ?: return emptyList()
        return Regex("""<option[^>]*value="([^"]*)"[^>]*>([^<]*)""", RegexOption.IGNORE_CASE).findAll(corpo)
            .map { Voce(it.groupValues[1].trim(), testo(it.groupValues[2])) }
            .filter { it.codice.isNotEmpty() }
            .toList()
    }

    private fun testo(html: String): String =
        Html.fromHtml(html.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), " "), Html.FROM_HTML_MODE_LEGACY)
            .toString().replace(' ', ' ').replace(Regex("\\s+"), " ").trim()

    private fun numero(s: String): Double? = s.replace(".", "").replace(',', '.').trim().toDoubleOrNull()

    private fun richiesta(pagina: String, parametri: Map<String, String>?): String {
        val connessione = (URL(BASE + pagina).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000
            readTimeout = 30000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) SchedaCliente")
            if (parametri != null) {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            }
        }
        try {
            if (parametri != null) {
                val corpo = parametri.entries.joinToString("&") {
                    URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
                }
                connessione.outputStream.use { it.write(corpo.toByteArray(Charsets.UTF_8)) }
            }
            if (connessione.responseCode !in 200..299) {
                throw ErroreOmi("Il servizio OMI dell'Agenzia delle Entrate ha risposto con errore ${connessione.responseCode}")
            }
            return connessione.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connessione.disconnect()
        }
    }
}
