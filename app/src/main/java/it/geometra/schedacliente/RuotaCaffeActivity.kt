package it.geometra.schedacliente

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.CountDownTimer
import android.text.InputType
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import it.geometra.schedacliente.databinding.ActivityRuotaCaffeBinding
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * "Dove prendere il caffè oggi": una ruota della fortuna che sceglie a caso, tra i bar
 * dell'elenco (di default quelli di Castelfranco di Sopra, modificabile liberamente), dove
 * andare a prendere il caffè. Nessun calcolo tecnico qui dentro: è solo per togliersi il
 * pensiero della scelta ogni mattina.
 */
class RuotaCaffeActivity : AppCompatActivity() {

    companion object {
        /**
         * Link del sondaggio condiviso "Avvistamenti FDM": da condividere con le persone
         * fidate dello studio tramite WhatsApp, per segnare quante volte si incontra un FDM
         * al bar o nel tragitto. Per scrivere nel sondaggio bisogna essere accedere con un
         * account claude.ai; chi apre il link senza account può solo vedere il totale.
         */
        private const val LINK_SONDAGGIO_FDM = "https://claude.ai/artifact/UvSBStUmaEyitaDkPXxHKb"

        /** Durata della finestra del sondaggio: 15 minuti dal momento in cui si gira la ruota. */
        private const val DURATA_SONDAGGIO_MS = 15 * 60 * 1000L
    }

    private lateinit var binding: ActivityRuotaCaffeBinding

    /** Conto alla rovescia dei 15 minuti del sondaggio, attivo da quando si gira la ruota. */
    private var timerSondaggio: CountDownTimer? = null

    /** Icone testuali del registro locale, nello stesso ordine di [AvvistamentiFdmLocaliStore.CANDIDATI]. */
    private val iconeRegistroLocale = StileFdm.ICONE

    /**
     * Momento in cui si è entrati nella schermata della ruota: il filtro "Aperti entro 30′"
     * tiene i bar aperti adesso o che apriranno entro 30 minuti da questo istante.
     */
    private val momentoIngresso: Calendar = Calendar.getInstance()

    private val clienteGoogleSignIn by lazy {
        val opzioni = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            // Senza requestEmail(), l'email dell'account non viene restituita nel risultato del
            // login anche se l'accesso riesce: è questo che causava "senza email valida".
            .requestEmail()
            .requestScopes(Scope("https://www.googleapis.com/auth/drive.file"))
            .build()
        GoogleSignIn.getClient(this, opzioni)
    }

    private val launcherAccessoGoogle = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { risultato ->
        gestisciRisultatoAccessoGoogle(risultato)
    }

    /** True mentre la ruota sta girando: evita di far partire un altro giro sopra a uno già in corso. */
    private var inRotazione = false

    /**
     * Rotazione totale accumulata della ruota in gradi, sempre crescente (mai riportata a 0):
     * tenerla sempre crescente, invece di normalizzarla a ogni giro, evita che la ruota
     * "scatti" indietro all'inizio di un nuovo giro invece di continuare a girare in avanti
     * da dove si era fermata.
     */
    private var rotazioneAccumulata = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRuotaCaffeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        aggiornaElencoInVista()

        binding.btnGira.setOnClickListener { giraRuota() }
        binding.btnModificaElenco.setOnClickListener { mostraModificaElenco() }
        binding.btnApriSondaggio.setOnClickListener { apriSondaggioFdm() }
        binding.btnCondividiSondaggio.setOnClickListener { condividiSondaggioFdm() }
        binding.toggleFiltroBar.addOnButtonCheckedListener { _, _, isChecked ->
            if (isChecked && !inRotazione) aggiornaElencoInVista()
        }

