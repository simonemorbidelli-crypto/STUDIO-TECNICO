package it.geometra.schedacliente

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Un progetto di planimetria salvato: immagine importata + scala + locali tracciati. */
data class ProgettoPlanimetria(
    val id: Long,
    val nome: String,
    val dataSalvataggio: Long,
    val bitmap: Bitmap,
    val scalaX: Double?,
    val scalaY: Double?,
    val locali: List<LocaleTracciato>
)

/** Solo i metadati di un progetto salvato (per la lista), senza caricare l'immagine in memoria. */
data class RiepilogoProgetto(
    val id: Long,
    val nome: String,
    val dataSalvataggio: Long,
    val numeroLocali: Int,
    val superficieTotale: Double
) {
    fun dataFormattata(): String =
        SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALY).format(Date(dataSalvataggio))
}

/**
 * Salva/carica progetti di planimetria (immagine importata, scala e locali tracciati) su file
 * nella memoria interna dell'app, uno per cartella: "progetti_planimetria/<id>/dati.json" e
 * "progetti_planimetria/<id>/immagine.jpg". Così, riaprendo "Aree locali da planimetria", si può
 * richiamare un lavoro lasciato a metà invece di dover ricaricare l'immagine e ritracciare tutto.
 */
object ProgettoStore {

    private fun cartellaBase(context: Context): File =
        File(context.filesDir, "progetti_planimetria").apply { mkdirs() }

    private fun cartellaProgetto(context: Context, id: Long): File =
        File(cartellaBase(context), id.toString())

    fun elenca(context: Context): List<RiepilogoProgetto> {
        val base = cartellaBase(context)
        val risultato = mutableListOf<RiepilogoProgetto>()
        base.listFiles()?.forEach { cartella ->
            if (!cartella.isDirectory) return@forEach
            val fileDati = File(cartella, "dati.json")
            if (!fileDati.exists()) return@forEach
            try {
                val json = JSONObject(fileDati.readText())
                val localiJson = json.getJSONArray("locali")
                var superficieTotale = 0.0
                for (i in 0 until localiJson.length()) {
                    superficieTotale += localiJson.getJSONObject(i).optDouble("areaM2", 0.0)
                }
                risultato.add(
                    RiepilogoProgetto(
                        id = json.optLong("id", cartella.name.toLongOrNull() ?: 0L),
                        nome = json.optString("nome", "Progetto"),
                        dataSalvataggio = json.optLong("dataSalvataggio", 0L),
                        numeroLocali = localiJson.length(),
                        superficieTotale = superficieTotale
                    )
                )
            } catch (e: Exception) {
                // Progetto illeggibile/corrotto: viene semplicemente ignorato nell'elenco.
            }
        }
        risultato.sortByDescending { it.dataSalvataggio }
        return risultato
    }

    fun salva(
        context: Context,
        nome: String,
        bitmap: Bitmap,
        scalaX: Double?,
        scalaY: Double?,
        locali: List<LocaleTracciato>,
        idEsistente: Long? = null
    ): Long {
        val id = idEsistente ?: IdUnivoci.nuovo(elenca(context).map { it.id }.toSet())
        scrivi(cartellaProgetto(context, id).apply { mkdirs() }, id, nome, bitmap, scalaX, scalaY, locali)
        return id
    }

    private fun scrivi(
        cartella: File,
        id: Long,
        nome: String,
        bitmap: Bitmap?,
        scalaX: Double?,
        scalaY: Double?,
        locali: List<LocaleTracciato>
    ) {
        cartella.mkdirs()
        if (bitmap != null) {
            val fileImmagine = File(cartella, "immagine.jpg")
            FileOutputStream(fileImmagine).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
        }

        val localiJson = JSONArray()
        for (locale in locali) {
            val verticiJson = JSONArray()
            for (v in locale.verticiPx) {
                verticiJson.put(JSONObject().apply {
                    put("x", v.x.toDouble())
                    put("y", v.y.toDouble())
                })
            }
            val latiJson = JSONArray()
            for (lato in locale.latiM) latiJson.put(lato)
            localiJson.put(JSONObject().apply {
                put("nome", locale.nome)
                put("vertici", verticiJson)
                put("areaM2", locale.areaM2)
                put("lati", latiJson)
            })
        }

        val json = JSONObject().apply {
            put("id", id)
            put("nome", nome)
            put("dataSalvataggio", System.currentTimeMillis())
            put("scalaX", scalaX ?: JSONObject.NULL)
            put("scalaY", scalaY ?: JSONObject.NULL)
            put("locali", localiJson)
        }

        val fileTemporaneo = File(cartella, "dati.json.tmp")
        fileTemporaneo.writeText(json.toString())
        val fileDati = File(cartella, "dati.json")
        if (!fileTemporaneo.renameTo(fileDati)) {
            fileDati.writeText(json.toString())
            fileTemporaneo.delete()
        }
    }

