package it.geometra.schedacliente

import android.content.Context
import org.json.JSONObject

/**
 * Carica l'elenco ufficiale dei comuni italiani raggruppati per provincia
 * dal file assets/comuni_per_provincia.json (fonte: elenco ISTAT comuni).
 */
object ComuniProvider {

    private var cache: Map<String, List<String>>? = null

    fun caricaMappa(context: Context): Map<String, List<String>> {
        cache?.let { return it }

        val testo = context.assets.open("comuni_per_provincia.json")
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }

        val obj = JSONObject(testo)
        val mappa = LinkedHashMap<String, List<String>>()
        val chiavi = obj.keys()
        while (chiavi.hasNext()) {
            val provincia = chiavi.next()
            val array = obj.getJSONArray(provincia)
            val comuni = ArrayList<String>(array.length())
            for (i in 0 until array.length()) {
                comuni.add(array.getString(i))
            }
            mappa[provincia] = comuni
        }
        cache = mappa
        return mappa
    }

    fun province(context: Context): List<String> =
        caricaMappa(context).keys.sorted()

    fun comuniDi(context: Context, provincia: String): List<String> =
        caricaMappa(context)[provincia] ?: emptyList()

    /** Risale alla provincia di un comune, utile per ricostruire la selezione
     * nelle schede salvate prima che il campo provincia esistesse. */
    fun provinciaDiComune(context: Context, comune: String): String? {
        if (comune.isBlank()) return null
        return caricaMappa(context).entries.firstOrNull { (_, comuni) -> comuni.contains(comune) }?.key
    }
}
