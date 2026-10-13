package it.geometra.schedacliente

import android.content.Context

/**
 * Elenco dei bar tra cui scegliere con la "ruota della fortuna del caffè", personalizzabile
 * dall'utente dalla schermata stessa. Viene salvato come semplice preferenza di testo (un
 * nome di bar per riga): per un elenco così piccolo non serve un file o un database a parte,
 * una SharedPreferences è più che sufficiente e sopravvive comunque alla chiusura dell'app.
 */
object RuotaCaffeStore {
    private const val PREFS = "ruota_caffe_prefs"
    private const val CHIAVE_ELENCO = "elenco_bar"

    /**
     * Bar reali di Castelfranco di Sopra (AR), usati come elenco di partenza finché l'utente
     * non lo personalizza da "Modifica elenco bar". L'elenco può essere modificato liberamente:
     * se in futuro apre/chiude qualche locale, o se vuole aggiungerne altri non elencati qui,
     * basta aggiornarlo dalla schermata stessa senza bisogno di una nuova versione dell'app.
     */
    val elencoPredefinito = listOf(
        "Mr Jack's Bar",
        "Bar Pasticceria La Dolce Via",
        "New California",
        "Gelateria Caffetteria Turismo",
        "Enoteca La Torre",
        "Circolo MCL",
        "Casa del Popolo"
    )

    fun leggiElenco(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val salvato = prefs.getString(CHIAVE_ELENCO, null) ?: return elencoPredefinito
        val lista = salvato.split("\n").map { it.trim() }.filter { it.isNotBlank() }
        return lista.ifEmpty { elencoPredefinito }
    }

    fun salvaElenco(context: Context, elenco: List<String>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(CHIAVE_ELENCO, elenco.joinToString("\n")).apply()
    }

    fun ripristinaPredefinito(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().remove(CHIAVE_ELENCO).apply()
    }

    private const val CHIAVE_ULTIMO_BAR = "ultimo_bar_scelto"
    private const val CHIAVE_ULTIMO_BAR_TS = "ultimo_bar_scelto_ts"

    /** Memorizza il bar appena estratto dalla ruota, da riusare nel riepilogo del sondaggio FDM. */
    fun salvaUltimoBarScelto(context: Context, nomeBar: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(CHIAVE_ULTIMO_BAR, nomeBar)
            .putLong(CHIAVE_ULTIMO_BAR_TS, System.currentTimeMillis())
            .apply()
    }

    /** Il bar estratto l'ultima volta e quando, oppure null se la ruota non è mai stata girata. */
    fun leggiUltimoBarScelto(context: Context): Pair<String, Long>? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val nome = prefs.getString(CHIAVE_ULTIMO_BAR, null) ?: return null
        val ts = prefs.getLong(CHIAVE_ULTIMO_BAR_TS, 0L)
        return nome to ts
    }
}
