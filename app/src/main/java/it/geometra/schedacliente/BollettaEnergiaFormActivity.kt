package it.geometra.schedacliente

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.material.textfield.TextInputLayout
import it.geometra.schedacliente.databinding.ActivityBollettaEnergiaFormBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Un allegato (foto o PDF) mostrato nella riga di miniature del form: può essere uno già salvato
 * in precedenza ([Esistente]) oppure uno appena scelto/scattato in questa sessione del form
 * ([Nuovo], non ancora copiato nella cartella del record finché non si salva la bolletta).
 */
private sealed class VoceAllegato {
    abstract val uri: Uri
    abstract val estensione: String
    abstract val nomeVisualizzato: String

    data class Esistente(val nomeFile: String, override val uri: Uri, override val estensione: String) : VoceAllegato() {
        override val nomeVisualizzato: String get() = nomeFile
    }

    data class Nuovo(override val uri: Uri, override val estensione: String, override val nomeVisualizzato: String) : VoceAllegato()
}

/**
 * Form di inserimento/modifica di una bolletta di energia elettrica condivisa tra lo studio e
 * l'abitazione (sezione "Ripartizione Bolletta Energia Elettrica"): raccoglie i dati della
 * bolletta e delle letture del contatore dello studio, mostra dal vivo il calcolo della quota
 * spettante allo studio (vedi [RipartizioneBollettaCalcolatore]), permette di allegare più foto o
 * PDF della bolletta e di farne riconoscere automaticamente alcuni dati con l'OCR (vedi
 * [OcrBolletta]) — sempre e solo come pre-compilazione da verificare, mai salvata da sola — e di
 * esportare il prospetto di ripartizione in PDF.
 */
class BollettaEnergiaFormActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBollettaEnergiaFormBinding

    private var idBollettaInModifica: Long? = null
    private var dataCreazioneOriginale: Long = System.currentTimeMillis()

    /** Tutti gli allegati attualmente collegati al form (già salvati o appena aggiunti), nell'ordine di aggiunta. */
    private val allegati = mutableListOf<VoceAllegato>()

    private var uriFotoScattata: Uri? = null

    /** Dati del prospetto in attesa di essere scritti su file non appena [esportaPdfLauncher] restituisce l'Uri scelto. */
    private var datiPdfInAttesa: DatiProspettoPdf? = null

    companion object {
        const val EXTRA_BOLLETTA_ID = BollettaEnergiaListaActivity.EXTRA_BOLLETTA_ID
    }

    private val sceltaFileLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            uris.forEach { gestisciNuovoAllegato(it) }
        }

    private val scattaFotoLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { riuscita ->
            val uri = uriFotoScattata
            if (riuscita && uri != null) {
                gestisciNuovoAllegato(uri)
            } else if (!riuscita) {
                Toast.makeText(this, "Foto annullata", Toast.LENGTH_SHORT).show()
            }
        }

    private val permessoFotocameraLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { concesso ->
            if (concesso) avviaScattoFoto() else Toast.makeText(this, "Permesso fotocamera negato", Toast.LENGTH_SHORT).show()
        }

    private val esportaPdfLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
            if (uri != null) scriviPdfProspettoSuUri(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBollettaEnergiaFormBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val idEsistente = intent.getLongExtra(EXTRA_BOLLETTA_ID, -1L).takeIf { it != -1L }
        if (idEsistente != null) {
            val bolletta = BollettaEnergiaStore.carica(this, idEsistente)
            if (bolletta == null) {
                Toast.makeText(this, "Bolletta non trovata", Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            popolaCampi(bolletta)
        }

        collegaCampoCalcolo(binding.etConsumoTotale, binding.tilConsumoTotale)
        collegaCampoCalcolo(binding.etImportoTotale, binding.tilImportoTotale)
        collegaCampoCalcolo(binding.etCanoneFisso, binding.tilCanoneFisso)
        collegaCampoCalcolo(binding.etCostoPotenza, binding.tilCostoPotenza)
        collegaCampoCalcolo(binding.etLetturaIniziale, binding.tilLetturaIniziale)
        collegaCampoCalcolo(binding.etLetturaFinale, binding.tilLetturaFinale)
        binding.etNomeProprietario.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                binding.etNomeProprietario.error = null
                aggiornaRiepilogo()
            }
        })

        binding.btnScattaFotoBolletta.setOnClickListener { richiediPermessoEScattaFoto() }
        binding.btnScegliFileBolletta.setOnClickListener {
            sceltaFileLauncher.launch(arrayOf("image/*", "application/pdf"))
        }
        binding.btnRiconosciOcr.setOnClickListener { avviaOcr() }
        binding.btnSalvaBolletta.setOnClickListener { salvaBolletta() }
        binding.btnEsportaPdfBolletta.setOnClickListener { avviaEsportazionePdfProspetto() }

        ridisegnaAllegati()
        aggiornaRiepilogo()
    }

    // ---------------------------------------------------------------------
    // Caricamento di una bolletta esistente (modifica)
    // ---------------------------------------------------------------------

    private fun popolaCampi(bolletta: BollettaEnergia) {
        idBollettaInModifica = bolletta.id
        dataCreazioneOriginale = bolletta.dataCreazione
        binding.toolbar.title = "Modifica bolletta"

        binding.etNomeProprietario.setText(bolletta.nomeProprietario)
        binding.etConsumoTotale.setText(formattaNumero(bolletta.consumoTotaleKwh))
        binding.etImportoTotale.setText(formattaNumero(bolletta.importoTotale))
        binding.etCanoneFisso.setText(formattaNumero(bolletta.canoneFisso))
        binding.etCostoPotenza.setText(formattaNumero(bolletta.costoPotenzaUnaTantum))
        binding.etLetturaIniziale.setText(formattaNumero(bolletta.letturaIniziale))
        binding.etLetturaFinale.setText(formattaNumero(bolletta.letturaFinale))
        binding.etNote.setText(bolletta.note)

        binding.btnEliminaBolletta.visibility = View.VISIBLE
        binding.btnEliminaBolletta.setOnClickListener { confermaEliminazione(bolletta) }

        BollettaEnergiaStore.fileAllegati(this, bolletta).forEach { file ->
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            allegati.add(VoceAllegato.Esistente(file.name, uri, file.extension.lowercase(Locale.ITALY)))
        }
        if (allegati.isNotEmpty()) binding.btnRiconosciOcr.isEnabled = true
    }

    // ---------------------------------------------------------------------
    // Allegati (foto/PDF) — più di uno per bolletta
    // ---------------------------------------------------------------------

    private fun richiediPermessoEScattaFoto() {
        val concesso = ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (concesso) avviaScattoFoto() else permessoFotocameraLauncher.launch(android.Manifest.permission.CAMERA)
    }

    private fun avviaScattoFoto() {
        val cartella = File(cacheDir, "fotocamera").apply { mkdirs() }
        // Il nome include orario + un contatore, così più foto scattate in rapida successione
        // ottengono sempre file diversi e non si sovrascrivono a vicenda.
        val file = File(cartella, "bolletta_${System.currentTimeMillis()}_${allegati.size}.jpg")
        val uri = try {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        } catch (e: Exception) {
            Toast.makeText(this, "Non sono riuscito a preparare la fotocamera: ${e.message}", Toast.LENGTH_SHORT).show()
            return
        }
        uriFotoScattata = uri
        scattaFotoLauncher.launch(uri)
    }

    private fun gestisciNuovoAllegato(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
            // Non tutti i provider la supportano (es. l'Uri della fotocamera): non è bloccante.
        }
        val tipo = contentResolver.getType(uri) ?: ""
        val isPdf = tipo == "application/pdf" || uri.toString().lowercase(Locale.ITALY).endsWith(".pdf")
        val estensione = when {
            isPdf -> "pdf"
            tipo.contains("png") -> "png"
            else -> "jpg"
        }
        allegati.add(VoceAllegato.Nuovo(uri, estensione, nomeFileDaUri(uri)))
        ridisegnaAllegati()
        binding.btnRiconosciOcr.isEnabled = true
    }

    private fun rimuoviAllegato(indice: Int) {
        if (indice !in allegati.indices) return
        allegati.removeAt(indice)
        ridisegnaAllegati()
        binding.btnRiconosciOcr.isEnabled = allegati.isNotEmpty()
    }

    /** Ridisegna la riga di miniature in base al contenuto attuale di [allegati]. */
    private fun ridisegnaAllegati() {
        binding.llAnteprimeAllegati.removeAllViews()
        if (allegati.isEmpty()) {
            binding.tvNessunAllegato.visibility = View.VISIBLE
            binding.scrollAnteprimeAllegati.visibility = View.GONE
            return
        }
        binding.tvNessunAllegato.visibility = View.GONE
        binding.scrollAnteprimeAllegati.visibility = View.VISIBLE

        allegati.forEachIndexed { indice, voce ->
            val vista = LayoutInflater.from(this).inflate(R.layout.item_allegato_bolletta, binding.llAnteprimeAllegati, false)
            val iv = vista.findViewById<ImageView>(R.id.ivMiniaturaAllegato)
            val btnRimuovi = vista.findViewById<TextView>(R.id.btnRimuoviAllegato)

            val miniatura = if (voce.estensione == "pdf") {
                OcrBolletta.renderizzaPrimaPaginaPdf(this, voce.uri)
            } else {
                OcrBolletta.decodificaImmagine(this, voce.uri, 300)
            }
            if (miniatura != null) iv.setImageBitmap(miniatura)

            btnRimuovi.setOnClickListener { rimuoviAllegato(indice) }
            binding.llAnteprimeAllegati.addView(vista)
        }
    }

    private fun nomeFileDaUri(uri: Uri): String {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { cursore ->
                val indice = cursore.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (indice >= 0 && cursore.moveToFirst()) cursore.getString(indice) else null
            } ?: (uri.lastPathSegment ?: "Allegato")
        } catch (e: Exception) {
            uri.lastPathSegment ?: "Allegato"
        }
    }

    // ---------------------------------------------------------------------
    // OCR: lettura automatica dei dati da tutti gli allegati
    // ---------------------------------------------------------------------

    /**
     * Esegue il riconoscimento testo su ogni allegato e unisce tutto il testo riconosciuto prima
     * di analizzarlo: così, se un dato compare in una foto/pagina e un altro in una diversa (es.
     * il totale nella prima pagina del PDF e il consumo in una pagina successiva), i campi vengono
     * comunque pre-compilati. Un PDF viene rasterizzato in TUTTE le sue pagine (non solo la
     * prima): i dati richiesti possono trovarsi in un riepilogo su una pagina successiva.
     */
    private fun avviaOcr() {
        if (allegati.isEmpty()) {
            Toast.makeText(this, "Allega prima almeno una foto o un PDF della bolletta", Toast.LENGTH_SHORT).show()
            return
        }
        binding.btnRiconosciOcr.isEnabled = false
        binding.btnRiconosciOcr.text = "Riconoscimento in corso..."
        val bitmap = raccogliBitmapDaRiconoscere()
        if (bitmap.isEmpty()) {
            Toast.makeText(this, "Non sono riuscito a leggere gli allegati per il riconoscimento", Toast.LENGTH_SHORT).show()
            ripristinaBottoneOcr()
            return
        }
        elaboraBitmapOcr(bitmap, 0, StringBuilder())
    }

    /** Trasforma ogni allegato in uno o più bitmap da passare all'OCR (un PDF conta tutte le sue pagine). */
    private fun raccogliBitmapDaRiconoscere(): List<android.graphics.Bitmap> {
        val lista = mutableListOf<android.graphics.Bitmap>()
        allegati.forEach { voce ->
            if (voce.estensione == "pdf") {
                lista.addAll(OcrBolletta.renderizzaPaginePdf(this, voce.uri))
            } else {
                OcrBolletta.decodificaImmagine(this, voce.uri)?.let { lista.add(it) }
            }
        }
        return lista
    }

    private fun elaboraBitmapOcr(bitmap: List<android.graphics.Bitmap>, indice: Int, testoCombinato: StringBuilder) {
        if (indice >= bitmap.size) {
            applicaDatiOcr(OcrBolletta.analizzaTesto(testoCombinato.toString()))
            ripristinaBottoneOcr()
            return
        }
        OcrBolletta.riconosciTesto(
            bitmap[indice],
            onSuccesso = { testo ->
                testoCombinato.append(testo).append("\n")
                elaboraBitmapOcr(bitmap, indice + 1, testoCombinato)
            },
            onErrore = { _ ->
                elaboraBitmapOcr(bitmap, indice + 1, testoCombinato)
            }
        )
    }

    private fun ripristinaBottoneOcr() {
        binding.btnRiconosciOcr.isEnabled = true
        binding.btnRiconosciOcr.text = "🔍 Riconosci dati automaticamente"
    }

    /**
     * Pre-compila i campi riconosciuti dall'OCR, marcandoli visivamente come "da verificare"
     * (vedi [marcaDaVerificare]): non viene salvato nulla qui, è solo un aiuto per velocizzare
     * l'inserimento, da controllare sempre prima di toccare "Salva bolletta". Le letture del
     * contatore dello studio non vengono mai toccate: non compaiono sulla bolletta.
     */
    private fun applicaDatiOcr(dati: DatiEstrattiBolletta) {
        var trovatoQualcosa = false
        if (dati.importoTotale != null) {
            binding.etImportoTotale.setText(formattaNumero(dati.importoTotale))
            marcaDaVerificare(binding.tilImportoTotale)
            trovatoQualcosa = true
        }
        if (dati.consumoTotaleKwh != null) {
            binding.etConsumoTotale.setText(formattaNumero(dati.consumoTotaleKwh))
            marcaDaVerificare(binding.tilConsumoTotale)
            trovatoQualcosa = true
        }
        aggiornaRiepilogo()
        Toast.makeText(
            this,
            if (trovatoQualcosa) "Dati riconosciuti: controllali prima di salvare"
            else "Non sono riuscito a riconoscere automaticamente i dati: inseriscili a mano",
            Toast.LENGTH_LONG
        ).show()
    }

    private val coloreAvviso by lazy { Color.parseColor("#8A6D00") }

    private fun marcaDaVerificare(til: TextInputLayout) {
        til.isHelperTextEnabled = true
        til.helperText = "⚠ Da verificare (dato letto automaticamente)"
        til.setHelperTextColor(ColorStateList.valueOf(coloreAvviso))
        til.boxStrokeColor = coloreAvviso
    }

    private fun resetMarcatura(til: TextInputLayout) {
        if (til.helperText == null) return
        til.isHelperTextEnabled = false
        til.helperText = null
        til.boxStrokeColor = ContextCompat.getColor(this, R.color.primary)
    }

    // ---------------------------------------------------------------------
    // Calcolo live del riepilogo
    // ---------------------------------------------------------------------

    private fun collegaCampoCalcolo(et: EditText, til: TextInputLayout) {
        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                et.error = null
                resetMarcatura(til)
                aggiornaRiepilogo()
            }
        })
    }

    private fun aggiornaRiepilogo() {
        val consumoTotale = parseCampo(binding.etConsumoTotale)
        val importoTotale = parseCampo(binding.etImportoTotale)
        val canone = parseCampo(binding.etCanoneFisso) ?: 0.0
        val costoPotenza = parseCampo(binding.etCostoPotenza) ?: 0.0
        val letturaIniziale = parseCampo(binding.etLetturaIniziale)
        val letturaFinale = parseCampo(binding.etLetturaFinale)

        if (consumoTotale == null || importoTotale == null || letturaIniziale == null || letturaFinale == null) {
            binding.tvRiepilogoCalcolo.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            binding.tvRiepilogoCalcolo.text = "Compila i campi sopra per vedere il calcolo."
            return
        }

        try {
            RipartizioneBollettaCalcolatore.valida(consumoTotale, importoTotale, canone, costoPotenza, letturaIniziale, letturaFinale)
            val esito = RipartizioneBollettaCalcolatore.calcola(consumoTotale, importoTotale, canone, costoPotenza, letturaIniziale, letturaFinale)
            val nomeProprietario = nomeProprietarioCorrente()
            binding.tvRiepilogoCalcolo.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            binding.tvRiepilogoCalcolo.text = buildString {
                append("Consumo studio: ${fmt(esito.consumoStudioKwh)} kWh\n")
                append("Consumo $nomeProprietario: ${fmt(esito.consumoProprietarioKwh)} kWh\n")
                append("Costo unitario: ${fmt(esito.costoUnitarioKwh)} €/kWh\n\n")
                append("Quota studio: ${fmt(esito.quotaStudio)} €\n")
                append("Quota $nomeProprietario: ${fmt(esito.quotaProprietario)} €")
            }
        } catch (e: BollettaValidazioneException) {
            binding.tvRiepilogoCalcolo.setTextColor(Color.parseColor("#B3261E"))
            binding.tvRiepilogoCalcolo.text = e.message
        }
    }

    private fun nomeProprietarioCorrente(): String =
        binding.etNomeProprietario.text?.toString()?.trim()?.ifBlank { null } ?: "il proprietario"

    // ---------------------------------------------------------------------
    // Salvataggio
    // ---------------------------------------------------------------------

    /**
     * Il pulsante Salva si disattiva al primo tocco e torna attivo solo se il salvataggio non
     * va a buon fine (campi mancanti o errore di scrittura); se riesce la schermata si chiude.
     * Così un doppio tap non può avviare due scritture sugli stessi file.
     */
    private fun salvaBolletta() {
        if (!binding.btnSalvaBolletta.isEnabled) return
        binding.btnSalvaBolletta.isEnabled = false
        var chiusa = false
        try {
            chiusa = eseguiSalvataggioBolletta()
        } finally {
            if (!chiusa) binding.btnSalvaBolletta.isEnabled = true
        }
    }

    /** Restituisce true se la bolletta è stata salvata e la schermata si sta chiudendo. */
    private fun eseguiSalvataggioBolletta(): Boolean {
        val nome = binding.etNomeProprietario.text?.toString()?.trim().orEmpty()
        if (nome.isBlank()) {
            binding.etNomeProprietario.error = "Campo obbligatorio"
            return false
        }

        val consumoTotale = parseCampo(binding.etConsumoTotale)
        if (consumoTotale == null) {
            binding.etConsumoTotale.error = "Campo obbligatorio"
            return false
        }
        val importoTotale = parseCampo(binding.etImportoTotale)
        if (importoTotale == null) {
            binding.etImportoTotale.error = "Campo obbligatorio"
            return false
        }
        val canone = parseCampo(binding.etCanoneFisso) ?: 0.0
        val costoPotenza = parseCampo(binding.etCostoPotenza) ?: 0.0
        val letturaIniziale = parseCampo(binding.etLetturaIniziale)
        if (letturaIniziale == null) {
            binding.etLetturaIniziale.error = "Campo obbligatorio"
            return false
        }
        val letturaFinale = parseCampo(binding.etLetturaFinale)
        if (letturaFinale == null) {
            binding.etLetturaFinale.error = "Campo obbligatorio"
            return false
        }

        try {
            RipartizioneBollettaCalcolatore.valida(consumoTotale, importoTotale, canone, costoPotenza, letturaIniziale, letturaFinale)
        } catch (e: BollettaValidazioneException) {
            Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
            return false
        }

        val id = idBollettaInModifica ?: IdUnivoci.nuovo(BollettaEnergiaStore.caricaTutte(this).map { it.id }.toSet())
        val adesso = System.currentTimeMillis()
        val bolletta = BollettaEnergia(
            id = id,
            nomeProprietario = nome,
            consumoTotaleKwh = consumoTotale,
            importoTotale = importoTotale,
            canoneFisso = canone,
            costoPotenzaUnaTantum = costoPotenza,
            letturaIniziale = letturaIniziale,
            letturaFinale = letturaFinale,
            note = binding.etNote.text?.toString().orEmpty(),
            dataCreazione = if (idBollettaInModifica != null) dataCreazioneOriginale else adesso,
            ultimaModifica = adesso
        )

        val nomiEsistentiDaMantenere = allegati.filterIsInstance<VoceAllegato.Esistente>().map { it.nomeFile }
        val nuoviAllegati = allegati.filterIsInstance<VoceAllegato.Nuovo>().map { it.uri to it.estensione }

        try {
            BollettaEnergiaStore.salva(this, bolletta, nuoviAllegati, nomiEsistentiDaMantenere)
            Toast.makeText(this, "Bolletta salvata", Toast.LENGTH_SHORT).show()
            finish()
            return true
        } catch (e: Exception) {
            Toast.makeText(this, "Non sono riuscito a salvare: ${e.message}", Toast.LENGTH_LONG).show()
            return false
        }
    }

    private fun confermaEliminazione(bolletta: BollettaEnergia) {
        val dialog = AlertDialog.Builder(this, R.style.Theme_SchedaCliente_Dialog)
            .setTitle("Eliminare questa bolletta?")
            .setMessage("La bolletta del ${bolletta.dataCreazioneFormattata()} e i relativi allegati verranno eliminati definitivamente.")
            .setPositiveButton("Elimina") { _, _ ->
                BollettaEnergiaStore.elimina(this, bolletta.id)
                Toast.makeText(this, "Bolletta eliminata", Toast.LENGTH_SHORT).show()
                finish()
            }
            .setNegativeButton("Annulla", null)
            .show()
        dialog.rendiPulsantiLeggibili(this)
    }

    // ---------------------------------------------------------------------
    // Esportazione PDF del prospetto
    // ---------------------------------------------------------------------

    private data class DatiProspettoPdf(
        val nome: String,
        val consumoTotale: Double,
        val importoTotale: Double,
        val canone: Double,
        val costoPotenza: Double,
        val letturaIniziale: Double,
        val letturaFinale: Double,
        val esito: RipartizioneBolletta,
        val note: String
    )

    private fun avviaEsportazionePdfProspetto() {
        val nome = binding.etNomeProprietario.text?.toString()?.trim().orEmpty()
        val consumoTotale = parseCampo(binding.etConsumoTotale)
        val importoTotale = parseCampo(binding.etImportoTotale)
        val canone = parseCampo(binding.etCanoneFisso) ?: 0.0
        val costoPotenza = parseCampo(binding.etCostoPotenza) ?: 0.0
        val letturaIniziale = parseCampo(binding.etLetturaIniziale)
        val letturaFinale = parseCampo(binding.etLetturaFinale)

        if (nome.isBlank() || consumoTotale == null || importoTotale == null || letturaIniziale == null || letturaFinale == null) {
            Toast.makeText(this, "Completa tutti i campi obbligatori prima di esportare il PDF", Toast.LENGTH_LONG).show()
            return
        }

        val esito = try {
            RipartizioneBollettaCalcolatore.valida(consumoTotale, importoTotale, canone, costoPotenza, letturaIniziale, letturaFinale)
            RipartizioneBollettaCalcolatore.calcola(consumoTotale, importoTotale, canone, costoPotenza, letturaIniziale, letturaFinale)
        } catch (e: BollettaValidazioneException) {
            Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
            return
        }

        datiPdfInAttesa = DatiProspettoPdf(
            nome, consumoTotale, importoTotale, canone, costoPotenza,
            letturaIniziale, letturaFinale, esito, binding.etNote.text?.toString().orEmpty()
        )
        val nomeFile = "Ripartizione_bolletta_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.ITALY).format(Date()) + ".pdf"
        esportaPdfLauncher.launch(nomeFile)
    }

    private fun scriviPdfProspettoSuUri(uri: Uri) {
        val dati = datiPdfInAttesa ?: return
        var documento: PdfDocument? = null
        try {
            documento = generaPdfProspetto(dati)
            contentResolver.openOutputStream(uri)?.use { out -> documento.writeTo(out) }
            Toast.makeText(this, "PDF salvato correttamente", Toast.LENGTH_SHORT).show()
            offriAperturaPdf(uri)
        } catch (e: Exception) {
            Toast.makeText(this, "Non sono riuscito a salvare il PDF: ${e.message}", Toast.LENGTH_SHORT).show()
        } finally {
            documento?.close()
        }
    }

    private fun generaPdfProspetto(dati: DatiProspettoPdf): PdfDocument {
        val documento = PdfDocument()
        val larghezzaPagina = 595
        val altezzaPagina = 842
        val margine = 40f
        val pagina = documento.startPage(PdfDocument.PageInfo.Builder(larghezzaPagina, altezzaPagina, 1).create())
        val canvas = pagina.canvas

        val paintTitolo = Paint().apply { color = Color.BLACK; textSize = 18f; isFakeBoldText = true }
        val paintEtichetta = Paint().apply { color = Color.BLACK; textSize = 12f; isFakeBoldText = true }
        val paintTesto = Paint().apply { color = Color.BLACK; textSize = 12f }
        val paintTotale = Paint().apply { color = Color.BLACK; textSize = 14f; isFakeBoldText = true }
        val paintPiePagina = Paint().apply { color = Color.DKGRAY; textSize = 9f }

        var y = margine
        canvas.drawText("Ripartizione bolletta energia elettrica", margine, y, paintTitolo)
        y += 26f
        canvas.drawText("Intestatario bolletta: ${dati.nome}", margine, y, paintTesto)
        y += 30f

        fun riga(etichetta: String, valore: String) {
            canvas.drawText(etichetta, margine, y, paintEtichetta)
            canvas.drawText(valore, margine + 230f, y, paintTesto)
            y += 20f
        }

        riga("Consumo totale bolletta", "${fmt(dati.consumoTotale)} kWh")
        riga("Importo totale bolletta", "${fmt(dati.importoTotale)} €")
        riga("Canone/quota fissa", "${fmt(dati.canone)} €")
        riga("Costo una tantum aumento potenza", "${fmt(dati.costoPotenza)} €")
        riga("Lettura iniziale contatore studio", "${fmt(dati.letturaIniziale)} kWh")
        riga("Lettura finale contatore studio", "${fmt(dati.letturaFinale)} kWh")

        y += 8f
        canvas.drawLine(margine, y, larghezzaPagina - margine, y, paintTesto)
        y += 22f

        riga("Consumo studio", "${fmt(dati.esito.consumoStudioKwh)} kWh")
        riga("Consumo ${dati.nome}", "${fmt(dati.esito.consumoProprietarioKwh)} kWh")
        riga("Costo unitario", "${fmt(dati.esito.costoUnitarioKwh)} €/kWh")

        y += 12f
        canvas.drawText("Quota a carico dello studio: ${fmt(dati.esito.quotaStudio)} €", margine, y, paintTotale)
        y += 22f
        canvas.drawText("Quota a carico di ${dati.nome}: ${fmt(dati.esito.quotaProprietario)} €", margine, y, paintTotale)
        y += 30f

        if (dati.note.isNotBlank()) {
            canvas.drawText("Note:", margine, y, paintEtichetta)
            y += 18f
            y = disegnaTestoAvvolto(canvas, dati.note, margine, y, paintTesto, larghezzaPagina - margine * 2)
        }

        val sdfOra = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALY)
        canvas.drawText(
            "Prospetto generato il ${sdfOra.format(Date())}",
            margine,
            altezzaPagina - margine / 2,
            paintPiePagina
        )

        documento.finishPage(pagina)
        return documento
    }

    /** Spezza [testo] su più righe non più larghe di [larghezzaMax], come in PlanimetriaActivity.generaPdf(). */
    private fun disegnaTestoAvvolto(canvas: android.graphics.Canvas, testo: String, x: Float, yIniziale: Float, paint: Paint, larghezzaMax: Float): Float {
        var y = yIniziale
        val interlinea = paint.textSize + 3f
        var resto = testo
        while (resto.isNotEmpty()) {
            val contate = paint.breakText(resto, true, larghezzaMax, null)
            var fine = contate
            if (fine < resto.length) {
                val ultimoSpazio = resto.lastIndexOf(' ', fine - 1)
                if (ultimoSpazio > 0) fine = ultimoSpazio
            }
            val riga = resto.substring(0, fine).trimEnd()
            canvas.drawText(riga, x, y, paint)
            y += interlinea
            resto = resto.substring(fine).trimStart()
        }
        return y
    }

    private fun offriAperturaPdf(uri: Uri) {
        val dialog = AlertDialog.Builder(this, R.style.Theme_SchedaCliente_Dialog)
            .setTitle("Aprire il file?")
            .setMessage("Vuoi aprire subito il PDF appena salvato?")
            .setPositiveButton("Apri") { _, _ ->
                try {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/pdf")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(Intent.createChooser(intent, "Apri con"))
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(this, "Nessuna app sul telefono sa aprire questo tipo di file", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Chiudi", null)
            .show()
        dialog.rendiPulsantiLeggibili(this)
    }

    // ---------------------------------------------------------------------
    // Utilità di formato
    // ---------------------------------------------------------------------

    private fun parseCampo(et: EditText): Double? = et.text?.toString()?.trim()?.replace(",", ".")?.toDoubleOrNull()

    private fun formattaNumero(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    private fun fmt(v: Double): String = String.format(Locale.ITALY, "%.2f", v)
}
