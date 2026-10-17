package it.geometra.schedacliente

import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.text.InputType
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import it.geometra.schedacliente.databinding.ActivityPlanimetriaBinding
import it.geometra.schedacliente.databinding.DialogTracciamentoSchermoInteroBinding
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class PlanimetriaActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlanimetriaBinding
    /** Handler unico per i postDelayed di questa Activity (es. animazione del rilevamento automatico): cancellato in onDestroy per non toccare la UI dopo che la schermata è stata chiusa. */
    private val handlerPrincipale = Handler(Looper.getMainLooper())

    /**
     * True dal momento in cui si apre la finestra "Salva progetto" fino alla fine della
     * scrittura su file: impedisce che un doppio tap apra due finestre o avvii due salvataggi
     * concorrenti (che scriverebbero gli stessi file insieme).
     */
    private var salvataggioInCorso = false
    private var immagineSelezionata: Uri? = null
    private var eraFilePdf = false
    private var bitmapPdfRenderizzato: Bitmap? = null
    private var paginaPdfLarghezzaPt: Float? = null
    private var bitmapRenderPx: Int? = null

    private enum class Modalita { NESSUNA, CALIBRAZIONE, TRACCIAMENTO, MODIFICA_LOCALE }
    private enum class DirezioneScala { ORIZZONTALE, VERTICALE, OBLIQUA }

    private var modalita = Modalita.NESSUNA
    private var scalaX: Double? = null
    /** Zone del foglio con muri pieni (piante), calcolate all'avvio del rilevamento automatico. */
    private var zoneDisegno: List<Rect>? = null
    /** Tipo di disegno riconosciuto nell'ultima misura dei contorni (mostrato all'utente). */
    @Volatile private var ultimaDescrizioneDisegno: String? = null
    private var scalaY: Double? = null
    private var direzioneCalibrazioneCorrente = DirezioneScala.OBLIQUA
    private var calibrazioneEntrambeInCorso = false
    private var calibrazioneUltimoStep = true
    private var calibrazioneConParametri = false
    private var scalaXTemporanea: Double? = null

    private val locali = mutableListOf<LocaleTracciato>()
    private var indiceLocaleInModifica: Int? = null

    /** Nome nuovo dato con "✏️ Nome" al locale in correzione (null = invariato); applicato alla conferma. */
    private var nomeModificato: String? = null

    /** Un locale individuato dall'AI con una stima (approssimativa) dei suoi vertici in pixel, non ancora verificato dall'utente. */
    /**
     * Locale proposto dal rilevamento automatico. [puntoInterno] è il punto dentro al vano indicato
     * dall'AI; [precisa] = true quando il contorno è stato misurato dal telefono sui muri del
     * disegno (SegmentazionePlanimetria) e non stimato a occhio dall'AI: in quel caso non serve
     * l'affinamento AI successivo.
     */
    private data class PropostaLocaleAi(
        val nome: String,
        val verticiPx: List<PointF>,
        val puntoInterno: PointF? = null,
        val precisa: Boolean = false
    )
    private var proposteAi: MutableList<PropostaLocaleAi> = mutableListOf()
    private var proposizioneInVerifica: PropostaLocaleAi? = null

    /** Rilevamento automatico sequenziale (scala dal reticolo/segmento + vertici AI, con animazione). */
    private var rilevamentoAutomaticoInCorso = false
    private var codaRilevamentoAi: MutableList<PropostaLocaleAi> = mutableListOf()
    /**
     * Numero progressivo incrementato ogni volta che si passa a elaborare un nuovo locale (o lo
     * si salta). Ogni passo asincrono (chiamata AI, animazione ritardata) cattura il valore
     * corrente all'avvio e lo confronta con questo campo prima di agire: se nel frattempo si è
     * passati a un altro locale (es. "Salta locale" premuto mentre una risposta AI per quello
     * precedente era ancora in arrivo), il confronto fallisce e il passo non fa nulla invece di
     * applicare dati del locale sbagliato alla vista già passata al locale successivo (causa
     * dei vertici "a caso" comparsi e scomparsi segnalati dall'utente: con il semplice flag
     * booleano usato in precedenza, il passaggio al locale successivo lo resettava subito,
     * prima ancora che la risposta AI in ritardo del locale saltato arrivasse.
     */
    private var generazioneElaborazioneLocale = 0
    /**
     * Numero della "sessione" di rilevamento automatico: cambia a ogni avvio e a ogni fine o
     * interruzione (vedi terminaOperazioneInCorso). Le risposte AI e i calcoli in background della
     * fase iniziale (scala, piante, misura dei contorni sui muri) catturano il valore all'avvio e,
     * se nel frattempo il rilevamento è stato interrotto o riavviato, non fanno nulla.
     * CORREZIONE v3.76: prima "Interrompi" premuto in questa fase non fermava le risposte già in
     * viaggio, che facevano ripartire il rilevamento da sole (con chiamate AI non volute e, se nel
     * frattempo si caricava un'altra planimetria, locali della vecchia disegnati sulla nuova).
     */
    private var sessioneRilevamento = 0
    /** true solo quando si stanno esaminando i locali uno alla volta (dopo la fase iniziale): serve a "Salta locale". */
    private var faseLocaliInCorso = false
    /**
     * true mentre è a schermo il dialogo "Interrompere il rilevamento?" (vedi
     * [confermaAnnullaRilevamentoAutomatico]): finché l'utente non preme "Interrompi" o
     * "Continua" il rilevamento resta in pausa, cioè il conto alla rovescia di "Conferma così"
     * (vedi [chiediCorrezioneManuale]) e la rivelazione animata dei vertici (vedi
     * [avviaAnimazioneVertici]) si fermano, invece di proseguire in sottofondo mentre l'utente
     * sta ancora decidendo. Prima di questa correzione il conto alla rovescia ignorava il
     * dialogo sovrapposto e poteva confermare un locale (o rivelarne i vertici) senza che
     * l'utente se ne accorgesse, proprio mentre stava valutando se interrompere tutto.
     */
    private var rilevamentoInPausaPerConferma = false

    private fun sessioneAttiva(sessione: Int): Boolean =
        !isFinishing && !isDestroyed && rilevamentoAutomaticoInCorso && sessione == sessioneRilevamento

    /** Azione da eseguire subito dopo che l'utente ha impostato una scala con il tocco di due punti. */
    private var azioneDopoCalibrazione: (() -> Unit)? = null
    /**
     * Locali ancora da esaminare lasciati da un rilevamento automatico interrotto con
     * "Interrompi rilevamento" (null se nessun rilevamento è stato interrotto, o se quello
     * interrotto è già stato ripreso o scartato). Permette di tornare sulla planimetria senza
     * perdere il lavoro dell'AI già fatto su questi locali, e poi riprendere da dove ci si era
     * fermati con "Rileva locali automaticamente" invece di dover rianalizzare tutta la
     * planimetria da capo.
     */
    private var codaRilevamentoSospesa: MutableList<PropostaLocaleAi>? = null

    private var dialogSchermoIntero: Dialog? = null
    private var bindingSchermoIntero: DialogTracciamentoSchermoInteroBinding? = null
    private var genitoreOriginaleSuperficie: ViewGroup? = null
    private var indiceOriginaleSuperficie: Int = -1
    private var layoutParamsOriginaliSuperficie: ViewGroup.LayoutParams? = null

    private val sceltaFileLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) gestisciFileScelto(uri)
        }

    private val esportaPdfLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
            if (uri != null) scriviPdfSuUri(uri)
        }

    private val esportaDxfLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/dxf")) { uri ->
            if (uri != null) scriviDxfSuUri(uri)
        }

    private var uriFotoScattata: Uri? = null

    private val scattaFotoLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { riuscita ->
            val uri = uriFotoScattata
            if (riuscita && uri != null) {
                gestisciFileScelto(uri)
            } else if (!riuscita) {
                avviso("Foto annullata")
            }
        }

    private val permessoFotocameraLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { concesso ->
            if (concesso) avviaScattoFoto() else avviso("Permesso fotocamera negato")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlanimetriaBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }
        NavigazioneIndietro.installa(this)

        binding.btnCaricaImmagine.setOnClickListener {
            sceltaFileLauncher.launch(arrayOf("image/*", "application/pdf"))
        }
        binding.btnScattaFoto.setOnClickListener { richiediPermessoEScattaFoto() }

        binding.btnAnalizza.setOnClickListener { avviaAnalisi() }
        binding.btnRifaiPoligoni.setOnClickListener { chiediCancellaTuttiERifai() }
        binding.btnRilevamentoAutomatico.setOnClickListener { impostaMetodoRilevamento(1); avviaRilevamentoAutomatico() }

        binding.superficieView.onNuovoPunto = { gestisciNuovoPunto() }
        binding.superficieView.onPuntoSpostato = { gestisciPuntoSpostato() }
        binding.superficieView.onTapNonConsentito = {
            avviso("Premi prima \"Imposta scala\" o \"Nuovo locale\"")
        }
        binding.superficieView.onVerticeRimosso = {
            avviso("Vertice rimosso")
        }
        binding.superficieView.onRimozioneNonConsentita = {
            avviso("Servono almeno 3 vertici: non posso rimuoverne altri")
        }
        binding.superficieView.onLocaleAltroTenutoPremuto = { indiceVista -> gestisciCambioLocaleInModifica(indiceVista) }
        // In visualizzazione a schermo intero: dito fermo 2 s su un locale già inserito → correzione dei suoi vertici.
        binding.superficieView.onLocaleTenutoPremutoInVisualizzazione = { indiceVista ->
            if (modalita == Modalita.NESSUNA && !rilevamentoAutomaticoInCorso) {
                val locale = binding.superficieView.locali.getOrNull(indiceVista)
                val indice = locali.indexOfFirst { it === locale }
                if (indice >= 0) avviaModificaLocale(indice)
            }
        }

        binding.btnImpostaScala.setOnClickListener { avviaCalibrazione() }
        binding.btnNuovoLocale.setOnClickListener { avviaNuovoLocale() }
        binding.btnAnnullaUltimoPunto.setOnClickListener { annullaUltimoPunto() }
        binding.btnChiudiLocale.setOnClickListener { chiudiLocaleCorrente() }
        binding.btnConfermaModificaLocale.setOnClickListener { confermaModificaLocale() }
        binding.btnAnnullaModificaLocale.setOnClickListener { annullaModificaLocale() }
        binding.btnAggiungiVertice.setOnClickListener { attivaModalitaInserimentoVertice() }
        binding.btnRimuoviVertice.setOnClickListener { attivaModalitaRimozioneVertice() }
        binding.btnEsportaPdf.setOnClickListener { avviaEsportazionePdf() }
        binding.btnEsportaDxf.setOnClickListener { avviaEsportazioneDxf() }
        binding.btnPeriziaUnita.setOnClickListener { apriPeriziaUnita() }
        binding.btnSchermoIntero.setOnClickListener {
            if (modalita == Modalita.NESSUNA) apriVisualizzazione() else apriSchermoIntero()
        }
        binding.btnAnnullaVertice.setOnClickListener { annullaUltimaModificaVertici() }
        binding.btnRinominaLocale.setOnClickListener { rinominaLocaleInModifica() }
        binding.btnEliminaLocale.setOnClickListener { eliminaLocaleInModifica() }
        binding.superficieView.onStoriaCambiata = { aggiornaPulsantiAnnulla() }

        disegnaElencoLocali()

        if (savedInstanceState == null) proponiRipresaUltimoLavoro()
    }

    /** Ultima immagine già scritta nel salvataggio automatico (per non riscriverla a ogni uscita). */
    private var bitmapUltimoScritto: Bitmap? = null

    /**
     * All'ingresso nella sezione: se c'è un lavoro lasciato aperto l'ultima volta, chiede se
     * riprenderlo o cominciare un nuovo progetto.
     */
    private fun proponiRipresaUltimoLavoro() {
        val ultimo = ProgettoStore.riepilogoUltimo(this) ?: return
        val dialog = AlertDialog.Builder(this, R.style.Theme_SchedaCliente_Dialog)
            .setTitle("Riprendere il lavoro?")
            .setMessage(
                "Hai lasciato un progetto aperto il ${ultimo.dataFormattata()} " +
                    "(${ultimo.numeroLocali} locali). Vuoi ripartire dall'ultimo progetto su cui lavoravi " +
                    "oppure iniziare un nuovo progetto?"
            )
            .setPositiveButton("Riprendi l'ultimo") { _, _ ->
                val progetto = ProgettoStore.caricaUltimo(this)
                if (progetto == null) {
                    avviso("Non sono riuscito a riaprire l'ultimo progetto")
                } else {
                    applicaProgetto(progetto)
                    bitmapUltimoScritto = progetto.bitmap
                    avviso("Ultimo progetto ripreso")
                }
            }
            .setNegativeButton("Nuovo progetto", null)
            .setCancelable(false)
            .show()
        dialog.rendiPulsantiLeggibili(this)
    }

    /** Salvataggio automatico dell'ultimo lavoro quando si esce dalla schermata o dall'app. */
    override fun onStop() {
        super.onStop()
        val bitmap = binding.superficieView.bitmap ?: return
        val serveImmagine = bitmap !== bitmapUltimoScritto
        val localiDaSalvare = locali.toList()
        val sx = scalaX
        val sy = scalaY
        val contesto = applicationContext
        bitmapUltimoScritto = bitmap
        Thread {
            try {
                ProgettoStore.salvaUltimo(contesto, bitmap, sx, sy, localiDaSalvare, serveImmagine)
            } catch (e: Exception) {
                // Salvataggio automatico non riuscito: il lavoro resta comunque a schermo.
            }
        }.start()
    }

    override fun onDestroy() {
        handlerPrincipale.removeCallbacksAndMessages(null)
        dialogSchermoIntero?.dismiss()
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_planimetria, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_salva_progetto -> {
                avviaSalvataggioProgetto()
                true
            }
            R.id.action_apri_progetto -> {
                avviaAperturaProgetto()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ---------------------------------------------------------------------
    // Caricamento del file (immagine o PDF catastale)
    // ---------------------------------------------------------------------

    private fun gestisciFileScelto(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
            // Non tutti i provider la supportano: non è bloccante.
        }

        val tipo = contentResolver.getType(uri) ?: ""
        val isPdf = tipo == "application/pdf" || uri.toString().lowercase(Locale.ITALY).endsWith(".pdf")

        if (isPdf) {
            val bitmapPdf = renderizzaPrimaPaginaPdf(uri) ?: return
            eraFilePdf = true
            bitmapPdfRenderizzato = bitmapPdf
            immagineSelezionata = uri

            binding.imgPlanimetria.setImageBitmap(bitmapPdf)
            binding.imgPlanimetria.visibility = View.VISIBLE
            binding.btnAnalizza.isEnabled = true
            binding.tvRisultatoPlanimetria.visibility = View.GONE

            caricaBitmapNellaVistaTracciamento(bitmapPdf)
            binding.tvStatoScala.text = "Scala: cerco la barra blu sul bordo del foglio..."
            rilevaScalaDalBordo(bitmapPdf) { trovata ->
                if (trovata == null) avviaRiconoscimentoScalaAutomatico(bitmapPdf)
            }
        } else {
            eraFilePdf = false
            bitmapPdfRenderizzato = null
            paginaPdfLarghezzaPt = null
            bitmapRenderPx = null
            immagineSelezionata = uri

            binding.imgPlanimetria.setImageURI(uri)
            binding.imgPlanimetria.visibility = View.VISIBLE
            binding.btnAnalizza.isEnabled = true
            binding.tvRisultatoPlanimetria.visibility = View.GONE

            val bitmap = decodificaBitmapLimitato(uri)
            if (bitmap != null) {
                caricaBitmapNellaVistaTracciamento(bitmap)
                // Anche foto/screenshot di un PLN possono avere la barra blu sul bordo.
                rilevaScalaDalBordo(bitmap) { trovata ->
                    if (trovata == null) binding.tvStatoScala.text = "Scala: non impostata"
                }
            }
        }
    }

    private fun richiediPermessoEScattaFoto() {
        val concesso = ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (concesso) {
            avviaScattoFoto()
        } else {
            permessoFotocameraLauncher.launch(android.Manifest.permission.CAMERA)
        }
    }

    private fun avviaScattoFoto() {
        val cartella = File(cacheDir, "fotocamera").apply { mkdirs() }
        val file = File(cartella, "planimetria_${System.currentTimeMillis()}.jpg")
        val uri = try {
            androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        } catch (e: Exception) {
            avviso("Non sono riuscito a preparare la fotocamera: ${e.message}")
            return
        }
        uriFotoScattata = uri
        scattaFotoLauncher.launch(uri)
    }

    private fun renderizzaPrimaPaginaPdf(uri: Uri): Bitmap? {
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        return try {
            pfd = contentResolver.openFileDescriptor(uri, "r")
                ?: throw IllegalStateException("Impossibile aprire il file")
            renderer = PdfRenderer(pfd)
            if (renderer.pageCount == 0) throw IllegalStateException("Il PDF non contiene pagine")

            val pagina = renderer.openPage(0)
            val larghezzaPt = pagina.width.toFloat()
            val altezzaPt = pagina.height.toFloat()
            // Lato lungo a ~4000 px (prima 2200 px di larghezza): più dettaglio quando si zooma.
            fun crea(latoPx: Float): Bitmap {
                val scalaRender = (latoPx / maxOf(larghezzaPt, altezzaPt)).coerceAtMost(8f)
                return Bitmap.createBitmap(
                    (larghezzaPt * scalaRender).toInt().coerceAtLeast(1),
                    (altezzaPt * scalaRender).toInt().coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888
                )
            }
            // Se il telefono non ha memoria per 4000 px, si ripiega su 2200 px come prima.
            val bmp = try { crea(4000f) } catch (e: OutOfMemoryError) { crea(2200f) }
            val canvasBmp = Canvas(bmp)
            canvasBmp.drawColor(Color.WHITE)
            pagina.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            pagina.close()

            paginaPdfLarghezzaPt = larghezzaPt
            bitmapRenderPx = bmp.width
            bmp
        } catch (e: Exception) {
            avviso("Non sono riuscito a leggere il PDF: ${e.message}")
            null
        } finally {
            renderer?.close()
            pfd?.close()
        }
    }

    /**
     * Carica la foto/scansione a risoluzione alta (lato lungo fino a [latoMassimo] px, prima
     * 2000) così lo zoom spinto serve davvero a essere più precisi. Il campionamento a potenze
     * di 2 non scende mai sotto il lato massimo (prima poteva dimezzare troppo: una scansione da
     * 3800 px diventava 1900); l'eventuale eccesso si riduce poi con un ridimensionamento esatto.
     * Se il telefono non ha memoria sufficiente si riprova automaticamente a 2000 px.
     */
    private fun decodificaBitmapLimitato(uri: Uri, latoMassimo: Int = 4000): Bitmap? {
        return try {
            val opzioniMisura = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opzioniMisura) }
            var inSampleSize = 1
            val latoOriginale = maxOf(opzioniMisura.outWidth, opzioniMisura.outHeight)
            while (latoOriginale / (inSampleSize * 2) >= latoMassimo) inSampleSize *= 2

            val opzioniDecodifica = BitmapFactory.Options().apply { this.inSampleSize = inSampleSize }
            val decodificato = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opzioniDecodifica) }
                ?: return null
            val lato = maxOf(decodificato.width, decodificato.height)
            if (lato <= latoMassimo) return decodificato
            val scala = latoMassimo.toFloat() / lato
            val ridotto = Bitmap.createScaledBitmap(
                decodificato,
                (decodificato.width * scala).toInt().coerceAtLeast(1),
                (decodificato.height * scala).toInt().coerceAtLeast(1),
                true
            )
            if (ridotto !== decodificato) decodificato.recycle()
            ridotto
        } catch (e: OutOfMemoryError) {
            if (latoMassimo > 2000) decodificaBitmapLimitato(uri, 2000) else null
        } catch (e: Exception) {
            avviso("Non sono riuscito a caricare l'immagine per il tracciamento")
            null
        }
    }

    private fun caricaBitmapNellaVistaTracciamento(bitmap: Bitmap) {
        terminaOperazioneInCorso()
        scalaX = null
        scalaY = null
        calibrazioneEntrambeInCorso = false
        calibrazioneConParametri = false
        scalaXTemporanea = null
        locali.clear()
        binding.superficieView.locali.clear()
        binding.superficieView.bitmap = bitmap
        proposteAi.clear()
        proposizioneInVerifica = null
        // Un rilevamento sospeso contiene coordinate in pixel riferite all'immagine precedente:
        // caricandone una diversa (o una nuova pagina/foto) non avrebbe più alcun senso riproporlo.
        codaRilevamentoSospesa = null
        disegnaProposteAi()

        binding.tvStatoScala.text = "Scala: non impostata"
        disegnaElencoLocali()
        aggiornaTotale()
        abilitaControlli(true)
        aggiornaPulsanteVisualizza()
    }

    // ---------------------------------------------------------------------
    // Salvataggio/apertura di un progetto (immagine + scala + locali)
    // ---------------------------------------------------------------------

    private fun avviaSalvataggioProgetto() {
        val bitmap = binding.superficieView.bitmap
        if (bitmap == null) {
            avviso("Carica prima una planimetria da salvare")
            return
        }
        if (salvataggioInCorso) return
        salvataggioInCorso = true
        var salvaConfermato = false

        val campoNome = EditText(this).apply {
            hint = "Es. Pratica Rossi - Via Roma 12"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(
                "Planimetria " + SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALY).format(Date())
            )
            setSelection(text.length)
        }
        val padding = (20 * resources.displayMetrics.density).toInt()
        val contenitore = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, 0)
            addView(campoNome)
        }

        val dialog = AlertDialog.Builder(this, R.style.Theme_SchedaCliente_Dialog)
            .setTitle("Salva progetto")
            .setMessage("Nome con cui ritrovare questa planimetria (immagine, scala e locali tracciati) riaprendo in seguito \"Aree locali da planimetria\".")
            .setView(contenitore)
            .setPositiveButton("Salva") { _, _ ->
                salvaConfermato = true
                val nome = campoNome.text?.toString()?.trim().orEmpty().ifBlank { "Progetto senza nome" }
                salvaProgetto(nome, bitmap)
            }
            .setNegativeButton("Annulla", null)
            // Finestra chiusa senza salvare (Annulla, tasto indietro, tocco fuori): si sblocca subito.
            .setOnDismissListener { if (!salvaConfermato) salvataggioInCorso = false }
            .show()
        dialog.rendiPulsantiLeggibili(this)
    }

    private fun salvaProgetto(nome: String, bitmap: Bitmap) {
        // Scrittura su file (JPEG + JSON): eseguita su un thread separato per non bloccare
        // l'interfaccia con una planimetria di grandi dimensioni, con lo stesso schema usato
        // altrove nell'app per le chiamate che richiedono un po' di tempo.
        val localiDaSalvare = locali.toList()
        val sx = scalaX
        val sy = scalaY
        Thread {
            try {
                ProgettoStore.salva(this, nome, bitmap, sx, sy, localiDaSalvare)
                handlerPrincipale.post {
                    salvataggioInCorso = false
                    if (isFinishing || isDestroyed) return@post
                    avviso("Progetto \"$nome\" salvato")
                }
            } catch (e: Exception) {
                handlerPrincipale.post {
                    salvataggioInCorso = false
                    if (isFinishing || isDestroyed) return@post
                    avviso("Non sono riuscito a salvare il progetto: ${e.message}")
                }
            }
        }.start()
    }

    private fun avviaAperturaProgetto() {
        val elenco = ProgettoStore.elenca(this)
        if (elenco.isEmpty()) {
            avviso("Nessun progetto salvato ancora")
            return
        }

        lateinit var dialog: AlertDialog
        val padding = (12 * resources.displayMetrics.density).toInt()
        val contenitoreLista = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        fun ridisegnaElenco(righe: List<RiepilogoProgetto>) {
            contenitoreLista.removeAllViews()
            if (righe.isEmpty()) {
                contenitoreLista.addView(TextView(this).apply {
                    text = "Nessun progetto salvato."
                    setTextColor(ContextCompat.getColor(this@PlanimetriaActivity, R.color.text_secondary))
                })
                return
            }
            righe.forEach { progetto ->
                val riga = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, padding / 2, 0, padding / 2)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        dialog.dismiss()
                        caricaProgettoSalvato(progetto.id)
                    }
                }
                val testo = TextView(this).apply {
                    text = "${progetto.nome}\n${progetto.dataFormattata()} · ${progetto.numeroLocali} locali · " +
                        "${String.format(Locale.ITALY, "%,.2f", progetto.superficieTotale)} m²"
                    setTextColor(ContextCompat.getColor(this@PlanimetriaActivity, R.color.text_primary))
                    textSize = 13f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val btnElimina = ImageButton(this).apply {
                    setImageResource(android.R.drawable.ic_menu_delete)
                    background = null
                    contentDescription = "Elimina progetto ${progetto.nome}"
                    layoutParams = LinearLayout.LayoutParams(
                        (36 * resources.displayMetrics.density).toInt(), (36 * resources.displayMetrics.density).toInt()
                    )
                    setOnClickListener {
                        val confermaDialog = AlertDialog.Builder(this@PlanimetriaActivity, R.style.Theme_SchedaCliente_Dialog)
                            .setTitle("Eliminare questo progetto?")
                            .setMessage("\"${progetto.nome}\" verrà eliminato definitivamente. L'operazione non può essere annullata.")
                            .setPositiveButton("Elimina") { _, _ ->
                                ProgettoStore.elimina(this@PlanimetriaActivity, progetto.id)
                                ridisegnaElenco(ProgettoStore.elenca(this@PlanimetriaActivity))
                            }
                            .setNegativeButton("Annulla", null)
                            .show()
                        confermaDialog.rendiPulsantiLeggibili(this@PlanimetriaActivity)
                    }
                }
                riga.addView(testo)
                riga.addView(btnElimina)
                contenitoreLista.addView(riga)

                val separatore = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, (1 * resources.displayMetrics.density).toInt()
                    )
                    setBackgroundColor(ContextCompat.getColor(this@PlanimetriaActivity, R.color.divider))
                }
                contenitoreLista.addView(separatore)
            }
        }
        ridisegnaElenco(elenco)

        val scroll = ScrollView(this).apply { addView(contenitoreLista) }

        dialog = AlertDialog.Builder(this, R.style.Theme_SchedaCliente_Dialog)
            .setTitle("Apri progetto salvato")
            .setView(scroll)
            .setNegativeButton("Chiudi", null)
            .show()
        dialog.rendiPulsantiLeggibili(this)
    }

    private fun caricaProgettoSalvato(id: Long) {
        val progetto = ProgettoStore.carica(this, id)
        if (progetto == null) {
            avviso("Non sono riuscito ad aprire il progetto salvato")
            return
        }
        applicaProgetto(progetto)
        avviso("Progetto \"${progetto.nome}\" caricato")
    }

    private fun applicaProgetto(progetto: ProgettoPlanimetria) {
        terminaOperazioneInCorso()
        immagineSelezionata = null
        eraFilePdf = false
        bitmapPdfRenderizzato = null
        paginaPdfLarghezzaPt = null
        bitmapRenderPx = null

        scalaX = progetto.scalaX
        scalaY = progetto.scalaY
        calibrazioneEntrambeInCorso = false
        calibrazioneConParametri = false
        scalaXTemporanea = null

        locali.clear()
        locali.addAll(progetto.locali)
        binding.superficieView.locali.clear()
        binding.superficieView.locali.addAll(progetto.locali)
        binding.superficieView.bitmap = progetto.bitmap

        proposteAi.clear()
        proposizioneInVerifica = null
        // Vedi commento analogo in caricaBitmapNellaVistaTracciamento: coordinate riferite alla
        // planimetria precedente, non più valide caricando un progetto (quindi un'immagine) diverso.
        codaRilevamentoSospesa = null
        disegnaProposteAi()

        binding.imgPlanimetria.setImageBitmap(progetto.bitmap)
        binding.imgPlanimetria.visibility = View.VISIBLE
        binding.btnAnalizza.isEnabled = true
        binding.tvRisultatoPlanimetria.visibility = View.GONE

        if (scalaX != null && scalaY != null) {
            aggiornaTestoScala()
        } else {
            binding.tvStatoScala.text = "Scala: non impostata"
        }

        disegnaElencoLocali()
        aggiornaTotale()
        abilitaControlli(true)
        aggiornaPulsanteVisualizza()
    }

    // ---------------------------------------------------------------------
    // Sezione AI: lettura quote scritte sulla planimetria
    // ---------------------------------------------------------------------

    private fun avviaAnalisi() {
        val chiaveApi = ChiaveApiStore.leggiChiave(this)
        if (chiaveApi.isBlank()) {
            val dialog = AlertDialog.Builder(this, R.style.Theme_SchedaCliente_Dialog)
                .setTitle("Funzione AI non attiva")
                .setMessage(
                    "Per analizzare una planimetria serve prima inserire una chiave API " +
                        "personale nelle Impostazioni (stesso uso minimo, pochi centesimi " +
                        "per analisi, pagato con il tuo account)."
                )
                .setPositiveButton("Vai alle Impostazioni") { _, _ ->
                    startActivity(Intent(this, SettingsActivity::class.java))
                }
                .setNegativeButton("Annulla", null)
                .show()
            dialog.rendiPulsantiLeggibili(this)
            return
        }

        val bitmapPdf = bitmapPdfRenderizzato
        if (eraFilePdf && bitmapPdf == null) {
            avviso("Carica prima una planimetria")
            return
        }
        if (!eraFilePdf && immagineSelezionata == null) {
            avviso("Carica prima una planimetria")
            return
        }

        if (scalaX == null) {
            mostraPromemoriaScalaPrimaAnalisi()
            return
        }

        eseguiAnalisiAi(chiaveApi, bitmapPdf)
    }

    /**
     * L'AI calcola le aree SOLO dalle quote scritte sul disegno, mai misurando pixel:
     * per questo non le viene mai chiesto di leggere la scala dal reticolo catastale.
     * Ma senza una scala impostata, un locale senza quote leggibili non potrà poi
     * essere completato tracciandolo manualmente (il metodo che garantisce la
     * precisione), quindi lo segnaliamo prima di partire con l'analisi.
     */
    private fun mostraPromemoriaScalaPrimaAnalisi() {
        val dialog = AlertDialog.Builder(this, R.style.Theme_SchedaCliente_Dialog)
            .setTitle("Scala non impostata")
            .setMessage(
                "Non hai ancora impostato una scala con \"Imposta scala\". L'AI calcola le aree " +
                    "solo dalle quote scritte sul disegno (non misura mai sui pixel dell'immagine): " +
                    "se un locale non ha quote leggibili, l'unico modo per ottenerne l'area in modo " +
                    "preciso è tracciarlo a mano, ma per farlo serve prima una scala.\n\n" +
                    "Vuoi impostarla ora misurando una distanza nota (come nel metodo manuale), " +
                    "oppure procedere comunque con l'analisi AI delle sole quote scritte?"
            )
            .setPositiveButton("Imposta scala ora") { _, _ -> avviaCalibrazione() }
            .setNegativeButton("Procedi senza scala") { _, _ ->
                val chiaveApi = ChiaveApiStore.leggiChiave(this)
                eseguiAnalisiAi(chiaveApi, bitmapPdfRenderizzato)
            }
            .show()
        dialog.rendiPulsantiLeggibili(this)
    }

    private fun eseguiAnalisiAi(chiaveApi: String, bitmapPdf: Bitmap?) {
        binding.btnAnalizza.isEnabled = false
        binding.progressoAnalisi.visibility = View.VISIBLE
        binding.tvRisultatoPlanimetria.visibility = View.GONE

        val callback = object : InterpretePlanimetria.Callback {
            override fun onSuccesso(risultato: JSONObject) {
                if (isFinishing || isDestroyed) return
                binding.progressoAnalisi.visibility = View.GONE
                binding.btnAnalizza.isEnabled = true
                mostraRisultato(risultato)
            }

            override fun onErrore(messaggio: String) {
                if (isFinishing || isDestroyed) return
                binding.progressoAnalisi.visibility = View.GONE
                binding.btnAnalizza.isEnabled = true
                val dialog = AlertDialog.Builder(this@PlanimetriaActivity, R.style.Theme_SchedaCliente_Dialog)
                    .setTitle("Non sono riuscito ad analizzare l'immagine")
                    .setMessage(messaggio)
                    .setPositiveButton("OK", null)
                    .show()
                dialog.rendiPulsantiLeggibili(this@PlanimetriaActivity)
            }
        }

        if (eraFilePdf && bitmapPdf != null) {
            InterpretePlanimetria.analizzaBitmap(chiaveApi, bitmapPdf, callback)
        } else {
            InterpretePlanimetria.analizza(this, chiaveApi, immagineSelezionata!!, callback)
        }
    }

    private fun mostraRisultato(risultato: JSONObject) {
        val localiAi = risultato.optJSONArray("locali")
        var presenteLocaleSenzaArea = false
        estraiProposteAi(localiAi)
        val testo = buildString {
            if (localiAi == null || localiAi.length() == 0) {
                append("Non ho individuato locali con quote leggibili in questa immagine.")
            } else {
                for (i in 0 until localiAi.length()) {
                    val locale = localiAi.getJSONObject(i)
                    val nome = locale.optString("nome").ifBlank { "Locale ${i + 1}" }
                    val destinazione = locale.optString("destinazione")
                    val quote = locale.optString("quote")
                    val nota = locale.optString("nota")

                    append("• $nome")
                    if (destinazione.isNotBlank()) append(" – $destinazione")
                    if (quote.isNotBlank()) append(" ($quote)")
                    append(": ")

                    val areaNonCalcolata = locale.isNull("area_mq") || !locale.has("area_mq")
                    if (areaNonCalcolata) {
                        append("area non calcolata")
                        presenteLocaleSenzaArea = true
                    } else {
                        val area = locale.optDouble("area_mq", Double.NaN)
                        if (area.isNaN()) {
                            append("area non calcolata")
                            presenteLocaleSenzaArea = true
                        } else {
                            append("${String.format(Locale.ITALY, "%,.2f", area)} m²")
                        }
                    }
                    if (nota.isNotBlank()) append("\n   ⚠ $nota")
                    append("\n\n")
                }

                val superficieTotale = risultato.optDouble("superficie_totale_mq", Double.NaN)
                if (!superficieTotale.isNaN()) {
                    append("Superficie totale calcolata: ${String.format(Locale.ITALY, "%,.2f", superficieTotale)} m²\n")
                }
            }

            val avviso = risultato.optString("avviso_generale")
            if (avviso.isNotBlank()) {
                append("\n⚠ $avviso")
            }

            if (presenteLocaleSenzaArea) {
                append("\n\nPer i locali senza area calcolata, puoi ottenere una misura precisa tracciandoli " +
                    "a mano con \"Nuovo locale\" qui sotto" +
                    (if (scalaX == null) " (serve prima impostare una scala con \"Imposta scala\")." else ", con la scala già impostata."))
            }

            append("\n\nControlla sempre questi valori prima di usarli in una pratica: la lettura delle quote " +
                "dipende dalla qualità dell'immagine. L'area indicata è quella netta del locale: non include " +
                "sguanci di porte/finestre o nicchie.")
        }

        binding.tvRisultatoPlanimetria.visibility = View.VISIBLE
        binding.tvRisultatoPlanimetria.text = testo
    }

    // ---------------------------------------------------------------------
    // Proposte AI di vertici (stima approssimativa sui pixel, SEMPRE da
    // verificare e correggere a mano prima di essere usate: l'AI non può
    // garantire la precisione di una misura tecnica, per questo ogni
    // proposta passa obbligatoriamente dal tracciamento manuale esistente.
    // ---------------------------------------------------------------------

    /**
     * Converte i locali restituiti dall'AI (vertici come frazione 0-1 dell'immagine
     * effettivamente analizzata) in coordinate pixel dell'immagine COMPLETA caricata
     * nella vista. Quando l'AI ha analizzato un ritaglio (una singola pianta, o un singolo
     * locale) invece dell'immagine intera, [origineX]/[origineY] sono l'angolo in alto a
     * sinistra del ritaglio in pixel dell'immagine completa, e [larghezzaRegione]/
     * [altezzaRegione] le dimensioni del ritaglio stesso (non dell'immagine intera).
     */
    /**
     * Spigoli come intersezione di pareti consecutive. Ogni lato è un segmento (due punti sulla stessa
     * parete); si prolunga a retta. Lo spigolo tra il lato k-1 e il lato k deve cadere vicino alla stima
     * dei vertici dell'AI (entro il 6% del lato maggiore dell'immagine), altrimenti si tiene la stima.
     * Restituisce null se i lati non sono utilizzabili.
     */
    private fun verticiDaLati(
        lati: org.json.JSONArray, ox: Float, oy: Float, larg: Float, alt: Float, stima: List<PointF>
    ): List<PointF>? {
        data class Retta(val a: PointF, val b: PointF)
        val rette = mutableListOf<Retta>()
        for (k in 0 until lati.length()) {
            val o = lati.optJSONObject(k) ?: return null
            val da = o.optJSONObject("da") ?: return null
            val a = o.optJSONObject("a") ?: return null
            val x1 = da.optDouble("x", Double.NaN); val y1 = da.optDouble("y", Double.NaN)
            val x2 = a.optDouble("x", Double.NaN); val y2 = a.optDouble("y", Double.NaN)
            if (x1.isNaN() || y1.isNaN() || x2.isNaN() || y2.isNaN()) return null
            rette += Retta(
                PointF(ox + x1.toFloat() * larg, oy + y1.toFloat() * alt),
                PointF(ox + x2.toFloat() * larg, oy + y2.toFloat() * alt)
            )
        }
        val n = rette.size
        val tolleranza = 0.06 * maxOf(larg, alt)
        val esito = mutableListOf<PointF>()
        for (k in 0 until n) {
            val r1 = rette[(k - 1 + n) % n]
            val r2 = rette[k]
            val d1x = (r1.b.x - r1.a.x).toDouble(); val d1y = (r1.b.y - r1.a.y).toDouble()
            val d2x = (r2.b.x - r2.a.x).toDouble(); val d2y = (r2.b.y - r2.a.y).toDouble()
            val den = d1x * d2y - d1y * d2x
            // Punto di riferimento: vertice stimato più vicino all'estremo di r1 e inizio di r2.
            val rifX = (r1.b.x + r2.a.x) / 2f; val rifY = (r1.b.y + r2.a.y) / 2f
            val ref = PointF(rifX, rifY)
            val vicino = stima.minByOrNull { GeometriaUtils.distanza(it, ref) } ?: ref
            if (kotlin.math.abs(den) < 1e-6 * (kotlin.math.hypot(d1x, d1y) * kotlin.math.hypot(d2x, d2y) + 1e-9)) {
                esito += vicino; continue
            }
            val t = ((r2.a.x - r1.a.x) * d2y - (r2.a.y - r1.a.y) * d2x) / den
            val p = PointF((r1.a.x + t * d1x).toFloat(), (r1.a.y + t * d1y).toFloat())
            esito += if (GeometriaUtils.distanza(p, vicino) <= tolleranza) p else vicino
        }
        return if (esito.size >= 3) esito else null
    }

    private fun estraiVerticiDaRisultato(
        localiAi: org.json.JSONArray?,
        origineX: Float = 0f,
        origineY: Float = 0f,
        larghezzaRegione: Float? = null,
        altezzaRegione: Float? = null
    ): MutableList<PropostaLocaleAi> {
        val risultato = mutableListOf<PropostaLocaleAi>()
        val bitmap = binding.superficieView.bitmap
        if (localiAi != null && bitmap != null) {
            val larghezza = larghezzaRegione ?: bitmap.width.toFloat()
            val altezza = altezzaRegione ?: bitmap.height.toFloat()
            for (i in 0 until localiAi.length()) {
                val locale = localiAi.getJSONObject(i)
                val puntoInterno = locale.optJSONObject("punto_interno")?.let { pi ->
                    val fx = pi.optDouble("x", Double.NaN)
                    val fy = pi.optDouble("y", Double.NaN)
                    if (fx.isNaN() || fy.isNaN() || fx !in 0.0..1.0 || fy !in 0.0..1.0) null
                    else PointF(origineX + fx.toFloat() * larghezza, origineY + fy.toFloat() * altezza)
                }
                val verticiJson = locale.optJSONArray("vertici") ?: org.json.JSONArray()
                // Un vano con solo il punto interno si tiene: il contorno lo può misurare il telefono.
                if (verticiJson.length() < 3 && puntoInterno == null) continue
                val vertici = mutableListOf<PointF>()
                for (v in 0 until verticiJson.length()) {
                    val punto = verticiJson.optJSONObject(v) ?: continue
                    val fx = punto.optDouble("x", Double.NaN)
                    val fy = punto.optDouble("y", Double.NaN)
                    if (fx.isNaN() || fy.isNaN()) continue
                    vertici.add(PointF(origineX + fx.toFloat() * larghezza, origineY + fy.toFloat() * altezza))
                }
                if (vertici.size < 3 && puntoInterno == null) continue
                if (vertici.size < 3) vertici.clear()
                // Modalità "lato per lato": gli spigoli sono le intersezioni delle rette delle pareti
                // percorse per intero dall'AI (se coerenti con la stima dei vertici).
                if (InterpretePlanimetria.latoPerLato && vertici.size >= 3) {
                    val lati = locale.optJSONArray("lati")
                    if (lati != null && lati.length() >= 3) {
                        val calcolati = verticiDaLati(lati, origineX, origineY, larghezza, altezza, vertici)
                        if (calcolati != null) { vertici.clear(); vertici.addAll(calcolati) }
                    }
                }
                val nome = locale.optString("nome").trim().ifBlank { "Locale ${i + 1}" }
                val destinazione = locale.optString("destinazione").trim()
                // CORREZIONE (ottobre 2026): "destinazione" veniva accodata al nome ogni volta
                // che il testo non era IDENTICO carattere per carattere a "nome", ma l'AI spesso
                // restituisce lo stesso ambiente scritto in modo diverso (es. nome="BAGNO" tutto
                // maiuscolo come scritto sul disegno, destinazione="Bagno" come categoria) — con
                // il solo confronto esatto, questo produceva doppioni illeggibili tipo
                // "BAGNO – Bagno" invece di un singolo nome. Ora il confronto ignora
                // maiuscole/minuscole e considera uguali anche i casi in cui una delle due
                // stringhe è semplicemente contenuta nell'altra (es. "Disimpegno" / "DISIMPEGNO").
                val sonoEquivalenti = destinazione.isBlank() ||
                    destinazione.equals(nome, ignoreCase = true) ||
                    nome.contains(destinazione, ignoreCase = true) ||
                    destinazione.contains(nome, ignoreCase = true)
                val nomeCompleto = if (sonoEquivalenti) nome else "$nome - $destinazione"
                risultato.add(PropostaLocaleAi(nomeCompleto, vertici, puntoInterno))
            }
        }
        return risultato
    }

    private fun estraiProposteAi(localiAi: org.json.JSONArray?) {
        proposteAi = estraiVerticiDaRisultato(localiAi).filter { it.verticiPx.size >= 3 }.toMutableList()
        disegnaProposteAi()
    }

    private fun disegnaProposteAi() {
        binding.containerProposteAi.removeAllViews()
        binding.tvTitoloProposteAi.visibility = if (proposteAi.isEmpty()) View.GONE else View.VISIBLE
        if (proposteAi.isEmpty()) return

        val inVerifica = modalita == Modalita.MODIFICA_LOCALE && proposizioneInVerifica != null

        proposteAi.forEach { proposta ->
            val riga = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (4 * resources.displayMetrics.density).toInt(), 0, (4 * resources.displayMetrics.density).toInt())
            }
            val testo = TextView(this).apply {
                text = "${proposta.nome} (${proposta.verticiPx.size} vertici stimati)"
                setTextColor(ContextCompat.getColor(this@PlanimetriaActivity, R.color.text_primary))
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val btnVerifica = com.google.android.material.button.MaterialButton(
                androidx.appcompat.view.ContextThemeWrapper(this, com.google.android.material.R.style.Widget_MaterialComponents_Button_OutlinedButton)
            ).apply {
                text = "Verifica vertici"
                textSize = 12f
                isAllCaps = false
                strokeColor = ContextCompat.getColorStateList(this@PlanimetriaActivity, R.color.primary)
                setTextColor(ContextCompat.getColor(this@PlanimetriaActivity, R.color.primary))
                isEnabled = !inVerifica && modalita == Modalita.NESSUNA
                setOnClickListener { avviaVerificaPropostaAi(proposta) }
            }
            riga.addView(testo)
            riga.addView(btnVerifica)
            binding.containerProposteAi.addView(riga)
        }
    }

    private fun avviaVerificaPropostaAi(proposta: PropostaLocaleAi) {
        if (binding.superficieView.bitmap == null) {
            avviso("Carica prima una planimetria")
            return
        }
        if (scalaX == null) {
            avviso("Imposta prima la scala con \"Imposta scala\"")
            return
        }
        if (modalita != Modalita.NESSUNA) {
            avviso("Finisci prima l'operazione in corso")
            return
        }

        proposizioneInVerifica = proposta
        indiceLocaleInModifica = null
        modalita = Modalita.MODIFICA_LOCALE
        nomeModificato = null

        binding.superficieView.puntiLocaleCorrente.clear()
        binding.superficieView.puntiLocaleCorrente.addAll(proposta.verticiPx.map { PointF(it.x, it.y) })
        binding.superficieView.disegnaCorrenteChiuso = true
        binding.superficieView.modoTocco = SuperficieView.ModoTocco.SOLO_TRASCINA
        binding.superficieView.listaAttiva = binding.superficieView.puntiLocaleCorrente
        binding.superficieView.limiteMassimoPunti = null
        binding.superficieView.invalidate()

        binding.tvIstruzioneTocco.visibility = View.VISIBLE
        binding.tvIstruzioneTocco.text =
            "Vertici stimati dall'AI per \"${proposta.nome}\": CONTROLLALI UNO PER UNO. Tieni premuto un vertice, spostalo con la lente finché non è esattamente sull'angolo del locale, poi rilascia. Ripeti per tutti i vertici, poi premi \"Conferma modifiche\". Pizzica con due dita per ingrandire."
        binding.btnSchermoIntero.visibility = View.VISIBLE
        binding.containerControlliNormali.visibility = View.GONE
        binding.containerControlliModifica.visibility = View.VISIBLE
        binding.containerStrumentiModifica.visibility = View.VISIBLE
        binding.superficieView.modalitaInserimentoBordo = false
        binding.superficieView.modalitaRimozioneVertice = false
        disegnaProposteAi()
        apriSchermoIntero()
    }

    private fun chiediNomeLocaleProposto(proposta: PropostaLocaleAi, verticiPx: List<PointF>) {
        val campo = EditText(this).apply {
            setText(nomeModificato ?: proposta.nome)
            setPadding(
                (16 * resources.displayMetrics.density).toInt(), (12 * resources.displayMetrics.density).toInt(),
                (16 * resources.displayMetrics.density).toInt(), (12 * resources.displayMetrics.density).toInt()
            )
        }
        val dialog = AlertDialog.Builder(this, R.style.Theme_SchedaCliente_Dialog)
            .setTitle("Conferma nome del locale")
            .setMessage("Vertici verificati e corretti. Conferma o modifica il nome del locale.")
            .setView(campo)
            .setPositiveButton("Salva locale") { _, _ ->
                val nome = campo.text?.toString()?.trim()?.ifBlank { proposta.nome } ?: proposta.nome
                val (area, lati) = calcolaAreaELati(verticiPx)
                val localeTracciato = LocaleTracciato(nome, verticiPx, area, lati)
                locali.add(localeTracciato)
                binding.superficieView.locali.add(localeTracciato)
                proposteAi.remove(proposta)

                terminaModificaLocale()
                disegnaElencoLocali()
                disegnaProposteAi()
                aggiornaTotale()
            }
            .setNegativeButton("Annulla") { _, _ -> terminaModificaLocale() }
            .setCancelable(false)
            .show()
        dialog.rendiPulsantiLeggibili(this)
    }

    // ---------------------------------------------------------------------
    // Rilevamento automatico sequenziale: scala (reticolo o segmento) +
    // vertici di ogni locale individuati dall'AI sui pixel, mostrati in
    // progressione a schermo intero. NON legge quote o testi di scala: usa
    // solo coordinate. Al termine di ogni locale chiede se correggerlo a
    // mano oppure confermarlo così com'è.
    // ---------------------------------------------------------------------

    private fun avviaRilevamentoAutomatico() {
        if (binding.superficieView.bitmap == null) {
            avviso("Carica prima una planimetria")
            return
        }
        if (modalita != Modalita.NESSUNA) {
            avviso("Finisci prima l'operazione in corso")
            return
        }

        // Se un rilevamento automatico precedente è stato interrotto con "Interrompi
        // rilevamento" (vedi annullaRilevamentoAutomatico), i locali rimasti da esaminare sono
        // ancora in sospeso: si chiede se riprendere da lì (senza rianalizzare tutta la
        // planimetria da capo) o ricominciare un rilevamento completamente nuovo, scartandoli.
        val sospesi = codaRilevamentoSospesa
        if (sospesi != null && sospesi.isNotEmpty()) {
            val dialog = AlertDialog.Builder(this, R.style.Theme_SchedaCliente_Dialog)
                .setTitle("Rilevamento interrotto in sospeso")
                .setMessage(
                    "Hai un rilevamento automatico interrotto con ${sospesi.size} locali ancora " +
                        "da esaminare. Vuoi riprenderlo da lì o ricominciare un nuovo rilevamento " +
                        "su tutta la planimetria (i locali sospesi andranno persi)?"
                )
                .setPositiveButton("Riprendi") { _, _ -> riprendiRilevamentoSospeso() }
                .setNegativeButton("Ricomincia da capo") { _, _ ->
                    codaRilevamentoSospesa = null
                    avviaRilevamentoAutomaticoCompleto()
                }
                .setNeutralButton("Annulla", null)
                .show()
            dialog.rendiPulsantiLeggibili(this)
            return
        }

        avviaRilevamentoAutomaticoCompleto()
    }

    /** Riprende un rilevamento automatico lasciato in sospeso da [annullaRilevamentoAutomatico], partendo subito dal primo locale ancora da esaminare, senza rianalizzare la planimetria. */
    private fun riprendiRilevamentoSospeso() {
        val coda = codaRilevamentoSospesa ?: return
        codaRilevamentoSospesa = null
        rilevamentoAutomaticoInCorso = true
        sessioneRilevamento++
        modalita = Modalita.TRACCIAMENTO
        binding.tvRisultatoPlanimetria.visibility = View.GONE
        binding.tvIstruzioneTocco.visibility = View.VISIBLE
        binding.tvIstruzioneTocco.text = "Ripresa del rilevamento automatico..."
        binding.btnSchermoIntero.visibility = View.VISIBLE
        binding.containerControlliNormali.visibility = View.GONE
        binding.containerControlliModifica.visibility = View.GONE
        abilitaControlli(false)
        apriSchermoIntero()
        avviaCodaLocaliRilevati(coda)
    }

    /** Corpo originale di [avviaRilevamentoAutomatico]: avvia un rilevamento automatico completo da zero (ricerca scala + rianalisi di tutta la planimetria). */
    private fun avviaRilevamentoAutomaticoCompleto() {
        val chiaveApi = ChiaveApiStore.leggiChiave(this)
        if (chiaveApi.isBlank()) {
            val dialog = AlertDialog.Builder(this, R.style.Theme_SchedaCliente_Dialog)
                .setTitle("Funzione AI non attiva")
                .setMessage(
                    "Per il rilevamento automatico serve prima inserire una chiave API " +
                        "personale nelle Impostazioni."
                )
                .setPositiveButton("Vai alle Impostazioni") { _, _ -> startActivity(Intent(this, SettingsActivity::class.java)) }
                .setNegativeButton("Annulla", null)
                .show()
            dialog.rendiPulsantiLeggibili(this)
            return
        }

        rilevamentoAutomaticoInCorso = true
        faseLocaliInCorso = false
        val sessione = ++sessioneRilevamento
        modalita = Modalita.TRACCIAMENTO
        binding.tvRisultatoPlanimetria.visibility = View.GONE
        binding.tvIstruzioneTocco.visibility = View.VISIBLE
        binding.tvIstruzioneTocco.text = "Avvio rilevamento automatico..."
        binding.btnSchermoIntero.visibility = View.VISIBLE
        binding.containerControlliNormali.visibility = View.GONE
        binding.containerControlliModifica.visibility = View.GONE
        abilitaControlli(false)
        apriSchermoIntero()

        val bitmapPdf = bitmapPdfRenderizzato
        // Il reticolo di riferimento (i "parametri") può essere presente sia nei PDF che nelle
        // immagini/foto della stessa planimetria catastale: si tenta sempre il riconoscimento
        // automatico sul bitmap effettivamente caricato, non solo quando il file è un PDF.
        val bitmapPerParametri = bitmapPdf ?: binding.superficieView.bitmap
        if (bitmapPerParametri != null) {
            binding.tvIstruzioneTocco.text = "Ricerca della scala sul bordo del foglio (barra blu \"10 metri\" e tacche)..."
            aggiornaBarraSchermoIntero()
            // Prima la ricerca locale, precisa e senza AI, della barra/tacche blu sul bordo; l'AI
            // sul reticolo resta solo come ripiego se il bordo blu non c'è.
            rilevaScalaDalBordo(bitmapPerParametri) { trovata ->
                if (!sessioneAttiva(sessione)) return@rilevaScalaDalBordo
                if (trovata != null) {
                    evidenziaScalaEProsegui(trovata, chiaveApi)
                } else {
                    binding.tvIstruzioneTocco.text = "Ricerca automatica della scala dal reticolo catastale (parametri)..."
                    aggiornaBarraSchermoIntero()
                    avviaScalaDaParametriAi(chiaveApi, bitmapPerParametri)
                }
            }
        } else if (scalaX != null) {
            avviaRilevamentoVertici(chiaveApi)
        } else {
            richiediScalaDaSegmento()
        }
    }

    /** Ripiego con l'AI sul reticolo, se la ricerca locale della barra blu non ha trovato nulla. */
    private fun avviaScalaDaParametriAi(chiaveApi: String, bitmapPerParametri: Bitmap) {
        val sessione = sessioneRilevamento
        run {
            InterpretaScalaParametri.analizza(chiaveApi, bitmapPerParametri, object : InterpretaScalaParametri.Callback {
                override fun onSuccesso(trovato: Boolean, x1: Double?, y1: Double?, x2: Double?, y2: Double?, numeroIntervalli: Int, nota: String?) {
                    if (!sessioneAttiva(sessione)) return
                    val bmp = binding.superficieView.bitmap
                    if (trovato && bmp != null && x1 != null && y1 != null && x2 != null && y2 != null) {
                        val p1 = PointF((x1 * bmp.width).toFloat(), (y1 * bmp.height).toFloat())
                        val p2 = PointF((x2 * bmp.width).toFloat(), (y2 * bmp.height).toFloat())
                        val distanzaPixel = GeometriaUtils.distanza(p1, p2)
                        // La distanza reale fra i due segni scelti (i più lontani tra loro lungo lo
                        // stesso verso) è il numero di intervalli contati tra loro per 10 m ciascuno,
                        // non sempre un solo intervallo: una base più lunga rende la scala più precisa.
                        val distanzaReale = numeroIntervalli * 10.0
                        if (distanzaPixel > 0.0) {
                            val scala = distanzaReale / distanzaPixel
                            scalaX = scala
                            scalaY = scala
                            aggiornaTestoScala()
                            ricalcolaTuttiLocali()
                            avviso("Scala rilevata automaticamente dal reticolo catastale.", lungo = true)
                            mostraTrattoScala(p1, p2, distanzaReale, "reticolo catastale ($numeroIntervalli intervalli)", sessione) {
                                avviaRilevamentoVertici(chiaveApi)
                            }
                            return
                        }
                    }
                    richiediScalaDaSegmento()
                }

                override fun onErrore(messaggio: String) {
                    if (!sessioneAttiva(sessione)) return
                    richiediScalaDaSegmento()
                }
            })
        }
    }

    /**
     * Cerca la scala sul bordo del foglio (barra blu "10 metri" e tacche del reticolo, vedi
     * [RilevatoreScalaBlu]) in background e, se la trova, la imposta. Se accanto alla barra si
     * legge con l'OCR un valore diverso da 10 m (es. "5 metri"), si usa quello.
     */
    private fun rilevaScalaDalBordo(bitmap: Bitmap, fatto: (RilevatoreScalaBlu.Risultato?) -> Unit) {
        Thread {
            var risultato = try { RilevatoreScalaBlu.rileva(bitmap) } catch (e: Exception) { null }
            var valoreLetto: Double? = null
            val zona = risultato?.zonaEtichetta
            if (risultato != null && zona != null) {
                valoreLetto = LetturaEtichettaScala.leggiMetri(bitmap, zona)
                if (valoreLetto != null && valoreLetto != RilevatoreScalaBlu.METRI_STANDARD) {
                    risultato = RilevatoreScalaBlu.rileva(bitmap, valoreLetto)
                }
            }
            handlerPrincipale.post {
                if (isFinishing || isDestroyed) return@post
                // Nel frattempo è stata caricata un'altra planimetria: questa scala non le appartiene.
                if (binding.superficieView.bitmap !== bitmap) return@post
                val r = risultato
                if (r != null) {
                    scalaX = r.metriPerPixel
                    scalaY = r.metriPerPixel
                    ricalcolaTuttiLocali()
                    val valore = if (valoreLetto != null) "letto accanto alla barra: ${String.format(Locale.ITALY, "%.0f", r.metriTraPunti)} m"
                    else "valore della barra assunto 10 m"
                    binding.tvStatoScala.text =
                        "Scala rilevata automaticamente dalla ${r.descrizione} ($valore): " +
                            "${String.format(Locale.ITALY, "%.4f", r.metriPerPixel)} m/pixel. " +
                            "Verifica su una misura nota con \"Imposta scala\" prima di usarla in una pratica."
                }
                fatto(r)
            }
        }.start()
    }

    /** Evidenzia sul disegno il tratto usato per la scala (vedi [mostraTrattoScala]), poi prosegue col rilevamento dei locali. */
    private fun evidenziaScalaEProsegui(r: RilevatoreScalaBlu.Risultato, chiaveApi: String) {
        if (binding.superficieView.bitmap == null) return avviaRilevamentoVertici(chiaveApi)
        avviso("Scala rilevata automaticamente dal bordo del foglio.", lungo = true)
        mostraTrattoScala(r.p1, r.p2, r.metriTraPunti, r.descrizione, sessioneRilevamento) {
            avviaRilevamentoVertici(chiaveApi)
        }
    }

    /**
     * Fa vedere il tratto preso a riferimento per la scala, in tre tempi (v3.77, su richiesta):
     *  1. zoom sul primo punto di riferimento, 2. zoom sul secondo punto (così si controlla che
     *  cadano esattamente sui segni giusti), 3. la linea gialla intera inquadrata, con scritta la
     *  misura in metri che le viene assegnata. Poi riporta la vista intera ed esegue [poi].
     * Se nel frattempo il rilevamento viene interrotto, si ferma (vedi sessioneRilevamento).
     *
     * Le tre transizioni sono animate (zoom/spostamento GRADUALE, vedi
     * [SuperficieView.zoomAnimatoVerso]) invece di saltare di colpo da un punto all'altro: su
     * richiesta esplicita dell'utente, che con il salto istantaneo precedente non riusciva a
     * capire dove si trovasse il secondo segno di riferimento rispetto al primo.
     */
    private fun mostraTrattoScala(p1: PointF, p2: PointF, metri: Double, origine: String, sessione: Int, poi: () -> Unit) {
        val vista = binding.superficieView
        val misura = formattaMetri(metri)
        vista.puntiScalaEvidenziati.clear()
        vista.puntiScalaEvidenziati.add(p1)
        vista.puntiScalaEvidenziati.add(p2)
        vista.etichettaScala = null
        fun fase(testo: String) {
            binding.tvIstruzioneTocco.text = testo
            aggiornaBarraSchermoIntero()
        }
        fun interrotto() {
            vista.puntiScalaEvidenziati.clear()
            vista.etichettaScala = null
            vista.invalidate()
        }
        fun dopo(ms: Long, azione: () -> Unit) = handlerPrincipale.postDelayed({
            if (sessioneAttiva(sessione)) azione() else interrotto()
        }, ms)

        val zoomPunto = 6f
        val durataAnimazione = 900L
        fase("Scala ($origine): avvicinamento al primo punto di riferimento (1 di 2)...")
        vista.zoomAnimatoVerso(p1, zoomPunto, durataAnimazione) {
            if (!sessioneAttiva(sessione)) return@zoomAnimatoVerso interrotto()
            fase("Scala ($origine): primo punto di riferimento (1 di 2)")
            dopo(900) {
                fase("Scala ($origine): spostamento verso il secondo punto di riferimento (2 di 2)...")
                vista.zoomAnimatoVerso(p2, zoomPunto, durataAnimazione) {
                    if (!sessioneAttiva(sessione)) return@zoomAnimatoVerso interrotto()
                    fase("Scala ($origine): secondo punto di riferimento (2 di 2)")
                    dopo(900) {
                        vista.etichettaScala = misura
                        val centro = PointF((p1.x + p2.x) / 2f, (p1.y + p2.y) / 2f)
                        fase("Scala ($origine): il tratto giallo misura $misura.")
                        vista.zoomAnimatoVerso(centro, zoomPerInquadrare(p1, p2), durataAnimazione) {
                            if (!sessioneAttiva(sessione)) return@zoomAnimatoVerso interrotto()
                            dopo(2000) {
                                vista.puntiScalaEvidenziati.clear()
                                vista.etichettaScala = null
                                vista.azzeraZoom()
                                poi()
                            }
                        }
                    }
                }
            }
        }
    }

    /** Ingrandimento che fa stare per intero nella vista il segmento p1-p2, con un po' di margine. */
    private fun zoomPerInquadrare(p1: PointF, p2: PointF): Float {
        val vista = binding.superficieView
        val bmp = vista.bitmap ?: return 1f
        val w = vista.width.toFloat(); val h = vista.height.toFloat()
        if (w <= 0f || h <= 0f || bmp.width <= 0 || bmp.height <= 0) return 1f
        val adattata = minOf(w / bmp.width, h / bmp.height)
        val dx = kotlin.math.abs(p2.x - p1.x) * adattata
        val dy = kotlin.math.abs(p2.y - p1.y) * adattata
        val fx = if (dx > 1f) w * 0.7f / dx else Float.MAX_VALUE
        val fy = if (dy > 1f) h * 0.7f / dy else Float.MAX_VALUE
        return minOf(fx, fy, 6f).coerceAtLeast(1f)
    }

    /** "10 m" se la misura è intera, altrimenti "12,50 m". */
    private fun formattaMetri(metri: Double): String =
        if (kotlin.math.abs(metri - kotlin.math.round(metri)) < 0.005) String.format(Locale.ITALY, "%.0f m", metri)
        else String.format(Locale.ITALY, "%.2f m", metri)

    private fun richiediScalaDaSegmento() {
        avviso("Non ho trovato una scala leggibile dal reticolo: tocca due punti di una misura nota sul disegno.", lungo = true)
        azioneDopoCalibrazione = {
            val chiaveApi = ChiaveApiStore.leggiChiave(this)
            avviaRilevamentoVertici(chiaveApi)
        }
        calibrazioneConParametri = false
        mostraSceltaDirezioneCalibrazione()
    }

    private fun avviaRilevamentoVertici(chiaveApi: String) {
        modalita = Modalita.TRACCIAMENTO
        binding.tvIstruzioneTocco.visibility = View.VISIBLE
        binding.tvIstruzioneTocco.text = "Ricerca delle piante presenti nel disegno..."
        binding.superficieView.modoTocco = SuperficieView.ModoTocco.NESSUNO
        binding.superficieView.listaAttiva = null
        aggiornaBarraSchermoIntero()

        val bitmap = binding.superficieView.bitmap
        if (bitmap == null) {
            avviso("Carica prima una planimetria")
            terminaRilevamentoAutomatico()
            return
        }

        // Prima si individuano le piante/piani presenti nel disegno (una pagina catastale
        // contiene spesso più piani nella stessa immagine, es. piano seminterrato + piano
        // terra): analizzarli separatamente, uno alla volta e ciascuno ritagliato per conto
        // proprio, evita che l'AI confonda locali o vertici appartenenti a piante diverse.
        // Zone del foglio con muri pieni (le piante vere), calcolate sul telefono in parallelo
        // alla richiesta all'AI: servono ad agganciare i riquadri delle piante al disegno reale.
        zoneDisegno = null
        val sessione = sessioneRilevamento
        Thread {
            val z = try { RilevatoreZoneDisegno.zone(bitmap) } catch (e: Throwable) { emptyList() }
            // Solo se è ancora lo stesso rilevamento (altrimenti sarebbero zone di un'altra immagine).
            handlerPrincipale.post { if (sessione == sessioneRilevamento) zoneDisegno = z }
        }.start()

        InterpretePlanimetria.rilevaPiante(chiaveApi, bitmap, object : InterpretePlanimetria.CallbackPiante {
            override fun onSuccesso(piante: List<RettangoloPianta>) {
                if (!sessioneAttiva(sessione)) return
                // Anche con una sola pianta si lavora sul ritaglio della pianta (zoom sul piano)
                // invece che sull'intera tavola: su un foglio grande i vani piccoli sfuggono.
                if (piante.isEmpty()) {
                    rilevaLocaliInteraImmagine(chiaveApi, bitmap)
                } else {
                    elaboraPianteInSequenza(chiaveApi, bitmap, piante.toMutableList(), mutableListOf())
                }
            }

            override fun onErrore(messaggio: String) {
                if (!sessioneAttiva(sessione)) return
                // Se non si riescono a distinguere le piante, si procede comunque con
                // un'unica analisi sull'intera immagine (comportamento precedente).
                rilevaLocaliInteraImmagine(chiaveApi, bitmap)
            }
        })
    }

    /** Rilevamento locali in un'unica chiamata su tutta l'immagine: usato quando è presente una sola pianta, o quando non è stato possibile distinguerne più d'una. */
    private fun rilevaLocaliInteraImmagine(chiaveApi: String, bitmap: Bitmap) {
        val sessione = sessioneRilevamento
        if (!sessioneAttiva(sessione)) return
        binding.tvIstruzioneTocco.text = "Rilevamento automatico dei locali in corso (AI)..."
        aggiornaBarraSchermoIntero()
        InterpretePlanimetria.analizzaVerticiBitmap(chiaveApi, bitmap, object : InterpretePlanimetria.Callback {
            override fun onSuccesso(risultato: JSONObject) {
                if (!sessioneAttiva(sessione)) return
                val proposte = estraiVerticiDaRisultato(risultato.optJSONArray("locali"))
                // Ogni gruppo di locali si misura sulla zona di disegno (pianta) che lo contiene,
                // ad alta risoluzione, invece che sull'intera tavola rimpicciolita.
                val margine = (maxOf(bitmap.width, bitmap.height) * 0.01f).toInt()
                val zone = (zoneDisegno ?: emptyList()).map { Rect(it).apply { inset(-margine, -margine); intersect(0, 0, bitmap.width, bitmap.height) } }
                misuraPerRiquadri(bitmap, proposte, zone) { misurate -> avviaCodaLocaliRilevati(misurate) }
            }

            override fun onErrore(messaggio: String) {
                if (!sessioneAttiva(sessione)) return
                avviso("Rilevamento non riuscito: $messaggio", lungo = true)
                terminaRilevamentoAutomatico()
            }
        })
    }

    /**
     * Elabora le piante individuate una alla volta: per ciascuna, ritaglia l'immagine sulla
     * sola area di quella pianta e cerca i locali SOLO al suo interno, accumulando i risultati.
     * Se l'analisi di una pianta fallisce non blocca le altre: si prosegue con le restanti.
     */
    private fun elaboraPianteInSequenza(
        chiaveApi: String,
        bitmap: Bitmap,
        pianteRestanti: MutableList<RettangoloPianta>,
        accumulato: MutableList<PropostaLocaleAi>,
        pianteVuote: MutableList<RettangoloPianta> = mutableListOf()
    ) {
        val sessione = sessioneRilevamento
        if (!sessioneAttiva(sessione)) return
        if (pianteRestanti.isEmpty()) {
            if (pianteVuote.isEmpty()) avviaCodaLocaliRilevati(accumulato)
            else recuperaPianteVuote(chiaveApi, bitmap, accumulato, pianteVuote)
            return
        }
        val pianta = pianteRestanti.removeAt(0)
        binding.tvIstruzioneTocco.text = "Rilevamento locali: \"${pianta.nome}\"..."
        aggiornaBarraSchermoIntero()

        val rettangolo = rettangoloDaFrazioni(bitmap, pianta.x0, pianta.y0, pianta.x1, pianta.y1)
            ?.let { agganciaAlDisegno(bitmap, it) }
        val ritaglio = rettangolo?.let {
            try {
                Bitmap.createBitmap(bitmap, it.left, it.top, it.width(), it.height())
            } catch (e: Exception) {
                null
            }
        }
        if (rettangolo == null || ritaglio == null) {
            pianteVuote.add(pianta)
            elaboraPianteInSequenza(chiaveApi, bitmap, pianteRestanti, accumulato, pianteVuote)
            return
        }

        InterpretePlanimetria.analizzaVerticiBitmap(chiaveApi, ritaglio, object : InterpretePlanimetria.Callback {
            override fun onSuccesso(risultato: JSONObject) {
                if (!sessioneAttiva(sessione)) return
                val proposte = estraiVerticiDaRisultato(
                    risultato.optJSONArray("locali"),
                    rettangolo.left.toFloat(),
                    rettangolo.top.toFloat(),
                    rettangolo.width().toFloat(),
                    rettangolo.height().toFloat()
                )
                // Una pianta senza alcun locale trovato non si scarta più in silenzio: si
                // riprova alla fine sull'immagine intera (vedi recuperaPianteVuote).
                if (proposte.isEmpty()) {
                    pianteVuote.add(pianta)
                    elaboraPianteInSequenza(chiaveApi, bitmap, pianteRestanti, accumulato, pianteVuote)
                    return
                }
                binding.tvIstruzioneTocco.text = "Misura dei contorni sui muri: \"${pianta.nome}\"..."
                aggiornaBarraSchermoIntero()
                misuraLocaliSulDisegno(bitmap, rettangolo, proposte) { misurate ->
                    accumulato.addAll(misurate)
                    elaboraPianteInSequenza(chiaveApi, bitmap, pianteRestanti, accumulato, pianteVuote)
                }
            }

            override fun onErrore(messaggio: String) {
                if (!sessioneAttiva(sessione)) return
                // Prima un errore (es. risposta troncata) faceva saltare la pianta senza avvisi.
                pianteVuote.add(pianta)
                elaboraPianteInSequenza(chiaveApi, bitmap, pianteRestanti, accumulato, pianteVuote)
            }
        })
    }

    /**
     * Secondo tentativo per le piante in cui l'analisi del ritaglio non ha trovato locali (o è
     * fallita): si analizza l'immagine INTERA e si tengono i locali che cadono dentro quelle
     * piante e che non si sovrappongono a locali già trovati. Così un riquadro della pianta
     * stimato male, o una risposta non leggibile, non lascia più un intero piano senza locali.
     * Se anche così una pianta resta vuota, lo si dice chiaramente.
     */
    private fun recuperaPianteVuote(
        chiaveApi: String,
        bitmap: Bitmap,
        accumulato: MutableList<PropostaLocaleAi>,
        pianteVuote: List<RettangoloPianta>
    ) {
        val sessione = sessioneRilevamento
        if (!sessioneAttiva(sessione)) return
        binding.tvIstruzioneTocco.text =
            "Nessun locale trovato in \"${pianteVuote.joinToString("\", \"") { it.nome }}\": riprovo sull'intera planimetria..."
        aggiornaBarraSchermoIntero()

        fun concludi(recuperati: List<PropostaLocaleAi>) {
            accumulato.addAll(recuperati)
            val ancoraVuote = pianteVuote.filter { pianta ->
                val r = rettangoloDaFrazioni(bitmap, pianta.x0, pianta.y0, pianta.x1, pianta.y1)
                r == null || recuperati.none { p ->
                    val c = GeometriaUtils.centroide(p.verticiPx)
                    r.contains(c.x.toInt(), c.y.toInt())
                }
            }
            if (ancoraVuote.isNotEmpty()) {
                avviso("Nessun locale individuato in: ${ancoraVuote.joinToString(", ") { it.nome }}. Puoi tracciarli a mano con \"Nuovo locale\".", lungo = true)
            }
            avviaCodaLocaliRilevati(accumulato)
        }

        InterpretePlanimetria.analizzaVerticiBitmap(chiaveApi, bitmap, object : InterpretePlanimetria.Callback {
            override fun onSuccesso(risultato: JSONObject) {
                if (!sessioneAttiva(sessione)) return
                val tutti = estraiVerticiDaRisultato(risultato.optJSONArray("locali")).filter { it.verticiPx.size >= 3 }
                val rettangoliVuoti = pianteVuote.mapNotNull { rettangoloDaFrazioni(bitmap, it.x0, it.y0, it.x1, it.y1) }
                val recuperati = tutti.filter { p ->
                    if (p.verticiPx.size < 3) return@filter false
                    val c = GeometriaUtils.centroide(p.verticiPx)
                    // Dentro una delle piante rimaste vuote (se i loro riquadri sono attendibili)...
                    val inPiantaVuota = rettangoliVuoti.isEmpty() || rettangoliVuoti.any { it.contains(c.x.toInt(), c.y.toInt()) }
                    // ...e non già trovato nelle altre piante.
                    val giaPresente = accumulato.any { GeometriaUtils.puntoDentroPoligono(c, it.verticiPx) }
                    inPiantaVuota && !giaPresente
                }
                // Riquadri delle piante stimati male: si tengono comunque i locali nuovi non sovrapposti.
                val finali = if (recuperati.isEmpty()) tutti.filter { p ->
                    p.verticiPx.size >= 3 && accumulato.none { GeometriaUtils.puntoDentroPoligono(GeometriaUtils.centroide(p.verticiPx), it.verticiPx) }
                } else recuperati
                // Anche i locali recuperati dall'intera tavola si misurano sui muri (prima no:
                // per questo una pianta "ripescata" restava con i contorni stimati a occhio).
                val riquadri = rettangoliVuoti.map { agganciaAlDisegno(bitmap, it) }
                binding.tvIstruzioneTocco.text = "Misura dei contorni sui muri..."
                aggiornaBarraSchermoIntero()
                misuraPerRiquadri(bitmap, finali, riquadri) { concludi(it) }
            }

            override fun onErrore(messaggio: String) {
                if (!sessioneAttiva(sessione)) return
                concludi(emptyList())
            }
        })
    }

    /**
     * Aggancia il riquadro di una pianta stimato dall'AI (sull'immagine rimpicciolita, quindi
     * spesso largo o spostato) alle zone del foglio che contengono davvero muri. Se l'aggancio
     * desse un riquadro molto più grande (piante attaccate tra loro, cornici) si tiene la stima.
     */
    private fun agganciaAlDisegno(bitmap: Bitmap, stima: Rect): Rect {
        val zone = zoneDisegno ?: try { RilevatoreZoneDisegno.zone(bitmap).also { zoneDisegno = it } } catch (e: Throwable) { emptyList() }
        if (zone.isEmpty()) return stima
        val margine = (maxOf(bitmap.width, bitmap.height) * 0.01f).toInt()
        val agganciato = RilevatoreZoneDisegno.agganciaRiquadro(stima, zone, margine)
        if (!agganciato.intersect(0, 0, bitmap.width, bitmap.height)) return stima
        val areaStima = stima.width().toLong() * stima.height()
        val areaAgganciata = agganciato.width().toLong() * agganciato.height()
        return if (areaAgganciata <= areaStima * 5 / 2 && agganciato.width() >= 20 && agganciato.height() >= 20) agganciato else stima
    }

    /**
     * "L'AI dice cosa c'è, il telefono misura dov'è": per ogni vano proposto dall'AI, partendo dal
     * suo punto interno, il contorno esatto viene ricavato dai muri del disegno con un riempimento
     * locale (SegmentazionePlanimetria) sul riquadro della pianta. Se il vano non risulta chiuso
     * (loggia aperta, porta molto larga, disegno a linee sottili) si tengono i vertici stimati
     * dall'AI. Infine si aggiungono gli spazi chiusi dai muri che nessuno ha indicato, come
     * "Locale da verificare", così un vano evidente non resta fuori. Il calcolo gira in background.
     */
    private fun misuraLocaliSulDisegno(
        bitmap: Bitmap,
        regione: Rect,
        proposte: List<PropostaLocaleAi>,
        fatto: (MutableList<PropostaLocaleAi>) -> Unit
    ) {
        val metriPerPixel = scalaX
        val sessione = sessioneRilevamento
        Thread {
            val risultato = try {
                calcolaLocaliMisurati(bitmap, regione, proposte, metriPerPixel)
            } catch (e: Throwable) {
                proposte.filter { it.verticiPx.size >= 3 }.toMutableList()
            }
            handlerPrincipale.post {
                val descrizione = ultimaDescrizioneDisegno
                ultimaDescrizioneDisegno = null
                // Rilevamento interrotto o riavviato mentre si misurava: il risultato non serve più.
                if (!sessioneAttiva(sessione)) return@post
                descrizione?.let { avviso("Disegno riconosciuto: $it", lungo = true) }
                fatto(risultato)
            }
        }.start()
    }

    /** Misura i locali raggruppandoli per riquadro di pianta (quello che contiene il loro punto); gli altri sull'immagine intera. */
    private fun misuraPerRiquadri(
        bitmap: Bitmap,
        proposte: List<PropostaLocaleAi>,
        riquadri: List<Rect>,
        fatto: (MutableList<PropostaLocaleAi>) -> Unit
    ) {
        val gruppi = LinkedHashMap<Rect, MutableList<PropostaLocaleAi>>()
        val intera = Rect(0, 0, bitmap.width, bitmap.height)
        proposte.forEach { p ->
            val c = p.puntoInterno ?: GeometriaUtils.centroide(p.verticiPx)
            val r = riquadri.firstOrNull { it.contains(c.x.toInt(), c.y.toInt()) } ?: intera
            gruppi.getOrPut(r) { mutableListOf() }.add(p)
        }
        val esito = mutableListOf<PropostaLocaleAi>()
        val coda = gruppi.entries.toMutableList()
        fun prossimo() {
            if (coda.isEmpty()) { fatto(esito); return }
            val (r, lista) = coda.removeAt(0)
            misuraLocaliSulDisegno(bitmap, r, lista) { misurate -> esito.addAll(misurate); prossimo() }
        }
        prossimo()
    }

    private fun calcolaLocaliMisurati(
        bitmap: Bitmap,
        regione: Rect,
        proposte: List<PropostaLocaleAi>,
        metriPerPixel: Double?
    ): MutableList<PropostaLocaleAi> {
        // Margine in più attorno al riquadro della pianta: se il riquadro stimato taglia un pezzo
        // di pianta, i vani sul bordo risulterebbero "aperti" e non misurabili.
        val m = (maxOf(bitmap.width, bitmap.height) * 0.04f).toInt()
        val regioneLarga = Rect(regione).apply { inset(-m, -m); intersect(0, 0, bitmap.width, bitmap.height) }
        // L'analizzatore tiene pronti due modelli dei muri (campiti / a linee sottili) e per ogni
        // vano sceglie il contorno migliore: così vanno bene sia i disegni CAD recenti sia le
        // vecchie schede disegnate a mano.
        val seg = AnalizzatoreVani(bitmap, regioneLarga, metriPerPixel)
        val esito = mutableListOf<PropostaLocaleAi>()
        if (!seg.pieni.utilizzabile && !seg.linee.utilizzabile) return proposte.filter { it.verticiPx.size >= 3 }.toMutableList()
        for (p in proposte) {
            // Vano scala: i gradini disegnati spezzano lo spazio in tanti pezzi e il riempimento
            // non è affidabile; si usa la forma dell'AI riportata sui muri.
            val eScala = Regex("scal", RegexOption.IGNORE_CASE).containsMatchIn(p.nome)
            val semi = buildList {
                p.puntoInterno?.let { add(it) }
                if (p.verticiPx.size >= 3) add(GeometriaUtils.centroide(p.verticiPx))
            }
            val areaAi = if (p.verticiPx.size >= 3) GeometriaUtils.areaPoligono(p.verticiPx) else null
            var misurato: List<PointF>? = null
            for (seme in if (eScala && p.verticiPx.size >= 3) emptyList() else semi) {
                val pol = seg.misura(seme, areaAi)?.vertici ?: continue
                // Stesso vano già misurato per un'altra proposta (due punti nella stessa stanza):
                // non lo si duplica.
                val c = GeometriaUtils.centroide(pol)
                if (esito.any { it.precisa && GeometriaUtils.puntoDentroPoligono(c, it.verticiPx) }) continue
                // Coerenza con la stima dell'AI, se c'è: area non troppo diversa (altrimenti il punto
                // è caduto nel vano accanto o il riempimento è uscito da una porta).
                if (p.verticiPx.size >= 3) {
                    val aAi = GeometriaUtils.areaPoligono(p.verticiPx)
                    val aMis = GeometriaUtils.areaPoligono(pol)
                    // Tolleranza stretta: un riempimento che ha inglobato il vano accanto (porta
                    // aperta) o ha perso un pezzo non va preso per buono; meglio l'aggancio ai muri.
                    if (aAi > 0 && (aMis / aAi !in 0.6..1.45)) continue
                }
                misurato = pol
                break
            }
            when {
                misurato != null -> esito += p.copy(verticiPx = misurato, precisa = true)
                p.verticiPx.size >= 3 -> {
                    // Vano non chiuso dai muri: si tiene la forma dell'AI ma coi lati portati sulle
                    // facce dei muri vere (niente più poligoni spostati rispetto al disegno).
                    val agganciato = seg.agganciaAiMuri(p.verticiPx)
                    val cambiato = agganciato.size != p.verticiPx.size ||
                        agganciato.zip(p.verticiPx).any { (a, b) -> a.x != b.x || a.y != b.y }
                    val aAgg = GeometriaUtils.areaPoligono(agganciato)
                    val plausibile = areaAi != null && areaAi > 0 && aAgg / areaAi in 0.5..2.0
                    esito += if (cambiato && plausibile) p.copy(verticiPx = agganciato, precisa = true) else p
                }
            }
        }
        ultimaDescrizioneDisegno = seg.descrizioneDisegno()
        // Passata di completezza.
        var n = 1
        seg.vaniChiusiMancanti(esito.map { it.verticiPx }).filter { pol ->
            // solo spazi della pianta stessa, non del margine aggiunto (potrebbe toccare un'altra pianta)
            val c = GeometriaUtils.centroide(pol); regione.contains(c.x.toInt(), c.y.toInt())
        }.forEach { pol ->
            esito += PropostaLocaleAi("Locale da verificare ${n++}", pol, null, precisa = true)
        }
        return esito
    }

    /** Converte un rettangolo espresso come frazioni (0-1) dell'immagine intera in coordinate pixel, con un piccolo margine di sicurezza. */
    private fun rettangoloDaFrazioni(bitmap: Bitmap, x0: Float, y0: Float, x1: Float, y1: Float): Rect? {
        val margineX = bitmap.width * 0.015f
        val margineY = bitmap.height * 0.015f
        val left = (x0.coerceIn(0f, 1f) * bitmap.width - margineX).coerceIn(0f, bitmap.width.toFloat())
        val top = (y0.coerceIn(0f, 1f) * bitmap.height - margineY).coerceIn(0f, bitmap.height.toFloat())
        val right = (x1.coerceIn(0f, 1f) * bitmap.width + margineX).coerceIn(0f, bitmap.width.toFloat())
        val bottom = (y1.coerceIn(0f, 1f) * bitmap.height + margineY).coerceIn(0f, bitmap.height.toFloat())
        val larghezza = (right - left).toInt()
        val altezza = (bottom - top).toInt()
        if (larghezza < 20 || altezza < 20) return null
        return Rect(left.toInt(), top.toInt(), left.toInt() + larghezza, top.toInt() + altezza)
    }

    private fun avviaCodaLocaliRilevati(proposteIn: MutableList<PropostaLocaleAi>) {
        if (isFinishing || isDestroyed || !rilevamentoAutomaticoInCorso) return
        // Modalità "essenziale": solo gli spigoli principali, senza nicchie e piccoli particolari.
        val proposte = if (InterpretePlanimetria.chiudiRientranze) {
            val bmp = binding.superficieView.bitmap
            val lato = if (bmp != null) maxOf(bmp.width, bmp.height).toDouble() else 2000.0
            val m = scalaX
            // Rientranze fino a 0,6 m² di area e 0,7 m di profondità (scala nota); altrimenti in proporzione al lato maggiore.
            val areaMax = if (m != null && m > 0.0) 0.6 / (m * m) else (lato * 0.03) * (lato * 0.03)
            val profMax = if (m != null && m > 0.0) 0.7 / m else lato * 0.03
            proposteIn.map { it.copy(verticiPx = GeometriaUtils.chiudiRientranze(it.verticiPx, areaMax, profMax)) }.toMutableList()
        } else if (!InterpretePlanimetria.soloSpigoli) proposteIn else {
            val bmp = binding.superficieView.bitmap
            val lato = if (bmp != null) maxOf(bmp.width, bmp.height).toDouble() else 2000.0
            val m = scalaX
            // Soglia: triangolo di 0,25 m² se la scala è nota, altrimenti 0,0002 del lato maggiore al quadrato.
            val soglia = if (m != null && m > 0.0) 0.25 / (m * m) else (lato * 0.014) * (lato * 0.014)
            proposteIn.map { it.copy(verticiPx = GeometriaUtils.semplificaSpigoli(it.verticiPx, soglia)) }.toMutableList()
        }
        faseLocaliInCorso = true
        codaRilevamentoAi = proposte
        if (codaRilevamentoAi.isEmpty()) {
            binding.tvIstruzioneTocco.text = "Non ho individuato locali con contorno riconoscibile in questa immagine."
            aggiornaBarraSchermoIntero()
            handlerPrincipale.postDelayed({ terminaRilevamentoAutomatico() }, 1800)
            return
        }
        avviaAnimazioneENextLocale()
    }

    private fun avviaAnimazioneENextLocale() {
        // Nuova "generazione": invalida automaticamente qualunque passo asincrono (chiamata AI,
        // animazione ritardata) ancora in volo per il locale precedente, vedi commento sul campo.
        generazioneElaborazioneLocale++
        val generazione = generazioneElaborazioneLocale
        if (codaRilevamentoAi.isEmpty()) {
            terminaRilevamentoAutomatico()
            return
        }
        val proposta = codaRilevamentoAi.removeAt(0)
        raffinaEAnimaLocale(proposta, generazione)
    }

    /**
     * Permette di scartare immediatamente il locale che l'AI sta elaborando o animando in
     * questo momento (prima ancora della domanda "vuoi correggerlo?"), per chi si accorge
     * durante l'elaborazione che quella forma non è un locale reale da rilevare. Interrompe
     * le animazioni/attese programmate per questo locale e ignora eventuali risposte AI per
     * esso ancora in arrivo, poi passa subito al locale successivo in coda.
     */
    private fun saltaLocaleInElaborazione() {
        // Nella fase iniziale (scala, piante, misura) non c'è ancora un locale da saltare: prima
        // il pulsante faceva terminare tutto con "completato" mentre l'analisi era ancora in corso.
        if (!rilevamentoAutomaticoInCorso || !faseLocaliInCorso || modalita == Modalita.MODIFICA_LOCALE) return
        handlerPrincipale.removeCallbacksAndMessages(null)
        binding.superficieView.puntiLocaleCorrente.clear()
        binding.superficieView.disegnaCorrenteChiuso = false
        binding.superficieView.azzeraZoom()
        binding.superficieView.invalidate()
        avviso("Locale saltato")
        avviaAnimazioneENextLocale()
    }

    /**
     * Chiede conferma prima di interrompere del tutto il rilevamento automatico (a differenza
     * di "Salta locale", che scarta solo quello in esame). I locali non ancora esaminati non
     * vengono persi: restano in sospeso e si può riprendere da lì in qualunque momento con
     * "Rileva locali automaticamente" (vedi [codaRilevamentoSospesa] e [annullaRilevamentoAutomatico]).
     */
    private fun confermaAnnullaRilevamentoAutomatico() {
        if (!rilevamentoAutomaticoInCorso) return
        // Metti in pausa il rilevamento finché questo dialogo non viene chiuso (con "Interrompi",
        // con "Continua" o anche con un tocco fuori/il tasto indietro): vedi rilevamentoInPausaPerConferma.
        rilevamentoInPausaPerConferma = true
        val dialog = AlertDialog.Builder(this, R.style.Theme_SchedaCliente_Dialog)
            .setTitle("Interrompere il rilevamento?")
            .setMessage(
                "I locali già confermati restano salvati. Potrai riprendere da dove ti sei " +
                    "fermato in qualunque momento con \"Rileva locali automaticamente\"."
            )
            .setPositiveButton("Interrompi") { _, _ -> annullaRilevamentoAutomatico() }
            .setNegativeButton("Continua", null)
            .setOnDismissListener { rilevamentoInPausaPerConferma = false }
            .show()
        dialog.rendiPulsantiLeggibili(this)
    }

    /**
     * Interrompe subito l'intero rilevamento automatico, qualunque sia il punto in cui si
     * trova (animazione di un locale, attesa di una risposta AI, verifica di una proposta):
     * CORREZIONE (ottobre 2026) — prima di questa funzione non esisteva un modo per annullare
     * tutta l'operazione in un colpo solo. "Salta locale" scartava un locale alla volta e la X
     * in alto chiudeva solo la vista a schermo intero senza fermare il rilevamento, che
     * proseguiva comunque in sottofondo finché non si arrivava da solo in fondo alla coda:
     * per fermare un rilevamento partito per errore bisognava premere "Salta locale" una volta
     * per OGNI locale rimasto. generazioneElaborazioneLocale viene incrementato per invalidare
     * qualunque passo asincrono (chiamata AI o animazione ritardata) ancora in volo per il
     * locale che si stava esaminando, esattamente come fa già avviaAnimazioneENextLocale().
     *
     * CORREZIONE (ottobre 2026, seconda parte): su richiesta esplicita dell'utente, i locali
     * non ancora esaminati (quello eventualmente in corso di verifica più tutti quelli ancora
     * in coda) vengono ora salvati in [codaRilevamentoSospesa] invece di essere scartati del
     * tutto: si può così tornare sulla planimetria, fare altro, e poi riprendere il rilevamento
     * automatico esattamente da dove era stato interrotto con "Rileva locali automaticamente",
     * senza dover rianalizzare da capo (e pagare di nuovo in chiamate AI) tutta la planimetria.
     */
    private fun annullaRilevamentoAutomatico() {
        handlerPrincipale.removeCallbacksAndMessages(null)
        generazioneElaborazioneLocale++
        val restanti = mutableListOf<PropostaLocaleAi>()
        proposizioneInVerifica?.let { restanti.add(it) }
        restanti.addAll(codaRilevamentoAi)
        codaRilevamentoSospesa = if (restanti.isNotEmpty()) restanti else null
        val messaggio = if (restanti.isNotEmpty()) {
            "Rilevamento interrotto: ${restanti.size} locali in sospeso, riprendibili con \"Rileva locali automaticamente\""
        } else {
            "Rilevamento automatico interrotto"
        }
        avviso(messaggio, lungo = true)
        terminaOperazioneInCorso()
    }

    /**
     * Calcola il rettangolo (nello spazio pixel dell'immagine intera) attorno ai vertici
     * grezzi di un locale, con un margine, da usare per ritagliare un'immagine centrata su
     * quel solo locale. Restituisce null se i vertici non bastano a calcolare un'area sensata.
     */
    private fun calcolaRettangoloRitaglio(bitmap: Bitmap, verticiPx: List<PointF>): Rect? {
        if (verticiPx.isEmpty()) return null
        var minX = verticiPx[0].x
        var maxX = verticiPx[0].x
        var minY = verticiPx[0].y
        var maxY = verticiPx[0].y
        for (p in verticiPx) {
            if (p.x < minX) minX = p.x
            if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y
            if (p.y > maxY) maxY = p.y
        }
        val largBbox = (maxX - minX).coerceAtLeast(1f)
        val altBbox = (maxY - minY).coerceAtLeast(1f)
        // Margine generoso attorno al locale stimato: la stima grezza può essere imprecisa,
        // quindi il ritaglio deve comunque contenere l'intero locale reale e le sue pareti.
        val margine = (maxOf(largBbox, altBbox) * 0.45f).coerceAtLeast(70f)
        val left = (minX - margine).coerceIn(0f, bitmap.width.toFloat())
        val top = (minY - margine).coerceIn(0f, bitmap.height.toFloat())
        val right = (maxX + margine).coerceIn(0f, bitmap.width.toFloat())
        val bottom = (maxY + margine).coerceIn(0f, bitmap.height.toFloat())
        val larghezza = (right - left).toInt()
        val altezza = (bottom - top).toInt()
        if (larghezza < 20 || altezza < 20) return null
        return Rect(left.toInt(), top.toInt(), left.toInt() + larghezza, top.toInt() + altezza)
    }

    /**
     * Prima di animare i vertici di un locale, li raffina lavorando su un ritaglio zoomato
     * dell'immagine centrato su quel solo locale (vedi [InterpretePlanimetria.raffinaVerticiLocale]):
     * molto più preciso che stimarli direttamente sull'intera planimetria, specie quando questa
     * contiene più locali e più piante. Se il raffinamento non è disponibile o non trova un
     * contorno affidabile, prosegue comunque con la stima grezza originale.
     */
    private fun raffinaEAnimaLocale(proposta: PropostaLocaleAi, generazione: Int) {
        binding.superficieView.puntiLocaleCorrente.clear()
        binding.superficieView.disegnaCorrenteChiuso = false
        binding.superficieView.modoTocco = SuperficieView.ModoTocco.NESSUNO
        binding.superficieView.listaAttiva = null
        binding.tvIstruzioneTocco.text = "Affinamento vertici: \"${proposta.nome}\"..."
        aggiornaBarraSchermoIntero()
        binding.superficieView.invalidate()

        val bitmap = binding.superficieView.bitmap
        val chiaveApi = ChiaveApiStore.leggiChiave(this)

        // Contorno già misurato sui muri del disegno: niente affinamento AI (sarebbe meno preciso).
        if (proposta.precisa || bitmap == null || chiaveApi.isBlank() || InterpretePlanimetria.latoPerLato) {
            avviaAnimazioneVertici(proposta, generazione)
            return
        }

        val rettangolo = calcolaRettangoloRitaglio(bitmap, proposta.verticiPx)
        if (rettangolo == null) {
            avviaAnimazioneVertici(proposta, generazione)
            return
        }

        val ritaglio = try {
            Bitmap.createBitmap(bitmap, rettangolo.left, rettangolo.top, rettangolo.width(), rettangolo.height())
        } catch (e: Exception) {
            null
        }
        if (ritaglio == null) {
            proseguiConRaffinamentoPerVertice(proposta, bitmap, chiaveApi, generazione)
            return
        }

        InterpretePlanimetria.raffinaVerticiLocale(
            chiaveApi,
            ritaglio,
            proposta.nome,
            "",
            object : InterpretePlanimetria.CallbackVertici {
                override fun onSuccesso(vertici: List<PointF>) {
                    if (isFinishing || isDestroyed || generazione != generazioneElaborazioneLocale) return
                    val propostaRaffinata = if (vertici.size >= 3) {
                        val verticiAssoluti = vertici.map { p ->
                            PointF(
                                rettangolo.left + p.x * rettangolo.width(),
                                rettangolo.top + p.y * rettangolo.height()
                            )
                        }
                        proposta.copy(verticiPx = verticiAssoluti)
                    } else {
                        proposta
                    }
                    // CORREZIONE (ottobre 2026): il passaggio di raffinamento "vertice per
                    // vertice" (una chiamata AI aggiuntiva per OGNI singolo vertice di OGNI
                    // locale) è stato tolto dal percorso normale su richiesta esplicita
                    // dell'utente, che ha segnalato tempi troppo lunghi durante il rilevamento
                    // automatico di planimetrie con molti locali: con locali da 5-6 vertici
                    // ciascuno, significava 5-6 chiamate di rete IN PIÙ, una dopo l'altra, solo
                    // per quel locale. Il raffinamento sull'intero locale (il passaggio appena
                    // completato sopra) resta invece attivo: è già una sola chiamata per
                    // locale e offre comunque una buona precisione. La funzione
                    // proseguiConRaffinamentoPerVertice() resta nel codice, pronta per essere
                    // ricollegata in futuro se la precisione dovesse tornare prioritaria sulla
                    // velocità.
                    avviaAnimazioneVertici(propostaRaffinata, generazione)
                }

                override fun onErrore(messaggio: String) {
                    if (isFinishing || isDestroyed || generazione != generazioneElaborazioneLocale) return
                    avviaAnimazioneVertici(proposta, generazione)
                }
            }
        )
    }

    /**
     * Ultimo passo, il più preciso: dopo aver raffinato l'intero locale in un colpo solo (sopra),
     * raffina UN VERTICE ALLA VOLTA lavorando su un ritaglio piccolissimo e molto ingrandito,
     * centrato sul solo angolo in esame (vedi [InterpretePlanimetria.raffinaVerticeSingolo]). È il
     * caso più favorevole possibile per la stima dell'AI: nel ritaglio non compare nessun'altra
     * parete o locale che possa confondere, e lo stesso piccolo errore "a occhio" del modello,
     * espresso come frazione di un ritaglio minuscolo, corrisponde a pochissimi pixel reali una
     * volta riportato sull'immagine intera. Procede un vertice alla volta (una chiamata AI per
     * vertice); se la stima di un singolo vertice fallisce o non è disponibile, mantiene quella
     * già nota per quel vertice e prosegue con gli altri.
     */
    private fun proseguiConRaffinamentoPerVertice(proposta: PropostaLocaleAi, bitmap: Bitmap, chiaveApi: String, generazione: Int) {
        if (isFinishing || isDestroyed || generazione != generazioneElaborazioneLocale) return
        val vertici = proposta.verticiPx.toMutableList()
        if (vertici.size < 3) {
            avviaAnimazioneVertici(proposta, generazione)
            return
        }
        binding.tvIstruzioneTocco.text = "Affinamento di precisione dei vertici: \"${proposta.nome}\"..."
        aggiornaBarraSchermoIntero()

        var indice = 0
        lateinit var passoSuccessivo: () -> Unit
        passoSuccessivo = passo@{
            if (isFinishing || isDestroyed || generazione != generazioneElaborazioneLocale) return@passo
            if (indice >= vertici.size) {
                avviaAnimazioneVertici(proposta.copy(verticiPx = vertici), generazione)
                return@passo
            }
            val rettangoloVertice = calcolaRettangoloRitaglioVertice(bitmap, vertici, indice)
            val ritaglioVertice = rettangoloVertice?.let {
                try {
                    Bitmap.createBitmap(bitmap, it.left, it.top, it.width(), it.height())
                } catch (e: Exception) {
                    null
                }
            }
            if (rettangoloVertice == null || ritaglioVertice == null) {
                indice++
                passoSuccessivo()
                return@passo
            }
            InterpretePlanimetria.raffinaVerticeSingolo(
                chiaveApi,
                ritaglioVertice,
                object : InterpretePlanimetria.CallbackVerticeSingolo {
                    override fun onSuccesso(trovato: Boolean, x: Float, y: Float) {
                        if (isFinishing || isDestroyed || generazione != generazioneElaborazioneLocale) return
                        if (trovato) {
                            vertici[indice] = PointF(
                                rettangoloVertice.left + x * rettangoloVertice.width(),
                                rettangoloVertice.top + y * rettangoloVertice.height()
                            )
                        }
                        indice++
                        passoSuccessivo()
                    }

                    override fun onErrore(messaggio: String) {
                        if (isFinishing || isDestroyed || generazione != generazioneElaborazioneLocale) return
                        indice++
                        passoSuccessivo()
                    }
                }
            )
        }
        passoSuccessivo()
    }

    /**
     * Rettangolo (pixel, immagine intera) centrato su UN SOLO vertice di un locale, con un
     * margine calcolato dai lati adiacenti: abbastanza grande da contenere con certezza
     * l'incrocio delle due pareti che formano l'angolo, ma abbastanza piccolo da non includere
     * l'intero locale (che renderebbe la stima di nuovo grossolana come il primo passaggio).
     */
    private fun calcolaRettangoloRitaglioVertice(bitmap: Bitmap, vertici: List<PointF>, indice: Int): Rect? {
        val n = vertici.size
        if (n < 3) return null
        val punto = vertici[indice]
        val precedente = vertici[(indice - 1 + n) % n]
        val successivo = vertici[(indice + 1) % n]
        val latoPiuCorto = minOf(
            GeometriaUtils.distanza(punto, precedente),
            GeometriaUtils.distanza(punto, successivo)
        ).toFloat()
        val margine = (latoPiuCorto * 0.45f).coerceIn(55f, 220f)
        val left = (punto.x - margine).coerceIn(0f, bitmap.width.toFloat())
        val top = (punto.y - margine).coerceIn(0f, bitmap.height.toFloat())
        val right = (punto.x + margine).coerceIn(0f, bitmap.width.toFloat())
        val bottom = (punto.y + margine).coerceIn(0f, bitmap.height.toFloat())
        val larghezza = (right - left).toInt()
        val altezza = (bottom - top).toInt()
        if (larghezza < 16 || altezza < 16) return null
        return Rect(left.toInt(), top.toInt(), left.toInt() + larghezza, top.toInt() + altezza)
    }

    private val PAUSA_VERTICE_MS = 140L
    // Richiesto dall'utente: 3 secondi (prima 1,5) prima che "Conferma così" confermi da solo.
    private val DURATA_CONFERMA_AUTOMATICA_MS = 3000L

    private fun avviaAnimazioneVertici(proposta: PropostaLocaleAi, generazione: Int) {
        if (isFinishing || isDestroyed || generazione != generazioneElaborazioneLocale) return
        // In pausa: il dialogo "Interrompere il rilevamento?" è aperto, quindi non si zooma né si
        // rivela il locale finché l'utente non lo chiude (vedi rilevamentoInPausaPerConferma).
        if (rilevamentoInPausaPerConferma) {
            handlerPrincipale.postDelayed({ avviaAnimazioneVertici(proposta, generazione) }, 150L)
            return
        }
        binding.tvIstruzioneTocco.text = "Rilevamento vertici: \"${proposta.nome}\"..."
        aggiornaBarraSchermoIntero()
        // v3.82: UN solo zoom per locale, che inquadra tutto il vano (prima uno zoom su ogni vertice).
        if (proposta.verticiPx.size >= 2) {
            val minP = PointF(proposta.verticiPx.minOf { it.x }, proposta.verticiPx.minOf { it.y })
            val maxP = PointF(proposta.verticiPx.maxOf { it.x }, proposta.verticiPx.maxOf { it.y })
            val centro = PointF((minP.x + maxP.x) / 2f, (minP.y + maxP.y) / 2f)
            binding.superficieView.zoomCentratoSu(centro, zoomPerInquadrare(minP, maxP).coerceIn(1f, 6f))
        }
        binding.superficieView.invalidate()

        var indice = 0
        lateinit var passoSuccessivo: () -> Unit
        passoSuccessivo = {
            if (isFinishing || isDestroyed || generazione != generazioneElaborazioneLocale) {
                // nessuna azione: l'operazione è stata interrotta insieme alla schermata, oppure
                // l'utente ha premuto "Salta locale" (o si è passati a un altro locale) nel
                // frattempo: questa è ormai una "generazione" superata.
            } else if (indice >= proposta.verticiPx.size) {
                binding.superficieView.disegnaCorrenteChiuso = true
                binding.superficieView.invalidate()
                // v3.82: pausa finale ridotta (prima 200 + 250 ms) e niente ritorno alla vista
                // intera: il vano resta inquadrato mentre si decide se confermarlo.
                handlerPrincipale.postDelayed({
                    if (isFinishing || isDestroyed || generazione != generazioneElaborazioneLocale) return@postDelayed
                    chiediCorrezioneManuale(proposta, generazione)
                }, 120)
            } else {
                // v3.82: niente più zoom avanti e indietro su OGNI vertice (circa 0,3 s ciascuno, quasi
                // 2 s per locale solo di animazione): i vertici compaiono in sequenza veloce sulla vista
                // intera e la verifica si fa sul locale completo (e col dialogo "Sposta manualmente").
                handlerPrincipale.postDelayed({
                    if (isFinishing || isDestroyed || generazione != generazioneElaborazioneLocale) return@postDelayed
                    binding.superficieView.puntiLocaleCorrente.add(PointF(proposta.verticiPx[indice].x, proposta.verticiPx[indice].y))
                    binding.superficieView.invalidate()
                    indice++
                    passoSuccessivo()
                }, PAUSA_VERTICE_MS)
            }
        }
        passoSuccessivo()
    }

    /**
     * Chiede se correggere il locale appena rilevato. Se entro 3 secondi non si preme nulla, il
     * locale si considera confermato così com'è e si passa al successivo (conto alla rovescia
     * visibile sul pulsante "Conferma così"). Qualunque pulsante premuto ferma il conto. Il conto
     * si ferma anche (senza consumare tempo) mentre è aperto il dialogo "Interrompere il
     * rilevamento?" (vedi [rilevamentoInPausaPerConferma]).
     */
    private fun chiediCorrezioneManuale(proposta: PropostaLocaleAi, generazione: Int) {
        if (generazione != generazioneElaborazioneLocale) return
        var scelto = false
        val dialog = AlertDialog.Builder(this, R.style.Theme_SchedaCliente_Dialog)
            .setTitle("Locale \"${proposta.nome}\" rilevato")
            .setMessage("Ho individuato ${proposta.verticiPx.size} vertici. Vuoi spostarli manualmente prima di confermare il locale?\n\nSe non premi nulla viene confermato così.")
            .setPositiveButton("Sposta manualmente") { _, _ -> scelto = true; abilitaCorrezioneManuale(proposta) }
            .setNegativeButton("Conferma così") { _, _ -> scelto = true; confermaLocaleRilevato(proposta, proposta.verticiPx) }
            .setNeutralButton("Scarta locale") { _, _ -> scelto = true; scartaLocaleRilevato(proposta) }
            .setCancelable(false)
            .show()
        dialog.rendiPulsantiLeggibili(this)
        val pulsanteConferma = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
        val durataMs = DURATA_CONFERMA_AUTOMATICA_MS
        // CORREZIONE v3.76: il tempo conta solo mentre l'app è in primo piano. Prima il conto
        // continuava anche con l'app in secondo piano (telefonata, cambio app, schermo spento) e al
        // ritorno si trovavano tutti i locali confermati da soli, senza averli visti.
        var trascorsoMs = 0L
        var ultimoTic = android.os.SystemClock.uptimeMillis()
        lateinit var tic: Runnable
        tic = Runnable {
            if (scelto || !dialog.isShowing || isFinishing || isDestroyed) return@Runnable
            if (generazione != generazioneElaborazioneLocale) { scelto = true; dialog.dismiss(); return@Runnable }
            val ora = android.os.SystemClock.uptimeMillis()
            // In pausa (dialogo "Interrompere il rilevamento?" aperto sopra questo): il conto non
            // avanza, ma resta "vivo" così riparte da solo non appena l'utente chiude quel dialogo.
            if (rilevamentoInPausaPerConferma) { ultimoTic = ora; handlerPrincipale.postDelayed(tic, 100L); return@Runnable }
            if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) trascorsoMs += ora - ultimoTic
            ultimoTic = ora
            val restanti = durataMs - trascorsoMs
            if (restanti <= 0) {
                scelto = true
                dialog.dismiss()
                confermaLocaleRilevato(proposta, proposta.verticiPx)
            } else {
                pulsanteConferma?.text = String.format(Locale.ITALY, "Conferma così (%.1f s)", restanti / 1000.0)
                handlerPrincipale.postDelayed(tic, 100L)
            }
        }
        handlerPrincipale.post(tic)
    }

    /** Scarta un locale appena rilevato (visto dall'animazione finale) senza aggiungerlo all'elenco, e passa subito al successivo. */
    private fun scartaLocaleRilevato(proposta: PropostaLocaleAi) {
        avviso("Locale \"${proposta.nome}\" scartato")
        binding.superficieView.puntiLocaleCorrente.clear()
        binding.superficieView.disegnaCorrenteChiuso = false
        binding.superficieView.invalidate()
        avviaAnimazioneENextLocale()
    }

    private fun abilitaCorrezioneManuale(proposta: PropostaLocaleAi) {
        proposizioneInVerifica = proposta
        modalita = Modalita.MODIFICA_LOCALE
        nomeModificato = null
        binding.superficieView.modoTocco = SuperficieView.ModoTocco.SOLO_TRASCINA
        binding.superficieView.listaAttiva = binding.superficieView.puntiLocaleCorrente
        binding.containerControlliModifica.visibility = View.VISIBLE
        binding.containerStrumentiModifica.visibility = View.VISIBLE
        binding.superficieView.modalitaInserimentoBordo = false
        binding.superficieView.modalitaRimozioneVertice = false
        binding.tvIstruzioneTocco.text =
            "Correggi i vertici di \"${proposta.nome}\": tieni premuto un vertice e spostalo con la lente, poi premi \"Conferma modifiche\"."
        aggiornaBarraSchermoIntero()
    }

    private fun confermaLocaleRilevato(proposta: PropostaLocaleAi, verticiPx: List<PointF>) {
        modalita = Modalita.TRACCIAMENTO
        val (area, lati) = calcolaAreaELati(verticiPx)
        val localeTracciato = LocaleTracciato(proposta.nome, verticiPx, area, lati)
        locali.add(localeTracciato)
        binding.superficieView.locali.add(localeTracciato)
        binding.superficieView.puntiLocaleCorrente.clear()
        binding.superficieView.disegnaCorrenteChiuso = false
        binding.superficieView.invalidate()
        disegnaElencoLocali()
        aggiornaTotale()
        avviaAnimazioneENextLocale()
    }

    private fun terminaRilevamentoAutomatico() {
        avviso("Rilevamento automatico completato")
        terminaOperazioneInCorso()
    }

    // ---------------------------------------------------------------------
    // Riconoscimento automatico della scala sui PDF catastali
    // ---------------------------------------------------------------------

    private fun avviaRiconoscimentoScalaAutomatico(bitmap: Bitmap) {
        val chiaveApi = ChiaveApiStore.leggiChiave(this)
        if (chiaveApi.isBlank()) {
            binding.tvStatoScala.text =
                "Scala: non impostata. Per riconoscerla automaticamente dal PDF inserisci una chiave AI nelle Impostazioni, oppure usa \"Imposta scala\" manualmente."
            return
        }
        val larghezzaPt = paginaPdfLarghezzaPt
        val larghezzaRenderPx = bitmapRenderPx
        if (larghezzaPt == null || larghezzaRenderPx == null) return

        binding.tvStatoScala.text = "Scala: riconoscimento automatico dal PDF in corso..."
        InterpretaScalaCatastale.analizza(chiaveApi, bitmap, object : InterpretaScalaCatastale.Callback {
            override fun onSuccesso(denominatore: Int?, testoOriginale: String?, nota: String?) {
                if (isFinishing || isDestroyed) return
                if (denominatore != null) {
                    val paginaLarghezzaCm = (larghezzaPt / 72f) * 2.54f
                    val metriPerPixel = (denominatore / 100.0) * (paginaLarghezzaCm / larghezzaRenderPx)
                    scalaX = metriPerPixel
                    scalaY = metriPerPixel
                    val testoLetto = if (!testoOriginale.isNullOrBlank()) " (testo letto: \"$testoOriginale\")" else ""
                    binding.tvStatoScala.text =
                        "Scala riconosciuta automaticamente dal PDF: 1:$denominatore$testoLetto. " +
                            "Verifica sempre con \"Imposta scala\" su una misura nota prima di usare i risultati in una pratica."
                    ricalcolaTuttiLocali()
                } else {
                    val spiegazione = if (!nota.isNullOrBlank()) " ($nota)" else ""
                    binding.tvStatoScala.text = "Scala: non riconosciuta automaticamente nel PDF$spiegazione. Impostala con \"Imposta scala\"."
                }
            }

            override fun onErrore(messaggio: String) {
                if (isFinishing || isDestroyed) return
                binding.tvStatoScala.text = "Scala: non riconosciuta automaticamente ($messaggio). Impostala con \"Imposta scala\"."
            }
        })
    }

    // ---------------------------------------------------------------------
    // Sezione tracciamento manuale: scala (anche successiva) + vertici modificabili
    // ---------------------------------------------------------------------

    private fun gestisciNuovoPunto() {
        when (modalita) {
            Modalita.CALIBRAZIONE -> {
                if (binding.superficieView.puntiCalibrazione.size == 2) chiediMisuraReale()
            }
            else -> { /* nessuna azione aggiuntiva: il punto è già visibile */ }
        }
    }

    private fun gestisciPuntoSpostato() {
        if (modalita == Modalita.MODIFICA_LOCALE) {
            // L'anteprima si aggiorna da sola col ridisegno della vista; nulla da calcolare finché non si conferma.
        }
    }

    private fun avviaCalibrazione() {
        if (binding.superficieView.bitmap == null) {
            avviso("Carica prima una planimetria")
            return
        }
        if (modalita != Modalita.NESSUNA) {
            avviso("Finisci prima l'operazione in corso")
            return
        }

        // Icona reticolo/pareti accanto alle due opzioni (richiesta dall'utente), vedi
        // mostraListaConIcone().
        val opzioniTipo = listOf(
            "Misura nota in metri (es. una parete)" to R.drawable.ic_pareti,
            "Parametri del reticolo catastale (ogni parametro = 10 m)" to R.drawable.ic_reticolo
        )
        val dialogTipo = AlertDialog.Builder(this, R.style.Theme_SchedaCliente_Dialog)
            .setTitle("Riferimento per la scala")
            .setAdapter(AdapterListaConIcone(this, opzioniTipo)) { _, indiceTipo ->
                calibrazioneConParametri = indiceTipo == 1
                mostraSceltaDirezioneCalibrazione()
            }
            .setNegativeButton("Annulla", null)
            .show()
        dialogTipo.rendiPulsantiLeggibili(this)
    }

    private fun mostraSceltaDirezioneCalibrazione() {
        // Con il reticolo catastale la direzione è sempre quella dei parametri (orizzontale e/o
        // verticale): i segni del reticolo non sono mai obliqui, quindi quell'opzione ha senso
        // solo misurando a mano una parete o un altro riferimento che può comparire inclinato
        // nella foto/scansione. Richiesto dall'utente: togliere "Obliqua" dal reticolo.
        val opzioni = if (calibrazioneConParametri) {
            arrayOf(
                "Orizzontale",
                "Verticale",
                "Entrambe (orizzontale e verticale, per planimetrie non uniformi)"
            )
        } else {
            arrayOf(
                "Orizzontale",
                "Verticale",
                "Obliqua (qualsiasi direzione)",
                "Entrambe (orizzontale e verticale, per planimetrie non uniformi)"
            )
        }
        val dialog = AlertDialog.Builder(this, R.style.Theme_SchedaCliente_Dialog)
            .setTitle("Direzione della misura di riferimento")
            .setItems(opzioni) { _, indice ->
                if (calibrazioneConParametri) {
                    when (indice) {
                        0 -> iniziaStepCalibrazione(DirezioneScala.ORIZZONTALE, ultimoStep = true)
                        1 -> iniziaStepCalibrazione(DirezioneScala.VERTICALE, ultimoStep = true)
                        2 -> {
                            calibrazioneEntrambeInCorso = true
                            scalaXTemporanea = null
                            avviso("Prima misura: tocca due punti su una linea orizzontale e indica parametri.", lungo = true)
                            iniziaStepCalibrazione(DirezioneScala.ORIZZONTALE, ultimoStep = false)
                        }
                    }
                } else {
                    when (indice) {
                        0 -> iniziaStepCalibrazione(DirezioneScala.ORIZZONTALE, ultimoStep = true)
                        1 -> iniziaStepCalibrazione(DirezioneScala.VERTICALE, ultimoStep = true)
                        2 -> iniziaStepCalibrazione(DirezioneScala.OBLIQUA, ultimoStep = true)
                        3 -> {
                            calibrazioneEntrambeInCorso = true
                            scalaXTemporanea = null
                            avviso("Prima misura: tocca due punti su una linea orizzontale e indica una misura nota.", lungo = true)
                            iniziaStepCalibrazione(DirezioneScala.ORIZZONTALE, ultimoStep = false)
                        }
                    }
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
        dialog.rendiPulsantiLeggibili(this)
    }

    private fun iniziaStepCalibrazione(direzione: DirezioneScala, ultimoStep: Boolean) {
        direzioneCalibrazioneCorrente = direzione
        calibrazioneUltimoStep = ultimoStep
        modalita = Modalita.CALIBRAZIONE

        binding.superficieView.puntiCalibrazione.clear()
        binding.superficieView.modoTocco = SuperficieView.ModoTocco.AGGIUNGI_E_TRASCINA
        binding.superficieView.listaAttiva = binding.superficieView.puntiCalibrazione
        binding.superficieView.limiteMassimoPunti = 2
        binding.superficieView.invalidate()

        binding.tvIstruzioneTocco.visibility = View.VISIBLE
        val suggerimentoParametri = if (calibrazioneConParametri) " Tocca le due crocette più lontane del reticolo catastale (quelle lungo il riquadro blu)." else ""
        binding.tvIstruzioneTocco.text = when (direzione) {
            DirezioneScala.ORIZZONTALE -> "Tocca i due estremi di una misura nota orizzontale (es. una parete).$suggerimentoParametri Tieni il dito premuto per correggere il punto con la lente, poi rilascia. Pizzica con due dita per ingrandire."
            DirezioneScala.VERTICALE -> "Tocca i due estremi di una misura nota verticale.$suggerimentoParametri Tieni il dito premuto per correggere il punto con la lente, poi rilascia. Pizzica con due dita per ingrandire."
            DirezioneScala.OBLIQUA -> "Tocca i due estremi di una misura nota sul disegno (qualsiasi direzione).$suggerimentoParametri Tieni il dito premuto per correggere il punto con la lente, poi rilascia. Pizzica con due dita per ingrandire."
        }
        binding.btnSchermoIntero.visibility = View.VISIBLE
        abilitaControlli(false)
        apriSchermoIntero()
    }

    private fun chiediMisuraReale() {
        if (calibrazioneConParametri) chiediNumeroParametri() else chiediDistanzaInMetri()
    }

    private fun chiediDistanzaInMetri() {
        val campo = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "Distanza reale in metri (es. 4,50)"
            setPadding(
                (16 * resources.displayMetrics.density).toInt(), (12 * resources.displayMetrics.density).toInt(),
                (16 * resources.displayMetrics.density).toInt(), (12 * resources.displayMetrics.density).toInt()
            )
        }
        val messaggio = when (direzioneCalibrazioneCorrente) {
            DirezioneScala.ORIZZONTALE -> "Quanto misura in realtà, in metri, questa distanza orizzontale?"
            DirezioneScala.VERTICALE -> "Quanto misura in realtà, in metri, questa distanza verticale?"
            DirezioneScala.OBLIQUA -> "Quanto misura in realtà, in metri, la distanza tra i due punti toccati?"
        }
        val dialog = AlertDialog.Builder(this, R.style.Theme_SchedaCliente_Dialog)
            .setTitle("Misura nota")
            .setMessage(messaggio)
            .setView(campo)
            .setPositiveButton("Conferma") { _, _ ->
                val valore = campo.text?.toString()?.trim()?.replace(',', '.')?.toDoubleOrNull()
                if (valore == null || valore <= 0.0) {
                    avviso("Distanza non valida: scala non impostata")
                    calibrazioneEntrambeInCorso = false
                    scalaXTemporanea = null
                    terminaOperazioneInCorso()
                } else {
                    confermaMisuraReale(valore)
                }
            }
            .setNegativeButton("Annulla") { _, _ ->
                calibrazioneEntrambeInCorso = false
                scalaXTemporanea = null
                terminaOperazioneInCorso()
            }
            .setCancelable(false)
            .show()
        dialog.rendiPulsantiLeggibili(this)
    }

    private fun chiediNumeroParametri() {
        val campo = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "Numero di parametri (es. 7)"
            setPadding(
                (16 * resources.displayMetrics.density).toInt(), (12 * resources.displayMetrics.density).toInt(),
                (16 * resources.displayMetrics.density).toInt(), (12 * resources.displayMetrics.density).toInt()
            )
        }
        val dialog = AlertDialog.Builder(this, R.style.Theme_SchedaCliente_Dialog)
            .setTitle("Parametri del reticolo catastale")
            .setMessage(
                "Quanti parametri (intervalli da 10 metri ciascuno) ci sono tra i due punti toccati? " +
                    "Conta le crocette del riquadro blu tra i due punti, inclusa la distanza che hai toccato."
            )
            .setView(campo)
            .setPositiveButton("Conferma") { _, _ ->
                val numeroParametri = campo.text?.toString()?.trim()?.toIntOrNull()
                if (numeroParametri == null || numeroParametri <= 0) {
                    avviso("Numero non valido: scala non impostata")
                    calibrazioneEntrambeInCorso = false
                    scalaXTemporanea = null
                    terminaOperazioneInCorso()
                } else {
                    confermaMisuraReale(numeroParametri * 10.0)
                }
            }
            .setNegativeButton("Annulla") { _, _ ->
                calibrazioneEntrambeInCorso = false
                scalaXTemporanea = null
                terminaOperazioneInCorso()
            }
            .setCancelable(false)
            .show()
        dialog.rendiPulsantiLeggibili(this)
    }

    private fun confermaMisuraReale(valore: Double) {
        val punti = binding.superficieView.puntiCalibrazione
        if (punti.size != 2) {
            avviso("Punti non validi: scala non impostata")
            calibrazioneEntrambeInCorso = false
            scalaXTemporanea = null
            terminaOperazioneInCorso()
            return
        }

        val p1 = punti[0]
        val p2 = punti[1]
        val distanzaPixel = when (direzioneCalibrazioneCorrente) {
            DirezioneScala.ORIZZONTALE -> abs(p2.x - p1.x).toDouble()
            DirezioneScala.VERTICALE -> abs(p2.y - p1.y).toDouble()
            DirezioneScala.OBLIQUA -> GeometriaUtils.distanza(p1, p2)
        }
        if (distanzaPixel <= 0.0) {
            avviso("I due punti toccati non definiscono una misura valida: riprova")
            calibrazioneEntrambeInCorso = false
            scalaXTemporanea = null
            terminaOperazioneInCorso()
            return
        }
        val scalaCalcolata = valore / distanzaPixel

        if (calibrazioneEntrambeInCorso && !calibrazioneUltimoStep) {
            scalaXTemporanea = scalaCalcolata
            avviso("Scala orizzontale impostata. Ora misura una distanza verticale nota.", lungo = true)
            iniziaStepCalibrazione(DirezioneScala.VERTICALE, ultimoStep = true)
            return
        }

        if (calibrazioneEntrambeInCorso) {
            scalaX = scalaXTemporanea
            scalaY = scalaCalcolata
            calibrazioneEntrambeInCorso = false
            scalaXTemporanea = null
        } else {
            scalaX = scalaCalcolata
            scalaY = scalaCalcolata
        }

        aggiornaTestoScala()
        ricalcolaTuttiLocali()

        val continua = azioneDopoCalibrazione
        azioneDopoCalibrazione = null
        if (continua != null) {
            // Non chiudere lo schermo intero: l'operazione prosegue con il rilevamento automatico.
            modalita = Modalita.TRACCIAMENTO
            binding.superficieView.puntiCalibrazione.clear()
            binding.superficieView.puntiLocaleCorrente.clear()
            binding.superficieView.disegnaCorrenteChiuso = false
            binding.superficieView.listaAttiva = null
            binding.superficieView.modoTocco = SuperficieView.ModoTocco.NESSUNO
            binding.superficieView.invalidate()
            avviso("Scala impostata")
            continua.invoke()
        } else {
            terminaOperazioneInCorso()
            avviso("Scala impostata")
        }
    }

    private fun aggiornaTestoScala() {
        val sx = scalaX
        val sy = scalaY
        binding.tvStatoScala.text = if (sx != null && sy != null) {
            if (abs(sx - sy) < 1e-9) {
                "Scala impostata: ${String.format(Locale.ITALY, "%.4f", sx)} m/pixel"
            } else {
                "Scala impostata — orizzontale: ${String.format(Locale.ITALY, "%.4f", sx)} m/pixel, " +
                    "verticale: ${String.format(Locale.ITALY, "%.4f", sy)} m/pixel " +
                    "(planimetria corretta in modo non uniforme tra le due direzioni)"
            }
        } else {
            "Scala: non impostata"
        }
    }

    private fun avviaNuovoLocale() {
        if (binding.superficieView.bitmap == null) {
            avviso("Carica prima una planimetria")
            return
        }
        if (scalaX == null) {
            avviso("Imposta prima la scala con \"Imposta scala\"")
            return
        }
        if (modalita != Modalita.NESSUNA) {
            avviso("Finisci prima l'operazione in corso")
            return
        }

        modalita = Modalita.TRACCIAMENTO
        binding.superficieView.puntiLocaleCorrente.clear()
        binding.superficieView.disegnaCorrenteChiuso = false
        binding.superficieView.modoTocco = SuperficieView.ModoTocco.AGGIUNGI_E_TRASCINA
        binding.superficieView.listaAttiva = binding.superficieView.puntiLocaleCorrente
        binding.superficieView.limiteMassimoPunti = null
        binding.superficieView.invalidate()

        binding.tvIstruzioneTocco.visibility = View.VISIBLE
        binding.tvIstruzioneTocco.text =
            "Tocca in ordine i vertici del locale, anche se non è un rettangolo. Tieni il dito premuto su un vertice per spostarlo con la lente. Pizzica con due dita per ingrandire. Poi premi \"Chiudi locale\"."
        binding.btnSchermoIntero.visibility = View.VISIBLE
        abilitaControlli(false)
        apriSchermoIntero()
    }

    private fun annullaUltimoPunto() {
        // Prima la cronologia (annulla anche spostamenti), altrimenti si toglie l'ultimo punto.
        if (binding.superficieView.annullaUltimaModifica()) return
        val lista = binding.superficieView.listaAttiva
        if (lista == null || lista.isEmpty()) {
            avviso("Nessun punto da annullare")
            return
        }
        lista.removeAt(lista.size - 1)
        binding.superficieView.invalidate()
    }

    /** Nome attuale del locale in correzione (già inserito o proposta dell'AI), tenendo conto di un'eventuale rinomina. */
    private fun nomeLocaleInModifica(): String? =
        nomeModificato ?: indiceLocaleInModifica?.let { locali.getOrNull(it)?.nome } ?: proposizioneInVerifica?.nome

    /** "✏️ Nome": cambia il nome del locale che si sta correggendo, senza uscire dalla modifica dei vertici. */
    private fun rinominaLocaleInModifica() {
        if (modalita != Modalita.MODIFICA_LOCALE) return
        val densita = resources.displayMetrics.density
        val campo = EditText(this).apply {
            setText(nomeLocaleInModifica().orEmpty())
            setSelection(text.length)
            hint = "Nome locale (es. Cucina)"
            setPadding((16 * densita).toInt(), (12 * densita).toInt(), (16 * densita).toInt(), (12 * densita).toInt())
        }
        val dialog = AlertDialog.Builder(dialogSchermoIntero?.context ?: this, R.style.Theme_SchedaCliente_Dialog)
            .setTitle("Nome del locale")
            .setView(campo)
            .setPositiveButton("OK") { _, _ ->
                val nuovo = campo.text?.toString()?.trim().orEmpty()
                if (nuovo.isNotEmpty()) {
                    nomeModificato = nuovo
                    avviso("Nome: \"$nuovo\" (si applica con \"Conferma modifiche\")")
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
        dialog.rendiPulsantiLeggibili(this)
    }

    /**
     * "🗑 Elimina" nella finestra di modifica: elimina per intero il poligono che si sta correggendo
     * (dopo conferma). Per un locale già in elenco lo toglie dalla planimetria e dal totale; per una
     * proposta appena rilevata dall'AI equivale a scartarla.
     */
    private fun eliminaLocaleInModifica() {
        if (modalita != Modalita.MODIFICA_LOCALE) return
        val nome = nomeLocaleInModifica() ?: "questo locale"
        val proposta = proposizioneInVerifica
        val dialog = AlertDialog.Builder(dialogSchermoIntero?.context ?: this, R.style.Theme_SchedaCliente_Dialog)
            .setTitle(if (proposta != null) "Scartare il locale?" else "Eliminare il locale?")
            .setMessage(
                if (proposta != null) "\"$nome\" non verrà aggiunto alla planimetria."
                else "\"$nome\" verrà eliminato dalla planimetria e dal totale delle superfici. L'operazione non può essere annullata."
            )
            .setPositiveButton(if (proposta != null) "Scarta" else "Elimina") { _, _ ->
                if (modalita != Modalita.MODIFICA_LOCALE) return@setPositiveButton
                if (proposizioneInVerifica != null) { annullaModificaLocale(); return@setPositiveButton }
                val indice = indiceLocaleInModifica ?: return@setPositiveButton
                if (indice !in locali.indices) { terminaModificaLocale(); return@setPositiveButton }
                // Durante la modifica il poligono non è nella vista (vedi avviaModificaLocale): basta toglierlo dall'elenco.
                locali.removeAt(indice)
                terminaModificaLocale()
                disegnaElencoLocali()
                aggiornaTotale()
                avviso("Locale \"$nome\" eliminato")
            }
            .setNegativeButton("Annulla", null)
            .show()
        dialog.rendiPulsantiLeggibili(this)
    }

    /** "↶ Annulla" durante la correzione di un locale: annulla l'ultima aggiunta, rimozione o spostamento di un vertice. */
    private fun annullaUltimaModificaVertici() {
        if (!binding.superficieView.annullaUltimaModifica()) {
            avviso("Nessuna modifica da annullare")
        }
    }

    private fun aggiornaPulsantiAnnulla() {
        val puo = binding.superficieView.puoAnnullare
        binding.btnAnnullaVertice.isEnabled = puo
        bindingSchermoIntero?.btnAnnullaVerticeSchermoIntero?.isEnabled = puo
    }

    /**
     * Pulsante per guardare la planimetria: sempre disponibile dopo aver caricato un file o una
     * foto (anche con i locali già inseriti). Senza operazioni in corso apre la sola
     * visualizzazione a schermo intero; durante un'operazione apre lo schermo intero di lavoro.
     */
    private fun aggiornaPulsanteVisualizza() {
        val caricata = binding.superficieView.bitmap != null
        binding.btnSchermoIntero.visibility = if (caricata) View.VISIBLE else View.GONE
        binding.btnSchermoIntero.text = "🔍 Visualizza / ingrandisci a schermo intero"
    }

    /** Sola visualizzazione a schermo intero: pizzica per ingrandire, un dito per scorrere, niente punti. */
    private fun apriVisualizzazione() {
        if (binding.superficieView.bitmap == null) return
        binding.superficieView.avvisaTapNonConsentito = false
        binding.tvIstruzioneTocco.text =
            "Visualizzazione: pizzica con due dita per ingrandire, trascina con un dito per spostarti. Tieni il dito fermo 2 secondi su un locale per correggerlo. Il pulsante in alto riadatta la vista, la X chiude."
        apriSchermoIntero()
        dialogSchermoIntero?.setOnDismissListener {
            ripristinaSuperficieView()
            binding.superficieView.avvisaTapNonConsentito = true
            binding.tvIstruzioneTocco.visibility = View.GONE
        }
    }

    private fun chiudiLocaleCorrente() {
        if (modalita != Modalita.TRACCIAMENTO) {
            avviso("Premi prima \"Nuovo locale\" e tocca i suoi vertici")
            return
        }
        val punti = binding.superficieView.puntiLocaleCorrente
        if (punti.size < 3) {
            avviso("Servono almeno 3 vertici per chiudere un locale")
            return
        }
        chiediNomeLocale(punti.map { PointF(it.x, it.y) })
    }

    private fun chiediNomeLocale(verticiPx: List<PointF>) {
        val campo = EditText(this).apply {
            hint = "Nome locale (es. Cucina)"
            setPadding(
                (16 * resources.displayMetrics.density).toInt(), (12 * resources.displayMetrics.density).toInt(),
                (16 * resources.displayMetrics.density).toInt(), (12 * resources.displayMetrics.density).toInt()
            )
        }
        val dialog = AlertDialog.Builder(this, R.style.Theme_SchedaCliente_Dialog)
            .setTitle("Nome del locale")
            .setView(campo)
            .setPositiveButton("Salva locale") { _, _ ->
                val nome = campo.text?.toString()?.trim()?.ifBlank { "Locale ${locali.size + 1}" } ?: "Locale ${locali.size + 1}"
                confermaNuovoLocale(nome, verticiPx)
            }
            .setNegativeButton("Annulla", null)
            .setCancelable(false)
            .show()
        dialog.rendiPulsantiLeggibili(this)
    }

    private fun confermaNuovoLocale(nome: String, verticiPx: List<PointF>) {
        val (area, lati) = calcolaAreaELati(verticiPx)
        val localeTracciato = LocaleTracciato(nome, verticiPx, area, lati)
        locali.add(localeTracciato)
        binding.superficieView.locali.add(localeTracciato)

        terminaOperazioneInCorso()
        disegnaElencoLocali()
        aggiornaTotale()
    }

    // ---------------------------------------------------------------------
    // Modifica di un vertice su un locale già tracciato
    // ---------------------------------------------------------------------

    /**
     * Chiamata quando, durante la correzione di un locale già confermato (dalla lista), il dito
     * resta premuto più a lungo su un ALTRO locale già confermato disegnato sulla planimetria:
     * applica le eventuali modifiche già fatte al locale corrente (senza doverle confermare a
     * parte) e passa direttamente a correggere quello appena toccato, senza chiudere e riaprire
     * la schermata di correzione. Non interviene durante la verifica di una proposta appena
     * rilevata dall'AI (non ancora in elenco, "gli altri locali" potrebbero essere solo altre
     * proposte ancora da confermare).
     */
    private fun gestisciCambioLocaleInModifica(indiceVista: Int) {
        if (modalita != Modalita.MODIFICA_LOCALE || proposizioneInVerifica != null) return
        val localeVista = binding.superficieView.locali.getOrNull(indiceVista) ?: return
        val nuovoIndice = locali.indexOfFirst { it === localeVista }
        val indiceAttuale = indiceLocaleInModifica
        if (nuovoIndice < 0 || nuovoIndice == indiceAttuale) return

        if (indiceAttuale != null) {
            val verticiCorrente = binding.superficieView.puntiLocaleCorrente.map { PointF(it.x, it.y) }
            if (verticiCorrente.size >= 3) {
                val (area, lati) = calcolaAreaELati(verticiCorrente)
                val aggiornato = locali[indiceAttuale].copy(
                    nome = nomeModificato ?: locali[indiceAttuale].nome,
                    verticiPx = verticiCorrente, areaM2 = area, latiM = lati
                )
                locali[indiceAttuale] = aggiornato
                binding.superficieView.locali.add(aggiornato)
            } else {
                binding.superficieView.locali.add(locali[indiceAttuale])
            }
            aggiornaTotale()
        }

        avviso("Passaggio a \"${locali[nuovoIndice].nome}\"")
        avviaModificaLocale(nuovoIndice, forzato = true)
    }

    /** @param forzato true per passare direttamente a correggere [indice] anche se si è già in MODIFICA_LOCALE (cambio locale da dito tenuto premuto su un altro locale), saltando il controllo che normalmente impedisce di avviare una nuova operazione mentre una è in corso. */
    private fun avviaModificaLocale(indice: Int, forzato: Boolean = false) {
        if (!forzato && modalita != Modalita.NESSUNA) {
            avviso("Finisci prima l'operazione in corso")
            return
        }
        indiceLocaleInModifica = indice
        proposizioneInVerifica = null
        modalita = Modalita.MODIFICA_LOCALE
        nomeModificato = null

        val locale = locali[indice]
        val posizioneNellaVista = binding.superficieView.locali.indexOfFirst { it === locale }
        if (posizioneNellaVista >= 0) binding.superficieView.locali.removeAt(posizioneNellaVista)

        binding.superficieView.puntiLocaleCorrente.clear()
        binding.superficieView.puntiLocaleCorrente.addAll(locale.verticiPx.map { PointF(it.x, it.y) })
        binding.superficieView.disegnaCorrenteChiuso = true
        binding.superficieView.modoTocco = SuperficieView.ModoTocco.SOLO_TRASCINA
        binding.superficieView.listaAttiva = binding.superficieView.puntiLocaleCorrente
        binding.superficieView.limiteMassimoPunti = null
        binding.superficieView.invalidate()

        binding.tvIstruzioneTocco.visibility = View.VISIBLE
        binding.tvIstruzioneTocco.text = "Tieni premuto un vertice e spostalo per correggerlo, poi premi \"Conferma modifiche\". Pizzica con due dita per ingrandire, oppure tocca e trascina un punto vuoto per scorrere la planimetria. Tieni premuto un altro locale per passare a correggerlo."
        binding.btnSchermoIntero.visibility = View.VISIBLE
        binding.containerControlliNormali.visibility = View.GONE
        binding.containerControlliModifica.visibility = View.VISIBLE
        binding.containerStrumentiModifica.visibility = View.VISIBLE
        binding.superficieView.modalitaInserimentoBordo = false
        binding.superficieView.modalitaRimozioneVertice = false
        disegnaElencoLocali()
        apriSchermoIntero()
    }

    private fun confermaModificaLocale() {
        val nuoviVertici = binding.superficieView.puntiLocaleCorrente.map { PointF(it.x, it.y) }
        if (nuoviVertici.size < 3) {
            avviso("Servono almeno 3 vertici")
            return
        }

        val proposta = proposizioneInVerifica
        if (proposta != null) {
            if (rilevamentoAutomaticoInCorso) {
                proposizioneInVerifica = null
                binding.containerControlliModifica.visibility = View.GONE
                binding.containerStrumentiModifica.visibility = View.GONE
                confermaLocaleRilevato(nomeModificato?.let { proposta.copy(nome = it) } ?: proposta, nuoviVertici)
            } else {
                chiediNomeLocaleProposto(proposta, nuoviVertici)
            }
            return
        }

        val indice = indiceLocaleInModifica ?: return
        val (area, lati) = calcolaAreaELati(nuoviVertici)
        val aggiornato = locali[indice].copy(
            nome = nomeModificato ?: locali[indice].nome,
            verticiPx = nuoviVertici, areaM2 = area, latiM = lati
        )
        locali[indice] = aggiornato
        binding.superficieView.locali.add(aggiornato)

        terminaModificaLocale()
        disegnaElencoLocali()
        aggiornaTotale()
    }

    private fun annullaModificaLocale() {
        val proposta = proposizioneInVerifica
        if (proposta != null) {
            proposizioneInVerifica = null
            if (rilevamentoAutomaticoInCorso) {
                modalita = Modalita.TRACCIAMENTO
                binding.containerControlliModifica.visibility = View.GONE
                binding.containerStrumentiModifica.visibility = View.GONE
                avviso("Locale \"${proposta.nome}\" scartato")
                avviaAnimazioneENextLocale()
            } else {
                avviso("Proposta scartata")
                terminaModificaLocale()
            }
            return
        }
        val indice = indiceLocaleInModifica ?: return
        binding.superficieView.locali.add(locali[indice])
        terminaModificaLocale()
        disegnaElencoLocali()
    }

    private fun terminaModificaLocale() {
        indiceLocaleInModifica = null
        proposizioneInVerifica = null
        modalita = Modalita.NESSUNA
        binding.superficieView.puntiLocaleCorrente.clear()
        binding.superficieView.disegnaCorrenteChiuso = false
        binding.superficieView.listaAttiva = null
        binding.superficieView.modoTocco = SuperficieView.ModoTocco.NESSUNO
        binding.superficieView.modalitaInserimentoBordo = false
        binding.superficieView.modalitaRimozioneVertice = false
        binding.superficieView.azzeraZoom()
        binding.superficieView.invalidate()
        binding.tvIstruzioneTocco.visibility = View.GONE
        aggiornaPulsanteVisualizza()
        binding.containerControlliNormali.visibility = View.VISIBLE
        binding.containerControlliModifica.visibility = View.GONE
        binding.containerStrumentiModifica.visibility = View.GONE
        abilitaControlli(true)
        disegnaProposteAi()
        chiudiSchermoIntero()
    }

    private fun terminaOperazioneInCorso() {
        modalita = Modalita.NESSUNA
        proposizioneInVerifica = null
        rilevamentoAutomaticoInCorso = false
        faseLocaliInCorso = false
        // Invalida qualunque risposta AI o calcolo del rilevamento ancora in arrivo (vedi sessioneRilevamento).
        sessioneRilevamento++
        codaRilevamentoAi.clear()
        azioneDopoCalibrazione = null
        binding.superficieView.puntiCalibrazione.clear()
        binding.superficieView.puntiLocaleCorrente.clear()
        binding.superficieView.disegnaCorrenteChiuso = false
        binding.superficieView.listaAttiva = null
        binding.superficieView.modoTocco = SuperficieView.ModoTocco.NESSUNO
        binding.superficieView.modalitaInserimentoBordo = false
        binding.superficieView.modalitaRimozioneVertice = false
        binding.superficieView.azzeraZoom()
        binding.superficieView.invalidate()
        binding.tvIstruzioneTocco.visibility = View.GONE
        aggiornaPulsanteVisualizza()
        binding.containerControlliNormali.visibility = View.VISIBLE
        binding.containerControlliModifica.visibility = View.GONE
        binding.containerStrumentiModifica.visibility = View.GONE
        abilitaControlli(true)
        disegnaProposteAi()
        chiudiSchermoIntero()
    }

    private fun attivaModalitaInserimentoVertice() {
        if (modalita != Modalita.MODIFICA_LOCALE) return
        val superficie = binding.superficieView
        val nuovoStato = !superficie.modalitaInserimentoBordo
        superficie.modalitaInserimentoBordo = nuovoStato
        superficie.modalitaRimozioneVertice = false
        binding.tvIstruzioneTocco.text = if (nuovoStato) {
            "Tocca un punto del contorno per aggiungere lì un nuovo vertice. Premi di nuovo \"+ Vertice\" per tornare a correggere normalmente."
        } else {
            "Tieni premuto un vertice e spostalo per correggerlo, poi premi \"Conferma modifiche\". Pizzica con due dita per ingrandire."
        }
        aggiornaStatoPulsantiStrumentiModifica()
        aggiornaBarraSchermoIntero()
    }

    private fun attivaModalitaRimozioneVertice() {
        if (modalita != Modalita.MODIFICA_LOCALE) return
        val superficie = binding.superficieView
        val nuovoStato = !superficie.modalitaRimozioneVertice
        superficie.modalitaRimozioneVertice = nuovoStato
        superficie.modalitaInserimentoBordo = false
        binding.tvIstruzioneTocco.text = if (nuovoStato) {
            "Tocca un vertice per rimuoverlo. Premi di nuovo \"− Vertice\" per tornare a correggere normalmente."
        } else {
            "Tieni premuto un vertice e spostalo per correggerlo, poi premi \"Conferma modifiche\". Pizzica con due dita per ingrandire."
        }
        aggiornaStatoPulsantiStrumentiModifica()
        aggiornaBarraSchermoIntero()
    }

    private fun aggiornaStatoPulsantiStrumentiModifica() {
        val superficie = binding.superficieView
        impostaStatoToggle(binding.btnAggiungiVertice, superficie.modalitaInserimentoBordo)
        impostaStatoToggle(binding.btnRimuoviVertice, superficie.modalitaRimozioneVertice)
        bindingSchermoIntero?.let {
            impostaStatoToggle(it.btnAggiungiVerticeSchermoIntero, superficie.modalitaInserimentoBordo)
            impostaStatoToggle(it.btnRimuoviVerticeSchermoIntero, superficie.modalitaRimozioneVertice)
        }
    }

    private fun impostaStatoToggle(pulsante: com.google.android.material.button.MaterialButton, attivo: Boolean) {
        val colore = ContextCompat.getColor(this, if (attivo) android.R.color.white else R.color.text_secondary)
        pulsante.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (attivo) ContextCompat.getColor(this, R.color.primary) else android.graphics.Color.TRANSPARENT
        )
        pulsante.setTextColor(colore)
        pulsante.iconTint = android.content.res.ColorStateList.valueOf(colore)
    }

    private fun abilitaControlli(abilitati: Boolean) {
        binding.btnImpostaScala.isEnabled = abilitati
        binding.btnNuovoLocale.isEnabled = abilitati
    }

    // ---------------------------------------------------------------------
    // Vista a schermo intero (con zoom) per inserire la scala o i vertici
    // ---------------------------------------------------------------------

    private fun apriSchermoIntero() {
        if (dialogSchermoIntero != null) {
            aggiornaBarraSchermoIntero()
            return
        }
        val superficie = binding.superficieView
        genitoreOriginaleSuperficie = superficie.parent as? ViewGroup
        indiceOriginaleSuperficie = genitoreOriginaleSuperficie?.indexOfChild(superficie) ?: -1
        layoutParamsOriginaliSuperficie = superficie.layoutParams
        genitoreOriginaleSuperficie?.removeView(superficie)

        val dialogBinding = DialogTracciamentoSchermoInteroBinding.inflate(layoutInflater)
        bindingSchermoIntero = dialogBinding
        dialogBinding.contenitoreSuperficie.addView(
            superficie,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )

        dialogBinding.btnChiudiSchermoIntero.setOnClickListener { chiudiSchermoIntero() }
        dialogBinding.btnAdattaZoom.setOnClickListener { superficie.azzeraZoom() }
        dialogBinding.btnAnnullaUltimoPuntoSchermoIntero.setOnClickListener { annullaUltimoPunto() }
        dialogBinding.btnChiudiLocaleSchermoIntero.setOnClickListener { chiudiLocaleCorrente() }
        dialogBinding.btnConfermaModificaSchermoIntero.setOnClickListener { confermaModificaLocale() }
        dialogBinding.btnAnnullaModificaSchermoIntero.setOnClickListener { annullaModificaLocale() }
        dialogBinding.btnAggiungiVerticeSchermoIntero.setOnClickListener { attivaModalitaInserimentoVertice() }
        dialogBinding.btnRimuoviVerticeSchermoIntero.setOnClickListener { attivaModalitaRimozioneVertice() }
        dialogBinding.btnAnnullaVerticeSchermoIntero.setOnClickListener { annullaUltimaModificaVertici() }
        dialogBinding.btnRinominaLocaleSchermoIntero.setOnClickListener { rinominaLocaleInModifica() }
        dialogBinding.btnEliminaLocaleSchermoIntero.setOnClickListener { eliminaLocaleInModifica() }
        dialogBinding.btnSaltaLocaleSchermoIntero.setOnClickListener { saltaLocaleInElaborazione() }
        dialogBinding.btnInterrompiRilevamentoSchermoIntero.setOnClickListener { confermaAnnullaRilevamentoAutomatico() }

        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(dialogBinding.root)
        dialog.setCancelable(false)
        dialog.setOnDismissListener { ripristinaSuperficieView() }
        dialogSchermoIntero = dialog
        aggiornaBarraSchermoIntero()
        aggiornaPulsantiAnnulla()
        dialog.show()
    }

    /** Aggiorna testo istruzioni e pulsanti visibili nella barra dello schermo intero in base alla modalità corrente. */
    /**
     * Avviso breve in alto, subito sotto la barra verde (o sotto la barra dello schermo intero
     * quando è aperto). CORREZIONE v3.77: prima erano Toast, che Android mostra sempre in basso
     * (da Android 11 non si possono spostare) e che coprivano i pulsanti di modifica dei vertici.
     * Un tocco sull'avviso lo chiude subito.
     */
    private fun avviso(testo: CharSequence, lungo: Boolean = false) {
        val tvSchermoIntero = if (dialogSchermoIntero?.isShowing == true) bindingSchermoIntero?.tvAvvisoSchermoIntero else null
        val tv = tvSchermoIntero ?: binding.tvAvviso
        // Un solo avviso alla volta: quello dell'altra vista si spegne.
        (if (tv === binding.tvAvviso) bindingSchermoIntero?.tvAvvisoSchermoIntero else binding.tvAvviso)?.let { altro ->
            nascondiAvviso[altro]?.let { altro.removeCallbacks(it) }
            altro.animate().cancel(); altro.visibility = View.GONE
        }
        tv.text = testo
        tv.animate().cancel()
        if (tv.visibility != View.VISIBLE) { tv.alpha = 0f; tv.visibility = View.VISIBLE }
        tv.animate().alpha(1f).setDuration(150).start()
        nascondiAvviso[tv]?.let { tv.removeCallbacks(it) }
        val nascondi = Runnable {
            tv.animate().alpha(0f).setDuration(250).withEndAction { tv.visibility = View.GONE }.start()
        }
        nascondiAvviso[tv] = nascondi
        tv.setOnClickListener { tv.removeCallbacks(nascondi); nascondi.run() }
        // I tempi dei vecchi Toast (breve ~2 s, lungo ~3,5 s), un po' allungati: in alto si leggono meglio.
        tv.postDelayed(nascondi, if (lungo) 4000L else 2500L)
    }
    private val nascondiAvviso = HashMap<View, Runnable>()

    private fun aggiornaBarraSchermoIntero() {
        val dialogBinding = bindingSchermoIntero ?: return
        dialogBinding.tvIstruzioneSchermoIntero.text = binding.tvIstruzioneTocco.text
        val inModifica = modalita == Modalita.MODIFICA_LOCALE
        // Durante il rilevamento automatico (animazione o correzione di una proposta AI) i pulsanti
        // del tracciamento manuale ("Chiudi locale", "Annulla ultimo punto") non si applicano mai:
        // nasconderli evita di creare un locale "fantasma" toccandoli per sbaglio in quella fase.
        val inTracciamentoManuale = modalita == Modalita.TRACCIAMENTO && !rilevamentoAutomaticoInCorso
        // Solo mentre si inseriscono punti (scala o nuovo locale): non in visualizzazione né in correzione.
        dialogBinding.btnAnnullaUltimoPuntoSchermoIntero.visibility =
            if ((modalita == Modalita.TRACCIAMENTO || modalita == Modalita.CALIBRAZIONE) && !rilevamentoAutomaticoInCorso) View.VISIBLE else View.GONE
        dialogBinding.btnChiudiLocaleSchermoIntero.visibility = if (inTracciamentoManuale) View.VISIBLE else View.GONE
        dialogBinding.btnConfermaModificaSchermoIntero.visibility = if (inModifica) View.VISIBLE else View.GONE
        dialogBinding.btnAnnullaModificaSchermoIntero.visibility = if (inModifica) View.VISIBLE else View.GONE
        dialogBinding.containerStrumentiModificaSchermoIntero.visibility = if (inModifica) View.VISIBLE else View.GONE
        // "Salta locale" permette di scartare subito il locale che l'AI sta elaborando/animando
        // (prima ancora che venga chiesto se correggerlo), per chi si accorge che non va rilevato.
        dialogBinding.btnSaltaLocaleSchermoIntero.visibility =
            if (rilevamentoAutomaticoInCorso && faseLocaliInCorso && !inModifica) View.VISIBLE else View.GONE
        // A differenza di "Salta locale" (che scarta solo il locale in esame in quel momento),
        // questo pulsante interrompe subito TUTTO il rilevamento automatico, qualunque sia il
        // locale in corso o quanti ne restino ancora in coda: prima della sua introduzione
        // l'unico modo per fermare un rilevamento partito per errore era premere "Salta locale"
        // una volta per ciascun locale rimasto, uno per uno, fino alla fine della planimetria.
        // Resta visibile anche durante la verifica di una proposta (MODIFICA_LOCALE), a
        // differenza di "Salta locale" che lì è nascosto perché sostituito da "Annulla modifiche".
        dialogBinding.btnInterrompiRilevamentoSchermoIntero.visibility =
            if (rilevamentoAutomaticoInCorso) View.VISIBLE else View.GONE
        aggiornaStatoPulsantiStrumentiModifica()
    }

    private fun chiudiSchermoIntero() {
        dialogSchermoIntero?.dismiss()
    }

    private fun ripristinaSuperficieView() {
        val superficie = binding.superficieView
        bindingSchermoIntero?.contenitoreSuperficie?.removeView(superficie)
        val genitore = genitoreOriginaleSuperficie
        if (genitore != null) {
            if (indiceOriginaleSuperficie in 0..genitore.childCount) {
                genitore.addView(superficie, indiceOriginaleSuperficie, layoutParamsOriginaliSuperficie)
            } else {
                genitore.addView(superficie, layoutParamsOriginaliSuperficie)
            }
        }
        dialogSchermoIntero = null
        bindingSchermoIntero = null
        // Tornando alla sezione dopo aver corretto, zoomato o rilevato: vista intera della planimetria.
        superficie.post { superficie.azzeraZoom() }
    }

    // ---------------------------------------------------------------------
    // Calcolo area/lati (tiene conto di una scala eventualmente diversa tra
    // orizzontale e verticale) ed elenco dei locali
    // ---------------------------------------------------------------------

    private fun calcolaAreaELati(verticiPx: List<PointF>): Pair<Double, List<Double>> {
        val sx = scalaX ?: return 0.0 to emptyList()
        val sy = scalaY ?: sx
        val verticiMetri = verticiPx.map { PointF((it.x * sx).toFloat(), (it.y * sy).toFloat()) }
        val area = GeometriaUtils.areaPoligono(verticiMetri)
        val lati = GeometriaUtils.latiPoligono(verticiMetri)
        return area to lati
    }

    private fun ricalcolaTuttiLocali() {
        if (locali.isEmpty()) return
        for (i in locali.indices) {
            val originale = locali[i]
            val (area, lati) = calcolaAreaELati(originale.verticiPx)
            val aggiornato = originale.copy(areaM2 = area, latiM = lati)
            locali[i] = aggiornato
            val posizioneNellaVista = binding.superficieView.locali.indexOfFirst { it === originale }
            if (posizioneNellaVista >= 0) binding.superficieView.locali[posizioneNellaVista] = aggiornato
        }
        binding.superficieView.invalidate()
        disegnaElencoLocali()
        aggiornaTotale()
    }

    /** Ricostruisce l'elenco dei locali tracciati come righe testo + pulsanti modifica/elimina. */
    private fun disegnaElencoLocali() {
        binding.containerLocaliTracciati.removeAllViews()

        if (locali.isEmpty()) {
            val vuoto = TextView(this).apply {
                text = "Nessun locale tracciato ancora."
                setTextColor(ContextCompat.getColor(this@PlanimetriaActivity, R.color.text_secondary))
                textSize = 13f
            }
            binding.containerLocaliTracciati.addView(vuoto)
            return
        }

        val padding8 = (8 * resources.displayMetrics.density).toInt()
        val inModifica = modalita == Modalita.MODIFICA_LOCALE

        locali.forEachIndexed { indice, locale ->
            val riga = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, padding8 / 2, 0, padding8 / 2)
                alpha = if (inModifica && indice != indiceLocaleInModifica) 0.4f else 1f
            }

            val latiTesto = locale.latiM.joinToString(" / ") { String.format(Locale.ITALY, "%.2f m", it) }
            val testo = TextView(this).apply {
                text = "${locale.nome}: ${String.format(Locale.ITALY, "%,.2f", locale.areaM2)} m²\nlati: $latiTesto"
                setTextColor(ContextCompat.getColor(this@PlanimetriaActivity, R.color.text_primary))
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val btnModifica = ImageButton(this).apply {
                setImageResource(android.R.drawable.ic_menu_edit)
                background = null
                isEnabled = !inModifica
                contentDescription = "Modifica vertici di ${locale.nome}"
                layoutParams = LinearLayout.LayoutParams(
                    (36 * resources.displayMetrics.density).toInt(), (36 * resources.displayMetrics.density).toInt()
                )
                setOnClickListener { avviaModificaLocale(indice) }
            }

            val btnElimina = ImageButton(this).apply {
                setImageResource(android.R.drawable.ic_menu_delete)
                background = null
                isEnabled = !inModifica
                contentDescription = "Elimina ${locale.nome}"
                layoutParams = LinearLayout.LayoutParams(
                    (36 * resources.displayMetrics.density).toInt(), (36 * resources.displayMetrics.density).toInt()
                )
                setOnClickListener {
                    locali.removeAt(indice)
                    val posizioneNellaVista = binding.superficieView.locali.indexOfFirst { it === locale }
                    if (posizioneNellaVista >= 0) binding.superficieView.locali.removeAt(posizioneNellaVista)
                    binding.superficieView.invalidate()
                    disegnaElencoLocali()
                    aggiornaTotale()
                }
            }

            riga.addView(testo)
            riga.addView(btnModifica)
            riga.addView(btnElimina)
            binding.containerLocaliTracciati.addView(riga)
        }
    }

    /** Metodo di rilevamento: 0 = da spigolo a spigolo, 1 = raffinato, 2 = lato per lato. */
    private fun impostaMetodoRilevamento(metodo: Int) {
        InterpretePlanimetria.soloSpigoli = metodo == 0
        InterpretePlanimetria.latoPerLato = metodo == 2
        InterpretePlanimetria.chiudiRientranze = metodo == 3
    }

    /** Cancella tutti i vani rilevati (la planimetria resta caricata) e chiede con quale metodo rifarli. Raggiungibile sempre dalla pagina generale. */
    private fun chiediCancellaTuttiERifai() {
        if (binding.superficieView.bitmap == null) { avviso("Carica prima una planimetria"); return }
        if (modalita != Modalita.NESSUNA) { avviso("Finisci prima l'operazione in corso"); return }
        val voci = arrayOf(
            "Da spigolo a spigolo (ignora i particolari)",
            "Raffinato (tiene conto di ogni particolare)",
            "Lato per lato (l'AI percorre ogni parete per intero, poi ricava gli spigoli)",
            "Raffinato + chiude le piccole rientranze (pilastri, spalle, cellette, nicchie restano dentro il vano)",
            "Solo cancella, non rifare"
        )
        val titolo = if (locali.isEmpty()) "Rifare i poligoni: quale metodo?" else "Cancellare tutti i ${locali.size} vani e rifarli: quale metodo?"
        val dialog = AlertDialog.Builder(this, R.style.Theme_SchedaCliente_Dialog)
            .setTitle(titolo)
            .setItems(voci) { _, quale ->
                cancellaTuttiILocali()
                if (quale == 4) { avviso("Vani cancellati"); return@setItems }
                impostaMetodoRilevamento(quale)
                avviaRilevamentoAutomaticoCompleto()
            }
            .setNegativeButton("Annulla", null)
            .show()
        dialog.rendiPulsantiLeggibili(this)
    }

    private fun cancellaTuttiILocali() {
        locali.clear()
        binding.superficieView.locali.clear()
        codaRilevamentoSospesa = null
        binding.superficieView.puntiLocaleCorrente.clear()
        binding.superficieView.disegnaCorrenteChiuso = false
        binding.superficieView.invalidate()
        disegnaElencoLocali()
        aggiornaTotale()
    }

    private fun aggiornaTotale() {
        if (locali.isEmpty()) {
            binding.tvSuperficieTotaleTracciata.text = ""
            binding.btnEsportaPdf.isEnabled = false
            binding.btnEsportaDxf.isEnabled = false
            binding.btnPeriziaUnita.isEnabled = false
            return
        }
        val totale = locali.sumOf { it.areaM2 }
        binding.tvSuperficieTotaleTracciata.text = "Superficie totale tracciata: ${String.format(Locale.ITALY, "%,.2f", totale)} m²"
        binding.btnEsportaPdf.isEnabled = true
        binding.btnEsportaDxf.isEnabled = true
        binding.btnPeriziaUnita.isEnabled = true
    }

    /** Passa alla perizia dell'unità con le superfici dei locali tracciati (servono la scala e almeno un locale). */
    private fun apriPeriziaUnita() {
        if (locali.isEmpty()) return
        if (scalaX == null || scalaY == null || locali.all { it.areaM2 <= 0.0 }) {
            avviso("Imposta prima la scala: senza scala le superfici dei locali non sono in metri", lungo = true)
            return
        }
        startActivity(
            Intent(this, PeriziaUnitaActivity::class.java)
                .putExtra(PeriziaUnitaActivity.EXTRA_LOCALI, PeriziaUnitaActivity.localiInJson(locali))
        )
    }

    // ---------------------------------------------------------------------
    // Esportazione DXF (CAD): locali come polilinee chiuse con nome e superficie
    // ---------------------------------------------------------------------

    private fun avviaEsportazioneDxf() {
        if (locali.isEmpty()) {
            avviso("Traccia almeno un locale prima di esportare")
            return
        }
        val sx = scalaX
        val sy = scalaY
        if (sx == null || sy == null) {
            avviso("Imposta prima la scala della planimetria")
            return
        }
        val nomeFile = "Planimetria_locali_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.ITALY).format(Date()) + ".dxf"
        esportaDxfLauncher.launch(nomeFile)
    }

    private fun scriviDxfSuUri(uri: Uri) {
        val sx = scalaX
        val sy = scalaY
        if (sx == null || sy == null) {
            avviso("Imposta prima la scala della planimetria")
            return
        }
        try {
            val contenuto = EsportatoreDxf.genera(locali, sx, sy)
            // Un DXF "R12 ASCII" va scritto in Windows-1252 (ANSI), non UTF-8: vedi la nota
            // nella documentazione di EsportatoreDxf.genera() sui simboli illeggibili che
            // comparivano in AutoCAD/LibreCAD con la codifica sbagliata.
            contentResolver.openOutputStream(uri)?.use { out ->
                out.write(contenuto.toByteArray(java.nio.charset.Charset.forName("Windows-1252")))
            }
            avviso("DXF salvato correttamente")
            offriAperturaFile(uri, "application/dxf", "il DXF")
        } catch (e: Exception) {
            avviso("Non sono riuscito a salvare il DXF: ${e.message}")
        }
    }

    // ---------------------------------------------------------------------
    // Esportazione PDF con dimostrazione delle superfici
    // ---------------------------------------------------------------------

    private fun avviaEsportazionePdf() {
        if (locali.isEmpty()) {
            avviso("Traccia almeno un locale prima di esportare")
            return
        }
        val nomeFile = "Planimetria_aree_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.ITALY).format(Date()) + ".pdf"
        esportaPdfLauncher.launch(nomeFile)
    }

    private fun scriviPdfSuUri(uri: Uri) {
        var documento: PdfDocument? = null
        try {
            documento = generaPdf()
            contentResolver.openOutputStream(uri)?.use { out -> documento.writeTo(out) }
            avviso("PDF salvato correttamente")
            offriAperturaFile(uri, "application/pdf", "il PDF")
        } catch (e: Exception) {
            avviso("Non sono riuscito a salvare il PDF: ${e.message}")
        } finally {
            documento?.close()
        }
    }

    /**
     * Dopo un'esportazione riuscita, chiede se aprire subito il file appena salvato (con
     * qualunque app del telefono sappia gestire quel tipo di file), invece di lasciare l'utente
     * a doverlo cercare manualmente. Se nessuna app sul telefono sa aprire quel tipo di file
     * (frequente per il DXF, meno per il PDF), lo segnala con un messaggio invece di fallire
     * silenziosamente.
     */
    private fun offriAperturaFile(uri: Uri, tipoMime: String, nomeVisualizzato: String) {
        val dialog = AlertDialog.Builder(this, R.style.Theme_SchedaCliente_Dialog)
            .setTitle("Aprire il file?")
            .setMessage("Vuoi aprire subito $nomeVisualizzato appena salvato?")
            .setPositiveButton("Apri") { _, _ ->
                try {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, tipoMime)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(Intent.createChooser(intent, "Apri con"))
                } catch (e: android.content.ActivityNotFoundException) {
                    avviso("Nessuna app sul telefono sa aprire questo tipo di file", lungo = true)
                }
            }
            .setNegativeButton("Chiudi", null)
            .show()
        dialog.rendiPulsantiLeggibili(this)
    }

    /**
     * Spezza [testo] su più righe, ciascuna non più larga di [larghezzaMax] punti (misurata
     * davvero con [paint], non stimata a occhio), disegnandole a partire da [x], [yIniziale].
     * CORREZIONE (ottobre 2026): prima di questa funzione il PDF disegnava ogni riga con una
     * singola drawText, senza alcun controllo sulla sua larghezza — una riga più lunga della
     * larghezza utile della pagina (il nome di un locale lungo, o l'elenco dei lati di un
     * locale con molti vertici) non andava a capo da sola: proseguiva comunque oltre il bordo
     * destro del foglio e lì veniva tagliata, perché la pagina PDF finisce fisicamente a quel
     * punto (tutto ciò che è disegnato oltre il bordo della pagina non viene salvato nel file).
     * Restituisce la coordinata y subito sotto l'ultima riga disegnata, pronta per il testo
     * successivo.
     */
    private fun disegnaTestoAvvolto(canvas: Canvas, testo: String, x: Float, yIniziale: Float, paint: Paint, larghezzaMax: Float): Float {
        var y = yIniziale
        val interlinea = paint.textSize + 3f
        var resto = testo
        while (resto.isNotEmpty()) {
            val contate = paint.breakText(resto, true, larghezzaMax, null)
            var fine = contate
            if (fine < resto.length) {
                // Evita di spezzare a metà di una parola: torna indietro fino allo spazio più
                // vicino, se ce n'è uno abbastanza vicino da non lasciare la riga troppo corta.
                val ultimoSpazio = resto.lastIndexOf(' ', fine - 1)
                if (ultimoSpazio > 0 && ultimoSpazio > fine - 25) fine = ultimoSpazio
            }
            if (fine <= 0) fine = contate.coerceAtLeast(1)
            canvas.drawText(resto.substring(0, fine).trimEnd(), x, y, paint)
            resto = resto.substring(fine).trimStart()
            y += interlinea
        }
        return y
    }

    private fun generaPdf(): PdfDocument {
        val documento = PdfDocument()
        val larghezzaPagina = 595
        val altezzaPagina = 842
        val margine = 40f
        val larghezzaUtile = larghezzaPagina - margine * 2

        val paintTitolo = Paint().apply { color = Color.BLACK; textSize = 16f; isFakeBoldText = true }
        val paintTesto = Paint().apply { color = Color.BLACK; textSize = 11f }
        val paintEtichetta = Paint().apply { color = Color.BLACK; textSize = 12f; isFakeBoldText = true }
        val paintAvviso = Paint().apply { color = Color.DKGRAY; textSize = 10f }

        val infoPagina1 = PdfDocument.PageInfo.Builder(larghezzaPagina, altezzaPagina, 1).create()
        val pagina1 = documento.startPage(infoPagina1)
        val canvas1 = pagina1.canvas

        var y = margine
        canvas1.drawText("Aree dei locali - rilievo con planimetria", margine, y, paintTitolo)
        y += 20f
        canvas1.drawText("Scheda Cliente - generato il ${SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALY).format(Date())}", margine, y, paintTesto)
        y += 24f

        val vistaBmp = catturaVistaComeBitmap()
        if (vistaBmp != null) {
            val fattoreScala = larghezzaUtile / vistaBmp.width
            var altezzaScalata = vistaBmp.height * fattoreScala
            // CORREZIONE (ottobre 2026): l'immagine catturata (planimetria + locali tracciati)
            // veniva incollata nella pagina alla sua altezza scalata "naturale", senza mai
            // controllare se superasse lo spazio rimasto in fondo al foglio: con una
            // planimetria molto alta rispetto alla sua larghezza, l'immagine finiva anch'essa
            // tagliata al bordo inferiore della pagina. Ora, se non ci sta per intero, viene
            // rimpicciolita ulteriormente (mantenendo le proporzioni) finché non entra tutta
            // nello spazio disponibile sulla prima pagina.
            val altezzaDisponibile = altezzaPagina - margine - y
            var larghezzaFinale = larghezzaUtile
            if (altezzaScalata > altezzaDisponibile) {
                val fattoreRiduzione = altezzaDisponibile / altezzaScalata
                larghezzaFinale *= fattoreRiduzione
                altezzaScalata = altezzaDisponibile
            }
            val destRect = RectF(margine, y, margine + larghezzaFinale, y + altezzaScalata)
            canvas1.drawBitmap(vistaBmp, null, destRect, null)
        }
        documento.finishPage(pagina1)

        // ---------------------------------------------------------------
        // Pagine successive: elenco dei locali, con interruzione di pagina automatica.
        // ---------------------------------------------------------------
        var numeroPagina = 2
        var paginaCorrente = documento.startPage(PdfDocument.PageInfo.Builder(larghezzaPagina, altezzaPagina, numeroPagina).create())
        var canvas = paginaCorrente.canvas
        var y2 = margine

        /** Chiude la pagina corrente e ne apre una nuova, azzerando la y: usata per il testo che non entra più in quella corrente. */
        fun nuovaPagina() {
            documento.finishPage(paginaCorrente)
            numeroPagina++
            paginaCorrente = documento.startPage(PdfDocument.PageInfo.Builder(larghezzaPagina, altezzaPagina, numeroPagina).create())
            canvas = paginaCorrente.canvas
            y2 = margine
        }
        /** Garantisce che restino almeno [spazioNecessario] punti fino al margine inferiore, cambiando pagina altrimenti. */
        fun assicuraSpazio(spazioNecessario: Float) {
            if (y2 + spazioNecessario > altezzaPagina - margine) nuovaPagina()
        }

        canvas.drawText("Dimostrazione del calcolo delle superfici", margine, y2, paintTitolo)
        y2 += 22f

        val sx = scalaX
        val sy = scalaY
        if (sx != null && sy != null) {
            val testoScala = if (abs(sx - sy) < 1e-9) {
                "Scala impostata dall'utente: ${String.format(Locale.ITALY, "%.4f", sx)} metri/pixel"
            } else {
                "Scala impostata dall'utente — orizzontale: ${String.format(Locale.ITALY, "%.4f", sx)} m/px, " +
                    "verticale: ${String.format(Locale.ITALY, "%.4f", sy)} m/px (planimetria corretta in modo non uniforme)"
            }
            y2 = disegnaTestoAvvolto(canvas, testoScala, margine, y2, paintTesto, larghezzaUtile) + 4f
        }
        canvas.drawText(
            "Area di ciascun locale calcolata con la formula di Gauss (shoelace), esatta per qualsiasi",
            margine, y2, paintTesto
        )
        y2 += 14f
        canvas.drawText(
            "poligono anche non rettangolare: risultato equivalente alla scomposizione in triangoli",
            margine, y2, paintTesto
        )
        y2 += 14f
        canvas.drawText("con la formula di Erone, ma senza arrotondamenti intermedi.", margine, y2, paintTesto)
        y2 += 24f

        for ((indice, locale) in locali.withIndex()) {
            // Prima di ogni locale ci si assicura che ci sia spazio per le sue 4 righe (stima
            // prudente, verrà comunque ricontrollato riga per riga più sotto se il locale ha
            // molti lati e quindi la riga "Lati" va a capo più volte del previsto).
            assicuraSpazio(70f)

            canvas.drawText("${indice + 1}. ${locale.nome}", margine, y2, paintEtichetta)
            y2 += 16f
            canvas.drawText("   Area: ${String.format(Locale.ITALY, "%,.2f", locale.areaM2)} m²", margine, y2, paintTesto)
            y2 += 14f
            val latiTesto = locale.latiM.mapIndexed { i, lato -> "lato ${i + 1} = ${String.format(Locale.ITALY, "%.2f", lato)} m" }
                .joinToString(", ")
            // Un locale con molti vertici (quindi molti lati) può produrre una riga "Lati"
            // lunga quanto l'intera larghezza della pagina o più: va avvolta su più righe come
            // le altre, controllando anche qui che resti spazio verticale sufficiente.
            val altezzaStimataLati = (paintTesto.measureText("   Lati: $latiTesto") / (larghezzaUtile - 20f)).toInt().coerceAtLeast(0) * (paintTesto.textSize + 3f)
            assicuraSpazio(altezzaStimataLati + 14f + 20f)
            y2 = disegnaTestoAvvolto(canvas, "   Lati: $latiTesto", margine, y2, paintTesto, larghezzaUtile - 20f)
            canvas.drawText("   Vertici: ${locale.verticiPx.size}", margine, y2, paintTesto)
            y2 += 20f
        }

        val totale = locali.sumOf { it.areaM2 }
        assicuraSpazio(36f)
        y2 += 6f
        canvas.drawText("Superficie totale: ${String.format(Locale.ITALY, "%,.2f", totale)} m²", margine, y2, paintTitolo)
        y2 += 30f

        assicuraSpazio(4 * 13f)
        canvas.drawText(
            "Avviso: i vertici sono stati posizionati manualmente dall'utente toccando lo schermo.", margine, y2, paintAvviso
        )
        y2 += 13f
        canvas.drawText(
            "La precisione del risultato dipende dalla precisione del tocco, dalla qualità e dalla scala", margine, y2, paintAvviso
        )
        y2 += 13f
        canvas.drawText(
            "dell'immagine caricata (o, per i PDF catastali, dalla scala letta automaticamente sul disegno).", margine, y2, paintAvviso
        )
        y2 += 13f
        canvas.drawText(
            "Verificare sempre i valori prima di usarli in un atto o in una pratica.", margine, y2, paintAvviso
        )

        documento.finishPage(paginaCorrente)
        return documento
    }

    private fun catturaVistaComeBitmap(): Bitmap? {
        val view = binding.superficieView
        if (view.width <= 0 || view.height <= 0) return null
        return try {
            val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.WHITE)
            view.draw(canvas)
            bmp
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Adapter per liste di AlertDialog con un'icona accanto a ogni voce (richiesto dall'utente per
 * la scelta del riferimento della scala: icona reticolo / icona pareti, vedi
 * [PlanimetriaActivity.avviaCalibrazione]). Ogni voce è una coppia (testo, risorsa drawable).
 */
private class AdapterListaConIcone(
    context: android.content.Context,
    private val voci: List<Pair<String, Int>>
) : ArrayAdapter<Pair<String, Int>>(context, R.layout.item_dialogo_icona_testo, voci) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val vista = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.item_dialogo_icona_testo, parent, false)
        val (testo, risorsaIcona) = voci[position]
        vista.findViewById<TextView>(R.id.tvTestoRiga).text = testo
        vista.findViewById<ImageView>(R.id.imgIconaRiga).setImageResource(risorsaIcona)
        return vista
    }
}
