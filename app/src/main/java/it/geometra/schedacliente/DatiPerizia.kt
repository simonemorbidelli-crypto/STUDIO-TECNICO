package it.geometra.schedacliente

import org.json.JSONArray
import org.json.JSONObject

/**
 * Dati della "Perizia unità" calcolati dall'app (superfici ragguagliate, quotazione OMI, valore),
 * passati alla perizia completa (v3.82). Sono i NUMERI VERI dell'app: nel Word finiscono nelle
 * tabelle così come sono, senza passare dall'AI, che li riceve solo come dati di partenza.
 */
data class DatiPerizia(
    val destinazione: String,
    val righe: List<Riga>,
    val superficieCommerciale: Double,
    val superficieArrotondata: Long,
    val note: List<String>,
    /** Descrizione della quotazione OMI usata (comune, zona, semestre), vuota se non consultata. */
    val omiDescrizione: String,
    val omiTipologia: String,
    val omiMinimo: Double?,
    val omiMassimo: Double?,
    val valoreUnitario: Double?,
    val correttivi: List<Correttivo>,
    val valoreMercato: Double?,
    val valoreArrotondato: Double?
) {
    data class Riga(val nome: String, val categoria: String, val mqLordi: Double, val regola: String, val mqRagguagliati: Double)
    data class Correttivo(val descrizione: String, val percentuale: Double)

    fun toJson(): String = JSONObject().apply {
        put("destinazione", destinazione)
        put("righe", JSONArray().apply {
            righe.forEach {
                put(JSONObject().put("nome", it.nome).put("categoria", it.categoria).put("mqLordi", it.mqLordi)
                    .put("regola", it.regola).put("mqRagguagliati", it.mqRagguagliati))
            }
        })
        put("superficieCommerciale", superficieCommerciale)
        put("superficieArrotondata", superficieArrotondata)
        put("note", JSONArray(note))
        put("omiDescrizione", omiDescrizione)
        put("omiTipologia", omiTipologia)
        omiMinimo?.let { put("omiMinimo", it) }
        omiMassimo?.let { put("omiMassimo", it) }
        valoreUnitario?.let { put("valoreUnitario", it) }
        put("correttivi", JSONArray().apply {
            correttivi.forEach { put(JSONObject().put("descrizione", it.descrizione).put("percentuale", it.percentuale)) }
        })
        valoreMercato?.let { put("valoreMercato", it) }
        valoreArrotondato?.let { put("valoreArrotondato", it) }
    }.toString()

    /** Il riepilogo come testo per l'AI: dati di partenza da riportare senza modificarli. */
    fun riepilogoPerAi(): String = buildString {
        appendLine("DATI CALCOLATI DALL'APP (affidabili: riportali esattamente, senza ricalcolarli né arrotondarli):")
        appendLine("- Destinazione dell'unità: $destinazione")
        if (righe.isNotEmpty()) {
            appendLine("- Consistenza (ragguaglio con i coefficienti del Manuale OMI / DPR 138/1998 All. C):")
            righe.forEach {
                appendLine("  • ${it.nome}: ${PeriziaCalcolo.fmt(it.mqLordi)} mq, ${it.categoria}, criterio: ${it.regola} → ${PeriziaCalcolo.fmt(it.mqRagguagliati)} mq")
            }
            appendLine("- Superficie commerciale: ${PeriziaCalcolo.fmt(superficieCommerciale)} mq, arrotondata ${superficieArrotondata} mq")
        }
        note.forEach { appendLine("- Nota di calcolo: $it") }
        if (omiDescrizione.isNotBlank()) {
            appendLine("- Quotazione OMI: $omiDescrizione, $omiTipologia" +
                if (omiMinimo != null && omiMassimo != null) ": ${PeriziaCalcolo.fmt(omiMinimo)} – ${PeriziaCalcolo.fmt(omiMassimo)} €/mq" else "")
        }
        valoreUnitario?.let { appendLine("- Valore unitario adottato: ${PeriziaCalcolo.fmt(it)} €/mq") }
        correttivi.forEach { appendLine("- Correttivo: ${it.descrizione.ifBlank { "—" }} ${if (it.percentuale > 0) "+" else ""}${PeriziaCalcolo.fmt(it.percentuale)}%") }
        valoreMercato?.let { appendLine("- Valore di mercato calcolato: ${euro(it)}") }
        valoreArrotondato?.let { appendLine("- Valore arrotondato: ${euro(it)}") }
    }

    companion object {
        fun daJson(testo: String): DatiPerizia {
            val o = JSONObject(testo)
            val righe = o.optJSONArray("righe")?.let { a ->
                (0 until a.length()).map {
                    val r = a.getJSONObject(it)
                    Riga(r.optString("nome"), r.optString("categoria"), r.optDouble("mqLordi"), r.optString("regola"), r.optDouble("mqRagguagliati"))
                }
            }.orEmpty()
            val correttivi = o.optJSONArray("correttivi")?.let { a ->
                (0 until a.length()).map { val c = a.getJSONObject(it); Correttivo(c.optString("descrizione"), c.optDouble("percentuale")) }
            }.orEmpty()
            fun numero(chiave: String): Double? = if (o.has(chiave) && !o.isNull(chiave)) o.optDouble(chiave) else null
            return DatiPerizia(
                destinazione = o.optString("destinazione"),
                righe = righe,
                superficieCommerciale = o.optDouble("superficieCommerciale", 0.0),
                superficieArrotondata = o.optLong("superficieArrotondata", 0L),
                note = o.optJSONArray("note")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
                omiDescrizione = o.optString("omiDescrizione"),
                omiTipologia = o.optString("omiTipologia"),
                omiMinimo = numero("omiMinimo"), omiMassimo = numero("omiMassimo"),
                valoreUnitario = numero("valoreUnitario"),
                correttivi = correttivi,
                valoreMercato = numero("valoreMercato"), valoreArrotondato = numero("valoreArrotondato")
            )
        }

        /** "€ 123.000" nel formato italiano. */
        fun euro(x: Double): String =
            java.text.NumberFormat.getCurrencyInstance(java.util.Locale.ITALY).apply { maximumFractionDigits = 0 }.format(x)
    }
}
