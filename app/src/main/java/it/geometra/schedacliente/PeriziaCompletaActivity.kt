package it.geometra.schedacliente

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import it.geometra.schedacliente.databinding.ActivityPeriziaCompletaBinding
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v3.82 — Perizia di stima completa e professionale in Word.
 *
 * Parte dai dati calcolati dalla "Perizia unità" ([EXTRA_DATI]), permette di dettare una
 * richiesta e di allegare documenti (visure, foto, atti di acquisto, titoli edilizi…) dal telefono,
 * dal NAS o da Mega. L'AI ricava descrizione, provenienze, storia edilizia; i numeri restano
 * quelli dell'app. I file che l'AI non può leggere sono scartati e segnalati con il consiglio.
 */
class PeriziaCompletaActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_DATI = "dati_perizia"
        private const val MAX_ALLEGATI = 30
    }

    private class Allegato(val nome: String, val file: File?, val motivo: String)

    private lateinit var binding: ActivityPeriziaCompletaBinding
    private var dati: DatiPerizia? = null
    private var clienti: List<Cliente> = emptyList()
    private var cliente: Cliente? = null
    private val allegati = ArrayList<Allegato>()
    private var documentoGenerato: File? = null
    private var inGenerazione = false

    private val launcherDettatura = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            val frase = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
            if (frase.isNotBlank()) {
                val attuale = binding.etRichiesta.text?.toString().orEmpty().trimEnd()
                binding.etRichiesta.setText(if (attuale.isEmpty()) frase else "$attuale $frase")
                binding.etRichiesta.setSelection(binding.etRichiesta.text?.length ?: 0)
            }
        }
    }

    private val launcherTelefono = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) copiaDalTelefono(uris)
    }

    private val launcherArchivio = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val json = r.data?.getStringExtra(ParereMegaActivity.EXTRA_RISULTATO_FILE)
        if (r.resultCode == Activity.RESULT_OK && json != null) aggiungiConsegna(json)
    }

    private val launcherSalvaCopia = registerForActivityResult(
        ActivityResultContracts.CreateDocument(EsportatoreDocx.MIME)
    ) { uri -> if (uri != null) salvaCopiaIn(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPeriziaCompletaBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }
        NavigazioneIndietro.installa(this)

        dati = intent.getStringExtra(EXTRA_DATI)?.let { try { DatiPerizia.daJson(it) } catch (e: Exception) { null } }
        binding.tvDatiCalcolati.text = dati?.let { riassunto(it) }
            ?: "Nessun calcolo disponibile: la perizia sarà scritta dalla tua richiesta e dai documenti."

        clienti = ClienteStore(this).caricaTutti().sortedBy { it.nomeCompleto().lowercase() }
        val voci = listOf("Nessun cliente (perizia senza scheda)") + clienti.map { it.nomeCompleto() }
        binding.spinCliente.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, voci)
        binding.spinCliente.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                cliente = clienti.getOrNull(pos - 1)
            }
            override fun onNothingSelected(p: AdapterView<*>?) { cliente = null }
        }

        binding.btnDetta.setOnClickListener { dettatura() }
        binding.btnAllegaTelefono.setOnClickListener { launcherTelefono.launch(arrayOf("*/*")) }
        binding.btnAllegaNas.setOnClickListener { apriArchivio(ParereMegaActivity.ORIGINE_NAS) }
        binding.btnAllegaMega.setOnClickListener { apriArchivio(ParereMegaActivity.ORIGINE_MEGA) }
        binding.btnGeneraPerizia.setOnClickListener { genera() }
        binding.btnApri.setOnClickListener { apri() }
        binding.btnCondividi.setOnClickListener { condividi() }
        binding.btnSalvaCopia.setOnClickListener { documentoGenerato?.let { launcherSalvaCopia.launch(it.name) } }
        disegnaAllegati()
    }

    private fun riassunto(d: DatiPerizia): String = buildString {
        append("Destinazione: ${d.destinazione.ifBlank { "—" }}")
        if (d.righe.isNotEmpty()) append("\nSuperficie commerciale: ${PeriziaCalcolo.fmt(d.superficieCommerciale)} mq (arrotondata ${d.superficieArrotondata} mq)")
        d.valoreUnitario?.let { append("\nValore unitario: ${PeriziaCalcolo.fmt(it)} €/mq") }
        d.valoreArrotondato?.let { append("\nValore arrotondato: ${DatiPerizia.euro(it)}") }
        append("\n\nQuesti numeri finiranno nel Word così come sono, senza essere modificati dall'AI.")
    }

    // ---------------------------------------------------------------------
    // Richiesta dettata
    // ---------------------------------------------------------------------

    private fun dettatura() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Detta la richiesta per la perizia")
        }
        try {
            launcherDettatura.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Dettatura non disponibile su questo telefono: scrivi la richiesta con la tastiera (microfono)", Toast.LENGTH_LONG).show()
        }
    }

    // ---------------------------------------------------------------------
    // Allegati
    // ---------------------------------------------------------------------

    private fun cartellaAllegati(): File = File(cacheDir, ParereMegaActivity.CARTELLA_ALLEGATI).apply { mkdirs() }

    private fun copiaDalTelefono(uris: List<Uri>) {
        Thread {
            val nuovi = ArrayList<Allegato>()
            for (uri in uris) {
                val nome = nomeDa(uri)
                try {
                    if (!PreparatoreDocumenti.tipoDi(nome).supportato) {
                        nuovi += Allegato(nome, null, PreparatoreDocumenti.motivoNonSupportato(nome)); continue
                    }
                    val dest = File(cartellaAllegati(), "${IdUnivoci.nuovo()}_${nome.replace(Regex("[\\\\/:*?\"<>|]"), "_")}")
                    val byte = contentResolver.openInputStream(uri)?.use { inp ->
                        dest.outputStream().use { out -> inp.copyTo(out) }
                    } ?: -1L
                    if (byte < 0 || byte > MegaCartellaPubblica.DIMENSIONE_MAX_FILE) {
                        dest.delete()
                        nuovi += Allegato(nome, null, if (byte < 0) "non sono riuscito a leggerlo" else
                            "troppo grande (oltre ${MegaCartellaPubblica.DIMENSIONE_MAX_FILE / 1024 / 1024} MB): comprimilo o dividilo")
                    } else nuovi += Allegato(nome, dest, "")
                } catch (e: Exception) {
                    nuovi += Allegato(nome, null, "non sono riuscito a leggerlo (${e.message ?: "errore"})")
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                allegati += nuovi
                disegnaAllegati()
            }
        }.start()
    }

    private fun nomeDa(uri: Uri): String = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (e: Exception) { null } ?: (uri.lastPathSegment ?: "documento")

    private fun apriArchivio(origine: String) {
        val intent = Intent(this, ParereMegaActivity::class.java)
            .putExtra(ParereMegaActivity.EXTRA_SOLO_SELEZIONE, true)
            .putExtra(ParereMegaActivity.EXTRA_ORIGINE, origine)
        val c = cliente
        if (origine == ParereMegaActivity.ORIGINE_MEGA) {
            if (c == null || c.linkMega.isBlank()) {
                Toast.makeText(this, "Per Mega scegli un cliente la cui scheda ha il link Mega", Toast.LENGTH_LONG).show()
                return
            }
        }
        if (c != null) intent.putExtra(ParereMegaActivity.EXTRA_CLIENTE_ID, c.id)
        launcherArchivio.launch(intent)
    }

    private fun aggiungiConsegna(json: String) {
        try {
            val a = JSONArray(json)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val percorso = o.optString("percorso")
                allegati += Allegato(o.optString("nome"), percorso.takeIf { it.isNotEmpty() }?.let { File(it) }, o.optString("motivo"))
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Non sono riuscito a leggere i file scelti", Toast.LENGTH_LONG).show()
        }
        disegnaAllegati()
    }

    private fun disegnaAllegati() {
        val lista = binding.listaAllegati
        lista.removeAllViews()
        val dp = resources.displayMetrics.density
        allegati.toList().forEach { a ->
            val riga = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
            }
            val leggibile = a.file != null
            val testo = TextView(this).apply {
                text = if (leggibile) "${PreparatoreDocumenti.tipoDi(a.nome).icona} ${a.nome}"
                else "⚠️ ${a.nome} — scartato: ${a.motivo}"
                textSize = 14f
                setTextColor(Color.parseColor(if (leggibile) "#1E2A22" else "#B3261E"))
            }
            riga.addView(testo, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val togli = TextView(this).apply {
                text = "✕"
                textSize = 18f
                gravity = Gravity.CENTER
                contentDescription = "Rimuovi ${a.nome}"
                setTextColor(Color.parseColor("#5A5A5A"))
                setOnClickListener {
                    if (inGenerazione) return@setOnClickListener
                    a.file?.delete(); allegati.remove(a); disegnaAllegati()
                }
            }
            riga.addView(togli, LinearLayout.LayoutParams((44 * dp).toInt(), (44 * dp).toInt()))
            lista.addView(riga)
        }
        val buoni = allegati.count { it.file != null }
        val scartati = allegati.size - buoni
        binding.tvRiepilogoAllegati.text = when {
            allegati.isEmpty() -> "Nessun documento: la perizia sarà scritta dai dati e dalla richiesta. Allega visure, atti, titoli edilizi e foto per un risultato più completo."
            else -> "$buoni documenti leggibili" + if (scartati > 0) ", $scartati scartati (vedi sopra)" else ""
        }
    }

    // ---------------------------------------------------------------------
    // Generazione
    // ---------------------------------------------------------------------

    private fun genera() {
        if (inGenerazione) return
        val richiesta = binding.etRichiesta.text?.toString()?.trim().orEmpty()
        val buoni = allegati.filter { it.file != null }
        if (buoni.size > MAX_ALLEGATI) {
            Toast.makeText(this, "Massimo $MAX_ALLEGATI documenti per perizia", Toast.LENGTH_LONG).show(); return
        }
        if (dati == null && richiesta.isEmpty() && buoni.isEmpty()) {
            Toast.makeText(this, "Scrivi o detta una richiesta, oppure allega dei documenti", Toast.LENGTH_LONG).show(); return
        }
        impostaInGenerazione(true)
        binding.cardRisultato.visibility = View.GONE
        val clienteScelto = cliente
        val datiPerizia = dati
        Thread {
            try {
                val blocchi = ArrayList<PreparatoreDocumenti.Blocco>()
                val scartati = ArrayList<String>()
                allegati.filter { it.file == null }.forEach { scartati += "${it.nome}: ${it.motivo}" }
                var pagineTotali = 0
                buoni.forEachIndexed { i, a ->
                    avanzamento("Leggo ${i + 1} di ${buoni.size}: ${a.nome}")
                    try {
                        val blocco = PreparatoreDocumenti.prepara(this, a.nome, a.file!!.readBytes())
                        if (blocco is PreparatoreDocumenti.Blocco.Pdf) {
                            if (pagineTotali + blocco.pagine > GeneratoreParereAi.PAGINE_PDF_MAX) {
                                scartati += "${a.nome}: ha ${blocco.pagine} pagine e superano il massimo di ${GeneratoreParereAi.PAGINE_PDF_MAX} pagine PDF per richiesta: " +
                                    "dividilo e scegli solo le pagine utili"
                                return@forEachIndexed
                            }
                            pagineTotali += blocco.pagine
                        }
                        blocchi += blocco
                    } catch (e: PreparatoreDocumenti.DocumentoNonLeggibile) {
                        scartati += "${a.nome}: ${e.message}"
                    } catch (e: OutOfMemoryError) {
                        scartati += "${a.nome}: troppo pesante per la memoria del telefono: comprimilo o riducilo"
                    } catch (e: Exception) {
                        scartati += "${a.nome}: non leggibile (${e.message ?: "errore"})"
                    }
                }
                val nonLetti = scartati.map { it.substringBefore(": ") }

                avanzamento("L'AI sta scrivendo la perizia… può richiedere 3-6 minuti, tieni aperta questa schermata")
                val risultato = GeneratoreParereAi.generaPerizia(this, clienteScelto, datiPerizia, richiesta, blocchi, nonLetti)

                avanzamento("Creo il documento Word…")
                val destinazione = File(cartellaPareri(), nomeFile(clienteScelto))
                EsportatorePerizia.crea(destinazione, datiPerizia, clienteScelto, risultato.testo, blocchi.map { it.nome }, risultato.troncato, nonLetti)

                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    documentoGenerato = destinazione
                    impostaInGenerazione(false)
                    binding.tvAvanzamento.visibility = View.GONE
                    binding.cardRisultato.visibility = View.VISIBLE
                    binding.tvRisultato.text = buildString {
                        append("✅ Perizia pronta: ${destinazione.name}")
                        if (risultato.troncato) append("\n⚠️ Il testo si è interrotto perché troppo lungo: verifica la parte finale.")
                        if (scartati.isNotEmpty()) {
                            append("\n\n⚠️ ${scartati.size} file scartati (non letti dall'AI):\n• ")
                            append(scartati.joinToString("\n• "))
                        }
                        append("\n\nÈ una bozza: i punti evidenziati in giallo [DA VERIFICARE] / [DA COMPILARE] vanno controllati e i riferimenti normativi verificati prima di firmare.")
                    }
                    binding.cardRisultato.post {
                        binding.cardRisultato.requestRectangleOnScreen(
                            android.graphics.Rect(0, 0, binding.cardRisultato.width, binding.cardRisultato.height)
                        )
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    impostaInGenerazione(false)
                    binding.tvAvanzamento.visibility = View.VISIBLE
                    binding.tvAvanzamento.setTextColor(Color.parseColor("#B3261E"))
                    binding.tvAvanzamento.text = "⚠️ ${e.message ?: "Errore durante la generazione"}"
                }
            }
        }.start()
    }

    private fun impostaInGenerazione(attivo: Boolean) {
        inGenerazione = attivo
        binding.progressGenerazione.visibility = if (attivo) View.VISIBLE else View.GONE
        binding.btnGeneraPerizia.isEnabled = !attivo
        binding.etRichiesta.isEnabled = !attivo
        binding.btnDetta.isEnabled = !attivo
        binding.btnAllegaTelefono.isEnabled = !attivo
        binding.btnAllegaNas.isEnabled = !attivo
        binding.btnAllegaMega.isEnabled = !attivo
        binding.spinCliente.isEnabled = !attivo
        if (attivo) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun avanzamento(testo: String) = runOnUiThread {
        binding.tvAvanzamento.visibility = View.VISIBLE
        binding.tvAvanzamento.setTextColor(Color.parseColor("#1E2A22"))
        binding.tvAvanzamento.text = testo
    }

    // ---------------------------------------------------------------------
    // Documento prodotto
    // ---------------------------------------------------------------------

    private fun cartellaPareri(): File =
        File(getExternalFilesDir(null) ?: filesDir, "Pareri").apply { mkdirs() }

    private fun nomeFile(c: Cliente?): String {
        val base = c?.nomeCompleto() ?: "senza_cliente"
        val nome = base.replace(Regex("[^A-Za-z0-9À-ÿ]+"), "_").trim('_').take(40)
        val data = SimpleDateFormat("yyyyMMdd_HHmm", Locale.ITALY).format(Date())
        return "Perizia_${nome}_$data.docx"
    }

    private fun uriDocumento(): Uri? = documentoGenerato?.let { FileProvider.getUriForFile(this, "$packageName.fileprovider", it) }

    private fun apri() {
        val uri = uriDocumento() ?: return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, EsportatoreDocx.MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { startActivity(intent) } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Nessuna app per aprire file Word: installa Word o Google Documenti", Toast.LENGTH_LONG).show()
        }
    }

    private fun condividi() {
        val uri = uriDocumento() ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = EsportatoreDocx.MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, documentoGenerato?.nameWithoutExtension)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Condividi perizia"))
    }

    private fun salvaCopiaIn(uri: Uri) {
        val sorgente = documentoGenerato ?: return
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(sorgente.readBytes()) }
            Toast.makeText(this, "Copia salvata", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Impossibile salvare la copia: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
