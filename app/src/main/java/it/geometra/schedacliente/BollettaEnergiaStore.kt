package it.geometra.schedacliente

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Salva/carica lo storico delle bollette di energia elettrica (sezione "Ripartizione Bolletta
 * Energia Elettrica") nella memoria interna dell'app, una cartella per bolletta, sullo stesso
 * schema già usato da [ProgettoStore] per i progetti di planimetria:
 * "bollette_energia/<id>/dati.json" + uno o più file "bollette_energia/<id>/allegato_*.<estensione>".
 * Scrittura atomica (file temporaneo + rename) come in [ClienteStore], per non rischiare di
 * corrompere il record se l'app viene chiusa a metà salvataggio.
 */
object BollettaEnergiaStore {

    private fun cartellaBase(context: Context): File =
        File(context.filesDir, "bollette_energia").apply { mkdirs() }

    private fun cartellaRecord(context: Context, id: Long): File =
        File(cartellaBase(context), id.toString())

    fun caricaTutte(context: Context): List<BollettaEnergia> {
        val base = cartellaBase(context)
        val risultato = mutableListOf<BollettaEnergia>()
        base.listFiles()?.forEach { cartella ->
            if (!cartella.isDirectory) return@forEach
            val fileDati = File(cartella, "dati.json")
            if (!fileDati.exists()) return@forEach
            try {
                risultato.add(BollettaEnergia.fromJson(JSONObject(fileDati.readText())))
            } catch (e: Exception) {
                Log.e("BollettaEnergiaStore", "Record illeggibile/corrotto: ${cartella.name}", e)
            }
        }
        risultato.sortByDescending { it.dataCreazione }
        return risultato
    }

    fun carica(context: Context, id: Long): BollettaEnergia? {
        val fileDati = File(cartellaRecord(context, id), "dati.json")
        if (!fileDati.exists()) return null
        return try {
            BollettaEnergia.fromJson(JSONObject(fileDati.readText()))
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Salva (o aggiorna) una bolletta con i suoi allegati.
     *
     * [nuoviAllegati] sono gli Uri (foto appena scattate o file appena scelti) da copiare nella
     * cartella del record: ciascuno viene salvato con un nome nuovo e univoco (basato su orario +
     * indice), così più foto non si sovrascrivono mai a vicenda.
     *
     * [nomiAllegatiEsistentiDaMantenere] sono i nomi file (tra quelli già salvati in precedenza,
     * presenti in [bolletta].allegati) che l'utente vuole conservare: qualsiasi file "allegato*"
     * già presente nella cartella del record e non incluso né qui né tra i nuovi viene eliminato,
     * perché l'utente l'ha rimosso dal form. Di default si mantengono tutti quelli già presenti in
     * [bolletta] (nessuna rimozione).
     *
     * Il campo [BollettaEnergia.allegati] di [bolletta] viene ignorato in ingresso e ricalcolato da
     * questa funzione in base a cosa resta davvero salvato su disco al termine dell'operazione.
     */
    fun salva(
        context: Context,
        bolletta: BollettaEnergia,
        nuoviAllegati: List<Pair<Uri, String>> = emptyList(),
        nomiAllegatiEsistentiDaMantenere: List<String> = bolletta.allegati
    ) {
        val cartella = cartellaRecord(context, bolletta.id).apply { mkdirs() }

        // Elimina gli allegati già salvati che non sono più tra quelli da mantenere (rimossi dall'utente).
        cartella.listFiles()
            ?.filter { it.name.startsWith("allegato") && it.name !in nomiAllegatiEsistentiDaMantenere }
            ?.forEach { it.delete() }

        // Copia i nuovi allegati con un nome univoco, per non rischiare di sovrascrivere un file
        // già salvato con lo stesso nome (es. due foto scattate nello stesso istante).
        val nomiNuovi = mutableListOf<String>()
        nuoviAllegati.forEachIndexed { indice, (uri, estensione) ->
            val nomeFile = "allegato_${System.currentTimeMillis()}_$indice.$estensione"
            val destinazione = File(cartella, nomeFile)
            context.contentResolver.openInputStream(uri)?.use { input ->
                destinazione.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("Impossibile leggere uno degli allegati scelti")
            nomiNuovi.add(nomeFile)
        }

        val bollettaDaSalvare = bolletta.copy(allegati = nomiAllegatiEsistentiDaMantenere + nomiNuovi)

        val json = bollettaDaSalvare.toJson()
        val fileTemporaneo = File(cartella, "dati.json.tmp")
        fileTemporaneo.writeText(json.toString())
        val fileDati = File(cartella, "dati.json")
        if (!fileTemporaneo.renameTo(fileDati)) {
            fileDati.writeText(json.toString())
            fileTemporaneo.delete()
        }
    }

    fun elimina(context: Context, id: Long) {
        cartellaRecord(context, id).deleteRecursively()
    }

    /** File di tutti gli allegati salvati per questa bolletta, nell'ordine di [BollettaEnergia.allegati]. */
    fun fileAllegati(context: Context, bolletta: BollettaEnergia): List<File> {
        val cartella = cartellaRecord(context, bolletta.id)
        return bolletta.allegati.mapNotNull { nome ->
            val file = File(cartella, nome)
            if (file.exists()) file else null
        }
    }
}
