package it.geometra.schedacliente

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import it.geometra.schedacliente.databinding.ActivityPeriziaUnitaBinding
import org.json.JSONArray
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToLong

/**
 * "Perizia unità": dai locali tracciati sulla planimetria calcola la superficie commerciale
 * (ragguaglio di vani, accessori e pertinenze con i coefficienti del Manuale OMI, vedi
 * [PeriziaCalcolo]), recupera le quotazioni OMI della zona dal servizio pubblico dell'Agenzia
 * delle Entrate (vedi [OmiConsultazione]), permette di scegliere valore minimo, medio o massimo,
 * di applicare aumenti/deprezzamenti percentuali e calcola il valore di mercato.
 */
class PeriziaUnitaActivity : AppCompatActivity() {

    companion object {
        /** JSON: [{"nome": "Cucina", "mq": 12.5}, ...] con le superfici dei locali tracciati. */
        const val EXTRA_LOCALI = "locali_json"

        fun localiInJson(locali: List<LocaleTracciato>): String = JSONArray().apply {
            locali.forEach { put(org.json.JSONObject().put("nome", it.nome).put("mq", it.areaM2)) }
        }.toString()
    }

    private lateinit var binding: ActivityPeriziaUnitaBinding

    private val voci = mutableListOf<PeriziaCalcolo.Voce>()
    /** Voci aggiunte a mano (rimovibili), per indice di riferimento. */
    private val vociManuali = mutableSetOf<PeriziaCalcolo.Voce>()
    private var risultato: PeriziaCalcolo.Risultato? = null

    private class Correttivo(var descrizione: String, var percentuale: Double)
    private val correttivi = mutableListOf(Correttivo("", 0.0))

    // OMI
    private var province: List<OmiConsultazione.Voce> = emptyList()
    private var semestri: List<OmiConsultazione.Voce> = emptyList()
    private var comuni: List<OmiConsultazione.Voce> = emptyList()
    private var zone: List<OmiConsultazione.Zona> = emptyList()
    private var quotazioni: List<OmiConsultazione.Quotazione> = emptyList()
    private var quotazioneScelta: OmiConsultazione.Quotazione? = null
    private var zonaScelta: OmiConsultazione.Zona? = null