    // --- Ultimo lavoro (salvato in automatico all'uscita, non compare nell'elenco dei progetti) ---

    private fun cartellaUltimo(context: Context): File = File(context.filesDir, "ultimo_lavoro_planimetria")

    private const val ID_ULTIMO = -1L

    /** Salva l'ultimo lavoro. L'immagine (pesante) si riscrive solo se [scriviImmagine] è vero. */
    fun salvaUltimo(
        context: Context,
        bitmap: Bitmap,
        scalaX: Double?,
        scalaY: Double?,
        locali: List<LocaleTracciato>,
        scriviImmagine: Boolean
    ) {
        val cartella = cartellaUltimo(context)
        val serveImmagine = scriviImmagine || !File(cartella, "immagine.jpg").exists()
        scrivi(cartella, ID_ULTIMO, "Ultimo lavoro", if (serveImmagine) bitmap else null, scalaX, scalaY, locali)
    }

    /** Dati dell'ultimo lavoro (senza caricare l'immagine), o null se non c'è nulla da riprendere. */
    fun riepilogoUltimo(context: Context): RiepilogoProgetto? {
        val cartella = cartellaUltimo(context)
        val fileDati = File(cartella, "dati.json")
        if (!fileDati.exists() || !File(cartella, "immagine.jpg").exists()) return null
        return try {
            val json = JSONObject(fileDati.readText())
            val localiJson = json.getJSONArray("locali")
            var tot = 0.0
            for (i in 0 until localiJson.length()) tot += localiJson.getJSONObject(i).optDouble("areaM2", 0.0)
            RiepilogoProgetto(ID_ULTIMO, "Ultimo lavoro", json.optLong("dataSalvataggio", 0L), localiJson.length(), tot)
        } catch (e: Exception) {
            null
        }
    }

    fun caricaUltimo(context: Context): ProgettoPlanimetria? = leggi(cartellaUltimo(context), ID_ULTIMO)

    fun carica(context: Context, id: Long): ProgettoPlanimetria? = leggi(cartellaProgetto(context, id), id)

    private fun leggi(cartella: File, id: Long): ProgettoPlanimetria? {
        val fileDati = File(cartella, "dati.json")
        val fileImmagine = File(cartella, "immagine.jpg")
        if (!fileDati.exists() || !fileImmagine.exists()) return null

        return try {
            val json = JSONObject(fileDati.readText())
            val bitmap = BitmapFactory.decodeFile(fileImmagine.absolutePath)
                ?: return null

            val localiJson = json.getJSONArray("locali")
            val locali = mutableListOf<LocaleTracciato>()
            for (i in 0 until localiJson.length()) {
                val localeJson = localiJson.getJSONObject(i)
                val verticiJson = localeJson.getJSONArray("vertici")
                val vertici = mutableListOf<PointF>()
                for (v in 0 until verticiJson.length()) {
                    val punto = verticiJson.getJSONObject(v)
                    vertici.add(PointF(punto.getDouble("x").toFloat(), punto.getDouble("y").toFloat()))
                }
                val latiJson = localeJson.getJSONArray("lati")
                val lati = mutableListOf<Double>()
                for (l in 0 until latiJson.length()) lati.add(latiJson.getDouble(l))
                locali.add(
                    LocaleTracciato(
                        nome = localeJson.optString("nome"),
                        verticiPx = vertici,
                        areaM2 = localeJson.optDouble("areaM2", 0.0),
                        latiM = lati
                    )
                )
            }

            ProgettoPlanimetria(
                id = json.optLong("id", id),
                nome = json.optString("nome", "Progetto"),
                dataSalvataggio = json.optLong("dataSalvataggio", 0L),
                bitmap = bitmap,
                scalaX = if (json.isNull("scalaX")) null else json.optDouble("scalaX"),
                scalaY = if (json.isNull("scalaY")) null else json.optDouble("scalaY"),
                locali = locali
            )
        } catch (e: Exception) {
            null
        }
    }

    fun elimina(context: Context, id: Long) {
        cartellaProgetto(context, id).deleteRecursively()
    }
}
