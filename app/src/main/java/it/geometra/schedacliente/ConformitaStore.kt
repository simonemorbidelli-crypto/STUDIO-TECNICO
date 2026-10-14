package it.geometra.schedacliente

import android.content.Context
import java.io.File

/** Salva/carica i dati di lavoro della verifica di conformità, uno per cliente (o "generale" senza cliente). */
class ConformitaStore(private val context: Context) {

    private fun file(chiave: String): File =
        File(File(context.filesDir, "conformita").apply { mkdirs() }, "${chiave.replace(Regex("[^A-Za-z0-9_-]"), "_")}.json")

    fun carica(chiave: String): DatiConformita? = try {
        file(chiave).takeIf { it.exists() }?.readText()?.let { DatiConformita.daJson(it) }
    } catch (e: Exception) { null }

    fun salva(chiave: String, dati: DatiConformita) {
        try {
            val f = file(chiave)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(dati.toJson())
            if (!tmp.renameTo(f)) { f.writeText(dati.toJson()); tmp.delete() }
        } catch (e: Exception) {
            // best effort: la verifica resta comunque a schermo
        }
    }

    companion object {
        fun chiaveCliente(id: Long?): String = if (id == null) "generale" else "cliente_$id"
    }
}
