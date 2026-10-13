package it.geometra.schedacliente

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Registro locale degli avvistamenti FDM, tenuto solo sul telefono: a differenza del sondaggio
 * condiviso su Claude (che richiede un account per aggiungere o anche solo vedere i dati),
 * questo serve per poter generare un riepilogo (vedi [RiepilogoFdmImmagine]) da mandare, tramite
 * link di Google Drive, anche a chi non ha un account Claude.
 *
 * Le due liste restano volutamente indipendenti: non c'è modo di far scrivere l'app nativa nel
 * database condiviso dell'artifact su claude.ai (serve l'ambiente del sito stesso), quindi questo
 * è un secondo contatore, locale, con le stesse categorie per coerenza.
 */
object AvvistamentiFdmLocaliStore {

    /** Categorie nello stesso ordine in cui vanno mostrate (vedi anche [RuotaCaffeActivity]). */
    val CANDIDATI = listOf("mondiale", "fratello", "galli", "celli", "generale")

    val ETICHETTE = mapOf(
        "mondiale" to "Mondiale",
        "fratello" to "Fratello",
        "galli" to "Galli",
        "celli" to "Celli",
        "generale" to "(in generale)"
    )

    data class Avvistamento(val luogo: String, val ts: Long)

    private const val NOME_FILE = "avvistamenti_fdm_locali.json"

    private fun file(context: Context) = File(context.filesDir, NOME_FILE)

    fun leggiTutti(context: Context): List<Avvistamento> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return try {
            val array = JSONArray(f.readText())
            (0 until array.length()).map { i ->
                val oggetto = array.getJSONObject(i)
                Avvistamento(oggetto.getString("luogo"), oggetto.getLong("ts"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun aggiungi(context: Context, luogo: String) {
        val attuali = leggiTutti(context).toMutableList()
        attuali.add(Avvistamento(luogo, System.currentTimeMillis()))
        salva(context, attuali)
    }

    fun azzera(context: Context) {
        salva(context, emptyList())
    }

    // ---------------------------------------------------------------------
    // Azzeramenti separati di "sondaggio della giornata" e "sondaggio totale".
    // Non si cancellano voci: per ciascun sondaggio si ricorda il momento dell'ultimo
    // azzeramento e si contano solo le voci successive. Così azzerare la giornata non tocca
    // il totale e viceversa. Le voci che non compaiono più in nessuno dei due vengono eliminate.
    // ---------------------------------------------------------------------

    private const val PREF_AZZERAMENTI = "avvistamenti_fdm_azzeramenti"
    private const val CHIAVE_AZZ_GIORNATA = "azzeramento_giornata_ts"
    private const val CHIAVE_AZZ_TOTALE = "azzeramento_totale_ts"

    private fun pref(context: Context) = context.getSharedPreferences(PREF_AZZERAMENTI, Context.MODE_PRIVATE)
    private fun tsAzzGiornata(context: Context) = pref(context).getLong(CHIAVE_AZZ_GIORNATA, 0L)
    private fun tsAzzTotale(context: Context) = pref(context).getLong(CHIAVE_AZZ_TOTALE, 0L)

    /** Avvistamenti di oggi registrati dopo l'ultimo azzeramento della giornata. */
    fun vociGiornata(context: Context): List<Avvistamento> {
        val limite = tsAzzGiornata(context)
        return leggiTutti(context).filter { it.ts > limite && RiepilogoFdmImmagine.eDiOggi(it.ts) }
    }

    /** Avvistamenti registrati dopo l'ultimo azzeramento del totale. */
    fun vociTotale(context: Context): List<Avvistamento> {
        val limite = tsAzzTotale(context)
        return leggiTutti(context).filter { it.ts > limite }
    }

    /** Avvistamenti ancora visibili in almeno uno dei due sondaggi, dal più recente. */
    fun vociRecenti(context: Context): List<Avvistamento> {
        val giornata = vociGiornata(context).toSet()
        val totale = vociTotale(context).toSet()
        return (giornata + totale).sortedByDescending { it.ts }
    }

    fun azzeraGiornata(context: Context) {
        pref(context).edit().putLong(CHIAVE_AZZ_GIORNATA, System.currentTimeMillis()).apply()
        eliminaVociNonPiuVisibili(context)
    }

    fun azzeraTotale(context: Context) {
        pref(context).edit().putLong(CHIAVE_AZZ_TOTALE, System.currentTimeMillis()).apply()
        eliminaVociNonPiuVisibili(context)
    }

    private fun eliminaVociNonPiuVisibili(context: Context) {
        val visibili = vociRecenti(context).toSet()
        salva(context, leggiTutti(context).filter { it in visibili })
    }

    private fun salva(context: Context, lista: List<Avvistamento>) {
        val array = JSONArray()
        lista.forEach { a ->
            array.put(JSONObject().apply {
                put("luogo", a.luogo)
                put("ts", a.ts)
            })
        }
        file(context).writeText(array.toString())
    }
}
