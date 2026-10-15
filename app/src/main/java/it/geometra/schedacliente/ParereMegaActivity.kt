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
 *
 * v3.78: con [EXTRA_ORIGINE] = [ORIGINE_NAS] i documenti si leggono invece dal NAS dello studio
 * ([NasStudio]): la cartella del cliente si cerca per nome (cognome e nome) nella cartella delle
 * pratiche impostata nelle Impostazioni; se ce n'è più d'una, o nessuna, la si sceglie da un
 * elenco con ricerca, e la scelta resta memorizzata nella scheda cliente.
 */
class ParereMegaActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CLIENTE_ID = "cliente_id"
        const val EXTRA_LINK = "link_mega"
        const val EXTRA_ORIGINE = "origine"
        const val ORIGINE_MEGA = "mega"
        const val ORIGINE_NAS = "nas"
        private const val MENU_CAMBIA_CARTELLA = 1

        /**
         * Modalità "solo selezione" (v3.82, per la perizia completa): si scelgono i file dalla cartella
         * come al solito, ma invece di scrivere il parere vengono scaricati nella cache del telefono e
         * consegnati alla schermata chiamante ([EXTRA_RISULTATO_FILE]: JSON [{"nome","percorso","motivo"}]).
         * "percorso" vuoto + "motivo" = file che l'AI non può leggere (viene solo segnalato, non scaricato).
         */
        const val EXTRA_SOLO_SELEZIONE = "solo_selezione"
        const val EXTRA_RISULTATO_FILE = "risultato_file"
        const val CARTELLA_ALLEGATI = "allegati_perizia"

        /** Stima prudente della dimensione massima dei file selezionati (la richiesta all'API ha un limite di 32 MB, in base64). */
        private const val BYTE_SELEZIONE_MAX = 22L * 1024 * 1024
    }

    private lateinit var binding: ActivityParereMegaBinding
    private var cliente: Cliente? = null
    private var link: String = ""

    /** Documenti dal NAS dello studio invece che da Mega (v3.78). */
    private var soloSelezione = false
    private var modoNas = false
    private var accessoNas: NasStudio.Accesso? = null
    /** Cartella del cliente sul NAS (nome dentro la cartella delle pratiche); null finché non è stata trovata o scelta. */
    private var cartellaNas: String? = null

    private var contenuto: MegaCartellaPubblica.Contenuto? = null
    private var cartellaCorrente: String = ""
    private val fileCartella: List<MegaCartellaPubblica.FileMega> get() = contenuto?.file.orEmpty()
    /** NAS: cartelle già lette (le sottocartelle si leggono una alla volta, quando si aprono). */
    private val cartelleLette = HashSet<String>()
    private var letturaCartellaNas = 0
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
        NavigazioneIndietro.installa(this)
        onBackPressedDispatcher.addCallback(this, indietroRisaleCartella)

        modoNas = intent.getStringExtra(EXTRA_ORIGINE) == ORIGINE_NAS
        val linkLibero = intent.getStringExtra(EXTRA_LINK)
        if (modoNas) {
            binding.toolbar.title = "Parere / relazione dal NAS"
            accessoNas = NasCredenzialiStore.accesso(this)
            if (accessoNas == null) {
                val dialog = android.app.AlertDialog.Builder(this, R.style.Theme_SchedaCliente_Dialog)
                    .setTitle("NAS dello studio non impostato")
                    .setMessage("Nelle Impostazioni inserisci la cartella delle pratiche sul NAS (es. \\\\192.168.1.20\\Pratiche), utente e password del NAS.")
                    .setPositiveButton("Vai alle Impostazioni") { _, _ -> startActivity(Intent(this, SettingsActivity::class.java)); finish() }
                    .setNegativeButton("Annulla") { _, _ -> finish() }
                    .setOnCancelListener { finish() }
                    .show()
                dialog.rendiPulsantiLeggibili(this)
                return
            }
            val id = intent.getLongExtra(EXTRA_CLIENTE_ID, -1L)
            if (id != -1L) {
                val trovato = ClienteStore(this).caricaTutti().find { it.id == id }
                if (trovato == null) {
                    Toast.makeText(this, "Scheda cliente non trovata", Toast.LENGTH_SHORT).show()
                    finish()
                    return
                }
                cliente = trovato
                cartellaNas = trovato.cartellaNas.takeIf { it.isNotBlank() }
                binding.tvNomeCliente.text = trovato.nomeCompleto()
                binding.tvDatiPratica.text = EsportatoreDocx.datiPratica(trovato).drop(1)
                    .joinToString("\n") { (etichetta, valore) -> "$etichetta: $valore" }
                    .ifBlank { "Nessun dato immobile nella scheda" }
            } else {
                binding.tvNomeCliente.text = "NAS dello studio (senza scheda cliente)"
                binding.tvDatiPratica.text = "I dati della pratica verranno ricavati dai documenti; nel Word restano da compilare quelli mancanti."
            }
        } else if (linkLibero != null) {
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

        binding.btnRiprovaCartella.setOnClickListener { if (modoNas && cartellaNas == null) cercaCartellaNas() else caricaCartella() }
        binding.btnSelezionaNessuno.setOnClickListener {
            selezionati.clear()
            disegnaElencoFile()
        }
        soloSelezione = intent.getBooleanExtra(EXTRA_SOLO_SELEZIONE, false)
        if (soloSelezione) {
            binding.toolbar.title = if (modoNas) "Documenti dal NAS per la perizia" else "Documenti da Mega per la perizia"
            binding.tvTitoloFile.text = "SCEGLI I FILE DA AGGIUNGERE ALLA PERIZIA"
            binding.tvTitoloTipo.visibility = View.GONE
            binding.toggleTipo.visibility = View.GONE
            binding.tilRichiesta.visibility = View.GONE
            binding.btnGenera.text = "➕  Aggiungi alla perizia"
        }
        binding.btnGenera.setOnClickListener { if (soloSelezione) avviaConsegnaSelezione() else avviaGenerazione() }
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
        if (modoNas && cartellaNas == null) { cercaCartellaNas(); return }
        val nas = accessoNas.takeIf { modoNas }
        val cartellaCliente = cartellaNas
        binding.tvStatoCartella.text = if (nas != null) "Leggo la cartella \"$cartellaCliente\" sul NAS…" else "Leggo la cartella Mega…"
        binding.tvStatoCartella.setTextColor(Color.parseColor("#2B2B2B"))
        binding.progressCartella.visibility = View.VISIBLE
        binding.btnRiprovaCartella.visibility = View.GONE
        Thread {
            try {
                val letto = if (nas != null) NasStudio.leggiContenuto(nas, cartellaCliente!!) else MegaCartellaPubblica.leggiContenuto(link)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    contenuto = letto
                    cartelleLette.clear()
                    cartelleLette.add(letto.radice.handle)
                    binding.progressCartella.visibility = View.GONE
                    val vuota = letto.file.isEmpty() && (nas == null || letto.cartelle.isEmpty())
                    binding.tvStatoCartella.text = if (vuota) "La cartella è vuota."
                    else "Apri le cartelle e spunta solo i file utili al documento."
                    binding.rigaSelezione.visibility = if (letto.file.isEmpty() && nas == null) View.GONE else View.VISIBLE
                    apriCartella(letto.radice.handle)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    // Cartella memorizzata nella scheda ma non più presente (rinominata/spostata): si ricerca.
                    if (nas != null && e is NasStudio.ErroreNas && e.cartellaNonTrovata) {
                        cartellaNas = null
                        binding.progressCartella.visibility = View.GONE
                        cercaCartellaNas("La cartella \"$cartellaCliente\" non c'è più sul NAS (rinominata o spostata?): scegli quella giusta.")
                        return@runOnUiThread
                    }
                    binding.progressCartella.visibility = View.GONE
                    binding.tvStatoCartella.text = "⚠️ ${e.message ?: if (nas != null) "Impossibile leggere la cartella sul NAS" else "Impossibile leggere la cartella Mega"}"
                    binding.tvStatoCartella.setTextColor(Color.parseColor("#B3261E"))
                    binding.btnRiprovaCartella.visibility = View.VISIBLE
                }
            }
        }.start()
    }

    // ---------------------------------------------------------------------
    // NAS: ricerca e scelta della cartella del cliente
    // ---------------------------------------------------------------------

    /**
     * Elenca le cartelle delle pratiche sul NAS e cerca quella del cliente per nome. Se ce n'è
     * una sola compatibile la usa subito; altrimenti (più d'una, nessuna, oppure [nota] non nulla
     * cioè scelta richiesta esplicitamente) la fa scegliere da un elenco.
     */
    private fun cercaCartellaNas(nota: String? = null) {
        val nas = accessoNas ?: return
        binding.tvStatoCartella.text = "Cerco la cartella del cliente sul NAS…"
        binding.tvStatoCartella.setTextColor(Color.parseColor("#2B2B2B"))
        binding.progressCartella.visibility = View.VISIBLE
        binding.btnRiprovaCartella.visibility = View.GONE
        Thread {
            try {
                val primo = NasStudio.elencaCartelle(nas)
                val c = cliente
                var proposte = if (c != null) NasStudio.cartellePerCliente(primo, c.cognome, c.nome) else emptyList()
                // Nessuna cartella col nome del cliente: può essere dentro una cartella contenitore
                // (es. "LAVORI E DISEGNI IN CORSO\\Morbidelli Simone"), si guarda uno o due livelli più in basso.
                val dentro = if (proposte.isEmpty() && c != null) {
                    try { NasStudio.cercaInProfondita(nas, primo, c.cognome, c.nome) } catch (e: Exception) { emptyList() }
                } else emptyList()
                proposte = proposte + dentro
                val tutte = primo + dentro
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    binding.progressCartella.visibility = View.GONE
                    when {
                        tutte.isEmpty() -> mostraErroreCartella("Nella cartella delle pratiche sul NAS (${nas.percorso}) non ci sono cartelle.")
                        proposte.size == 1 && nota == null -> usaCartellaNas(proposte[0])
                        else -> scegliCartellaNas(tutte, proposte, nota)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    binding.progressCartella.visibility = View.GONE
                    mostraErroreCartella(e.message ?: "Impossibile leggere il NAS")
                }
            }
        }.start()
    }

    private fun mostraErroreCartella(messaggio: String) {
        binding.tvStatoCartella.text = "⚠️ $messaggio"
        binding.tvStatoCartella.setTextColor(Color.parseColor("#B3261E"))
        binding.btnRiprovaCartella.visibility = View.VISIBLE
    }

    /** Usa la cartella [nome] per questo cliente: la memorizza nella scheda e ne legge il contenuto. */
    private fun usaCartellaNas(nome: String) {
        cartellaNas = nome
        selezionati.clear()
        contenuto = null
        cartelleLette.clear()
        cliente?.let { c ->
            if (c.cartellaNas != nome) {
                val aggiornato = c.copy(cartellaNas = nome)
                try {
                    ClienteStore(this).aggiorna(aggiornato)
                    cliente = aggiornato
                } catch (e: Exception) {
                    // Non è grave: si userà la cartella solo per questa volta.
                    Toast.makeText(this, "Cartella non memorizzata nella scheda: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
        caricaCartella()
    }

    /** Elenco delle cartelle con un campo di ricerca; le cartelle compatibili col cliente in cima, con ★. */
    private fun scegliCartellaNas(tutte: List<String>, proposte: List<String>, nota: String?) {
        val ordinate = proposte + tutte.filter { it !in proposte }
        val c = cliente
        val messaggio = nota ?: when {
            c == null -> "Scegli la cartella della pratica."
            proposte.isEmpty() -> "Non ho trovato una cartella con il nome di ${c.nomeCompleto()}: scegli quella giusta (puoi cercare scrivendo)."
            else -> "Ho trovato più cartelle compatibili con ${c.nomeCompleto()} (★): scegli quella giusta."
        }
        val visibili = ArrayList(ordinate)
        fun etichetta(nome: String) = nome.replace("\\", " › ").let { if (nome in proposte) "★ $it" else it }
        val adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList(visibili.map { etichetta(it) }))
        val campo = com.google.android.material.textfield.TextInputEditText(this).apply {
            hint = "Cerca una cartella…"
            setSingleLine()
        }
        val elenco = android.widget.ListView(this).apply {
            this.adapter = adapter
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(340))
        }
        val contenitore = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
            addView(campo)
            addView(elenco)
        }
        campo.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val cerca = NasStudio.normalizza(s?.toString().orEmpty())
                visibili.clear()
                visibili.addAll(ordinate.filter { cerca.isEmpty() || NasStudio.normalizza(it).contains(cerca) })
                adapter.clear()
                adapter.addAll(visibili.map { etichetta(it) })
            }
        })
        var scelta = false
        val dialog = android.app.AlertDialog.Builder(this, R.style.Theme_SchedaCliente_Dialog)
            .setTitle("Cartella sul NAS")
            .setMessage(messaggio)
            .setView(contenitore)
            .setNegativeButton("Annulla", null)
            .setOnDismissListener {
                if (!scelta && cartellaNas == null) mostraErroreCartella("Nessuna cartella scelta. Premi \"Riprova\" per scegliere la cartella del cliente.")
            }
            .show()
        dialog.rendiPulsantiLeggibili(this)
        elenco.setOnItemClickListener { _, _, posizione, _ ->
            val nome = visibili.getOrNull(posizione) ?: return@setOnItemClickListener
            scelta = true
            dialog.dismiss()
            usaCartellaNas(nome)
        }
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        if (modoNas) menu.add(0, MENU_CAMBIA_CARTELLA, 0, "Cambia cartella sul NAS")
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == MENU_CAMBIA_CARTELLA) {
            if (!inGenerazione) cercaCartellaNas("Scegli la cartella da usare.")
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun apriCartella(handle: String) {
        val c = contenuto
        if (modoNas && c != null && handle !in cartelleLette) { leggiSottocartellaNas(handle); return }
        cartellaCorrente = handle
        indietroRisaleCartella.isEnabled = handle != contenuto?.radice?.handle
        disegnaElencoFile()
    }

    /** NAS: legge il contenuto di una sola sottocartella quando la si apre (le altre non servono). */
    private fun leggiSottocartellaNas(handle: String) {
        val nas = accessoNas ?: return
        val cartellaCliente = cartellaNas ?: return
        val c = contenuto ?: return
        val cartella = c.cartella(handle) ?: return
        val sessione = ++letturaCartellaNas
        binding.tvStatoCartella.text = "Leggo la cartella \"${cartella.nome}\"…"
        binding.tvStatoCartella.setTextColor(Color.parseColor("#2B2B2B"))
        binding.progressCartella.visibility = View.VISIBLE
        Thread {
            try {
                val livello = NasStudio.leggiLivello(nas, cartellaCliente, cartella.percorso)
                runOnUiThread {
                    if (isFinishing || isDestroyed || sessione != letturaCartellaNas) return@runOnUiThread
                    val attuale = contenuto ?: return@runOnUiThread
                    contenuto = MegaCartellaPubblica.Contenuto(attuale.radice, attuale.cartelle + livello.cartelle, attuale.file + livello.file)
                    cartelleLette.add(handle)
                    binding.progressCartella.visibility = View.GONE
                    binding.tvStatoCartella.text = "Apri le cartelle e spunta solo i file utili al documento."
                    binding.rigaSelezione.visibility = View.VISIBLE
                    apriCartella(handle)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (isFinishing || isDestroyed || sessione != letturaCartellaNas) return@runOnUiThread
                    binding.progressCartella.visibility = View.GONE
                    binding.tvStatoCartella.text = "⚠️ ${e.message ?: "Impossibile leggere la cartella sul NAS"}"
                    binding.tvStatoCartella.setTextColor(Color.parseColor("#B3261E"))
                }
            }
        }.start()
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
            val noto = !modoNas || sotto.handle in cartelleLette
            val dettaglio = (if (!noto) "" else if (quanti == 1) "1 file" else "$quanti file") +
                (if (scelti > 0) (if (noto) " · " else "") + "✓ $scelti selezionati" else "")
            val dettaglioOpt = dettaglio.ifEmpty { null }
            lista.addView(rigaNavigazione("📁", sotto.nome, dettaglioOpt) { apriCartella(sotto.handle) })
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
        // Tutti i file si possono spuntare: quelli che l'AI non può leggere vengono scartati alla
        // generazione, avvisando quali sono e come convertirli.
        val selezionabile = true

        val riga = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(12), dp(6))
            background = sfondoRiga(file.handle in selezionati)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
            alpha = if (tipo.supportato && !troppoGrande) 1f else 0.8f
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
            if (!tipo.supportato) append("\n⚠️ Verrà scartato. ").append(PreparatoreDocumenti.motivoNonSupportato(file.nome))
            else if (troppoGrande) append("\n⚠️ Verrà scartato: troppo grande (oltre ${MegaCartellaPubblica.DIMENSIONE_MAX_FILE / 1024 / 1024} MB). Comprimilo o dividilo")
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
        // I file che verranno scartati (formato illeggibile o troppo grandi) non pesano sulla richiesta.
        val totale = scelti.filter { PreparatoreDocumenti.tipoDi(it.nome).supportato && it.dimensione <= MegaCartellaPubblica.DIMENSIONE_MAX_FILE }.sumOf { it.dimensione }
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

    /** Solo selezione: scarica i file spuntati nella cache e li consegna alla schermata della perizia. */
    private fun avviaConsegnaSelezione() {
        if (inGenerazione) return
        val scelti = fileCartella.filter { it.handle in selezionati }
        if (scelti.isEmpty()) return
        impostaInGenerazione(true)
        Thread {
            try {
                val cartella = File(cacheDir, CARTELLA_ALLEGATI).apply { mkdirs() }
                val consegna = org.json.JSONArray()
                scelti.forEachIndexed { i, file ->
                    val voce = org.json.JSONObject().put("nome", file.nome)
                    when {
                        !PreparatoreDocumenti.tipoDi(file.nome).supportato ->
                            voce.put("percorso", "").put("motivo", PreparatoreDocumenti.motivoNonSupportato(file.nome))
                        file.dimensione > MegaCartellaPubblica.DIMENSIONE_MAX_FILE ->
                            voce.put("percorso", "").put("motivo", "troppo grande (oltre ${MegaCartellaPubblica.DIMENSIONE_MAX_FILE / 1024 / 1024} MB): comprimilo o dividilo")
                        else -> {
                            avanzamento("Scarico ${i + 1} di ${scelti.size}: ${file.nome}")
                            val dati = if (modoNas) NasStudio.scarica(accessoNas!!, cartellaNas!!, file) else MegaCartellaPubblica.scarica(link, file)
                            val destinazione = File(cartella, "${IdUnivoci.nuovo()}_${file.nome.replace(Regex("[\\\\/:*?\"<>|]"), "_")}")
                            destinazione.writeBytes(dati)
                            voce.put("percorso", destinazione.absolutePath).put("motivo", "")
                        }
                    }
                    consegna.put(voce)
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    setResult(RESULT_OK, Intent().putExtra(EXTRA_RISULTATO_FILE, consegna.toString()))
                    finish()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    impostaInGenerazione(false)
                    binding.tvAvanzamento.visibility = View.VISIBLE
                    binding.tvAvanzamento.setTextColor(Color.parseColor("#B3261E"))
                    binding.tvAvanzamento.text = "⚠️ ${e.message ?: "Errore durante lo scaricamento"}"
                }
            }
        }.start()
    }

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
                var pagineTotali = 0
                var errori = 0
                scelti.forEachIndexed { i, file ->
                    avanzamento("Controllo ${i + 1} di ${scelti.size}: ${file.nome}")
                    // Formato illeggibile o file troppo grande: scartato subito, senza scaricarlo.
                    if (!PreparatoreDocumenti.tipoDi(file.nome).supportato) {
                        scartati += "${file.nome}: ${PreparatoreDocumenti.motivoNonSupportato(file.nome)}"; return@forEachIndexed
                    }
                    if (file.dimensione > MegaCartellaPubblica.DIMENSIONE_MAX_FILE) {
                        scartati += "${file.nome}: troppo grande (oltre ${MegaCartellaPubblica.DIMENSIONE_MAX_FILE / 1024 / 1024} MB), comprimilo o dividilo in parti"; return@forEachIndexed
                    }
                    avanzamento("Scarico ${i + 1} di ${scelti.size}: ${file.nome}")
                    val dati = try {
                        if (modoNas) NasStudio.scarica(accessoNas!!, cartellaNas!!, file) else MegaCartellaPubblica.scarica(link, file)
                    } catch (e: Exception) {
                        errori++
                        scartati += "${file.nome}: non sono riuscito a scaricarlo (${e.message ?: "errore di rete"}): riprova"
                        return@forEachIndexed
                    }
                    try {
                        val blocco = PreparatoreDocumenti.prepara(this, file.nome, dati)
                        if (blocco is PreparatoreDocumenti.Blocco.Pdf) {
                            if (pagineTotali + blocco.pagine > GeneratoreParereAi.PAGINE_PDF_MAX) {
                                scartati += "${file.nome}: ha ${blocco.pagine} pagine e superano il massimo di ${GeneratoreParereAi.PAGINE_PDF_MAX} pagine PDF per richiesta: " +
                                    "dividilo e scegli solo le pagine utili, oppure fai un'altra richiesta separata"
                                return@forEachIndexed
                            }
                            pagineTotali += blocco.pagine
                        }
                        blocchi += blocco
                    } catch (e: PreparatoreDocumenti.DocumentoNonLeggibile) {
                        scartati += "${file.nome}: ${e.message}"
                    } catch (e: OutOfMemoryError) {
                        scartati += "${file.nome}: troppo pesante per la memoria del telefono: comprimilo o riducilo"
                    }
                }
                if (blocchi.isEmpty()) {
                    throw IllegalStateException("Nessuno dei file scelti è leggibile dall'AI, quindi non ho creato il documento:\n• " + scartati.joinToString("\n• "))
                }

                avanzamento("L'AI sta scrivendo la bozza… di solito 1-3 minuti, tieni aperta questa schermata")
                val risultato = GeneratoreParereAi.genera(this, cliente, tipo, richiesta, blocchi, scartati.map { it.substringBefore(": ") })

                avanzamento("Creo il documento Word…")
                val destinazione = File(cartellaPareri(), nomeFile(tipo))
                EsportatoreDocx.crea(destinazione, cliente, tipo, risultato.testo, blocchi.map { it.nome }, risultato.troncato, scartati.map { it.substringBefore(": ") })

                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    documentoGenerato = destinazione
                    impostaInGenerazione(false)
                    binding.tvAvanzamento.visibility = View.GONE
                    binding.cardRisultato.visibility = View.VISIBLE
                    binding.tvRisultato.text = buildString {
                        append("✅ Bozza pronta: ${destinazione.name}")
                        if (risultato.troncato) append("\n⚠️ Il testo si è interrotto perché troppo lungo: verifica la parte finale.")
                        if (scartati.isNotEmpty()) {
                            append("\n\n⚠️ ${scartati.size} file scartati (non letti dall'AI), il documento è basato sugli altri ${blocchi.size}:\n• ")
                            append(scartati.joinToString("\n• "))
                        }
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
        val base = cliente?.nomeCompleto() ?: contenuto?.radice?.nome ?: if (modoNas) "NAS" else "Mega"
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
