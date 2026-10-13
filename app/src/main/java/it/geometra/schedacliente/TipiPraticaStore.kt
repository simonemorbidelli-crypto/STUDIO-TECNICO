package it.geometra.schedacliente

import android.content.Context
import org.json.JSONArray

/**
 * Elenco dei "Tipo pratica" selezionabili nella scheda cliente, personalizzabile
 * da ogni studio (ognuno può avere categorie di lavoro diverse). Salvato
 * localmente sul telefono in formato JSON.
 */
object TipiPraticaStore {
    private const val PREFS = "scheda_cliente_prefs"
    private const val CHIAVE_TIPI = "tipi_pratica"

    val elencoPredefinito = listOf(
        "Catasto (DOCFA/Tipo mappale)",
        "Urbanistica (CILA/SCIA/PdC)",
        "APE / SIERT",
        "IMU / Fiscale",
        "Perizia / Valutazione",
        "Altro"
    )

    fun leggiTipi(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val testo = prefs.getString(CHIAVE_TIPI, null) ?: return elencoPredefinito

        return try {
            val array = JSONArray(testo)
            val lista = mutableListOf<String>()
            for (i in 0 until array.length()) {
                lista.add(array.getString(i))
            }
            if (lista.isEmpty()) elencoPredefinito else lista
        } catch (e: Exception) {
            elencoPredefinito
        }
    }

    fun salvaTipi(context: Context, tipi: List<String>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val array = JSONArray()
        tipi.forEach { array.put(it) }
        prefs.edit().putString(CHIAVE_TIPI, array.toString()).apply()
    }

    fun ripristinaPredefinito(context: Context) {
        salvaTipi(context, elencoPredefinito)
    }
}
