package it.geometra.schedacliente

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

data class Cliente(
    val id: Long = IdUnivoci.nuovo(),
    val cognome: String,
    val nome: String = "",
    val codiceFiscale: String,
    val partitaIva: String,
    val telefono: String,
    val email: String,
    val indirizzo: String,
    val provincia: String = "",
    val comune: String,
    val tipoPratica: String,
    val numeroPratica: String,
    val annoPratica: String,
    val foglio: String,
    val particella: String,
    val subalterno: String,
    val note: String,
    /**
     * Link pubblico della cartella Mega della pratica ("Ottieni link" su mega.nz, del tipo
     * https://mega.nz/folder/XXXX#chiave). Serve alla sezione "Parere / relazione dai documenti
     * Mega" per elencare e scaricare i file della cartella, senza login. Vuoto se non impostato.
     */
    val linkMega: String = "",
    val dataCreazione: Long = System.currentTimeMillis(),
    val ultimaModifica: Long = dataCreazione
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id)
        o.put("cognome", cognome)
        o.put("nome", nome)
        o.put("codiceFiscale", codiceFiscale)
        o.put("partitaIva", partitaIva)
        o.put("telefono", telefono)
        o.put("email", email)
        o.put("indirizzo", indirizzo)
        o.put("provincia", provincia)
        o.put("comune", comune)
        o.put("tipoPratica", tipoPratica)
        o.put("numeroPratica", numeroPratica)
        o.put("annoPratica", annoPratica)
        o.put("foglio", foglio)
        o.put("particella", particella)
        o.put("subalterno", subalterno)
        o.put("note", note)
        o.put("linkMega", linkMega)
        o.put("dataCreazione", dataCreazione)
        o.put("ultimaModifica", ultimaModifica)
        return o
    }

    /** "Cognome Nome" per le persone fisiche, oppure solo la ragione sociale se il nome è vuoto. */
    fun nomeCompleto(): String {
        return if (nome.isNotBlank()) "$cognome $nome" else cognome
    }

    fun dataFormattata(): String {
        val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALY)
        return sdf.format(Date(dataCreazione))
    }

    fun dataUltimaModificaFormattata(): String {
        val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALY)
        return sdf.format(Date(ultimaModifica))
    }

    /** Es. "12/2026", oppure "12" o "2026" se manca l'altro valore, o "" se entrambi vuoti. */
    fun praticaFormattata(): String {
        return when {
            numeroPratica.isNotBlank() && annoPratica.isNotBlank() -> "$numeroPratica/$annoPratica"
            numeroPratica.isNotBlank() -> numeroPratica
            annoPratica.isNotBlank() -> annoPratica
            else -> ""
        }
    }

    companion object {
        fun fromJson(o: JSONObject): Cliente {
            // Compatibilità con le schede salvate prima della v1.5, quando esisteva
            // un unico campo "nome" (nome e cognome insieme). In quel caso il
            // vecchio valore diventa il nuovo "cognome" e "nome" resta vuoto.
            val cognome: String
            val nome: String
            if (o.has("cognome")) {
                cognome = o.optString("cognome")
                nome = o.optString("nome")
            } else {
                cognome = o.optString("nome")
                nome = ""
            }

            return Cliente(
                id = o.optLong("id"),
                cognome = cognome,
                nome = nome,
                codiceFiscale = o.optString("codiceFiscale"),
                partitaIva = o.optString("partitaIva"),
                telefono = o.optString("telefono"),
                email = o.optString("email"),
                indirizzo = o.optString("indirizzo"),
                provincia = o.optString("provincia"),
                comune = o.optString("comune"),
                tipoPratica = o.optString("tipoPratica"),
                numeroPratica = o.optString("numeroPratica"),
                annoPratica = o.optString("annoPratica"),
                foglio = o.optString("foglio"),
                particella = o.optString("particella"),
                subalterno = o.optString("subalterno"),
                note = o.optString("note"),
                // Assente nelle schede salvate prima della v3.59: resta vuoto.
                linkMega = o.optString("linkMega", ""),
                dataCreazione = o.optLong("dataCreazione"),
                // Le schede salvate prima di questo campo non hanno una data di
                // ultima modifica: si usa la data di creazione come riferimento.
                ultimaModifica = if (o.has("ultimaModifica")) {
                    o.optLong("ultimaModifica")
                } else {
                    o.optLong("dataCreazione")
                }
            )
        }
    }
}
