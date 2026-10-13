package it.geometra.schedacliente

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Una bolletta dell'energia elettrica condivisa tra lo studio tecnico e l'abitazione (il
 * contatore Enel è unico, intestato al proprietario di casa, e misura sia lo studio che
 * l'abitazione; lo studio ha un proprio contatore "a valle" che misura solo i propri consumi).
 * Questo record raccoglie i dati di una singola bolletta, da cui si calcola quanto spetta allo
 * studio e quanto resta a carico del proprietario (vedi [RipartizioneBollettaCalcolatore]).
 *
 * Le date della bolletta non sono richieste: non entrano in nessuna formula di calcolo, quindi non
 * sono campi essenziali. [dataCreazione] (automatica, non inserita dall'utente) serve solo per
 * ordinare lo storico.
 *
 * [canoneFisso] è la quota fissa della bolletta (es. canone di gestione del contatore), sempre a
 * carico del proprietario. [costoPotenzaUnaTantum] è un eventuale addebito una tantum per
 * l'aumento di potenza richiesto dallo studio (3→4,5 kW), sempre a carico dello studio: non è un
 * consumo, quindi non entra nel calcolo del costo unitario per kWh.
 */
data class BollettaEnergia(
    val id: Long = IdUnivoci.nuovo(),
    val nomeProprietario: String = "Antonella",
    val consumoTotaleKwh: Double,
    val importoTotale: Double,
    val canoneFisso: Double = 0.0,
    val costoPotenzaUnaTantum: Double = 0.0,
    val letturaIniziale: Double,
    val letturaFinale: Double,
    /** Nomi dei file degli allegati (foto/PDF) salvati nella cartella del record: possono essere più di uno. */
    val allegati: List<String> = emptyList(),
    val note: String = "",
    val dataCreazione: Long = System.currentTimeMillis(),
    val ultimaModifica: Long = dataCreazione
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("nomeProprietario", nomeProprietario)
        put("consumoTotaleKwh", consumoTotaleKwh)
        put("importoTotale", importoTotale)
        put("canoneFisso", canoneFisso)
        put("costoPotenzaUnaTantum", costoPotenzaUnaTantum)
        put("letturaIniziale", letturaIniziale)
        put("letturaFinale", letturaFinale)
        put("allegati", JSONArray(allegati))
        put("note", note)
        put("dataCreazione", dataCreazione)
        put("ultimaModifica", ultimaModifica)
    }

    companion object {
        fun fromJson(o: JSONObject): BollettaEnergia {
            // Elenco allegati nel formato attuale (più file); se il record è stato salvato dalla
            // versione precedente (un solo allegato, campo "estensioneAllegato"), lo si ricostruisce
            // da lì per compatibilità con lo storico già salvato sul telefono.
            val allegati = mutableListOf<String>()
            val arrayAllegati = o.optJSONArray("allegati")
            if (arrayAllegati != null) {
                for (i in 0 until arrayAllegati.length()) allegati.add(arrayAllegati.getString(i))
            } else if (o.has("estensioneAllegato") && !o.isNull("estensioneAllegato")) {
                val estensioneVecchia = o.optString("estensioneAllegato").ifBlank { null }
                if (estensioneVecchia != null) allegati.add("allegato.$estensioneVecchia")
            }
            return BollettaEnergia(
                id = o.getLong("id"),
                nomeProprietario = o.optString("nomeProprietario", "Antonella"),
                consumoTotaleKwh = o.optDouble("consumoTotaleKwh", 0.0),
                importoTotale = o.optDouble("importoTotale", 0.0),
                canoneFisso = o.optDouble("canoneFisso", 0.0),
                costoPotenzaUnaTantum = o.optDouble("costoPotenzaUnaTantum", 0.0),
                letturaIniziale = o.optDouble("letturaIniziale", 0.0),
                letturaFinale = o.optDouble("letturaFinale", 0.0),
                allegati = allegati,
                note = o.optString("note", ""),
                dataCreazione = o.optLong("dataCreazione", System.currentTimeMillis()),
                ultimaModifica = o.optLong("ultimaModifica", o.optLong("dataCreazione", System.currentTimeMillis()))
            )
        }
    }

    fun dataCreazioneFormattata(): String = SimpleDateFormat("dd/MM/yyyy", Locale.ITALY).format(Date(dataCreazione))

    /** Tipo MIME di un allegato in base all'estensione nel suo nome file; null se non riconosciuto. */
    fun mimeAllegato(nomeFile: String): String? = when (nomeFile.substringAfterLast('.', "").lowercase(Locale.ITALY)) {
        "pdf" -> "application/pdf"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        else -> null
    }
}

/**
 * Esito del calcolo di ripartizione di una bolletta (vedi [RipartizioneBollettaCalcolatore]), con
 * tutti i valori intermedi mostrati nel riepilogo a schermo, non solo il risultato finale: così
 * chi legge può verificare passo per passo come si è arrivati alla quota dello studio.
 */
data class RipartizioneBolletta(
    val consumoStudioKwh: Double,
    val consumoProprietarioKwh: Double,
    val costoUnitarioKwh: Double,
    val quotaStudio: Double,
    val quotaProprietario: Double
)

/** Errore di validazione dei dati di una bolletta, con un messaggio pronto da mostrare all'utente. */
class BollettaValidazioneException(message: String) : Exception(message)

/**
 * Calcola la ripartizione dello studio/proprietario per una bolletta, secondo le formule concordate:
 *
 * ```
 * consumo_studio       = lettura_finale_studio - lettura_iniziale_studio
 * consumo_proprietario = consumo_totale_bolletta - consumo_studio
 * costo_unitario        = (importo_totale - canone - costo_potenza_una_tantum) / consumo_totale_bolletta
 * quota_studio          = (consumo_studio * costo_unitario) + costo_potenza_una_tantum
 * quota_proprietario     = importo_totale - quota_studio
 * ```
 *
 * Il canone fisso e il costo una tantum per l'aumento di potenza vengono tolti dall'importo prima
 * di calcolare il costo per kWh, perché non sono consumo: il canone resta sempre interamente a
 * carico del proprietario (non è mai ripartito), mentre il costo per l'aumento di potenza resta
 * sempre interamente a carico dello studio (si somma alla sua quota, non si divide per kWh).
 */
object RipartizioneBollettaCalcolatore {

    /**
     * Valida i dati prima del calcolo. Lancia [BollettaValidazioneException] con un messaggio in
     * italiano pronto da mostrare, invece di restituire un codice di errore generico, così il
     * form può semplicemente mostrare `e.message` all'utente.
     */
    fun valida(
        consumoTotaleKwh: Double,
        importoTotale: Double,
        canoneFisso: Double,
        costoPotenzaUnaTantum: Double,
        letturaIniziale: Double,
        letturaFinale: Double
    ) {
        if (letturaFinale < letturaIniziale) {
            throw BollettaValidazioneException(
                "La lettura finale del contatore dello studio (${fmt(letturaFinale)}) non può essere " +
                    "inferiore a quella iniziale (${fmt(letturaIniziale)})."
            )
        }
        val consumoStudio = letturaFinale - letturaIniziale
        if (consumoStudio > consumoTotaleKwh) {
            throw BollettaValidazioneException(
                "Il consumo dello studio (${fmt(consumoStudio)} kWh) non può superare il consumo " +
                    "totale della bolletta (${fmt(consumoTotaleKwh)} kWh): controlla le letture o il " +
                    "consumo totale inserito."
            )
        }
        if (consumoTotaleKwh <= 0.0) {
            throw BollettaValidazioneException("Il consumo totale della bolletta deve essere maggiore di zero.")
        }
        if (importoTotale < canoneFisso + costoPotenzaUnaTantum) {
            throw BollettaValidazioneException(
                "L'importo totale della bolletta (${fmt(importoTotale)} €) è inferiore alla somma di " +
                    "canone e costo una tantum (${fmt(canoneFisso + costoPotenzaUnaTantum)} €): controlla gli importi."
            )
        }
    }

    /** Esegue il calcolo. Chiamare [valida] prima, così i valori sono già garantiti coerenti. */
    fun calcola(
        consumoTotaleKwh: Double,
        importoTotale: Double,
        canoneFisso: Double,
        costoPotenzaUnaTantum: Double,
        letturaIniziale: Double,
        letturaFinale: Double
    ): RipartizioneBolletta {
        val consumoStudio = letturaFinale - letturaIniziale
        val consumoProprietario = consumoTotaleKwh - consumoStudio
        val costoUnitario = (importoTotale - canoneFisso - costoPotenzaUnaTantum) / consumoTotaleKwh
        val quotaStudio = (consumoStudio * costoUnitario) + costoPotenzaUnaTantum
        val quotaProprietario = importoTotale - quotaStudio
        return RipartizioneBolletta(
            consumoStudioKwh = consumoStudio,
            consumoProprietarioKwh = consumoProprietario,
            costoUnitarioKwh = costoUnitario,
            quotaStudio = quotaStudio,
            quotaProprietario = quotaProprietario
        )
    }

    private fun fmt(v: Double): String = String.format(Locale.ITALY, "%.2f", v)
}