    private val formatoEuro = NumberFormat.getCurrencyInstance(Locale.ITALY).apply { maximumFractionDigits = 0 }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPeriziaUnitaBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }
        NavigazioneIndietro.installa(this)

        intent.getStringExtra(EXTRA_LOCALI)?.let { json ->
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val nome = o.optString("nome").ifBlank { "Locale ${i + 1}" }
                voci += PeriziaCalcolo.Voce(nome, o.optDouble("mq", 0.0), PeriziaCalcolo.categoriaDaNome(nome))
            }
        }

        binding.toggleDestinazione.addOnButtonCheckedListener { _, _, checked ->
            if (checked) {
                binding.chkVilla.visibility = if (destinazione() == PeriziaCalcolo.Destinazione.ABITAZIONE) View.VISIBLE else View.GONE
                ricalcola()
                if (quotazioni.isNotEmpty() || zonaScelta != null) caricaQuotazioni()
            }
        }
        binding.chkVilla.setOnCheckedChangeListener { _, _ -> ricalcola() }
        binding.switchNette.setOnCheckedChangeListener { _, nette ->
            binding.tilMuri.isEnabled = nette
            ricalcola()
        }
        binding.etMuri.addTextChangedListener(osservatore { ricalcola() })
        binding.btnAggiungiVoce.setOnClickListener { aggiungiVoce() }
        binding.btnAggiungiCorrettivo.setOnClickListener {
            correttivi += Correttivo("", 0.0)
            disegnaCorrettivi()
        }
        binding.etValoreUnitario.addTextChangedListener(osservatore { aggiornaValore() })
        binding.toggleValore.addOnButtonCheckedListener { _, _, checked -> if (checked) applicaQuotazione() }
        binding.btnMostraOmi.setOnClickListener { caricaQuotazioni() }
        binding.btnCondividiPerizia.setOnClickListener { condividi() }

        disegnaCorrettivi()
        ricalcola()
        caricaProvince()
    }

    private fun destinazione(): PeriziaCalcolo.Destinazione = when (binding.toggleDestinazione.checkedButtonId) {
        R.id.btnDestUfficio -> PeriziaCalcolo.Destinazione.UFFICIO
        R.id.btnDestNegozio -> PeriziaCalcolo.Destinazione.NEGOZIO
        R.id.btnDestBox -> PeriziaCalcolo.Destinazione.BOX
        else -> PeriziaCalcolo.Destinazione.ABITAZIONE
    }

    // ---------------------------------------------------------------------
    // Superfici
    // ---------------------------------------------------------------------

    private fun ricalcola() {
        val muri = if (binding.switchNette.isChecked) numero(binding.etMuri.text?.toString()) ?: 0.0 else 0.0
        val r = PeriziaCalcolo.calcola(destinazione(), voci, muri, binding.chkVilla.isChecked && binding.chkVilla.visibility == View.VISIBLE)
        risultato = r
        disegnaVoci(r)
        binding.tvSuperficieCommerciale.text = if (voci.isEmpty()) "Nessuna superficie: aggiungine una qui sopra."
        else "Superficie commerciale: ${PeriziaCalcolo.fmt(r.superficieCommerciale)} mq → ${r.superficieArrotondata} mq (arrotondata)"
        binding.tvNoteSuperficie.visibility = if (r.note.isEmpty()) View.GONE else View.VISIBLE
        binding.tvNoteSuperficie.text = r.note.joinToString("\n") { "• $it" }
        aggiornaValore()
    }

    private fun disegnaVoci(r: PeriziaCalcolo.Risultato) {
        val lista = binding.listaVoci
        lista.removeAllViews()
        r.righe.forEach { riga ->
            val v = riga.voce
            val contenitore = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(8), dp(8), dp(8))
                background = sfondo(Color.WHITE, Color.parseColor("#D8DCD2"))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = dp(6) }
            }
            val intestazione = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            intestazione.addView(TextView(this).apply {
                text = v.nome
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor("#1E2A22"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            intestazione.addView(TextView(this).apply {
                text = "${PeriziaCalcolo.fmt(riga.mqLordi)} → ${PeriziaCalcolo.fmt(riga.mqRagguagliati)} mq"
                textSize = 14f
                setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                setTextColor(Color.parseColor("#2E5339"))
            })
            if (v in vociManuali) intestazione.addView(ImageButton(this).apply {
                setImageResource(android.R.drawable.ic_menu_delete)
                background = null
                contentDescription = "Rimuovi ${v.nome}"
                setOnClickListener { voci.remove(v); vociManuali.remove(v); ricalcola() }
            })
            contenitore.addView(intestazione)
            contenitore.addView(TextView(this).apply {
                text = "▾ ${v.categoria.etichetta} · ${riga.regola}"
                textSize = 13f
                setTextColor(Color.parseColor("#2E5339"))
                setPadding(0, dp(4), 0, 0)
                setOnClickListener { scegliCategoria(v) }
            })
            contenitore.setOnClickListener { scegliCategoria(v) }
            lista.addView(contenitore)
        }
    }

    private fun scegliCategoria(v: PeriziaCalcolo.Voce) {
        val categorie = PeriziaCalcolo.Categoria.values()
        val dialog = android.app.AlertDialog.Builder(this, R.style.Theme_SchedaCliente_Dialog)
            .setTitle(v.nome)
            .setSingleChoiceItems(categorie.map { it.etichetta }.toTypedArray(), categorie.indexOf(v.categoria)) { d, scelta ->
                val nuova = v.copy(categoria = categorie[scelta])
                val i = voci.indexOf(v)
                if (i >= 0) voci[i] = nuova
                if (vociManuali.remove(v)) vociManuali.add(nuova)
                d.dismiss()
                ricalcola()
            }
            .setNegativeButton("Annulla", null)
            .show()
        dialog.rendiPulsantiLeggibili(this)
    }

    private fun aggiungiVoce() {
        val densita = resources.displayMetrics.density
        val nome = EditText(this).apply { hint = "Nome (es. Giardino)" }
        val mq = EditText(this).apply { hint = "Superficie (mq)"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
        val contenitore = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (20 * densita).toInt()
            setPadding(p, (8 * densita).toInt(), p, 0)
            addView(nome); addView(mq)
        }
        val dialog = android.app.AlertDialog.Builder(this, R.style.Theme_SchedaCliente_Dialog)
            .setTitle("Aggiungi superficie")
            .setView(contenitore)
            .setPositiveButton("Aggiungi") { _, _ ->
                val n = nome.text?.toString()?.trim().orEmpty().ifBlank { "Superficie ${voci.size + 1}" }
                val m = numero(mq.text?.toString())
                if (m == null || m <= 0) {
                    Toast.makeText(this, "Superficie non valida", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val voce = PeriziaCalcolo.Voce(n, m, PeriziaCalcolo.categoriaDaNome(n))
                voci += voce
                vociManuali += voce
                ricalcola()
            }
            .setNegativeButton("Annulla", null)
            .show()
        dialog.rendiPulsantiLeggibili(this)
    }

    // ---------------------------------------------------------------------
    // OMI
    // ---------------------------------------------------------------------

    private fun stato(testo: String, errore: Boolean = false) {
        binding.tvStatoOmi.text = testo
        binding.tvStatoOmi.setTextColor(if (errore) Color.parseColor("#B3261E") else Color.parseColor("#3A4540"))
    }

    private fun <T> inBackground(lavoro: () -> T, fatto: (T) -> Unit) {
        Thread {
            try {
                val r = lavoro()
                runOnUiThread { if (!isFinishing && !isDestroyed) fatto(r) }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) stato("⚠️ ${e.message ?: "Servizio OMI non raggiungibile"}. Puoi inserire il valore €/mq a mano.", true)
                }
            }
        }.start()
    }

    private fun imposta(spinner: Spinner, voci: List<String>, selezione: Int = 0, alCambio: (Int) -> Unit) {
        spinner.onItemSelectedListener = null
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, voci)
        spinner.setSelection(selezione, false)
        spinner.post {
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = alCambio(position)
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
    }

    private fun caricaProvince() {
        stato("Collegamento al servizio OMI dell'Agenzia delle Entrate…")
        inBackground({ OmiConsultazione.province() }) { elenco ->
            province = elenco
            val ar = elenco.indexOfFirst { it.codice == "AR" }.coerceAtLeast(0)
            imposta(binding.spinProvincia, elenco.map { "${it.descrizione} (${it.codice})" }, ar) { caricaComuni() }
            caricaComuni()
        }
    }

    private fun caricaComuni() {
        val pr = province.getOrNull(binding.spinProvincia.selectedItemPosition)?.codice ?: return
        stato("Carico comuni e semestri…")
        azzeraZone()
        inBackground({ OmiConsultazione.semestriEComuni(pr) }) { (sem, com) ->
            semestri = sem
            comuni = com
            imposta(binding.spinSemestre, sem.map { "Semestre ${it.descrizione}" }) { caricaZone() }
            imposta(binding.spinComune, listOf("— scegli il comune —") + com.map { it.descrizione }) { caricaZone() }
            stato("Scegli il comune: ${sem.firstOrNull()?.let { "dati più recenti: semestre ${it.descrizione}" } ?: ""}")
        }
    }

    private fun azzeraZone() {
        zone = emptyList(); zonaScelta = null
        imposta(binding.spinZona, emptyList()) {}
        binding.btnMostraOmi.isEnabled = false
        binding.gruppoQuotazioni.removeAllViews()
        quotazioni = emptyList()
    }

    private fun caricaZone() {
        val pr = province.getOrNull(binding.spinProvincia.selectedItemPosition)?.codice ?: return
        val sem = semestri.getOrNull(binding.spinSemestre.selectedItemPosition)?.codice ?: return
        val indiceComune = binding.spinComune.selectedItemPosition - 1
        val co = comuni.getOrNull(indiceComune)?.codice
        azzeraZone()
        if (co == null) return
        stato("Carico le zone OMI di ${comuni[indiceComune].descrizione}…")
        inBackground({ OmiConsultazione.zone(pr, sem, co) }) { elenco ->
            zone = elenco
            imposta(binding.spinZona, elenco.map { it.toString() }) { i -> zonaScelta = zone.getOrNull(i); caricaQuotazioni() }
            zonaScelta = elenco.firstOrNull()
            binding.btnMostraOmi.isEnabled = true
            stato("${elenco.size} zone OMI: scegli quella dell'immobile (vedi anche la mappa GEOPOI dell'Agenzia).")
        }
    }

    private fun caricaQuotazioni() {
        val pr = province.getOrNull(binding.spinProvincia.selectedItemPosition)?.codice ?: return
        val sem = semestri.getOrNull(binding.spinSemestre.selectedItemPosition) ?: return
        val co = comuni.getOrNull(binding.spinComune.selectedItemPosition - 1) ?: return
        val zona = zonaScelta ?: zone.getOrNull(binding.spinZona.selectedItemPosition) ?: return
        val dest = destinazione()
        stato("Carico le quotazioni ${dest.utilizzoOmi.lowercase()} della zona ${zona.codice}…")
        inBackground({ OmiConsultazione.quotazioni(pr, sem.codice, co.codice, zona, dest.utilizzoOmi) }) { elenco ->
            quotazioni = elenco
            mostraQuotazioni(dest)
            stato(
                if (elenco.isEmpty()) "Nessuna quotazione ${dest.utilizzoOmi.lowercase()} per questa zona e semestre."
                else "Quotazioni OMI · ${co.descrizione} · zona ${zona.codice} · semestre ${sem.descrizione} (€/mq). Scegli la riga."
            )
        }
    }

    private fun mostraQuotazioni(dest: PeriziaCalcolo.Destinazione) {
        val gruppo = binding.gruppoQuotazioni
        gruppo.setOnCheckedChangeListener(null)
        gruppo.removeAllViews()
        quotazioneScelta = null
        // Preselezione: prima riga della tipologia adatta alla destinazione (es. "Abitazioni civili" NORMALE).
        val preferita = dest.tipologieOmi.firstNotNullOfOrNull { t ->
            quotazioni.firstOrNull { it.tipologia.equals(t, true) && it.stato.equals("NORMALE", true) }
                ?: quotazioni.firstOrNull { it.tipologia.equals(t, true) }
        }
        quotazioni.forEachIndexed { i, q ->
            gruppo.addView(RadioButton(this).apply {
                id = View.generateViewId()
                tag = i
                text = "${q.tipologia} · ${q.stato.lowercase().replaceFirstChar { it.uppercase() }}: " +
                    "${PeriziaCalcolo.fmt(q.minimo)} – ${PeriziaCalcolo.fmt(q.massimo)} €/mq" +
                    if (q.superficie.equals("N", true)) " (superficie netta)" else " (superficie lorda)"
                textSize = 14f
                setTextColor(Color.parseColor("#1E2A22"))
                isChecked = q == preferita
            })
        }
        quotazioneScelta = preferita
        gruppo.setOnCheckedChangeListener { g, idScelto ->
            val indice = g.findViewById<View>(idScelto)?.tag as? Int ?: return@setOnCheckedChangeListener
            quotazioneScelta = quotazioni.getOrNull(indice)
            applicaQuotazione()
        }
        applicaQuotazione()
    }

    private fun applicaQuotazione() {
        val q = quotazioneScelta ?: return
        val valore = when (binding.toggleValore.checkedButtonId) {
            R.id.btnValMin -> q.minimo
            R.id.btnValMax -> q.massimo
            else -> q.medio
        }
        binding.etValoreUnitario.setText(PeriziaCalcolo.fmt(valore))
        if (q.superficie.equals("N", true)) {
            Toast.makeText(this, "Attenzione: questa quotazione è riferita alla superficie NETTA", Toast.LENGTH_LONG).show()
        }
    }

    // ---------------------------------------------------------------------
    // Correttivi e valore
    // ---------------------------------------------------------------------

    private fun disegnaCorrettivi() {
        val lista = binding.listaCorrettivi
        lista.removeAllViews()
        correttivi.forEach { c ->
            val riga = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            riga.addView(EditText(this).apply {
                hint = "Motivo (es. stato manutentivo)"
                setText(c.descrizione)
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addTextChangedListener(osservatore { c.descrizione = it; aggiornaValore() })
            })
            riga.addView(EditText(this).apply {
                hint = "±"
                setText(if (c.percentuale == 0.0) "" else PeriziaCalcolo.fmt(c.percentuale))
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                textSize = 14f
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(dp(90), LinearLayout.LayoutParams.WRAP_CONTENT)
                addTextChangedListener(osservatore { c.percentuale = numero(it) ?: 0.0; aggiornaValore() })
            })
            riga.addView(TextView(this).apply { text = "%"; textSize = 14f; setPadding(dp(2), 0, dp(4), 0) })
            riga.addView(ImageButton(this).apply {
                setImageResource(android.R.drawable.ic_menu_delete)
                background = null
                contentDescription = "Rimuovi"
                setOnClickListener { correttivi.remove(c); disegnaCorrettivi(); aggiornaValore() }
            })
            lista.addView(riga)
        }
    }

    private fun aggiornaValore() {
        val r = risultato ?: return
        val unitario = numero(binding.etValoreUnitario.text?.toString())
        val percentuali = correttivi.map { it.percentuale }.filter { it != 0.0 }
        val fattore = PeriziaCalcolo.fattoreCorrettivi(percentuali)
        if (unitario == null || unitario <= 0 || r.superficieArrotondata <= 0) {
            binding.tvCalcoloValore.text = "Servono la superficie commerciale e il valore unitario (€/mq)."
            binding.tvValoreMercato.text = "—"
            binding.tvValoreArrotondato.text = ""
            return
        }
        val valore = PeriziaCalcolo.valoreDiMercato(r.superficieArrotondata.toDouble(), unitario, percentuali)
        binding.tvCalcoloValore.text = buildString {
            append("${r.superficieArrotondata} mq × ${PeriziaCalcolo.fmt(unitario)} €/mq")
            if (percentuali.isNotEmpty()) {
                append(" × ${String.format(Locale.ITALY, "%.4f", fattore)}")
                append("\n(correttivi: ${percentuali.joinToString(" · ") { (if (it > 0) "+" else "") + PeriziaCalcolo.fmt(it) + "%" }}")
                append(" = ${if (fattore >= 1) "+" else ""}${PeriziaCalcolo.fmt(((fattore - 1) * 100 * 100).roundToLong() / 100.0)}% complessivo)")
            }
        }
        binding.tvValoreMercato.text = formatoEuro.format(valore)
        binding.tvValoreArrotondato.text = "Arrotondato: ${formatoEuro.format((valore / 1000.0).roundToLong() * 1000.0)}"
    }

    /** "Condividi": si sceglie tra il riepilogo in testo semplice e la perizia completa in Word. */
    private fun condividi() {
        if (risultato == null) return
        val voci = arrayOf(
            "📤  Riepilogo in testo semplice\nSuperfici, quotazione e valore, da inviare come messaggio",
            "📄  Perizia completa in Word\nDocumento professionale: aggiungi la tua richiesta dettata e i documenti (visure, foto, contratti…) che l'AI legge per descrivere l'immobile, provenienze e interventi edilizi"
        )
        val dialog = android.app.AlertDialog.Builder(this, R.style.Theme_SchedaCliente_Dialog)
            .setTitle("Come vuoi condividere la perizia?")
            .setItems(voci) { _, quale -> if (quale == 0) condividiTesto() else apriPeriziaCompleta() }
            .setNegativeButton("Annulla", null)
            .show()
        dialog.rendiPulsantiLeggibili(this)
    }

    private fun apriPeriziaCompleta() {
        val dati = datiPerizia() ?: return
        startActivity(Intent(this, PeriziaCompletaActivity::class.java).putExtra(PeriziaCompletaActivity.EXTRA_DATI, dati.toJson()))
    }

    /** I numeri della perizia come li ha calcolati l'app, per la perizia completa. */
    internal fun datiPerizia(): DatiPerizia? {
        val r = risultato ?: return null
        val unitario = numero(binding.etValoreUnitario.text?.toString())?.takeIf { it > 0 }
        val attivi = correttivi.filter { it.percentuale != 0.0 }
        val valore = if (unitario != null && r.superficieArrotondata > 0)
            PeriziaCalcolo.valoreDiMercato(r.superficieArrotondata.toDouble(), unitario, attivi.map { it.percentuale }) else null
        val q = quotazioneScelta
        val zona = zonaScelta
        val comune = comuni.getOrNull(binding.spinComune.selectedItemPosition - 1)?.descrizione
        val sem = semestri.getOrNull(binding.spinSemestre.selectedItemPosition)?.descrizione
        val omi = if (q != null) listOfNotNull(
            comune, zona?.let { "zona ${it.codice} (${it.fascia})" }, sem?.let { "semestre $it" }
        ).joinToString(", ") else ""
        return DatiPerizia(
            destinazione = destinazione().etichetta,
            righe = r.righe.map { DatiPerizia.Riga(it.voce.nome, it.voce.categoria.etichetta, it.mqLordi, it.regola, it.mqRagguagliati) },
            superficieCommerciale = r.superficieCommerciale,
            superficieArrotondata = r.superficieArrotondata,
            note = r.note,
            omiDescrizione = omi,
            omiTipologia = q?.let { "${it.tipologia} ${it.stato}".trim() }.orEmpty(),
            omiMinimo = q?.minimo, omiMassimo = q?.massimo,
            valoreUnitario = unitario,
            correttivi = attivi.map { DatiPerizia.Correttivo(it.descrizione, it.percentuale) },
            valoreMercato = valore,
            valoreArrotondato = valore?.let { (it / 1000.0).roundToLong() * 1000.0 }
        )
    }

    private fun condividiTesto() {
        val r = risultato ?: return
        val testo = buildString {
            appendLine("PERIZIA UNITÀ — ${destinazione().etichetta}")
            appendLine()
            appendLine("Ragguaglio superfici (Manuale OMI, All. 3 / DPR 138/1998):")
            r.righe.forEach { appendLine("- ${it.voce.nome}: ${PeriziaCalcolo.fmt(it.mqLordi)} mq × [${it.voce.categoria.etichetta}: ${it.regola}] = ${PeriziaCalcolo.fmt(it.mqRagguagliati)} mq") }
            appendLine("Superficie commerciale: ${r.superficieArrotondata} mq")
            r.note.forEach { appendLine("Nota: $it") }
            appendLine()
            quotazioneScelta?.let { q ->
                val zona = zonaScelta
                val comune = comuni.getOrNull(binding.spinComune.selectedItemPosition - 1)?.descrizione
                val sem = semestri.getOrNull(binding.spinSemestre.selectedItemPosition)?.descrizione
                appendLine("Quotazione OMI: $comune, zona ${zona?.codice} (${zona?.fascia}), semestre $sem")
                appendLine("${q.tipologia} ${q.stato}: ${PeriziaCalcolo.fmt(q.minimo)} – ${PeriziaCalcolo.fmt(q.massimo)} €/mq")
            }
            appendLine("Valore unitario adottato: ${binding.etValoreUnitario.text} €/mq")
            correttivi.filter { it.percentuale != 0.0 }.forEach {
                appendLine("Correttivo: ${it.descrizione.ifBlank { "—" }} ${if (it.percentuale > 0) "+" else ""}${PeriziaCalcolo.fmt(it.percentuale)}%")
            }
            appendLine()
            appendLine(binding.tvCalcoloValore.text)
            appendLine("VALORE DI MERCATO: ${binding.tvValoreMercato.text} (${binding.tvValoreArrotondato.text})")
        }
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, testo)
        }, "Condividi perizia"))
    }

    // ---------------------------------------------------------------------

    private fun osservatore(azione: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) = azione(s?.toString().orEmpty())
    }

    /** "1.250,5" o "1250,5" (formato italiano) oppure "1250.5": il punto è separatore delle migliaia solo se c'è anche la virgola. */
    private fun numero(s: String?): Double? {
        val t = s?.trim()?.replace(" ", "") ?: return null
        if (t.isEmpty()) return null
        return (if (t.contains(',')) t.replace(".", "").replace(',', '.') else t).toDoubleOrNull()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun sfondo(colore: Int, bordo: Int) = android.graphics.drawable.GradientDrawable().apply {
        setColor(colore)
        cornerRadius = 12 * resources.displayMetrics.density
        setStroke(dp(1), bordo)
    }
}
