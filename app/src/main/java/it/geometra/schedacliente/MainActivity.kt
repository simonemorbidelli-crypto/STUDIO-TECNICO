package it.geometra.schedacliente

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.LinearLayoutManager
import it.geometra.schedacliente.databinding.ActivityMainBinding
import org.json.JSONObject
import java.util.Locale

enum class CriterioOrdinamento(val etichetta: String) {
    DATA_RECENTE("più recenti"),
    DATA_MENO_RECENTE("meno recenti"),
    NOME_AZ("cognome (A-Z)"),
    NOME_ZA("cognome (Z-A)"),
    COMUNE_AZ("comune (A-Z)"),
    ANNO_PRATICA("anno pratica (più recente)"),
    NUMERO_PRATICA("numero pratica")
}

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: ClienteStore
    private lateinit var adapter: ClienteAdapter

    private var provinciaSelezionata: String = ""
    private var comuneSelezionato: String = ""
    private var listaCompleta: List<Cliente> = emptyList()
    private var ordinamentoAttivo: CriterioOrdinamento = CriterioOrdinamento.DATA_RECENTE
    private var clienteInModifica: Cliente? = null

    private var riconoscitoreVocale: SpeechRecognizer? = null
    private var inAscolto: Boolean = false
    private val trascrizioneDettatura = StringBuilder()

    // CORREZIONE (ottobre 2026): quando l'utente premeva "INTERROMPI ASCOLTO"
    // a mano (senza dire "salva"), il codice chiamava subito cancel() dopo
    // stopListening(): cancel() scarta la frase che il riconoscitore stava
    // ancora elaborando in quel momento, SENZA farla arrivare a onResults.
    // Risultato: l'ultima frase appena pronunciata andava perduta e, se era
    // l'unica detta in quella sessione, l'app diceva "non ho sentito nessun
    // dato" anche se l'utente aveva effettivamente parlato. Questo flag fa
    // sì che l'arresto manuale chieda solo stopListening() (che consegna
    // comunque il risultato finale a onResults/onError) e rinvia a quel
    // momento l'elaborazione, invece di troncare subito con cancel().
    private var elaborazioneInSospesoDopoArresto: Boolean = false

    private val importaLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { gestisciFileImportato(it) }
        }

    // Apre il selettore di sistema per scegliere DOVE salvare il backup:
    // una cartella locale, ma anche Google Drive, Dropbox, OneDrive ecc. se
    // installati, usando l'account personale già collegato su quel telefono.
    private val salvaCloudLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            uri?.let { scriviBackupSuUri(it) }
        }

    private val permessoMicrofonoLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { concesso ->
            if (concesso) {
                avviaDettatura()
            } else {
                Toast.makeText(
                    this,
                    "Senza il permesso al microfono non posso dettare la pratica",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    // "Seleziona tipo pratica..." è sempre il primo elemento, fisso: non fa
    // parte dell'elenco personalizzabile e significa "nessun tipo scelto".
    private var tipiPratica: List<String> = listOf("Seleziona tipo pratica...")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        impostaTitoloConVersione()
        NavigazioneIndietro.installaPaginaIniziale(this)

        store = ClienteStore(this)

        aggiornaTipiPratica()

        configuraSelettoreComune()

        adapter = ClienteAdapter(
            emptyList(),
            onClick = { cliente ->
                val intent = Intent(this, ClientDetailActivity::class.java)
                intent.putExtra("cliente_id", cliente.id)
                startActivity(intent)
            },
            onLongClick = { cliente -> iniziaModifica(cliente) }
        )
        binding.recyclerClienti.layoutManager = LinearLayoutManager(this)
        binding.recyclerClienti.adapter = adapter
        configuraScorrimentoPerEliminare()

        binding.btnSalva.setOnClickListener { salvaCliente() }
        binding.btnAnnullaModifica.setOnClickListener { annullaModifica() }

        binding.etRicerca.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                applicaFiltro(s?.toString() ?: "")
            }
        })

        binding.btnOrdina.setOnClickListener { mostraMenuOrdinamento() }

        binding.btnMenuPrincipale.setOnClickListener { mostraMenuPrincipale(it) }

        binding.btnDetta.setOnClickListener { gestisciPressioneDetta() }

        aggiornaLista()
    }

    override fun onDestroy() {
        super.onDestroy()
        riconoscitoreVocale?.destroy()
        riconoscitoreVocale = null
    }

    private fun mostraMenuOrdinamento() {
        val popup = PopupMenu(this, binding.btnOrdina)
        val criteri = CriterioOrdinamento.values()
        criteri.forEachIndexed { index, criterio ->
            popup.menu.add(0, index, index, criterio.etichetta.replaceFirstChar { it.uppercase() })
        }
        popup.setOnMenuItemClickListener { item ->
            ordinamentoAttivo = criteri[item.itemId]
            binding.tvOrdinamentoAttivo.text = "Ordina per: ${ordinamentoAttivo.etichetta}"
            applicaFiltro(binding.etRicerca.text?.toString() ?: "")
            true
        }
        popup.show()
    }

    override fun onResume() {
        super.onResume()
        aggiornaTipiPratica()
        aggiornaLista()
    }

    /**
     * Mostra nel titolo della toolbar il nome dell'app seguito dal numero di versione in piccolo
     * (es. "SchedaCliente v3.44"): così è sempre visibile, senza dover entrare nelle Impostazioni,
     * quale versione è installata sul telefono (utile quando si confrontano le funzioni con
     * l'ultimo APK inviato).
     */
    private fun impostaTitoloConVersione() {
        val etichettaVersione = "v${BuildConfig.VERSION_NAME}"
        val titolo = "${getString(R.string.app_name)} $etichettaVersione"
        val inizioVersione = titolo.length - etichettaVersione.length
        val titoloConStile = android.text.SpannableString(titolo).apply {
            setSpan(
                android.text.style.RelativeSizeSpan(0.55f),
                inizioVersione,
                titolo.length,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        binding.toolbar.title = titoloConStile
    }

    /** Ricarica l'elenco "Tipo pratica" (può essere stato modificato nelle Impostazioni). */
    private fun aggiornaTipiPratica() {
        val selezionatoPrima = binding.spinnerTipoPratica.selectedItem?.toString()

        tipiPratica = listOf("Seleziona tipo pratica...") + TipiPraticaStore.leggiTipi(this)
        binding.spinnerTipoPratica.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, tipiPratica
        )

        if (selezionatoPrima != null) {
            val indice = tipiPratica.indexOf(selezionatoPrima)
            if (indice >= 0) binding.spinnerTipoPratica.setSelection(indice)
        }
    }

    /** Una voce del menu principale disegnato a mano (vedi [mostraMenuPrincipale]). */
    private data class VoceMenuPrincipale(
        val icona: Int,
        val titolo: String,
        val separatoreSotto: Boolean = false,
        val azione: () -> Unit
    )

    /**
     * Costruisce e mostra il menu principale come popup disegnato a mano, invece di usare il
     * menu/overflow standard di Android (quello che veniva prima da menu_main.xml, inflazionato
     * in onCreateOptionsMenu): il menu di sistema forza ogni voce su una riga sola (il testo più
     * lungo, come quello della ruota del caffè, veniva tagliato) e non permette di inserire un
     * separatore tra una voce e le altre. Qui invece ogni riga è una semplice TextView che va a
     * capo liberamente, e un separatore è solo una piccola View colorata tra una riga e l'altra.
     */
    private fun mostraMenuPrincipale(ancora: View) {
        val voci = listOf(
            VoceMenuPrincipale(android.R.drawable.ic_menu_upload, "Salva backup su Drive o cloud") {
                avviaSalvataggioCloud()
            },
            VoceMenuPrincipale(android.R.drawable.ic_menu_share, "Condividi backup (email, WhatsApp...)") {
                esportaBackup()
            },
            VoceMenuPrincipale(android.R.drawable.ic_menu_add, "Importa backup (da file o da Drive/cloud)") {
                importaLauncher.launch(arrayOf("*/*"))
            },
            VoceMenuPrincipale(android.R.drawable.ic_menu_edit, "Calcoli utili") {
                startActivity(Intent(this, CalcoliActivity::class.java))
            },
            VoceMenuPrincipale(
                android.R.drawable.ic_menu_rotate,
                "Dove prendere il caffè oggi\nsenza vedere FDM",
                separatoreSotto = true
            ) {
                startActivity(Intent(this, RuotaCaffeActivity::class.java))
            },
            VoceMenuPrincipale(android.R.drawable.ic_menu_gallery, "Aree locali da planimetria") {
                startActivity(Intent(this, PlanimetriaActivity::class.java))
            },
            VoceMenuPrincipale(
                android.R.drawable.ic_menu_sort_by_size,
                "Ripartizione Bolletta\nEnergia Elettrica"
            ) {
                startActivity(Intent(this, BollettaEnergiaListaActivity::class.java))
            },
            VoceMenuPrincipale(
                android.R.drawable.ic_menu_agenda,
                "Documenti Mega →\nparere / relazione con l'AI"
            ) {
                chiediLinkMega()
            },
            VoceMenuPrincipale(
                android.R.drawable.ic_menu_upload,
                "Documenti NAS dello studio →\nparere / relazione con l'AI"
            ) {
                startActivity(
                    Intent(this, ParereMegaActivity::class.java)
                        .putExtra(ParereMegaActivity.EXTRA_ORIGINE, ParereMegaActivity.ORIGINE_NAS)
                )
            },
            VoceMenuPrincipale(
                android.R.drawable.ic_menu_compass,
                "Verifica di conformità →\ncatasto, titolo edilizio, stato di fatto"
            ) {
                startActivity(Intent(this, ConformitaActivity::class.java))
            },
            VoceMenuPrincipale(
                android.R.drawable.ic_menu_mapmode,
                "Mappa e WMS Regione Toscana\ncatasto, CTR, ortofoto"
            ) {
                startActivity(Intent(this, MappaActivity::class.java))
            },
            VoceMenuPrincipale(android.R.drawable.ic_menu_preferences, "Impostazioni") {
                startActivity(Intent(this, SettingsActivity::class.java))
            },
            VoceMenuPrincipale(android.R.drawable.ic_menu_info_details, "Informazioni\nstoria, guida all'uso, copyright") {
                startActivity(Intent(this, InformazioniActivity::class.java))
            }
        )

        val densita = resources.displayMetrics.density
        val paddingOrizzontale = (16 * densita).toInt()
        val paddingVerticale = (12 * densita).toInt()

        val contenitore = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        var popup: PopupWindow? = null

        val sfondoRigaTv = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, sfondoRigaTv, true)

        for (voce in voci) {
            val riga = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(paddingOrizzontale, paddingVerticale, paddingOrizzontale, paddingVerticale)
                isClickable = true
                isFocusable = true
                setBackgroundResource(sfondoRigaTv.resourceId)
                setOnClickListener {
                    popup?.dismiss()
                    voce.azione()
                }
            }
            val icona = ImageView(this).apply {
                setImageResource(voce.icona)
                layoutParams = LinearLayout.LayoutParams((22 * densita).toInt(), (22 * densita).toInt()).apply {
                    marginEnd = (16 * densita).toInt()
                }
                ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.primary)))
            }
            val testo = TextView(this).apply {
                text = voce.titolo
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            riga.addView(icona)
            riga.addView(testo)
            contenitore.addView(riga)

            if (voce.separatoreSotto) {
                contenitore.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (1 * densita).toInt()).apply {
                        topMargin = (6 * densita).toInt()
                        bottomMargin = (6 * densita).toInt()
                    }
                    setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.divider))
                })
            }
        }

        val scorribile = ScrollView(this).apply { addView(contenitore) }
        val larghezzaPopup = (290 * densita).toInt()
        popup = PopupWindow(scorribile, larghezzaPopup, LinearLayout.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_menu_popup))
            elevation = 12f
        }
        popup.showAsDropDown(ancora, -(larghezzaPopup - ancora.width), 0, Gravity.NO_GRAVITY)
    }

    /**
     * Voce di menu "Documenti Mega": incolla un link pubblico di cartella Mega e apri la sezione
     * parere/relazione senza doverlo salvare in una scheda cliente. Il campo è precompilato con
     * un link Mega già negli appunti, altrimenti con l'ultimo usato da qui.
     */
    private fun chiediLinkMega() {
        val prefs = getSharedPreferences("mega_link_libero", MODE_PRIVATE)
        val dagliAppunti = (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
            .primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
            ?.takeIf { MegaCartellaPubblica.analizzaLink(it) != null }
        val densita = resources.displayMetrics.density
        val campo = com.google.android.material.textfield.TextInputEditText(this).apply {
            hint = "https://mega.nz/folder/…#…"
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            setText(dagliAppunti ?: prefs.getString("ultimo", "").orEmpty())
            maxLines = 3
        }
        val contenitore = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (20 * densita).toInt()
            setPadding(p, (8 * densita).toInt(), p, 0)
            addView(campo)
        }
        val dialog = AlertDialog.Builder(this, R.style.Theme_SchedaCliente_Dialog)
            .setTitle("Cartella Mega")
            .setMessage("Incolla il link pubblico della cartella (su mega.nz: Ottieni link). Non viene salvato in nessuna scheda cliente.")
            .setView(contenitore)
            .setPositiveButton("Apri", null)
            .setNegativeButton("Annulla", null)
            .show()
        dialog.rendiPulsantiLeggibili(this)
        // Il pulsante "Apri" non chiude la finestra se il link non è valido.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val link = campo.text?.toString()?.trim().orEmpty()
            if (MegaCartellaPubblica.analizzaLink(link) == null) {
                campo.error = "Link di cartella Mega non valido (serve anche la chiave dopo il #)"
                return@setOnClickListener
            }
            prefs.edit().putString("ultimo", link).apply()
            dialog.dismiss()
            startActivity(Intent(this, ParereMegaActivity::class.java).putExtra(ParereMegaActivity.EXTRA_LINK, link))
        }
    }

    /**
     * Avvia il selettore di sistema per salvare il backup in una posizione
     * scelta dall'utente: una cartella del telefono, oppure, se installate,
     * app come Google Drive, Dropbox o OneDrive — con l'account personale di
     * chi usa quel telefono, senza passare da nessun account o server terzo.
     */
    private fun avviaSalvataggioCloud() {
        if (store.testoEsportazione() == null) {
            Toast.makeText(this, "Non ci sono ancora schede da esportare", Toast.LENGTH_SHORT).show()
            return
        }
        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmm", Locale.ITALY).format(java.util.Date())
        salvaCloudLauncher.launch("SchedaCliente_backup_$timestamp.json")
    }

    private fun scriviBackupSuUri(uri: Uri) {
        val testo = store.testoEsportazione()
        if (testo == null) {
            Toast.makeText(this, "Non ci sono ancora schede da esportare", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(testo.toByteArray(Charsets.UTF_8))
            } ?: throw IllegalStateException("Impossibile scrivere nella posizione scelta")
            Toast.makeText(this, "Backup salvato", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Errore nel salvataggio: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun esportaBackup() {
        val file = store.preparaFileEsportazione()
        if (file == null) {
            Toast.makeText(this, "Non ci sono ancora schede da esportare", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Backup Scheda Cliente")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Esporta scheda clienti tramite..."))
        } catch (e: Exception) {
            Toast.makeText(this, "Errore nell'esportazione: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun gestisciFileImportato(uri: Uri) {
        try {
            val testo = contentResolver.openInputStream(uri)
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: throw IllegalStateException("File non leggibile")

            val analisi = store.analizzaImportazione(testo)

            // Le schede nuove si aggiungono subito, senza bisogno di chiedere nulla.
            store.aggiungiTutte(analisi.nuove)

            if (analisi.conflitti.isEmpty()) {
                mostraRiepilogoImportazione(analisi.nuove.size, analisi.identiche, 0)
            } else {
                risolviConflitti(analisi.conflitti, 0, analisi.nuove.size, analisi.identiche)
            }
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Il file scelto non è un backup valido di Scheda Cliente",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * Mostra i conflitti uno alla volta (scheda presente sia in locale sia nel
     * file importato, ma con dati diversi), chiedendo ogni volta all'utente
     * quale versione mantenere, come richiesto esplicitamente dall'utente.
     */
    private fun risolviConflitti(
        conflitti: List<ConflittoImportazione>,
        indice: Int,
        nuoveContate: Int,
        identicheContate: Int
    ) {
        if (indice >= conflitti.size) {
            mostraRiepilogoImportazione(nuoveContate, identicheContate, conflitti.size)
            return
        }

        val conflitto = conflitti[indice]
        val messaggio = "La scheda di \"${conflitto.locale.nomeCompleto()}\" è presente " +
            "sia sul telefono che nel file importato, ma con dati diversi.\n\n" +
            "Versione sul telefono:\nultima modifica ${conflitto.locale.dataUltimaModificaFormattata()}\n\n" +
            "Versione importata:\nultima modifica ${conflitto.importata.dataUltimaModificaFormattata()}\n\n" +
            "Quale vuoi mantenere?"

        val dialog = AlertDialog.Builder(this, R.style.Theme_SchedaCliente_Dialog)
            .setTitle("Scheda in conflitto (${indice + 1} di ${conflitti.size})")
            .setMessage(messaggio)
            .setCancelable(false)
            .setPositiveButton("Usa importata") { _, _ ->
                store.aggiorna(conflitto.importata.copy(id = conflitto.locale.id))
                risolviConflitti(conflitti, indice + 1, nuoveContate, identicheContate)
            }
            .setNegativeButton("Mantieni quella sul telefono") { _, _ ->
                risolviConflitti(conflitti, indice + 1, nuoveContate, identicheContate)
            }
            .setNeutralButton("Tieni entrambe") { _, _ ->
                val idsEsistenti = store.caricaTutti().map { it.id }.toSet()
                store.salva(conflitto.importata.copy(id = IdUnivoci.nuovo(idsEsistenti)))
                risolviConflitti(conflitti, indice + 1, nuoveContate, identicheContate)
            }
            .show()
        dialog.rendiPulsantiLeggibili(this)
    }

    private fun mostraRiepilogoImportazione(nuove: Int, identiche: Int, conflittiRisolti: Int) {
        val messaggio = buildString {
            append("Importazione completata.\n\n")
            append("Nuove schede aggiunte: $nuove\n")
            if (conflittiRisolti > 0) append("Conflitti risolti: $conflittiRisolti\n")
            if (identiche > 0) append("Già identiche (nessuna modifica): $identiche")
        }
        val dialog = AlertDialog.Builder(this, R.style.Theme_SchedaCliente_Dialog)
            .setTitle("Importazione backup")
            .setMessage(messaggio.trim())
            .setPositiveButton("OK") { _, _ -> aggiornaLista() }
            .setCancelable(false)
            .show()
        dialog.rendiPulsantiLeggibili(this)
    }

    private fun configuraSelettoreComune() {
        val province = ComuniProvider.province(this)

        val adapterProvincia = ArrayAdapter(
            this,
            android.R.layout.simple_dropdown_item_1line,
            province
        )
        binding.autoProvincia.setAdapter(adapterProvincia)
        binding.autoProvincia.setOnClickListener { binding.autoProvincia.showDropDown() }
        binding.autoProvincia.setOnItemClickListener { _, _, position, _ ->
            val provinciaScelta = adapterProvincia.getItem(position) ?: return@setOnItemClickListener
            provinciaSelezionata = provinciaScelta
            comuneSelezionato = ""
            binding.autoComune.setText("", false)
            binding.autoComune.isEnabled = true
            binding.autoComune.hint = "Seleziona comune..."

            val comuni = ComuniProvider.comuniDi(this, provinciaScelta)
            val adapterComune = ArrayAdapter(
                this,
                android.R.layout.simple_dropdown_item_1line,
                comuni
            )
            binding.autoComune.setAdapter(adapterComune)
        }

        binding.autoComune.setOnClickListener {
            if (binding.autoComune.isEnabled) binding.autoComune.showDropDown()
        }
        binding.autoComune.setOnItemClickListener { parent, _, position, _ ->
            comuneSelezionato = parent.getItemAtPosition(position).toString()
        }
    }

    private fun iniziaModifica(cliente: Cliente) {
        clienteInModifica = cliente

        binding.etCognome.setText(cliente.cognome)
        binding.etNome.setText(cliente.nome)
        binding.etCodiceFiscale.setText(cliente.codiceFiscale)
        binding.etPartitaIva.setText(cliente.partitaIva)
        binding.etTelefono.setText(cliente.telefono)
        binding.etEmail.setText(cliente.email)
        binding.etIndirizzo.setText(cliente.indirizzo)
        binding.etNumeroPratica.setText(cliente.numeroPratica)
        binding.etAnnoPratica.setText(cliente.annoPratica)
        binding.etFoglio.setText(cliente.foglio)
        binding.etParticella.setText(cliente.particella)
        binding.etSubalterno.setText(cliente.subalterno)
        binding.etNote.setText(cliente.note)
        binding.etLinkMega.setText(cliente.linkMega)
        binding.tilLinkMega.error = null

        val indiceTipo = tipiPratica.indexOf(cliente.tipoPratica)
        binding.spinnerTipoPratica.setSelection(if (indiceTipo >= 0) indiceTipo else 0)

        // Provincia/comune: usa quella salvata, oppure prova a risalirci dal
        // comune per le schede create prima che esistesse questo campo.
        val provincia = cliente.provincia.ifBlank {
            ComuniProvider.provinciaDiComune(this, cliente.comune) ?: ""
        }
        provinciaSelezionata = provincia
        comuneSelezionato = cliente.comune
        binding.autoProvincia.setText(provincia, false)
        if (provincia.isNotBlank()) {
            binding.autoComune.isEnabled = true
            binding.autoComune.hint = "Seleziona comune..."
            val adapterComune = ArrayAdapter(
                this,
                android.R.layout.simple_dropdown_item_1line,
                ComuniProvider.comuniDi(this, provincia)
            )
            binding.autoComune.setAdapter(adapterComune)
        }
        binding.autoComune.setText(cliente.comune, false)

        binding.tvTitoloForm.text = "Modifica scheda cliente"
        binding.btnAnnullaModifica.visibility = android.view.View.VISIBLE
        binding.tvBannerModifica.visibility = android.view.View.VISIBLE
        binding.tvBannerModifica.text = "Stai modificando: ${cliente.nomeCompleto()}"
        binding.btnSalva.text = "AGGIORNA SCHEDA CLIENTE"

        binding.scrollPrincipale.post {
            binding.scrollPrincipale.smoothScrollTo(0, 0)
        }
        Toast.makeText(this, "Modifica i campi e premi Aggiorna", Toast.LENGTH_SHORT).show()
    }

    private fun annullaModifica() {
        clienteInModifica = null
        pulisciForm()
    }

    /**
     * Il pulsante Salva si disattiva al primo tocco e si riattiva poco dopo la fine del
     * salvataggio (la breve attesa assorbe un eventuale secondo tocco già in coda), così un
     * doppio tap non crea due schede né scrive due volte lo stesso file.
     */
    private fun salvaCliente() {
        if (!binding.btnSalva.isEnabled) return
        binding.btnSalva.isEnabled = false
        try {
            eseguiSalvataggioCliente()
        } finally {
            binding.btnSalva.postDelayed({ binding.btnSalva.isEnabled = true }, 700)
        }
    }

    private fun eseguiSalvataggioCliente() {
        val cognome = binding.etCognome.text?.toString()?.trim() ?: ""
        if (cognome.isEmpty()) {
            binding.etCognome.error = "Campo obbligatorio"
            Toast.makeText(this, "Inserisci cognome o ragione sociale del cliente", Toast.LENGTH_SHORT).show()
            return
        }
        val nome = binding.etNome.text?.toString()?.trim() ?: ""
        val inModifica = clienteInModifica

        // Link Mega facoltativo: se presente deve essere un link pubblico di cartella valido.
        val linkMega = binding.etLinkMega.text?.toString()?.trim() ?: ""
        if (linkMega.isNotEmpty() && MegaCartellaPubblica.analizzaLink(linkMega) == null) {
            binding.tilLinkMega.error = "Non è un link di cartella Mega valido (deve contenere anche la chiave dopo il #)"
            Toast.makeText(this, "Controlla il link della cartella Mega", Toast.LENGTH_SHORT).show()
            return
        }
        binding.tilLinkMega.error = null

        val cliente = Cliente(
            id = inModifica?.id ?: IdUnivoci.nuovo(store.caricaTutti().map { it.id }.toSet()),
            cognome = cognome,
            nome = nome,
            codiceFiscale = binding.etCodiceFiscale.text?.toString()?.trim()?.uppercase() ?: "",
            partitaIva = binding.etPartitaIva.text?.toString()?.trim() ?: "",
            telefono = binding.etTelefono.text?.toString()?.trim() ?: "",
            email = binding.etEmail.text?.toString()?.trim() ?: "",
            indirizzo = binding.etIndirizzo.text?.toString()?.trim() ?: "",
            provincia = provinciaSelezionata,
            comune = comuneSelezionato,
            tipoPratica = if (binding.spinnerTipoPratica.selectedItemPosition <= 0) {
                ""
            } else {
                binding.spinnerTipoPratica.selectedItem?.toString() ?: ""
            },
            numeroPratica = binding.etNumeroPratica.text?.toString()?.trim() ?: "",
            annoPratica = binding.etAnnoPratica.text?.toString()?.trim() ?: "",
            foglio = binding.etFoglio.text?.toString()?.trim() ?: "",
            particella = binding.etParticella.text?.toString()?.trim() ?: "",
            subalterno = binding.etSubalterno.text?.toString()?.trim() ?: "",
            note = binding.etNote.text?.toString()?.trim() ?: "",
            linkMega = linkMega,
            // Non è nel modulo: si conserva quella già memorizzata (altrimenti modificando la scheda andrebbe persa).
            cartellaNas = inModifica?.cartellaNas ?: "",
            dataCreazione = inModifica?.dataCreazione ?: System.currentTimeMillis(),
            ultimaModifica = System.currentTimeMillis()
        )

        try {
            if (inModifica != null) {
                store.aggiorna(cliente)
                Toast.makeText(this, "Scheda cliente aggiornata", Toast.LENGTH_SHORT).show()
            } else {
                store.salva(cliente)
                Toast.makeText(this, "Scheda cliente salvata", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            // Non deve mai sembrare che "non sia successo nulla": se il salvataggio
            // fallisce per qualunque motivo, l'utente deve vederlo con chiarezza.
            val dialog = AlertDialog.Builder(this, R.style.Theme_SchedaCliente_Dialog)
                .setTitle("Salvataggio non riuscito")
                .setMessage("Non sono riuscito a salvare la scheda: ${e.message ?: "errore sconosciuto"}.\n\nI dati inseriti nel modulo non sono stati persi: puoi riprovare.")
                .setPositiveButton("OK", null)
                .show()
            dialog.rendiPulsantiLeggibili(this)
            return
        }

        clienteInModifica = null
        pulisciForm()
        aggiornaLista()
    }

    private fun pulisciForm() {
        binding.etCognome.text?.clear()
        binding.etNome.text?.clear()
        binding.etCodiceFiscale.text?.clear()
        binding.etPartitaIva.text?.clear()
        binding.etTelefono.text?.clear()
        binding.etEmail.text?.clear()
        binding.etIndirizzo.text?.clear()
        provinciaSelezionata = ""
        comuneSelezionato = ""
        binding.autoProvincia.setText("", false)
        binding.autoComune.setText("", false)
        binding.autoComune.isEnabled = false
        binding.autoComune.hint = "Seleziona prima la provincia..."
        binding.etNumeroPratica.text?.clear()
        binding.etAnnoPratica.text?.clear()
        binding.etFoglio.text?.clear()
        binding.etParticella.text?.clear()
        binding.etSubalterno.text?.clear()
        binding.etNote.text?.clear()
        binding.etLinkMega.text?.clear()
        binding.tilLinkMega.error = null
        binding.spinnerTipoPratica.setSelection(0)
        binding.tvTitoloForm.text = "Nuova scheda cliente"
        binding.btnAnnullaModifica.visibility = android.view.View.GONE
        binding.tvBannerModifica.visibility = android.view.View.GONE
        binding.btnSalva.text = "SALVA SCHEDA CLIENTE"
        binding.etCognome.requestFocus()
    }

    /**
     * Scorrendo una scheda della lista verso destra compare uno sfondo rosso "Elimina" e, a fine
     * gesto, una richiesta di conferma. Con "Annulla" (o chiudendo la finestra) la scheda torna
     * al suo posto e non viene toccata.
     */
    private fun configuraScorrimentoPerEliminare() {
        val densita = resources.displayMetrics.density
        val paintSfondo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#B3261E") }
        val paintTesto = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 16 * densita
            typeface = Typeface.DEFAULT_BOLD
        }

        val callback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.RIGHT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false

            // Serve un gesto deciso (oltre metà larghezza) per evitare eliminazioni accidentali mentre si scorre la lista.
            override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder) = 0.5f

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val posizione = viewHolder.bindingAdapterPosition
                val cliente = adapter.clienteAt(posizione)
                if (cliente == null) {
                    adapter.notifyItemChanged(posizione)
                    return
                }
                var confermato = false
                val dialog = AlertDialog.Builder(this@MainActivity, R.style.Theme_SchedaCliente_Dialog)
                    .setTitle("Eliminare questa scheda?")
                    .setMessage("${cliente.nomeCompleto()}\n\nL'operazione non può essere annullata.")
                    .setPositiveButton("Elimina") { _, _ ->
                        confermato = true
                        store.elimina(cliente.id)
                        if (clienteInModifica?.id == cliente.id) annullaModifica()
                        aggiornaLista()
                        Toast.makeText(this@MainActivity, "Scheda eliminata", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Annulla", null)
                    .setOnDismissListener {
                        // Non confermato: la scheda torna al suo posto nella lista.
                        if (!confermato) adapter.notifyItemChanged(posizione)
                    }
                    .show()
                dialog.rendiPulsantiLeggibili(this@MainActivity)
            }

            override fun onChildDraw(
                c: Canvas, rv: RecyclerView, vh: RecyclerView.ViewHolder,
                dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean
            ) {
                if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && dX > 0) {
                    val v = vh.itemView
                    val raggio = 12 * densita
                    c.drawRoundRect(
                        RectF(v.left.toFloat(), v.top.toFloat(), v.left + dX, v.bottom.toFloat()),
                        raggio, raggio, paintSfondo
                    )
                    val testo = "🗑  Elimina"
                    val y = v.top + v.height / 2f - (paintTesto.descent() + paintTesto.ascent()) / 2f
                    c.save()
                    c.clipRect(v.left.toFloat(), v.top.toFloat(), v.left + dX, v.bottom.toFloat())
                    c.drawText(testo, v.left + 20 * densita, y, paintTesto)
                    c.restore()
                }
                super.onChildDraw(c, rv, vh, dX, dY, actionState, isCurrentlyActive)
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(binding.recyclerClienti)
    }

    private fun aggiornaLista() {
        listaCompleta = store.caricaTutti()
        applicaFiltro(binding.etRicerca.text?.toString() ?: "")
    }

    private fun applicaFiltro(testoRicerca: String) {
        val query = testoRicerca.trim().lowercase(Locale.ITALY)

        val filtrata = if (query.isEmpty()) {
            listaCompleta
        } else {
            listaCompleta.filter { cliente ->
                cliente.cognome.lowercase(Locale.ITALY).contains(query) ||
                    cliente.nome.lowercase(Locale.ITALY).contains(query) ||
                    cliente.comune.lowercase(Locale.ITALY).contains(query) ||
                    cliente.codiceFiscale.lowercase(Locale.ITALY).contains(query) ||
                    cliente.partitaIva.contains(query) ||
                    cliente.telefono.contains(query) ||
                    cliente.email.lowercase(Locale.ITALY).contains(query) ||
                    cliente.tipoPratica.lowercase(Locale.ITALY).contains(query) ||
                    cliente.praticaFormattata().lowercase(Locale.ITALY).contains(query) ||
                    cliente.numeroPratica.lowercase(Locale.ITALY).contains(query) ||
                    cliente.annoPratica.contains(query)
            }
        }

        val ordinata = ordina(filtrata)
        adapter.aggiorna(ordinata)

        val listaVuota = ordinata.isEmpty()
        binding.tvVuoto.visibility =
            if (listaVuota) android.view.View.VISIBLE else android.view.View.GONE
        binding.recyclerClienti.visibility =
            if (listaVuota) android.view.View.GONE else android.view.View.VISIBLE

        binding.tvVuoto.text = when {
            listaCompleta.isEmpty() -> "Nessun cliente salvato ancora."
            else -> "Nessun cliente trovato per \"$testoRicerca\"."
        }
    }

    private fun ordina(lista: List<Cliente>): List<Cliente> {
        val collatore = java.text.Collator.getInstance(Locale.ITALY)

        return when (ordinamentoAttivo) {
            CriterioOrdinamento.DATA_RECENTE ->
                lista.sortedByDescending { it.dataCreazione }

            CriterioOrdinamento.DATA_MENO_RECENTE ->
                lista.sortedBy { it.dataCreazione }

            CriterioOrdinamento.NOME_AZ ->
                lista.sortedWith(
                    compareBy(collatore, Cliente::cognome).thenBy(collatore, Cliente::nome)
                )

            CriterioOrdinamento.NOME_ZA ->
                lista.sortedWith(
                    compareByDescending(collatore, Cliente::cognome).thenByDescending(collatore, Cliente::nome)
                )

            CriterioOrdinamento.COMUNE_AZ ->
                lista.sortedWith(
                    compareBy(collatore) { it.comune.ifBlank { "zzzzz" } }
                )

            CriterioOrdinamento.ANNO_PRATICA ->
                lista.sortedWith(
                    compareByDescending<Cliente> { it.annoPratica.toIntOrNull() ?: -1 }
                        .thenByDescending { it.numeroPratica.toIntOrNull() ?: -1 }
                )

            CriterioOrdinamento.NUMERO_PRATICA ->
                lista.sortedWith(
                    compareBy<Cliente> { it.numeroPratica.toIntOrNull() ?: Int.MAX_VALUE }
                        .thenBy { it.annoPratica }
                )
        }
    }

    // ---------------------------------------------------------------
    // Dettatura pratica assistita da AI
    // ---------------------------------------------------------------

    private fun gestisciPressioneDetta() {
        if (inAscolto) {
            richiediArrestoAscolto()
            return
        }

        val chiaveApi = ChiaveApiStore.leggiChiave(this)
        if (chiaveApi.isBlank()) {
            val dialog = AlertDialog.Builder(this, R.style.Theme_SchedaCliente_Dialog)
                .setTitle("Dettatura AI non attiva")
                .setMessage(
                    "Per dettare una pratica serve prima inserire una chiave API " +
                        "personale nelle Impostazioni (un uso minimo, pochi centesimi " +
                        "a pratica, pagato con il tuo account)."
                )
                .setPositiveButton("Vai alle Impostazioni") { _, _ ->
                    startActivity(Intent(this, SettingsActivity::class.java))
                }
                .setNegativeButton("Annulla", null)
                .show()
            dialog.rendiPulsantiLeggibili(this)
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Il riconoscimento vocale non è disponibile su questo telefono", Toast.LENGTH_LONG).show()
            return
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            avviaDettatura()
        } else {
            permessoMicrofonoLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun avviaDettatura() {
        trascrizioneDettatura.clear()
        elaborazioneInSospesoDopoArresto = false
        inAscolto = true
        impostaStatoBottoneAscolto(ascolto = true)
        Toast.makeText(
            this,
            "In ascolto... detta i dati della pratica e di' \"salva\" per confermare",
            Toast.LENGTH_LONG
        ).show()
        avviaSessioneAscolto()
    }

    private fun avviaSessioneAscolto() {
        val riconoscitore = riconoscitoreVocale ?: SpeechRecognizer.createSpeechRecognizer(this).also {
            riconoscitoreVocale = it
        }

        riconoscitore.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                if (isFinishing || isDestroyed) return
                val frasi = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val testo = frasi?.firstOrNull().orEmpty()
                if (testo.isNotBlank()) {
                    if (trascrizioneDettatura.isNotEmpty()) trascrizioneDettatura.append(" ")
                    trascrizioneDettatura.append(testo)
                }

                val contieneSalva = Regex("\\bsalva\\b", RegexOption.IGNORE_CASE)
                    .containsMatchIn(testo)

                if (elaborazioneInSospesoDopoArresto || contieneSalva) {
                    // Frase finale (o l'ultima rimasta in sospeso dopo un arresto
                    // manuale) già consegnata qui: ora si può elaborare.
                    concludiDettaturaEElabora()
                } else if (inAscolto) {
                    // Continua la dettatura: una nuova sessione dopo ogni pausa.
                    avviaSessioneAscolto()
                }
            }

            override fun onError(error: Int) {
                if (isFinishing || isDestroyed) return
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        if (elaborazioneInSospesoDopoArresto) {
                            // L'arresto manuale non aveva nulla di nuovo da finalizzare:
                            // elabora comunque quanto già accumulato prima dell'arresto.
                            concludiDettaturaEElabora()
                        } else if (inAscolto) {
                            // Nessuna parola riconosciuta in questa pausa: continua ad ascoltare.
                            avviaSessioneAscolto()
                        }
                    }
                    else -> {
                        if (inAscolto || elaborazioneInSospesoDopoArresto) {
                            Toast.makeText(
                                this@MainActivity,
                                "Riconoscimento vocale interrotto, riprova",
                                Toast.LENGTH_SHORT
                            ).show()
                            concludiDettaturaEElabora()
                        }
                    }
                }
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        riconoscitore.startListening(intent)
    }

    /**
     * Richiesta manuale di interruzione (bottone "INTERROMPI ASCOLTO").
     * Chiede solo stopListening(), MAI cancel(): stopListening() fa sì che
     * la frase eventualmente ancora in corso di riconoscimento in quel
     * momento venga comunque finalizzata e consegnata a onResults (o, se
     * non c'era nulla da riconoscere, a onError) invece di essere scartata.
     * L'elaborazione vera e propria avviene quindi lì, non qui.
     */
    private fun richiediArrestoAscolto() {
        elaborazioneInSospesoDopoArresto = true
        impostaStatoBottoneAscolto(ascolto = false)
        riconoscitoreVocale?.stopListening()
    }

    /** Chiamata solo dopo che l'ultimo risultato (o errore) è già arrivato. */
    private fun concludiDettaturaEElabora() {
        inAscolto = false
        elaborazioneInSospesoDopoArresto = false
        riconoscitoreVocale?.cancel()
        impostaStatoBottoneAscolto(ascolto = false)

        // "salva" è la parola chiave usata per confermare: la togliamo dal
        // testo prima di inviarlo all'interprete AI.
        val testo = trascrizioneDettatura.toString()
            .replace(Regex("\\bsalva\\b", RegexOption.IGNORE_CASE), "")
            .trim()
        val salvaRichiesto = Regex("\\bsalva\\b", RegexOption.IGNORE_CASE)
            .containsMatchIn(trascrizioneDettatura.toString())

        if (testo.isBlank()) {
            Toast.makeText(this, "Non ho sentito nessun dato da trascrivere", Toast.LENGTH_SHORT).show()
            return
        }
        elaboraTrascrizione(testo, salvaRichiesto)
    }

    private fun impostaStatoBottoneAscolto(ascolto: Boolean) {
        binding.btnDetta.text = if (ascolto) "⏹  INTERROMPI ASCOLTO" else "🎤  DETTA PRATICA"
    }

    private fun elaboraTrascrizione(testo: String, salvaRichiesto: Boolean) {
        val chiaveApi = ChiaveApiStore.leggiChiave(this)
        Toast.makeText(this, "Interpretazione in corso...", Toast.LENGTH_SHORT).show()

        InterpreteDettatura.interpreta(chiaveApi, testo, object : InterpreteDettatura.Callback {
            override fun onSuccesso(risultato: JSONObject) {
                if (isFinishing || isDestroyed) return
                applicaDatiDettati(risultato)
                Toast.makeText(this@MainActivity, "Scheda compilata dalla dettatura", Toast.LENGTH_SHORT).show()
                if (salvaRichiesto) {
                    salvaCliente()
                }
            }

            override fun onErrore(messaggio: String) {
                if (isFinishing || isDestroyed) return
                binding.etNote.setText(
                    buildString {
                        append(binding.etNote.text?.toString()?.trim().orEmpty())
                        if (isNotEmpty()) append("\n")
                        append("[Dettato, non interpretato automaticamente] ")
                        append(testo)
                    }
                )
                val dialog = AlertDialog.Builder(this@MainActivity, R.style.Theme_SchedaCliente_Dialog)
                    .setTitle("Non sono riuscito a interpretare la dettatura")
                    .setMessage("$messaggio\n\nIl testo dettato è stato messo nelle Note: controlla e compila i campi a mano.")
                    .setPositiveButton("OK", null)
                    .show()
                dialog.rendiPulsantiLeggibili(this@MainActivity)
            }
        })
    }

    /** Riporta nei campi del form i dati estratti dalla dettatura. */
    private fun applicaDatiDettati(json: JSONObject) {
        fun campo(nome: String) = json.optString(nome).trim()

        campo("cognome").let { if (it.isNotBlank()) binding.etCognome.setText(it) }
        campo("nome").let { if (it.isNotBlank()) binding.etNome.setText(it) }
        campo("codiceFiscale").let { if (it.isNotBlank()) binding.etCodiceFiscale.setText(it.uppercase()) }
        campo("partitaIva").let { if (it.isNotBlank()) binding.etPartitaIva.setText(it) }
        campo("telefono").let { if (it.isNotBlank()) binding.etTelefono.setText(it) }
        campo("email").let { if (it.isNotBlank()) binding.etEmail.setText(it) }
        campo("indirizzo").let { if (it.isNotBlank()) binding.etIndirizzo.setText(it) }
        campo("numeroPratica").let { if (it.isNotBlank()) binding.etNumeroPratica.setText(it) }
        campo("annoPratica").let { if (it.isNotBlank()) binding.etAnnoPratica.setText(it) }
        campo("foglio").let { if (it.isNotBlank()) binding.etFoglio.setText(it) }
        campo("particella").let { if (it.isNotBlank()) binding.etParticella.setText(it) }
        campo("subalterno").let { if (it.isNotBlank()) binding.etSubalterno.setText(it) }
        campo("note").let { if (it.isNotBlank()) binding.etNote.setText(it) }

        val tipoDettato = campo("tipoPratica")
        if (tipoDettato.isNotBlank()) {
            val indice = tipiPratica.indexOfFirst { it.equals(tipoDettato, ignoreCase = true) }
            if (indice >= 0) binding.spinnerTipoPratica.setSelection(indice)
        }

        val provinciaDettata = campo("provincia")
        val comuneDettato = campo("comune")
        if (provinciaDettata.isNotBlank()) {
            val province = ComuniProvider.province(this)
            val provinciaReale = province.firstOrNull { it.equals(provinciaDettata, ignoreCase = true) }
            if (provinciaReale != null) {
                provinciaSelezionata = provinciaReale
                binding.autoProvincia.setText(provinciaReale, false)
                binding.autoComune.isEnabled = true
                binding.autoComune.hint = "Seleziona comune..."

                val comuni = ComuniProvider.comuniDi(this, provinciaReale)
                binding.autoComune.setAdapter(
                    ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, comuni)
                )

                if (comuneDettato.isNotBlank()) {
                    val comuneReale = comuni.firstOrNull { it.equals(comuneDettato, ignoreCase = true) }
                    if (comuneReale != null) {
                        comuneSelezionato = comuneReale
                        binding.autoComune.setText(comuneReale, false)
                    }
                }
            }
        }
    }
}