        binding.btnLocMondiale.setOnClickListener { registraAvvistamentoLocale("mondiale") }
        binding.btnLocFratello.setOnClickListener { registraAvvistamentoLocale("fratello") }
        binding.btnLocGalli.setOnClickListener { registraAvvistamentoLocale("galli") }
        binding.btnLocCelli.setOnClickListener { registraAvvistamentoLocale("celli") }
        binding.btnLocGenerale.setOnClickListener { registraAvvistamentoLocale("generale") }
        binding.btnInviaRiepilogoDrive.setOnClickListener { avviaInvioRiepilogoDrive() }
        binding.btnAzzeraGiornata.setOnClickListener {
            confermaAzzeramento("Azzerare il sondaggio della giornata?",
                "Il conteggio di oggi torna a zero. Il sondaggio totale resta com'è.") {
                AvvistamentiFdmLocaliStore.azzeraGiornata(this)
            }
        }
        binding.btnAzzeraTotale.setOnClickListener {
            confermaAzzeramento("Azzerare il sondaggio totale?",
                "Il conteggio totale torna a zero. Il sondaggio della giornata resta com'è.") {
                AvvistamentiFdmLocaliStore.azzeraTotale(this)
            }
        }
        aggiornaRiepilogoLocale()
    }

    override fun onResume() {
        super.onResume()
        // Il giorno/orario può essere cambiato da quando la schermata era stata aperta
        // l'ultima volta (es. tenuta in background oltre la mezzanotte, o oltre l'orario di
        // chiusura di un bar): si ricontrolla sempre, a meno che una rotazione sia in corso.
        if (!inRotazione) aggiornaElencoInVista()
    }

    override fun onDestroy() {
        super.onDestroy()
        timerSondaggio?.cancel()
    }

    /** True se è selezionato il filtro "Aperti entro 30′" (di default: tutti i bar). */
    private fun soloAperti(): Boolean = binding.toggleFiltroBar.checkedButtonId == R.id.btnFiltroAperti

    private fun limiteApertura(): Calendar =
        (momentoIngresso.clone() as Calendar).apply { add(Calendar.MINUTE, 30) }

    /**
     * Di default la ruota mostra tutti i bar dell'elenco. Con il filtro "Aperti entro 30′"
     * restano solo quelli aperti adesso o che apriranno entro 30 minuti dall'ingresso nella
     * schermata (secondo gli orari noti in [OrariBarNoti]; i bar senza orario noto restano sempre).
     */
    private fun elencoPerRuota(): List<String> {
        val elencoCompleto = RuotaCaffeStore.leggiElenco(this)
        if (!soloAperti()) return elencoCompleto
        val limite = limiteApertura()
        return elencoCompleto.filter { OrariBarNoti.isApertoOApreEntro(it, limite) }
    }

    private fun aggiornaElencoInVista() {
        val elencoCompleto = RuotaCaffeStore.leggiElenco(this)
        val elenco = elencoPerRuota()
        binding.ruota.etichette = elenco
        binding.btnGira.isEnabled = elenco.size >= 2 && !inRotazione

        val esclusi = elencoCompleto - elenco.toSet()
        val oraLimite = SimpleDateFormat("HH:mm", Locale.ITALY).format(limiteApertura().time)
        binding.tvInfoFiltro.text = if (soloAperti()) {
            if (esclusi.isEmpty()) "Tutti i bar sono aperti o aprono entro le $oraLimite"
            else "Esclusi perché chiusi fino alle $oraLimite: ${esclusi.joinToString(", ")}"
        } else {
            "${elencoCompleto.size} bar in ruota, orari ignorati"
        }

        binding.tvRisultato.text = when {
            elencoCompleto.size < 2 -> "Servono almeno due bar in elenco per poter far girare la ruota."
            elenco.isEmpty() -> "Nessun bar aperto ora o entro 30 minuti. Torna su \"Tutti i bar\"."
            elenco.size == 1 -> "A quest'ora c'è solo \"${elenco[0]}\": oggi si va lì."
            else -> "Fai girare la ruota!"
        }
    }

    private fun giraRuota() {
        val elenco = elencoPerRuota()
        binding.ruota.etichette = elenco
        if (elenco.size < 2 || inRotazione) {
            aggiornaElencoInVista()
            return
        }

        inRotazione = true
        binding.btnGira.isEnabled = false
        binding.tvRisultato.text = "Gira, gira, gira..."
        avviaCountdownSondaggio()

        val indiceVincente = (0 until elenco.size).random()
        val angoloPerSettore = 360f / elenco.size
        val centroSettore = indiceVincente * angoloPerSettore + angoloPerSettore / 2f

        // Piccolo scarto casuale per non far atterrare il puntatore sempre esattamente al
        // centro dello spicchio, restando comunque ben all'interno (mai vicino al bordo, per
        // non rischiare che un arrotondamento lo faccia sembrare sullo spicchio sbagliato).
        val margineSicurezza = (angoloPerSettore * 0.3f).coerceAtMost(16f)
        val scarto = (Math.random().toFloat() * 2f - 1f) * margineSicurezza

        // Il puntatore fisso è in cima alla ruota: nel sistema di angoli di drawArc (0° = ore 3,
        // in senso orario) la cima corrisponde a 270°. Si cerca quindi la rotazione che porta
        // il centro dello spicchio vincente a 270°.
        val rotazioneBase = (270f - centroSettore - scarto).mod(360f)
        val giriExtra = 360f * (5 + (0..2).random())
        val differenzaInAvanti = (rotazioneBase - rotazioneAccumulata).mod(360f)
        val rotazioneFinale = rotazioneAccumulata + differenzaInAvanti + giriExtra

        val animatore = ValueAnimator.ofFloat(rotazioneAccumulata, rotazioneFinale).apply {
            duration = 4200
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { animazione ->
                binding.ruota.rotation = animazione.animatedValue as Float
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    rotazioneAccumulata = rotazioneFinale
                    inRotazione = false
                    binding.btnGira.isEnabled = true
                    binding.tvRisultato.text = "☕ Oggi si va da: ${elenco[indiceVincente]}"
                    RuotaCaffeStore.salvaUltimoBarScelto(this@RuotaCaffeActivity, elenco[indiceVincente])
                }
            })
        }
        animatore.start()
    }

    /**
     * Fa partire la finestra di 15 minuti del sondaggio "Avvistamenti FDM", da quando si gira
     * la ruota: per tutta la sua durata il pulsante "Apri il sondaggio" resta attivo, con il
     * conto alla rovescia aggiornato ogni secondo. Girare di nuovo la ruota riparte la finestra
     * da capo (15 minuti nuovi), invece di accodarsi al tempo residuo.
     */
    private fun avviaCountdownSondaggio() {
        timerSondaggio?.cancel()
        binding.btnApriSondaggio.isEnabled = true
        timerSondaggio = object : CountDownTimer(DURATA_SONDAGGIO_MS, 1000) {
            override fun onTick(millisRimasti: Long) {
                aggiornaTestoCountdown(millisRimasti)
            }
            override fun onFinish() {
                aggiornaTestoCountdown(0)
                binding.btnApriSondaggio.isEnabled = false
            }
        }.also { it.start() }
    }

    private fun aggiornaTestoCountdown(millisRimasti: Long) {
        if (millisRimasti <= 0) {
            binding.tvCountdownSondaggio.text = "Tempo scaduto: gira di nuovo la ruota per riaprire il sondaggio"
            return
        }
        val secondiTotali = (millisRimasti / 1000).toInt()
        val minuti = secondiTotali / 60
        val secondi = secondiTotali % 60
        binding.tvCountdownSondaggio.text = String.format(
            Locale.ITALY, "Sondaggio aperto: %02d:%02d rimanenti", minuti, secondi
        )
    }

    /**
     * Apre il sondaggio "Avvistamenti FDM" nel browser, per registrare un avvistamento: attivo
     * solo nella finestra di 15 minuti dopo aver girato la ruota.
     */
    private fun apriSondaggioFdm() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(LINK_SONDAGGIO_FDM)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Nessuna app in grado di aprire il link", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Condivide il link del sondaggio "Avvistamenti FDM" tramite WhatsApp. Se WhatsApp non è
     * installato sul telefono, si apre invece il menu di condivisione generico di Android, così
     * il link può comunque essere mandato con un'altra app (email, SMS, altra app di messaggistica).
     */
    private fun condividiSondaggioFdm() {
        val testo = "Sondaggio condiviso \"Avvistamenti FDM\": segna qui ogni volta che ne incontri uno, al bar o nel tragitto.\n$LINK_SONDAGGIO_FDM"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, testo)
            setPackage("com.whatsapp")
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            val intentGenerico = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, testo)
            }
            startActivity(Intent.createChooser(intentGenerico, "Condividi sondaggio FDM"))
        }
    }

    /** Registra un avvistamento nel registro locale (solo su questo telefono) e aggiorna la vista. */
    private fun registraAvvistamentoLocale(luogo: String) {
        AvvistamentiFdmLocaliStore.aggiungi(this, luogo)
        aggiornaRiepilogoLocale()
        // Niente nomi nel messaggio di conferma, solo l'icona: il registro locale, come il
        // sondaggio condiviso, non deve mai riportare per esteso i nomi delle categorie.
        Toast.makeText(this, "Avvistamento registrato ✓", Toast.LENGTH_SHORT).show()
    }

    /**
     * Aggiorna le tre sezioni del registro locale: sondaggio della giornata (solo gli
     * avvistamenti di oggi), sondaggio totale e ultimi avvistamenti. Solo icone, mai i nomi.
     */
    private fun aggiornaRiepilogoLocale() {
        val voci = AvvistamentiFdmLocaliStore.vociTotale(this)
        val vociOggi = AvvistamentiFdmLocaliStore.vociGiornata(this)
        val recenti = AvvistamentiFdmLocaliStore.vociRecenti(this)
        binding.btnAzzeraGiornata.isEnabled = vociOggi.isNotEmpty()
        binding.btnAzzeraTotale.isEnabled = voci.isNotEmpty()

        binding.boxOggi.background = sfondoArrotondato(StileFdm.SFONDO_PAGINA, StileFdm.BORDO, 14f)
        binding.boxTotale.background = sfondoArrotondato(StileFdm.SFONDO_PAGINA, StileFdm.BORDO, 14f)

        binding.tvOggiNumero.text = vociOggi.size.toString()
        binding.tvOggiEtichetta.text = if (vociOggi.size == 1) "avvistamento oggi" else "avvistamenti oggi"
        riempiRigaChip(binding.rigaChipOggi, vociOggi)

        binding.tvTotaleNumero.text = voci.size.toString()
        binding.tvTotaleEtichetta.text = if (voci.size == 1) "avvistamento totale" else "avvistamenti totali"
        riempiRigaChip(binding.rigaChipTotale, voci)

        val lista = binding.listaUltimi
        lista.removeAllViews()
        val ultime = recenti.take(8)
        if (ultime.isEmpty()) {
            lista.addView(TextView(this).apply {
                text = "Nessun avvistamento ancora."
                textSize = 15f
                setTextColor(StileFdm.TESTO)
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(12))
            })
            return
        }
        val formattoOra = SimpleDateFormat("EEE dd/MM · HH:mm", Locale.ITALY)
        ultime.forEach { v ->
            val riga = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = sfondoArrotondato(StileFdm.SUPERFICIE, StileFdm.BORDO, 12f)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) }
            }
            // Pillola colorata di dimensione fissa, uguale per tutte le categorie (anche i due galli).
            riga.addView(FrameLayout(this).apply {
                background = sfondoArrotondato(StileFdm.COLORE_SFONDO[v.luogo] ?: StileFdm.SFONDO_PAGINA, null, 999f)
                layoutParams = LinearLayout.LayoutParams(dp(84), dp(40))
                aggiungiIconaCentrata(this, v.luogo, 22)
            })
            riga.addView(TextView(this).apply {
                text = formattoOra.format(Date(v.ts))
                textSize = 15f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                setTextColor(StileFdm.TESTO)
                setPadding(dp(12), 0, 0, 0)
            })
            lista.addView(riga)
        }
    }

    /** Riempie una riga con un "chip" per categoria: icona + conteggio, sui colori del sondaggio condiviso. */
    private fun riempiRigaChip(riga: LinearLayout, voci: List<AvvistamentiFdmLocaliStore.Avvistamento>) {
        riga.removeAllViews()
        AvvistamentiFdmLocaliStore.CANDIDATI.forEachIndexed { indice, c ->
            val conteggio = voci.count { it.luogo == c }
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(2), dp(6), dp(2), dp(6))
                background = sfondoArrotondato(StileFdm.COLORE_SFONDO[c] ?: Color.WHITE, null, 12f)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (indice > 0) marginStart = dp(5)
                }
            }
            // Area icona ad altezza fissa: tutti i riquadri hanno la stessa altezza e i numeri allineati.
            chip.addView(FrameLayout(this).apply {
                aggiungiIconaCentrata(this, c, 24)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36)))
            chip.addView(TextView(this).apply {
                text = conteggio.toString()
                textSize = 20f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                setTextColor(StileFdm.COLORE_TESTO[c] ?: StileFdm.TESTO)
                gravity = Gravity.CENTER
            })
            riga.addView(chip)
        }
    }

    /**
     * Icona di una categoria. Per "celli" è il disegno dell'uomo dietro le sbarre (non esiste
     * un'emoji adatta); per le altre è l'emoji, a colore pieno (con un colore semitrasparente
     * le emoji vengono disegnate sbiadite). I due galli usano un carattere più piccolo, così
     * occupano la stessa larghezza utile delle altre icone.
     */
    private fun aggiungiIconaCentrata(contenitore: FrameLayout, categoria: String, dimensioneSp: Int) {
        if (categoria == "celli") {
            val lato = (dimensioneSp * 1.35f * resources.displayMetrics.density).toInt()
            contenitore.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_fdm_celli)
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = "Uomo dietro le sbarre"
            }, FrameLayout.LayoutParams(lato, lato, Gravity.CENTER))
        } else {
            contenitore.addView(vistaIcona(categoria, dimensioneSp), FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER
            ))
        }
    }

    private fun vistaIcona(categoria: String, dimensioneSp: Int): View {
        return TextView(this).apply {
            text = iconeRegistroLocale[categoria]
            textSize = if (categoria == "galli") dimensioneSp * 0.72f else dimensioneSp.toFloat()
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
    }

    /** Chiede conferma prima di azzerare (l'azzeramento non si può annullare). */
    private fun confermaAzzeramento(titolo: String, messaggio: String, azione: () -> Unit) {
        val dialog = AlertDialog.Builder(this, R.style.Theme_SchedaCliente_Dialog)
            .setTitle(titolo)
            .setMessage(messaggio + "\n\nL'operazione non può essere annullata.")
            .setPositiveButton("Azzera") { _, _ ->
                azione()
                aggiornaRiepilogoLocale()
                Toast.makeText(this, "Azzerato", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Annulla", null)
            .show()
        dialog.rendiPulsantiLeggibili(this)
    }

    private fun sfondoArrotondato(colore: Int, coloreBordo: Int?, raggioDp: Float) = GradientDrawable().apply {
        setColor(colore)
        cornerRadius = raggioDp * resources.displayMetrics.density
        if (coloreBordo != null) setStroke(dp(1), coloreBordo)
    }

    private fun dp(valore: Int): Int = (valore * resources.displayMetrics.density).toInt()

    /**
     * Avvia il caricamento su Drive del riepilogo del registro locale: se l'utente non ha
     * ancora fatto il login Google (con il permesso "drive.file") lo richiede prima, altrimenti
     * procede direttamente.
     */
    private fun avviaInvioRiepilogoDrive() {
        val email = GoogleSignIn.getLastSignedInAccount(this)?.email
        if (email != null) {
            eseguiCaricamentoEInvio(email)
        } else {
            launcherAccessoGoogle.launch(clienteGoogleSignIn.signInIntent)
        }
    }

    private fun gestisciRisultatoAccessoGoogle(risultato: androidx.activity.result.ActivityResult) {
        if (risultato.resultCode != Activity.RESULT_OK) {
            Toast.makeText(this, "Accesso a Google annullato", Toast.LENGTH_SHORT).show()
            return
        }
        GoogleSignIn.getSignedInAccountFromIntent(risultato.data)
            .addOnSuccessListener { account ->
                // Si usa l'email (stringa) invece di account.account (oggetto Account di sistema):
                // su alcuni dispositivi quest'ultimo torna null anche a login riuscito, mentre
                // l'email dell'account Google appena autenticato è sempre presente.
                val email = account.email
                if (email != null) {
                    eseguiCaricamentoEInvio(email)
                } else {
                    Toast.makeText(this, "Accesso Google riuscito ma senza email valida", Toast.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener {
                Toast.makeText(this, "Accesso a Google non riuscito: ${it.message}", Toast.LENGTH_LONG).show()
            }
    }

    /**
     * Genera l'immagine di riepilogo e la carica su Drive in un thread separato (sia la rete
     * sia il recupero del token sono operazioni bloccanti, non vanno fatte sul thread principale),
     * poi condivide il link ottenuto su WhatsApp.
     */
    private fun eseguiCaricamentoEInvio(emailAccount: String) {
        Toast.makeText(this, "Carico il riepilogo su Drive…", Toast.LENGTH_SHORT).show()
        binding.tvErroreDrive.visibility = View.GONE
        Thread {
            try {
                val nomeBar = RuotaCaffeStore.leggiUltimoBarScelto(this)?.first
                val bytesPng = RiepilogoFdmImmagine.genera(
                    this, nomeBar,
                    AvvistamentiFdmLocaliStore.vociGiornata(this),
                    AvvistamentiFdmLocaliStore.vociTotale(this),
                    AvvistamentiFdmLocaliStore.vociRecenti(this)
                )
                val nomeFile = "Avvistamenti_FDM_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.ITALY).format(Date()) + ".png"
                val link = DriveUploader.caricaEContrassegnaCondivisibile(this, emailAccount, nomeFile, bytesPng)
                runOnUiThread { condividiLinkDrive(link) }
            } catch (e: Exception) {
                runOnUiThread {
                    // Il messaggio completo si scrive anche nel riepilogo (il Toast si taglia ed
                    // è troppo corto per mostrare per intero la risposta di errore di Drive).
                    Toast.makeText(this, "Errore durante il caricamento su Drive", Toast.LENGTH_LONG).show()
                    binding.tvErroreDrive.visibility = View.VISIBLE
                    binding.tvErroreDrive.text = "⚠️ Errore Drive:\n${e.message}"
                }
            }
        }.start()
    }

    private fun condividiLinkDrive(link: String) {
        val testo = "Riepilogo del sondaggio FDM (immagine su Drive):\n$link"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, testo)
            setPackage("com.whatsapp")
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            val intentGenerico = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, testo)
            }
            startActivity(Intent.createChooser(intentGenerico, "Condividi riepilogo FDM"))
        }
    }

    private fun mostraModificaElenco() {
        val elencoAttuale = RuotaCaffeStore.leggiElenco(this)
        val campo = EditText(this).apply {
            setText(elencoAttuale.joinToString("\n"))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 5
            setPadding(
                (16 * resources.displayMetrics.density).toInt(), (12 * resources.displayMetrics.density).toInt(),
                (16 * resources.displayMetrics.density).toInt(), (12 * resources.displayMetrics.density).toInt()
            )
        }
        val dialog = AlertDialog.Builder(this, R.style.Theme_SchedaCliente_Dialog)
            .setTitle("Elenco bar (uno per riga)")
            .setMessage(
                "Per i bar già presenti nell'elenco l'app conosce l'orario di apertura e li " +
                    "esclude automaticamente dalla ruota quando sono chiusi. Un bar scritto " +
                    "diverso o aggiunto di tuo (di cui l'orario non è noto) viene invece sempre " +
                    "considerato aperto."
            )
            .setView(campo)
            .setPositiveButton("Salva") { _, _ ->
                val nuovoElenco = campo.text?.toString()
                    ?.split("\n")
                    ?.map { it.trim() }
                    ?.filter { it.isNotBlank() }
                    ?: emptyList()
                if (nuovoElenco.size < 2) {
                    Toast.makeText(this, "Servono almeno due bar", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                RuotaCaffeStore.salvaElenco(this, nuovoElenco)
                aggiornaElencoInVista()
                Toast.makeText(this, "Elenco aggiornato", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Ripristina elenco di base") { _, _ ->
                RuotaCaffeStore.ripristinaPredefinito(this)
                aggiornaElencoInVista()
                Toast.makeText(this, "Elenco ripristinato", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Annulla", null)
            .show()
        dialog.rendiPulsantiLeggibili(this)
    }
}
