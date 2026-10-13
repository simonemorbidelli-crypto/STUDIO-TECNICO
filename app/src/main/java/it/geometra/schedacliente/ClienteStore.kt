package it.geometra.schedacliente

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Store dei clienti su file JSON locale.
 *
 * CORREZIONE (ottobre 2026): la versione precedente leggeva/scriveva
 * "clienti.json" senza alcuna protezione contro corruzione del file.
 * Se l'app veniva chiusa forzatamente, il telefono si spegneva o
 * finiva la memoria esattamente durante una scrittura, il file
 * restava a metà scritto (JSON non valido). Al riavvio, caricaTutti()
 * lanciava una JSONException non gestita da nessuna parte della
 * catena di chiamata (MainActivity.onCreate -> aggiornaLista ->
 * caricaTutti), quindi l'app crashava SEMPRE all'apertura, bloccando
 * l'accesso a tutte le schede clienti salvate, senza alcuna via di
 * recupero per l'utente.
 *
 * Questa versione:
 *  1) scrive in modo atomico (file temporaneo + rename), così una
 *     scrittura interrotta non puo' mai corrompere il file buono;
 *  2) mantiene una copia di sicurezza "clienti.json.bak" del
 *     contenuto valido precedente ad ogni scrittura;
 *  3) se il file principale risulta illeggibile/corrotto, tenta
 *     automaticamente il backup prima di arrendersi;
 *  4) se anche il backup è inutilizzabile, non lancia mai
 *     un'eccezione: sposta il file corrotto da parte (per poterlo
 *     recuperare manualmente/segnalare) e riparte con lista vuota,
 *     così l'app resta sempre utilizzabile.
 */
class ClienteStore(private val context: Context) {

    private val file: File
        get() = File(context.filesDir, "clienti.json")

    private val backupFile: File
        get() = File(context.filesDir, "clienti.json.bak")

    fun caricaTutti(): List<Cliente> {
        val lista = leggiListaDaFile(file) ?: leggiListaDaFile(backupFile)
        if (lista == null) {
            if (file.exists()) {
                metteDaParteFileCorrotto()
            }
            return ArrayList()
        }
        val risultato = ArrayList(lista)
        if (risultato.size > 1) {
            risultato.sortByDescending { it.dataCreazione }
        }
        return risultato
    }

    private fun leggiListaDaFile(f: File): List<Cliente>? {
        if (!f.exists()) return null
        return try {
            val testo = f.readText()
            if (testo.isBlank()) return null
            val array = JSONArray(testo)
            val lista = ArrayList<Cliente>()
            for (i in 0 until array.length()) {
                lista.add(Cliente.fromJson(array.getJSONObject(i)))
            }
            lista
        } catch (e: Exception) {
            Log.e("ClienteStore", "File clienti illeggibile/corrotto: ${f.name}", e)
            null
        }
    }

    private fun metteDaParteFileCorrotto() {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ITALY).format(Date())
            val destinazione = File(context.filesDir, "clienti_corrotto_$timestamp.json")
            file.copyTo(destinazione, overwrite = true)
        } catch (e: Exception) {
            Log.e("ClienteStore", "Impossibile salvare da parte il file corrotto", e)
        }
    }

    fun salva(cliente: Cliente) {
        val lista = caricaTutti().toMutableList()
        lista.add(0, cliente)
        scriviTutti(lista)
    }

    fun aggiorna(cliente: Cliente) {
        val lista = caricaTutti().toMutableList()
        val indice = lista.indexOfFirst { it.id == cliente.id }
        if (indice >= 0) {
            lista[indice] = cliente
        } else {
            lista.add(0, cliente)
        }
        scriviTutti(lista)
    }

    fun elimina(id: Long) {
        val lista = caricaTutti().toMutableList()
        lista.removeAll { it.id == id }
        scriviTutti(lista)
    }

    /**
     * Scrittura atomica: scrive su un file temporaneo e lo rinomina solo
     * se la scrittura è andata a buon fine, in modo che un crash/mancanza
     * di corrente a metà scrittura non possa mai lasciare "clienti.json"
     * in uno stato corrotto. Prima di sovrascrivere, copia il contenuto
     * precedente (ancora valido) in clienti.json.bak.
     */
    private fun scriviTutti(lista: List<Cliente>) {
        val array = JSONArray()
        for (c in lista) {
            array.put(c.toJson())
        }
        val testo = array.toString()

        if (file.exists()) {
            try {
                file.copyTo(backupFile, overwrite = true)
            } catch (e: Exception) {
                Log.w("ClienteStore", "Impossibile aggiornare il backup", e)
            }
        }

        val fileTemporaneo = File(context.filesDir, "clienti.json.tmp")
        fileTemporaneo.writeText(testo)
        if (!fileTemporaneo.renameTo(file)) {
            // Fallback per filesystem dove renameTo puo' fallire tra file
            // esistenti: scrittura diretta come ultima risorsa.
            file.writeText(testo)
            fileTemporaneo.delete()
        }
    }

    fun preparaFileEsportazione(): File? {
        val lista = caricaTutti()
        if (lista.isEmpty()) return null
        val cartellaExport = File(context.cacheDir, "export")
        cartellaExport.mkdirs()
        cartellaExport.listFiles()?.forEach { it.delete() }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.ITALY).format(Date())
        val destinazione = File(cartellaExport, "SchedaCliente_backup_$timestamp.json")
        val array = JSONArray()
        for (c in lista) array.put(c.toJson())
        destinazione.writeText(array.toString())
        return destinazione
    }

    fun testoEsportazione(): String? {
        val lista = caricaTutti()
        if (lista.isEmpty()) return null
        val array = JSONArray()
        for (c in lista) array.put(c.toJson())
        return array.toString()
    }

    fun analizzaImportazione(testoJson: String): RisultatoAnalisiImportazione {
        val arrayImportato = JSONArray(testoJson)
        val locali = caricaTutti().associateBy { it.id }
        val nuove = ArrayList<Cliente>()
        var identiche = 0
        val conflitti = ArrayList<ConflittoImportazione>()
        for (i in 0 until arrayImportato.length()) {
            val importata = Cliente.fromJson(arrayImportato.getJSONObject(i))
            val locale = locali[importata.id]
            when {
                locale == null -> nuove.add(importata)
                locale == importata -> identiche++
                else -> conflitti.add(ConflittoImportazione(locale, importata))
            }
        }
        return RisultatoAnalisiImportazione(nuove, identiche, conflitti)
    }

    fun aggiungiTutte(clienti: List<Cliente>) {
        if (clienti.isEmpty()) return
        val lista = caricaTutti().toMutableList()
        lista.addAll(0, clienti)
        scriviTutti(lista)
    }
}

/** Una scheda presente sia in locale sia nel file importato, ma con dati diversi. */
data class ConflittoImportazione(val locale: Cliente, val importata: Cliente)

data class RisultatoAnalisiImportazione(
    val nuove: List<Cliente>,
    val identiche: Int,
    val conflitti: List<ConflittoImportazione>
)
