package it.geometra.schedacliente

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import it.geometra.schedacliente.databinding.ActivityParereMegaBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Parere / relazione dai documenti Mega": legge una cartella Mega pubblica e permette di
 * navigarla cartella per cartella (prima le sottocartelle, poi i file della cartella aperta),
 * spuntando solo i file utili (nessuno selezionato all'inizio; la selezione resta anche
 * cambiando cartella). Scarica SOLO i file scelti, li passa all'AI e salva la bozza come
 * documento Word (.docx) da aprire, salvare altrove o condividere.
 *
 * Si apre in due modi:
 *  - dal dettaglio di una scheda cliente ([EXTRA_CLIENTE_ID]): usa il link salvato nella
 *    scheda e passa all'AI i dati della pratica;
 *  - dal menu principale con un link incollato al momento ([EXTRA_LINK]): nessuna scheda
 *    associata, i dati della pratica vengono ricavati dai documenti.
 */
class ParereMegaActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CLIENTE_ID = "cliente_id"
        const val EXTRA_LINK = "link_mega"

        /** Stima prudente della dimensione massima dei file selezionati (la richiesta all'API ha un limite di 32 MB, in base64). */
        private const val BYTE_SELEZIONE_MAX = 22L * 1024 * 1024
    }

    private lateinit var binding: ActivityParereMegaBinding
    private var cliente: Cliente? = null
    private lateinit var link: String

    private var contenuto: MegaCartellaPubblica.Contenuto? = null
    private var cartellaCorrente: String = ""
    private val fileCartella: List<MegaCartellaPubblica.FileMega> get() = contenuto?.file.orEmpty()
    private val selezionati = LinkedHashSet<String>() // handle dei file spuntati
    private var inGenerazione = false
    private var documentoGenerato: File? = null

    private val launcherSalvaCopia = registerForActivityResult(
        ActivityResultContracts.CreateDocument(EsportatoreDocx.MIME)
    ) { uri -> if (uri != null) salvaCopiaIn(uri) }

    /** Il tasto indietro risale di una cartella, finché non si torna alla cartella principale. */
    private val indietroRisaleCartella = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            contenuto?.cartella(cartellaCorrente)?.genitore?.takeIf { it.isNotEmpty() }?.let { apriCartella(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityParereMegaBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }
        onBackPressedDispatcher.addCallback(this, indietroRisaleCartella)

        val linkLibero = intent.getStringExtra(EXTRA_LINK)
        if (linkLibero != null) {
            link = linkLibero
            binding.tvNomeCliente.text = "Cartella Mega (senza scheda cliente)"
            binding.tvDatiPratica.text = "I dati della pratica verranno ricavati dai documenti; nel Word restano da compilare quelli mancanti."
        } else {
            val id = intent.getLongExtra(EXTRA_CLIENTE_ID, -1L)
            val trovato = ClienteStore(this).caricaTutti().find { it.id == id }
            if (trovato == null || trovato.linkMega.isBlank()) {
                Toast.makeText(this, "Scheda cliente o link Mega non trovati", Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            cliente = trovato
            link = trovato.linkMega
            binding.tvNomeCliente.text = trovato.nomeCompleto()
            binding.tvDatiPratica.text = EsportatoreDocx.datiPratica(trovato).drop(1)
                .joinToString("\n") { (etichetta, valore) -> "$etichetta: $valore" }
                .ifBlank { "Nessun dato immobile nella scheda" }
        }

        binding.btnRiprovaCartella.setOnClickListener { caricaCartella() }
        binding.btnSelezionaNessuno.setOnClickListener {
            selezionati.clear()
            disegnaElencoFile()
        }
        binding.btnGenera.setOnClickListener { avviaGenerazione() }
        binding.btnApri.setOnClickListener { apriDocumento() }
        binding.btnCondividi.setOnClickListener { condividiDocumento() }
        binding.btnSalvaCopia.setOnClickListener {
            documentoGenerato?.let { launcherSalvaCopia.launch(it.name) }
        }

        caricaCartella()
    }

    // ---------------------------------------------------------------------
    // Navigazione nella cartella
    // ---------------------------------------------------------------------

    private fun caricaCartella() {
        binding.tvStatoCartella.text = "Leggo la cartella Mega…"
        binding.tvStatoCartella.setTextColor(Color.parseColor("#2B2B2B"))
        binding.progressCartella.visibility = View.VISIBLE
        binding.btnRiprovaCartella.visibility = View.GONE
        Thread {
            try {
                val letto = MegaCartellaPubblica.leggiContenuto(link)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    contenuto = letto
                    binding.progressCartella.visibility = View.GONE
                    binding.tvStatoCartella.text = if (letto.file.isEmpty()) "La cartella è vuota."
                    else "Apri le cartelle e spunta solo i file utili al documento."
                    binding.rigaSelezione.visibility = if (letto.file.isEmpty()) View.GONE else View.VISIBLE
                    apriCartella(letto.radice.handle)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    binding.progressCartella.visibility = View.GONE
                    binding.tvStatoCartella.text = "⚠️ ${e.message ?: "Impossibile leggere la cartella Mega"}"
                    binding.tvStatoCartella.setTextColor(Color.parseColor("#B3261E"))
                    binding.btnRiprovaCartella.visibility = View.VISIBLE
                }
            }
        }.start()
    }

    private fun apriCartella(handle: String) {
        cartellaCorrente = handle
        indietroRisaleCartella.isEnabled = handle != contenuto?.radice?.handle
        disegnaElencoFile()
    }

    /** Percorso della cartella aperta, dalla principale: "📂 Pratica Rossi › Catasto". */
    private fun percorsoCorrente(): String {
        val c = contenuto ?: return ""
        val nomi = ArrayList<String>()
        var corrente = c.cartella(cartellaCorrente)
        var guardia = 0
        while (corrente != null && guardia++ < 50) {
            nomi.add(0, corrente.nome)
            corrente = corrente.genitore.takeIf { it.isNotEmpty() }?.let { c.cartella(it) }
        }
        return "📂 " + nomi.joinToString(" › ")
    }

    private fun disegnaElencoFile() {
        val lista = binding.listaFile
        lista.removeAllViews()
        val c = contenuto ?: run { aggiornaRiepilogoSelezione(); return }

        binding.tvPercorso.visibility = View.VISIBLE
        binding.tvPercorso.text = percorsoCorrente()

        // Torna alla cartella superiore.
        val corrente = c.cartella(cartellaCorrente)
        if (corrente != null && corrente.genitore.isNotEmpty()) {
            val superiore = c.cartella(corrente.genitore)
            lista.addView(rigaNavigazione("⬆️", "Torna a ${superiore?.nome ?: "cartella superiore"}", null) {
                apriCartella(corrente.genitore)
            })
        }

        // Prima le sottocartelle...
        c.sottocartelleDi(cartellaCorrente).forEach { sotto ->
            val quanti = c.fileTotaliIn(sotto.handle)
            val scelti = c.file.count { it.handle in selezionati && it.percorso.startsWith(sotto.percorso + "/") }
            val dettaglio = (if (quanti == 1) "1 file" else "$quanti file") + if (scelti > 0) " · ✓ $scelti selezionati" else ""
            lista.addView(rigaNavigazione("📁", sotto.nome, dettaglio) { apriCartella(sotto.handle) })
        }

        // ...poi i file della cartella aperta.
        val fileQui = c.fileIn(cartellaCorrente)
        fileQui.forEach { file -> lista.addView(rigaFile(file)) }

        if (fileQui.isEmpty() && c.sottocartelleDi(cartellaCorrente).isEmpty()) {
            lista.addView(TextView(this).apply {
                text = "Cartella vuota"
                textSize = 14f
                setTextColor(Color.parseColor("#3A4540"))
                gravity = Gravity.CENTER
                setPadding(0, dp(16), 0, dp(16))
            })
        }
        aggiornaRiepilogoSelezione()
    }

    private fun rigaNavigazione(icona: String, titolo: String, dettaglio: String?, azione: () -> Unit): View {
        val riga = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = sfondoCartella()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
            isClickable = true
            setOnClickListener { if (!inGenerazione) azione() }
        }
        riga.addView(TextView(this).apply {
            text = icona
            textSize = 24f
            setTextColor(Color.BLACK)
            setPadding(0, 0, dp(12), 0)
        })
        val testi = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        testi.addView(TextView(this).apply {
            text = titolo
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#1E2A22"))
        })
        if (dettaglio != null) testi.addView(TextView(this).apply {
            text = dettaglio
            textSize = 13f
            setTextColor(Color.parseColor("#3A4540"))
        })
        riga.addView(testi)
        if (icona == "📁") riga.addView(TextView(this).apply {
            text = "›"
            textSize = 26f
            setTextColor(Color.parseColor("#2E5339"))
        })
        return riga
    }

    private fun rigaFile(file: MegaCartellaPubblica.FileMega): View {
        val tipo = PreparatoreDocumenti.tipoDi(file.nome)
        val troppoGrande = file.dimensione > MegaCartellaPubblica.DIMENSIONE_MAX_FILE
        val selezionabile = tipo.supportato && !troppoGrande

        val riga = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(12), dp(6))
            background = sfondoRiga(file.handle in selezionati)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
            alpha = if (selezionabile) 1f else 0.55f
        }
        val spunta = CheckBox(this).apply {
            isChecked = file.handle in selezionati
            isEnabled = selezionabile && !inGenerazione
            contentDescription = "Seleziona ${file.nome}"
        }
        riga.addView(spunta)
        riga.addView(TextView(this).apply {
            text = tipo.icona
            textSize = 24f
            setTextColor(Color.BLACK)
            setPadding(dp(2), 0, dp(10), 0)
        })
        val testi = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        testi.addView(TextView(this).apply {
            text = file.nome
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#1E2A22"))
        })
        val dettagli = buildString {
            append(tipo.descrizione).append(" · ").append(dimensioneLeggibile(file.dimensione))
            if (!tipo.supportato) append("\n").append(PreparatoreDocumenti.motivoNonSupportato(file.nome))
            else if (troppoGrande) append("\nTroppo grande (oltre ${MegaCartellaPubblica.DIMENSIONE_MAX_FILE / 1024 / 1024} MB)")
        }
        testi.addView(TextView(this).apply {
            text = dettagli
            textSize = 13f
            setTextColor(Color.parseColor("#3A4540"))
        })
        riga.addView(testi)

        val cambia = {
            if (selezionabile && !inGenerazione) {
                if (!selezionati.add(file.handle)) selezionati.remove(file.handle)
                spunta.isChecked = file.handle in selezionati
                riga.background = sfondoRiga(file.handle in selezionati)
                aggiornaRiepilogoSelezione()
            }
        }
        riga.setOnClickListener { cambia() }
        spunta.setOnClickListener { cambia() }
        return riga
    }

    private fun aggiornaRiepilogoSelezione() {
        val scelti = fileCartella.filter { it.handle in selezionati }
        val totale = scelti.sumOf { it.dimensione }
        val troppo = totale > BYTE_SELEZIONE_MAX
        binding.tvRiepilogoSelezione.visibility = if (fileCartella.isEmpty()) View.GONE else View.VISIBLE
        // L'elenco dei file scelti resta visibile anche quando si cambia cartella.
        val elenco = scelti.joinToString("\n") { "• " + it.percorso }
        binding.tvRiepilogoSelezione.text = when {
            scelti.isEmpty() -> "Nessun file selezionato"
            troppo -> "⚠️ ${scelti.size} file · ${dimensioneLeggibile(totale)}: troppo per una sola richiesta (max circa ${BYTE_SELEZIONE_MAX / 1024 / 1024} MB). Togli qualche file.\n$elenco"
            else -> "✓ ${scelti.size} file selezionati · ${dimensioneLeggibile(totale)}\n$elenco"
        }
        binding.tvRiepilogoSelezione.setTextColor(if (troppo) Color.parseColor("#B3261E") else Color.parseColor("#1E2A22"))
        binding.btnGenera.isEnabled = scelti.isNotEmpty() && !troppo && !inGenerazione
    }

    // ---------------------------------------------------------------------
    // Generazione
    // ---------------------------------------------------------------------

    private fun avviaGenerazione() {
        if (inGenerazione) return
        val scelti = fileCartella.filter { it.handle in selezionati }
        if (scelti.isEmpty()) return
        val tipo = if (binding.toggleTipo.checkedButtonId == R.id.btnTipoRelazione)
            GeneratoreParereAi.TipoDocumento.RELAZIONE else GeneratoreParereAi.TipoDocumento.PARERE
        val richiesta = binding.etRichiesta.text?.toString()?.trim().orEmpty()

        impostaInGenerazione(true)
        binding.cardRisultato.visibility = View.GONE
        Thread {
            try {
                val blocchi = ArrayList<PreparatoreDocumenti.Blocco>()
                val scartati = ArrayList<String>()
                scelti.forEachIndexed { i, file ->
                    avanzamento("Scarico ${i + 1} di ${scelti.size}: ${file.nome}")
                    val dati = MegaCartellaPubblica.scarica(link, file)
                    try {
                        blocchi += PreparatoreDocumenti.prepara(this, file.nome, dati)
                    } catch (e: PreparatoreDocumenti.DocumentoNonLeggibile) {
                        scartati += "${file.nome}: ${e.message}"
                    }
                }
                if (blocchi.isEmpty()) {
                    throw IllegalStateException("Nessuno dei file scelti è leggibile:\n" + scartati.joinToString("\n"))
                }
                val pagine = blocchi.filterIsInstance<PreparatoreDocumenti.Blocco.Pdf>().sumOf { it.pagine }
                if (pagine > GeneratoreParereAi.PAGINE_PDF_MAX) {
                    throw IllegalStateException(
                        "I PDF scelti hanno in tutto $pagine pagine: il massimo per una richiesta è ${GeneratoreParereAi.PAGINE_PDF_MAX}. Togli qualche documento."
                    )
                }

                avanzamento("L'AI sta scrivendo la bozza… di solito 1-3 minuti, tieni aperta questa schermata")
                val risultato = GeneratoreParereAi.genera(this, cliente, tipo, richiesta, blocchi)

                avanzamento("Creo il documento Word…")
                val destinazione = File(cartellaPareri(), nomeFile(tipo))
                EsportatoreDocx.crea(destinazione, cliente, tipo, risultato.testo, blocchi.map { it.nome }, risultato.troncato)

                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    documentoGenerato = destinazione
                    impostaInGenerazione(false)
                    binding.tvAvanzamento.visibility = View.GONE
                    binding.cardRisultato.visibility = View.VISIBLE
                    binding.tvRisultato.text = buildString {
                        append("✅ Bozza pronta: ${destinazione.name}")
                        if (risultato.troncato) append("\n⚠️ Il testo si è interrotto perché troppo lungo: verifica la parte finale.")
                        if (scartati.isNotEmpty()) append("\n\nNon letti:\n").append(scartati.joinToString("\n"))
                        append("\n\nÈ una bozza: i punti [DA VERIFICARE] vanno controllati prima dell'uso.")
                    }
                    // Porta in vista la scheda del risultato.
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
        binding.toggleTipo.isEnabled = !attivo
        for (i in 0 until binding.toggleTipo.childCount) binding.toggleTipo.getChildAt(i).isEnabled = !attivo
        binding.etRichiesta.isEnabled = !attivo
        binding.btnSelezionaNessuno.isEnabled = !attivo
        // Lo schermo resta acceso mentre si attende l'AI (se si spegne il telefono può sospendere la rete).
        if (attivo) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        disegnaElencoFile()
    }

    private fun avanzamento(testo: String) = runOnUiThread {
        binding.tvAvanzamento.visibility = View.VISIBLE
        binding.tvAvanzamento.setTextColor(Color.parseColor("#1E2A22"))
        binding.tvAvanzamento.text = testo
    }

    // ---------------------------------------------------------------------
    // Documento generato
    // ---------------------------------------------------------------------

    private fun cartellaPareri(): File =
        File(getExternalFilesDir(null) ?: filesDir, "Pareri").apply { mkdirs() }

    private fun nomeFile(tipo: GeneratoreParereAi.TipoDocumento): String {
        val prefisso = if (tipo == GeneratoreParereAi.TipoDocumento.PARERE) "Parere" else "Relazione"
        val base = cliente?.nomeCompleto() ?: contenuto?.radice?.nome ?: "Mega"
        val nome = base.replace(Regex("[^A-Za-z0-9À-ÿ]+"), "_").trim('_').take(40)
        val data = SimpleDateFormat("yyyyMMdd_HHmm", Locale.ITALY).format(Date())
        return "${prefisso}_${nome}_$data.docx"
    }

    private fun uriDocumento(): Uri? = documentoGenerato?.let {
        FileProvider.getUriForFile(this, "$packageName.fileprovider", it)
    }

    private fun apriDocumento() {
        val uri = uriDocumento() ?: return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, EsportatoreDocx.MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Nessuna app per aprire file Word: installa Word o Google Documenti", Toast.LENGTH_LONG).show()
        }
    }

    private fun condividiDocumento() {
        val uri = uriDocumento() ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = EsportatoreDocx.MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, documentoGenerato?.nameWithoutExtension)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Condividi bozza"))
    }

    private fun salvaCopiaIn(uri: Uri) {
        val sorgente = documentoGenerato ?: return
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(sorgente.readBytes()) }
            Toast.makeText(this, "Copia salvata", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Non sono riuscito a salvare la copia: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ---------------------------------------------------------------------

    private fun sfondoRiga(selezionato: Boolean) = android.graphics.drawable.GradientDrawable().apply {
        setColor(if (selezionato) Color.parseColor("#E9F2EA") else Color.WHITE)
        cornerRadius = 12 * resources.displayMetrics.density
        setStroke(dp(if (selezionato) 2 else 1), Color.parseColor(if (selezionato) "#2E5339" else "#D8DCD2"))
    }

    private fun sfondoCartella() = android.graphics.drawable.GradientDrawable().apply {
        setColor(Color.parseColor("#FFF8E6"))
        cornerRadius = 12 * resources.displayMetrics.density
        setStroke(dp(1), Color.parseColor("#E0C097"))
    }

    private fun dp(valore: Int) = (valore * resources.displayMetrics.density).toInt()

    private fun dimensioneLeggibile(byte: Long): String = when {
        byte < 1024 -> "$byte B"
        byte < 1024 * 1024 -> String.format(Locale.ITALY, "%.0f KB", byte / 1024.0)
        else -> String.format(Locale.ITALY, "%.1f MB", byte / 1024.0 / 1024.0)
    }
}
