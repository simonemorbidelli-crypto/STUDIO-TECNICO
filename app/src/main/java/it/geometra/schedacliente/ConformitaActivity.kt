package it.geometra.schedacliente

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import it.geometra.schedacliente.ConformitaCalcolo.Fonte
import it.geometra.schedacliente.ConformitaCalcolo.Vano
import it.geometra.schedacliente.databinding.ActivityConformitaBinding
import java.util.Locale

/**
 * v3.84 — Verifica di conformità urbanistica e catastale assistita.
 * Confronto vano per vano tra planimetria catastale, titolo edilizio e stato di fatto, e tra atto e visura.
 * Nessuna dichiarazione di conformità: solo scostamenti e soglie, da valutare a cura del professionista.
 */
class ConformitaActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CLIENTE_ID = "cliente_id"
    }

    private lateinit var binding: ActivityConformitaBinding
    private val contenitoriVani = HashMap<Fonte, LinearLayout>()
    private val righeCampi = ArrayList<Triple<ConformitaCalcolo.Campo, EditText, EditText>>()
    private var clienti: List<Cliente> = emptyList()
    private var chiaveCorrente = ConformitaStore.chiaveCliente(null)
    private var ultimoTesto = ""
    private var ultimoTestoCampi = ""
    private lateinit var store: ConformitaStore
    private var spinnerPronto = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConformitaBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }
        store = ConformitaStore(this)

        clienti = ClienteStore(this).caricaTutti().sortedBy { it.nomeCompleto().lowercase() }
        binding.spinCliente.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Nessun cliente (verifica generale)") + clienti.map { it.nomeCompleto() })
        val idIniziale = intent.getLongExtra(EXTRA_CLIENTE_ID, -1L).takeIf { it != -1L }
        val posizione = clienti.indexOfFirst { it.id == idIniziale } + 1
        chiaveCorrente = ConformitaStore.chiaveCliente(clienti.getOrNull(posizione - 1)?.id)
        binding.spinCliente.setSelection(posizione)
        binding.spinCliente.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (!spinnerPronto) { spinnerPronto = true; return }
                salvaStato()
                chiaveCorrente = ConformitaStore.chiaveCliente(clienti.getOrNull(pos - 1)?.id)
                applica(store.carica(chiaveCorrente) ?: DatiConformita())
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        val nomiFonti = Fonte.values().map { it.etichetta }
        binding.spinRiferimento.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, nomiFonti)
        binding.spinConfrontata.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, nomiFonti)
        binding.spinRiferimento.setSelection(Fonte.TITOLO.ordinal)
        binding.spinConfrontata.setSelection(Fonte.RILIEVO.ordinal)

        costruisciSezioniFonti()
        applica(store.carica(chiaveCorrente) ?: DatiConformita())

        binding.btnConfronta.setOnClickListener { confronta() }
        binding.btnConfrontaCampi.setOnClickListener { confrontaCampi() }
        binding.btnCondividiRisultato.setOnClickListener { condividi("Verifica di conformità", ultimoTesto) }
        binding.btnCondividiCampi.setOnClickListener { condividi("Confronto atto/visura", ultimoTestoCampi) }
    }

    override fun onPause() {
        super.onPause()
        salvaStato()
    }

    // ------------------------------------------------------------------
    // Costruzione della schermata
    // ------------------------------------------------------------------

    private fun costruisciSezioniFonti() {
        val dp = resources.displayMetrics.density
        Fonte.values().forEach { fonte ->
            val titolo = TextView(this).apply {
                text = fonte.etichetta
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor("#2B2B2B"))
                setPadding(0, (14 * dp).toInt(), 0, 0)
            }
            val intestazione = TextView(this).apply {
                text = "Vano · mq · altezza (m) · ab. = abitabile (min. 2,70 m)"
                textSize = 11f
                setTextColor(Color.parseColor("#6B6B6B"))
            }
            val contenitore = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            contenitoriVani[fonte] = contenitore
            val aggiungi = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "➕ Aggiungi vano"
                setOnClickListener { aggiungiRigaVano(fonte, Vano("")) }
            }
            val importa = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "📐 Importa i locali da una planimetria salvata"
                setOnClickListener { importaDaPlanimetria(fonte) }
            }
            binding.sezioniFonti.addView(titolo)
            binding.sezioniFonti.addView(intestazione)
            binding.sezioniFonti.addView(contenitore)
            binding.sezioniFonti.addView(aggiungi)
            binding.sezioniFonti.addView(importa)
        }
    }

    private fun aggiungiRigaVano(fonte: Fonte, v: Vano) {
        val riga = LayoutInflater.from(this).inflate(R.layout.item_vano_conformita, contenitoriVani[fonte], false)
        riga.findViewById<EditText>(R.id.etNomeVano).setText(v.nome)
        riga.findViewById<EditText>(R.id.etAreaVano).setText(v.areaM2?.let { numeroPerCampo(it) } ?: "")
        riga.findViewById<EditText>(R.id.etAltezzaVano).setText(v.altezzaM?.let { numeroPerCampo(it) } ?: "")
        riga.findViewById<CheckBox>(R.id.cbAbitabile).isChecked = v.abitabile
        riga.findViewById<View>(R.id.btnTogliVano).setOnClickListener { contenitoriVani[fonte]?.removeView(riga) }
        contenitoriVani[fonte]?.addView(riga)
    }

    private fun numeroPerCampo(x: Double) = String.format(Locale.ITALY, "%.2f", x).trimEnd('0').trimEnd(',')

    private fun numero(s: CharSequence?): Double? = s?.toString()?.trim()?.replace(',', '.')?.toDoubleOrNull()

    private fun leggiVani(fonte: Fonte): List<Vano> {
        val c = contenitoriVani[fonte] ?: return emptyList()
        return (0 until c.childCount).mapNotNull { i ->
            val r = c.getChildAt(i)
            val nome = r.findViewById<EditText>(R.id.etNomeVano).text.toString().trim()
            val area = numero(r.findViewById<EditText>(R.id.etAreaVano).text)
            val h = numero(r.findViewById<EditText>(R.id.etAltezzaVano).text)
            if (nome.isEmpty() && area == null && h == null) null
            else Vano(nome.ifEmpty { "Vano ${i + 1}" }, area, h, r.findViewById<CheckBox>(R.id.cbAbitabile).isChecked)
        }
    }

    private fun leggiDati(): DatiConformita = DatiConformita(
        vani = Fonte.values().associateWith { leggiVani(it) },
        lavoriEntro24052024 = binding.cbLavori.isChecked,
        superficieUtileAssentitaMq = numero(binding.etSuperficieUtile.text),
        campi = righeCampi.map { (c, a, v) -> c.copy(atto = a.text.toString().trim(), visura = v.text.toString().trim()) }
    )

    private fun applica(d: DatiConformita) {
        binding.cbLavori.isChecked = d.lavoriEntro24052024
        binding.etSuperficieUtile.setText(d.superficieUtileAssentitaMq?.let { numeroPerCampo(it) } ?: "")
        Fonte.values().forEach { f ->
            contenitoriVani[f]?.removeAllViews()
            d.vani[f].orEmpty().forEach { aggiungiRigaVano(f, it) }
            if (d.vani[f].isNullOrEmpty()) aggiungiRigaVano(f, Vano(""))
        }
        binding.contenitoreCampi.removeAllViews()
        righeCampi.clear()
        d.campi.forEach { c ->
            val riga = LayoutInflater.from(this).inflate(R.layout.item_campo_conformita, binding.contenitoreCampi, false)
            riga.findViewById<TextView>(R.id.tvEtichettaCampo).text = c.etichetta
            val a = riga.findViewById<EditText>(R.id.etAtto).apply { setText(c.atto) }
            val v = riga.findViewById<EditText>(R.id.etVisura).apply { setText(c.visura) }
            if (c.tipo == ConformitaCalcolo.TipoCampo.PERSONE) {
                a.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                v.inputType = a.inputType
            }
            binding.contenitoreCampi.addView(riga)
            righeCampi.add(Triple(c, a, v))
        }
        binding.tvRisultato.visibility = View.GONE; binding.btnCondividiRisultato.visibility = View.GONE
        binding.tvRisultatoCampi.visibility = View.GONE; binding.btnCondividiCampi.visibility = View.GONE
    }

    private fun salvaStato() {
        if (righeCampi.isNotEmpty()) store.salva(chiaveCorrente, leggiDati())
    }

    // ------------------------------------------------------------------
    // Importazione dai progetti di planimetria
    // ------------------------------------------------------------------

    private fun importaDaPlanimetria(fonte: Fonte) {
        val progetti = ProgettoStore.elenca(this)
        if (progetti.isEmpty()) {
            Toast.makeText(this, "Nessuna planimetria salvata: tracciala prima in \"Aree locali da planimetria\"", Toast.LENGTH_LONG).show()
            return
        }
        val voci = progetti.map { "${it.nome} — ${it.numeroLocali} locali, ${String.format(Locale.ITALY, "%.1f", it.superficieTotale)} mq" }.toTypedArray()
        val d = AlertDialog.Builder(this, R.style.Theme_SchedaCliente_Dialog)
            .setTitle("Importa in: ${fonte.etichetta}")
            .setItems(voci) { _, i ->
                val id = progetti[i].id
                Thread {
                    val p = ProgettoStore.carica(this, id)
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        if (p == null) { Toast.makeText(this, "Non riesco a leggere quella planimetria", Toast.LENGTH_LONG).show(); return@runOnUiThread }
                        // Sostituisce le righe ancora vuote, poi aggiunge i locali.
                        contenitoriVani[fonte]?.let { c ->
                            for (k in c.childCount - 1 downTo 0) {
                                val r = c.getChildAt(k)
                                if (r.findViewById<EditText>(R.id.etNomeVano).text.isBlank() && r.findViewById<EditText>(R.id.etAreaVano).text.isBlank()) c.removeViewAt(k)
                            }
                        }
                        p.locali.forEach { aggiungiRigaVano(fonte, Vano(it.nome, it.areaM2)) }
                        Toast.makeText(this, "Importati ${p.locali.size} locali: aggiungi le altezze se servono", Toast.LENGTH_LONG).show()
                    }
                }.start()
            }
            .setNegativeButton("Annulla", null)
            .show()
        d.rendiPulsantiLeggibili(this)
    }

    // ------------------------------------------------------------------
    // Confronti
    // ------------------------------------------------------------------

    private fun confronta() {
        val d = leggiDati()
        val rif = Fonte.values()[binding.spinRiferimento.selectedItemPosition]
        val conf = Fonte.values()[binding.spinConfrontata.selectedItemPosition]
        if (rif == conf) { Toast.makeText(this, "Scegli due fonti diverse", Toast.LENGTH_SHORT).show(); return }
        val a = d.vani[rif].orEmpty(); val b = d.vani[conf].orEmpty()
        if (a.isEmpty() || b.isEmpty()) { Toast.makeText(this, "Inserisci i vani di entrambe le fonti", Toast.LENGTH_LONG).show(); return }
        val r = ConformitaCalcolo.confronta(rif, a, conf, b, d.lavoriEntro24052024, d.superficieUtileAssentitaMq)
        val titolo = "VERIFICA DI CONFORMITÀ" + (clienti.firstOrNull { ConformitaStore.chiaveCliente(it.id) == chiaveCorrente }?.let { " — ${it.nomeCompleto()}" } ?: "")
        ultimoTesto = ConformitaCalcolo.riepilogo(titolo, r)
        mostra(binding.tvRisultato, binding.btnCondividiRisultato, ultimoTesto)
        salvaStato()
    }

    private fun confrontaCampi() {
        val righe = ConformitaCalcolo.confrontaCampi(leggiDati().campi)
        ultimoTestoCampi = ConformitaCalcolo.riepilogoCampi(righe)
        mostra(binding.tvRisultatoCampi, binding.btnCondividiCampi, ultimoTestoCampi)
        salvaStato()
    }

    private fun mostra(tv: TextView, btn: View, testo: String) {
        val s = SpannableString(testo)
        var pos = 0
        testo.split('\n').forEach { riga ->
            val colore = when {
                riga.contains("oltre la soglia") || riga.contains(": DIVERSO") -> "#B3261E"
                riga.contains("entro la soglia") -> "#2E5339"
                else -> null
            }
            if (colore != null && riga.isNotEmpty()) s.setSpan(ForegroundColorSpan(Color.parseColor(colore)), pos, pos + riga.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            pos += riga.length + 1
        }
        tv.text = s
        tv.visibility = View.VISIBLE
        btn.visibility = View.VISIBLE
        tv.post { tv.requestRectangleOnScreen(android.graphics.Rect(0, 0, tv.width, tv.height.coerceAtMost(600))) }
    }

    private fun condividi(oggetto: String, testo: String) {
        if (testo.isBlank()) return
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, oggetto)
            putExtra(Intent.EXTRA_TEXT, testo)
        }, "Condividi"))
    }
}
