package it.geometra.schedacliente

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.hypot

/**
 * Mostra la planimetria caricata e permette di toccare punti sull'immagine
 * (per la calibrazione della scala e per tracciare i vertici dei locali).
 * Tutte le coordinate esposte all'esterno (punti toccati, vertici salvati)
 * sono nello spazio pixel dell'immagine ORIGINALE, indipendente da come la
 * vista la mostra a schermo: così la scala calcolata resta coerente anche
 * se la vista viene ridisegnata a dimensioni diverse.
 *
 * Ogni punto, sia nuovo che già esistente, può essere trascinato finché il
 * dito resta premuto: durante il trascinamento appare una lente che mostra
 * una vista ingrandita del punto esatto, per poter rilasciare con precisione.
 */
class SuperficieView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class ModoTocco { NESSUNO, AGGIUNGI_E_TRASCINA, SOLO_TRASCINA }

    var bitmap: Bitmap? = null
        set(value) {
            field = value
            // L'altezza della vista dipende ora dalla proporzione del bitmap
            // (vedi onMeasure): quando cambia bisogna rimisurare la vista,
            // non solo ridisegnarla, altrimenti l'altezza resterebbe quella
            // calcolata per il bitmap precedente (o per nessun bitmap).
            requestLayout()
            aggiornaMatrice()
            invalidate()
        }

    val puntiCalibrazione: MutableList<PointF> = mutableListOf()
    val puntiLocaleCorrente: MutableList<PointF> = mutableListOf()
    val locali: MutableList<LocaleTracciato> = mutableListOf()
    /** I due punti del reticolo catastale (parametri) usati per calcolare automaticamente la scala, mostrati temporaneamente per far verificare all'utente dove sono stati presi. */
    val puntiScalaEvidenziati: MutableList<PointF> = mutableListOf()

    /** Se true, i punti di puntiLocaleCorrente vengono disegnati come poligono chiuso (modifica di un locale già esistente) invece che come linea aperta (nuovo locale in corso). */
    var disegnaCorrenteChiuso: Boolean = false

    /** Quale delle liste sopra riceve i tocchi, e come. */
    var modoTocco: ModoTocco = ModoTocco.NESSUNO
    var listaAttiva: MutableList<PointF>? = null
        set(value) {
            if (value !== field) storiaModifiche.clear()
            field = value
            onStoriaCambiata?.invoke()
        }

    /**
     * Cronologia delle modifiche ai punti di [listaAttiva] (aggiunta, inserimento sul bordo,
     * rimozione, spostamento): ogni voce è lo stato della lista PRIMA della modifica, così
     * [annullaUltimaModifica] può tornare indietro di un passo alla volta.
     */
    private val storiaModifiche = ArrayDeque<List<PointF>>()
    private var puntoInizioTrascinamento: PointF? = null
    private val STORIA_MASSIMA = 100

    /** Chiamato quando cambia la possibilità di annullare (per abilitare/disabilitare il pulsante). */
    var onStoriaCambiata: (() -> Unit)? = null

    val puoAnnullare: Boolean get() = storiaModifiche.isNotEmpty()

    private fun registraStato() {
        val lista = listaAttiva ?: return
        storiaModifiche.addLast(lista.map { PointF(it.x, it.y) })
        while (storiaModifiche.size > STORIA_MASSIMA) storiaModifiche.removeFirst()
        onStoriaCambiata?.invoke()
    }

    /** Torna allo stato precedente all'ultima modifica dei punti; false se non c'è nulla da annullare. */
    fun annullaUltimaModifica(): Boolean {
        val lista = listaAttiva ?: return false
        val precedente = storiaModifiche.removeLastOrNull() ?: return false
        lista.clear()
        lista.addAll(precedente)
        invalidate()
        onStoriaCambiata?.invoke()
        return true
    }

    /**
     * Se false, un tocco senza operazione attiva non mostra l'avviso "Premi prima…": usato nella
     * sola visualizzazione a schermo intero, dove toccare serve solo a guardare/scorrere.
     */
    var avvisaTapNonConsentito: Boolean = true
    /** Numero massimo di punti che listaAttiva può contenere (es. 2 per la calibrazione); null = nessun limite. */
    var limiteMassimoPunti: Int? = null

    /** Un punto nuovo è stato aggiunto e il dito è stato rilasciato. */
    var onNuovoPunto: (() -> Unit)? = null
    /** Un punto già esistente è stato trascinato e il dito è stato rilasciato. */
    var onPuntoSpostato: (() -> Unit)? = null
    /** Tocco ricevuto mentre modoTocco è NESSUNO (nessuna operazione attiva). */
    var onTapNonConsentito: (() -> Unit)? = null
    /** Un vertice è stato rimosso toccandolo con [modalitaRimozioneVertice] attiva. */
    var onVerticeRimosso: (() -> Unit)? = null
    /** Si è provato a rimuovere un vertice ma ne restavano solo 3 (minimo per un poligono). */
    var onRimozioneNonConsentita: (() -> Unit)? = null
    /**
     * Il dito è stato tenuto premuto per più di [RITARDO_CAMBIO_LOCALE_MS] su un ALTRO locale
     * già confermato (diverso da quello in correzione), mentre si sta correggendo un locale:
     * passa l'indice del locale toccato (nella lista [locali]) a chi vuole cambiare il locale
     * in modifica senza dover prima chiudere la correzione corrente.
     */
    var onLocaleAltroTenutoPremuto: ((Int) -> Unit)? = null

    /**
     * In sola visualizzazione (nessuna operazione attiva): tenendo il dito fermo per 2 secondi
     * sopra un locale già inserito, passa l'indice (nella lista [locali]) a chi vuole aprirne la
     * correzione dei vertici.
     */
    var onLocaleTenutoPremutoInVisualizzazione: ((Int) -> Unit)? = null

    /**
     * Quando true (e [modoTocco] è [ModoTocco.SOLO_TRASCINA]), il tocco successivo non sposta
     * un vertice esistente ma ne inserisce uno nuovo sul bordo del poligono corrente, nel punto
     * più vicino al tocco: usato per correggere un locale già rilevato che ha bisogno di un
     * vertice in più (es. un angolo rientrante non individuato). Resta attivo finché non viene
     * disattivato esplicitamente, per poter aggiungere più vertici in sequenza.
     */
    var modalitaInserimentoBordo: Boolean = false
    /**
     * Quando true (e [modoTocco] è [ModoTocco.SOLO_TRASCINA]), toccare un vertice esistente lo
     * rimuove immediatamente (se ne restano più di 3) invece di prepararlo per lo spostamento.
     * Resta attivo finché non viene disattivato esplicitamente.
     */
    var modalitaRimozioneVertice: Boolean = false

    /** Matrice "a schermo intero": l'immagine adattata alla vista, senza zoom dell'utente. */
    private val matriceImmagine = Matrix()
    /** Matrice effettivamente usata per disegnare/toccare: parte da matriceImmagine e viene modificata dal pizzico (zoom) e dallo scorrimento a due dita. */
    private val matriceVista = Matrix()
    private val matriceVistaInversa = Matrix()
    private var fattoreZoomCorrente = 1f

    /**
     * Ingrandimento massimo con il pizzico (prima 8×): portato a 25× per poter appoggiare i
     * punti con più precisione sugli spigoli. Ha senso perché ora la planimetria viene caricata
     * a risoluzione più alta (vedi PlanimetriaActivity), altrimenti si ingrandirebbero solo pixel.
     */
    private val ZOOM_MASSIMO = 25f

    private var indiceTrascinato: Int? = null
    private var trascinatoNuovo: Boolean = false
    private var mostraLente: Boolean = false
    private var puntoLenteImmagine: PointF? = null
    private var puntoLenteSchermo: PointF? = null

    /** Vertice già esistente su cui si è appoggiato il dito, in attesa della pressione prolungata prima di poterlo spostare. */
    private var indiceCandidatoPressioneLunga: Int? = null
    private var downX = 0f
    private var downY = 0f
    private val handlerPressioneLunga = Handler(Looper.getMainLooper())
    private val pressioneLungaRunnable = Runnable { avviaTrascinamentoCandidato() }
    private val pressioneLungaCambioLocaleRunnable = Runnable { tentaCambioLocale() }
    private val pressioneLungaVisualizzazioneRunnable = Runnable {
        val callback = onLocaleTenutoPremutoInVisualizzazione ?: return@Runnable
        if (panInCorso) return@Runnable
        val puntoImmagine = schermoAImmagine(downX, downY)
        val indiceLocale = locali.indexOfFirst { GeometriaUtils.puntoDentroPoligono(puntoImmagine, it.verticiPx) }
        if (indiceLocale >= 0) {
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            pressioneVisualizzazioneUsata = true
            callback(indiceLocale)
        }
    }
    private var pressioneVisualizzazioneUsata = false

    /** Tocco a un dito partito su un punto vuoto (nessun vertice vicino): può diventare uno scorrimento libero della vista, oppure, se il dito resta fermo, un cambio del locale in modifica. */
    private var panCandidato = false
    private var panInCorso = false
    private var puntoPanPrecedenteX = 0f
    private var puntoPanPrecedenteY = 0f

    private var focoZoomAnterioreX = 0f
    private var focoZoomAnterioreY = 0f

    private val rilevatoreZoom = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            focoZoomAnterioreX = detector.focusX
            focoZoomAnterioreY = detector.focusY
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val fattoreRichiesto = detector.scaleFactor
            val nuovoFattore = (fattoreZoomCorrente * fattoreRichiesto).coerceIn(1f, ZOOM_MASSIMO)
            val fattoreEffettivo = if (fattoreZoomCorrente != 0f) nuovoFattore / fattoreZoomCorrente else 1f
            fattoreZoomCorrente = nuovoFattore
            if (fattoreEffettivo != 1f) {
                matriceVista.postScale(fattoreEffettivo, fattoreEffettivo, detector.focusX, detector.focusY)
            }
            matriceVista.postTranslate(detector.focusX - focoZoomAnterioreX, detector.focusY - focoZoomAnterioreY)
            focoZoomAnterioreX = detector.focusX
            focoZoomAnterioreY = detector.focusY
            matriceVista.invert(matriceVistaInversa)
            invalidate()
            return true
        }
    })

    private val paintCalibrazione = Paint().apply {
        color = Color.parseColor("#C1440E")
        strokeWidth = 6f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }
    private val paintCalibrazionePunti = Paint().apply {
        color = Color.parseColor("#C1440E")
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val paintLocaleCorrente = Paint().apply {
        color = Color.parseColor("#2E5339")
        strokeWidth = 5f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val paintLocaleCorrenteRiempimento = Paint().apply {
        color = Color.parseColor("#552E5339")
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val paintLocaleCorrentePunti = Paint().apply {
        color = Color.parseColor("#2E5339")
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val paintLocaleCorrentePuntiBordo = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }
    private val paintLocaleConfermato = Paint().apply {
        color = Color.parseColor("#552E5339")
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val paintLocaleConfermatoBordo = Paint().apply {
        color = Color.parseColor("#2E5339")
        strokeWidth = 4f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private fun sp(valore: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, valore, resources.displayMetrics)
    private fun dp(valore: Float) = valore * resources.displayMetrics.density

    // CORREZIONE v3.77: i nomi dei locali erano bianchi (30 px fissi, piccoli sugli schermi ad alta
    // densità) e sul riempimento verde chiaro non si leggevano. Ora testo scuro in grassetto su
    // un'etichetta bianca con bordo verde, leggibile su qualunque sfondo e a qualunque zoom.
    private val paintTestoLocale = Paint().apply {
        color = Color.parseColor("#0F2A17")
        textSize = sp(14f)
        isAntiAlias = true
        isSubpixelText = true
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val paintTestoLocaleRidotto = Paint(paintTestoLocale)
    private val paintEtichettaLocaleSfondo = Paint().apply {
        color = Color.argb(240, 255, 255, 255)
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val paintEtichettaLocaleBordo = Paint().apply {
        color = Color.parseColor("#2E5339")
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
        isAntiAlias = true
    }
    /** Misura in metri assegnata al tratto di scala evidenziato (es. "10 m"), scritta a metà della linea gialla. */
    var etichettaScala: String? = null
    private val paintTestoScala = Paint().apply {
        color = Color.BLACK
        textSize = sp(16f)
        isAntiAlias = true
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val paintEtichettaScalaSfondo = Paint().apply {
        color = Color.parseColor("#FFD600")
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val paintEtichettaScalaBordo = Paint().apply {
        color = Color.parseColor("#C1440E")
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        isAntiAlias = true
    }

    /** Testo centrato in (cx, cy) dentro un'etichetta arrotondata con sfondo e bordo. */
    private fun disegnaEtichetta(canvas: Canvas, testo: String, cx: Float, cy: Float, testoPaint: Paint, sfondo: Paint, bordo: Paint) {
        val fm = testoPaint.fontMetrics
        val larghezza = testoPaint.measureText(testo)
        val padX = dp(6f); val padY = dp(3f)
        val alto = fm.descent - fm.ascent
        val r = RectF(cx - larghezza / 2 - padX, cy - alto / 2 - padY, cx + larghezza / 2 + padX, cy + alto / 2 + padY)
        val raggio = dp(5f)
        canvas.drawRoundRect(r, raggio, raggio, sfondo)
        canvas.drawRoundRect(r, raggio, raggio, bordo)
        canvas.drawText(testo, cx, cy - (fm.ascent + fm.descent) / 2, testoPaint)
    }
    private val paintLenteBordo = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 5f
        isAntiAlias = true
    }
    private val paintLenteBordoEsterno = Paint().apply {
        color = Color.parseColor("#80000000")
        style = Paint.Style.STROKE
        strokeWidth = 8f
        isAntiAlias = true
    }
    private val paintLenteCrocicchio = Paint().apply {
        color = Color.parseColor("#C1440E")
        strokeWidth = 3f
        isAntiAlias = true
    }
    private val paintLenteSfondo = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    // v3.77: linea gialla più spessa (in dp) con un bordo scuro sotto: prima, sottile e senza bordo,
    // sulla carta bianca del foglio si vedeva poco.
    private val paintScalaEvidenziataBordo = Paint().apply {
        color = Color.parseColor("#C1440E")
        strokeWidth = dp(6f)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }
    private val paintScalaEvidenziata = Paint().apply {
        color = Color.parseColor("#FFD600")
        strokeWidth = dp(3.5f)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }
    private val paintScalaEvidenziataPunti = Paint().apply {
        color = Color.parseColor("#FFD600")
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val paintScalaEvidenziataPuntiBordo = Paint().apply {
        color = Color.parseColor("#C1440E")
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        isAntiAlias = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Se l'utente ha ingrandito, si mantengono zoom e punto inquadrato anche quando la vista
        // cambia dimensione (es. la barra delle istruzioni cambia altezza passando da "+ Vertice"
        // a "− Vertice", o si apre/chiude lo schermo intero): prima si tornava sempre a tutta la
        // planimetria, perdendo il punto su cui si stava lavorando.
        val zoom = fattoreZoomCorrente
        if (bitmap != null && oldw > 0 && oldh > 0 && w > 0 && h > 0 && zoom > 1.001f) {
            val centroImmagine = schermoAImmagine(oldw / 2f, oldh / 2f)
            aggiornaMatrice()
            zoomCentratoSu(centroImmagine, zoom)
            return
        }
        aggiornaMatrice()
    }

    // CORREZIONE (ottobre 2026): l'altezza di questa vista era fissa (320dp,
    // impostata nel layout XML). Con Matrix.ScaleToFit.CENTER l'immagine non
    // viene mai tagliata, ma se la sua proporzione è molto diversa da quella
    // di un riquadro fisso largo e basso (es. una planimetria in verticale),
    // viene rimpicciolita per entrarci lasciando grandi fasce vuote ai lati:
    // la cornice del disegno appare "staccata" dal bordo del riquadro, come
    // se fosse tagliata. Ora l'altezza della vista si adatta alla proporzione
    // reale dell'immagine caricata (entro un minimo e un massimo ragionevoli),
    // così l'immagine riempie il riquadro per intero e la cornice resta
    // sempre visibile tutta, a contatto con i bordi, su tutti i lati.
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Si adatta solo quando il genitore lascia libertà sull'altezza
        // (wrap_content nel layout normale incorporato nella scheda). A
        // schermo intero la vista viene spostata in un contenitore che
        // impone MATCH_PARENT (altezza EXACTLY): in quel caso NON si deve
        // toccare nulla, altrimenti la vista non occuperebbe più tutto lo
        // schermo disponibile per zoom e correzioni.
        val modoAltezza = MeasureSpec.getMode(heightMeasureSpec)
        val bmp = bitmap
        val larghezzaMisurata = MeasureSpec.getSize(widthMeasureSpec)
        if (modoAltezza != MeasureSpec.EXACTLY && bmp != null && bmp.width > 0 && larghezzaMisurata > 0) {
            val densita = resources.displayMetrics.density
            val altezzaMinima = (220 * densita).toInt()
            val altezzaMassima = (520 * densita).toInt()
            val altezzaProporzionale = (larghezzaMisurata.toFloat() * bmp.height / bmp.width).toInt()
            var altezzaScelta = altezzaProporzionale.coerceIn(altezzaMinima, altezzaMassima)
            if (modoAltezza == MeasureSpec.AT_MOST) {
                altezzaScelta = altezzaScelta.coerceAtMost(MeasureSpec.getSize(heightMeasureSpec))
            }
            setMeasuredDimension(larghezzaMisurata, altezzaScelta)
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    private fun aggiornaMatrice() {
        val bmp = bitmap ?: return
        if (width == 0 || height == 0) return
        val src = RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
        val dst = RectF(0f, 0f, width.toFloat(), height.toFloat())
        matriceImmagine.setRectToRect(src, dst, Matrix.ScaleToFit.CENTER)
        matriceVista.set(matriceImmagine)
        matriceVista.invert(matriceVistaInversa)
        fattoreZoomCorrente = 1f
    }

    fun aggiornaTutto() {
        aggiornaMatrice()
        invalidate()
    }

    /** Riporta lo zoom/scorrimento dell'utente alla vista adattata iniziale, senza toccare i punti già tracciati. */
    fun azzeraZoom() {
        aggiornaMatrice()
        invalidate()
    }

    /**
     * Zooma ed inquadra la vista centrata esattamente su un punto dell'immagine originale
     * (es. lo spigolo di un locale individuato dall'AI), così l'utente può verificare a colpo
     * d'occhio se il punto coincide con lo spigolo reale sul disegno, senza doverlo cercare a
     * occhio su una vista non ingrandita. Riparte sempre dalla vista adattata iniziale, quindi
     * lo zoom risultante è sempre lo stesso indipendentemente da eventuali pizzichi precedenti
     * dell'utente.
     */
    fun zoomCentratoSu(puntoImmagine: PointF, fattoreZoom: Float = 4f) {
        if (width == 0 || height == 0) return
        matriceVista.set(matriceImmagine)
        val puntoVista = floatArrayOf(puntoImmagine.x, puntoImmagine.y)
        matriceVista.mapPoints(puntoVista)
        matriceVista.postScale(fattoreZoom, fattoreZoom, puntoVista[0], puntoVista[1])
        val puntoDopoScala = floatArrayOf(puntoImmagine.x, puntoImmagine.y)
        matriceVista.mapPoints(puntoDopoScala)
        matriceVista.postTranslate(width / 2f - puntoDopoScala[0], height / 2f - puntoDopoScala[1])
        matriceVista.invert(matriceVistaInversa)
        fattoreZoomCorrente = fattoreZoom.coerceIn(1f, ZOOM_MASSIMO)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap ?: return
        canvas.drawBitmap(bmp, matriceVista, null)

        for (locale in locali) {
            val path = creaPath(locale.verticiPx)
            canvas.drawPath(path, paintLocaleConfermato)
            canvas.drawPath(path, paintLocaleConfermatoBordo)
        }
        // Etichette dopo tutti i riempimenti: un locale disegnato dopo non copre il nome di un altro.
        for (locale in locali) {
            val centro = trasforma(GeometriaUtils.centroide(locale.verticiPx))
            // Se il nome non entra nella larghezza del vano (vani stretti, vista rimpicciolita) il
            // testo si riduce, fino a 9 sp, invece di sovrapporsi all'etichetta del vano accanto.
            val xs = locale.verticiPx.map { trasforma(it).x }
            val larghezzaVano = (xs.maxOrNull() ?: 0f) - (xs.minOrNull() ?: 0f)
            val spazio = larghezzaVano * 0.92f - dp(12f)
            val pieno = paintTestoLocale.measureText(locale.nome)
            val testo = if (pieno > spazio && pieno > 0f) {
                paintTestoLocaleRidotto.textSize = (paintTestoLocale.textSize * spazio / pieno).coerceIn(sp(9f), paintTestoLocale.textSize)
                paintTestoLocaleRidotto
            } else paintTestoLocale
            disegnaEtichetta(canvas, locale.nome, centro.x, centro.y, testo, paintEtichettaLocaleSfondo, paintEtichettaLocaleBordo)
        }

        if (puntiLocaleCorrente.isNotEmpty()) {
            val pts = puntiLocaleCorrente.map { trasforma(it) }
            if (disegnaCorrenteChiuso && pts.size >= 3) {
                val path = Path()
                pts.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
                path.close()
                canvas.drawPath(path, paintLocaleCorrenteRiempimento)
                canvas.drawPath(path, paintLocaleCorrente)
            } else {
                for (i in 0 until pts.size - 1) {
                    canvas.drawLine(pts[i].x, pts[i].y, pts[i + 1].x, pts[i + 1].y, paintLocaleCorrente)
                }
            }
            pts.forEach {
                canvas.drawCircle(it.x, it.y, 10f, paintLocaleCorrentePunti)
                canvas.drawCircle(it.x, it.y, 10f, paintLocaleCorrentePuntiBordo)
            }
        }

        if (puntiCalibrazione.isNotEmpty()) {
            val pts = puntiCalibrazione.map { trasforma(it) }
            if (pts.size == 2) {
                canvas.drawLine(pts[0].x, pts[0].y, pts[1].x, pts[1].y, paintCalibrazione)
            }
            pts.forEach {
                canvas.drawCircle(it.x, it.y, 11f, paintCalibrazionePunti)
                canvas.drawCircle(it.x, it.y, 11f, paintLocaleCorrentePuntiBordo)
            }
        }

        if (puntiScalaEvidenziati.size == 2) {
            val pts = puntiScalaEvidenziati.map { trasforma(it) }
            canvas.drawLine(pts[0].x, pts[0].y, pts[1].x, pts[1].y, paintScalaEvidenziataBordo)
            canvas.drawLine(pts[0].x, pts[0].y, pts[1].x, pts[1].y, paintScalaEvidenziata)
            pts.forEach {
                canvas.drawCircle(it.x, it.y, dp(7f), paintScalaEvidenziataPunti)
                canvas.drawCircle(it.x, it.y, dp(7f), paintScalaEvidenziataPuntiBordo)
            }
            etichettaScala?.let { testo ->
                // A metà della linea, spostata di lato quanto basta per non coprirla.
                val mx = (pts[0].x + pts[1].x) / 2f; val my = (pts[0].y + pts[1].y) / 2f
                val dx = pts[1].x - pts[0].x; val dy = pts[1].y - pts[0].y
                val l = kotlin.math.hypot(dx, dy).coerceAtLeast(1f)
                val scostamento = dp(22f)
                disegnaEtichetta(canvas, testo, mx - dy / l * scostamento, my + dx / l * scostamento,
                    paintTestoScala, paintEtichettaScalaSfondo, paintEtichettaScalaBordo)
            }
        }

        if (mostraLente) disegnaLente(canvas)
    }

    private fun disegnaLente(canvas: Canvas) {
        val bmp = bitmap ?: return
        val centroImmagine = puntoLenteImmagine ?: return
        val centroSchermo = puntoLenteSchermo ?: return
        val densita = resources.displayMetrics.density

        val diametro = 150f * densita
        val raggio = diametro / 2f
        val zoom = 3f

        val scalaVista = matriceVista.mapRadius(1f)
        if (scalaVista <= 0f) return
        val scalaLente = scalaVista * zoom
        val semiLatoImmagine = (raggio / scalaLente).coerceAtLeast(4f)

        var left = centroImmagine.x - semiLatoImmagine
        var top = centroImmagine.y - semiLatoImmagine
        var right = centroImmagine.x + semiLatoImmagine
        var bottom = centroImmagine.y + semiLatoImmagine
        if (left < 0f) { right -= left; left = 0f }
        if (top < 0f) { bottom -= top; top = 0f }
        if (right > bmp.width) { left -= (right - bmp.width); right = bmp.width.toFloat() }
        if (bottom > bmp.height) { top -= (bottom - bmp.height); bottom = bmp.height.toFloat() }
        left = left.coerceAtLeast(0f)
        top = top.coerceAtLeast(0f)
        val srcRect = Rect(left.toInt(), top.toInt(), right.toInt().coerceIn(1, bmp.width), bottom.toInt().coerceIn(1, bmp.height))
        if (srcRect.width() <= 0 || srcRect.height() <= 0) return

        if (width < diametro) return

        var centroLenteX = centroSchermo.x.coerceIn(raggio, width - raggio)
        var centroLenteY = centroSchermo.y - raggio - 56f * densita
        if (centroLenteY - raggio < 0f) {
            centroLenteY = centroSchermo.y + raggio + 56f * densita
        }

        canvas.save()
        val percorsoCerchio = Path().apply { addCircle(centroLenteX, centroLenteY, raggio, Path.Direction.CW) }
        canvas.clipPath(percorsoCerchio)
        canvas.drawCircle(centroLenteX, centroLenteY, raggio, paintLenteSfondo)
        val dstRect = RectF(centroLenteX - raggio, centroLenteY - raggio, centroLenteX + raggio, centroLenteY + raggio)
        canvas.drawBitmap(bmp, srcRect, dstRect, null)
        canvas.restore()

        canvas.drawCircle(centroLenteX, centroLenteY, raggio, paintLenteBordoEsterno)
        canvas.drawCircle(centroLenteX, centroLenteY, raggio, paintLenteBordo)
        canvas.drawLine(centroLenteX - 16f, centroLenteY, centroLenteX + 16f, centroLenteY, paintLenteCrocicchio)
        canvas.drawLine(centroLenteX, centroLenteY - 16f, centroLenteX, centroLenteY + 16f, paintLenteCrocicchio)
    }

    private fun trasforma(p: PointF): PointF {
        val arr = floatArrayOf(p.x, p.y)
        matriceVista.mapPoints(arr)
        return PointF(arr[0], arr[1])
    }

    private fun schermoAImmagine(x: Float, y: Float): PointF {
        val arr = floatArrayOf(x, y)
        matriceVistaInversa.mapPoints(arr)
        return PointF(arr[0], arr[1])
    }

    private fun creaPath(verticiPx: List<PointF>): Path {
        val path = Path()
        verticiPx.map { trasforma(it) }.forEachIndexed { i, p ->
            if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        path.close()
        return path
    }

    /** Indice del punto della lista più vicino al tocco a schermo, entro una soglia comoda col dito; null se nessuno è abbastanza vicino. */
    private fun indicePuntoVicino(lista: List<PointF>, xSchermo: Float, ySchermo: Float): Int? {
        val soglia = 40f * resources.displayMetrics.density
        var migliorIndice: Int? = null
        var migliorDistanza = Float.MAX_VALUE
        lista.forEachIndexed { i, p ->
            val schermo = trasforma(p)
            val d = hypot((schermo.x - xSchermo).toDouble(), (schermo.y - ySchermo).toDouble()).toFloat()
            if (d < soglia && d < migliorDistanza) {
                migliorDistanza = d
                migliorIndice = i
            }
        }
        return migliorIndice
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Il pizzico (zoom) e lo scorrimento a due dita sono sempre attivi, indipendentemente
        // dalla modalità corrente, così si può ingrandire la planimetria anche solo per guardarla meglio.
        rilevatoreZoom.onTouchEvent(event)

        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            // Un secondo dito è appena arrivato: non è più un tocco singolo per inserire/spostare un punto.
            cancellaPressioneLunga()
            panCandidato = false
            panInCorso = false
            annullaTrascinamentoInCorso()
            return true
        }
        if (event.pointerCount > 1) {
            // Durante un gesto a due dita non si inseriscono o spostano punti.
            return true
        }

        val bmp = bitmap
        val lista = listaAttiva
        if (bmp == null || modoTocco == ModoTocco.NESSUNO || lista == null) {
            // Nessuna operazione attiva: un dito scorre la planimetria ingrandita (per guardarla),
            // e solo un tocco "fermo" mostra l'avviso che serve prima scegliere un'operazione.
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x; downY = event.y
                    puntoPanPrecedenteX = event.x; puntoPanPrecedenteY = event.y
                    panInCorso = false
                    pressioneVisualizzazioneUsata = false
                    if (onLocaleTenutoPremutoInVisualizzazione != null && locali.isNotEmpty()) {
                        handlerPressioneLunga.postDelayed(pressioneLungaVisualizzazioneRunnable, RITARDO_CAMBIO_LOCALE_MS)
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    val sogliaSpostamento = 18f * resources.displayMetrics.density
                    if (!panInCorso && hypot((event.x - downX).toDouble(), (event.y - downY).toDouble()) > sogliaSpostamento) {
                        panInCorso = true
                        handlerPressioneLunga.removeCallbacks(pressioneLungaVisualizzazioneRunnable)
                    }
                    if (panInCorso && fattoreZoomCorrente > 1.001f) {
                        matriceVista.postTranslate(event.x - puntoPanPrecedenteX, event.y - puntoPanPrecedenteY)
                        matriceVista.invert(matriceVistaInversa)
                        invalidate()
                    }
                    puntoPanPrecedenteX = event.x; puntoPanPrecedenteY = event.y
                    // Se si sta scorrendo la planimetria ingrandita, il genitore non deve scorrere la pagina.
                    if (panInCorso && fattoreZoomCorrente > 1.001f) parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_UP -> {
                    handlerPressioneLunga.removeCallbacks(pressioneLungaVisualizzazioneRunnable)
                    if (!panInCorso && !pressioneVisualizzazioneUsata && bmp != null && avvisaTapNonConsentito) onTapNonConsentito?.invoke()
                    panInCorso = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    handlerPressioneLunga.removeCallbacks(pressioneLungaVisualizzazioneRunnable)
                    panInCorso = false
                }
            }
            return true
        }

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                panCandidato = false
                panInCorso = false

                if (modalitaRimozioneVertice && modoTocco == ModoTocco.SOLO_TRASCINA) {
                    val indiceDaRimuovere = indicePuntoVicino(lista, event.x, event.y)
                    if (indiceDaRimuovere != null) {
                        if (lista.size > 3) {
                            registraStato()
                            lista.removeAt(indiceDaRimuovere)
                            invalidate()
                            onVerticeRimosso?.invoke()
                        } else {
                            onRimozioneNonConsentita?.invoke()
                        }
                    }
                    indiceTrascinato = null
                    return true
                }

                if (modalitaInserimentoBordo && modoTocco == ModoTocco.SOLO_TRASCINA && lista.size >= 2) {
                    val puntoTocco = schermoAImmagine(event.x, event.y)
                    val inserimento = trovaProiezioneSulBordoPiuVicino(lista, puntoTocco)
                    if (inserimento != null) {
                        val (indiceInserimento, puntoInserito) = inserimento
                        registraStato()
                        lista.add(indiceInserimento, puntoInserito)
                        indiceTrascinato = indiceInserimento
                        trascinatoNuovo = true
                        mostraLente = true
                        puntoLenteImmagine = puntoInserito
                        puntoLenteSchermo = PointF(event.x, event.y)
                        invalidate()
                        return true
                    }
                }

                val indiceVicino = indicePuntoVicino(lista, event.x, event.y)
                if (indiceVicino != null) {
                    // Un vertice già esistente si sposta solo dopo averlo tenuto premuto: così
                    // si può appoggiare il dito e pizzicare/scorrere liberamente per inquadrare
                    // meglio la vista, senza rischiare di spostarlo per sbaglio nel frattempo.
                    indiceCandidatoPressioneLunga = indiceVicino
                    handlerPressioneLunga.postDelayed(pressioneLungaRunnable, RITARDO_PRESSIONE_LUNGA_MS)
                } else if (modoTocco == ModoTocco.AGGIUNGI_E_TRASCINA &&
                    (limiteMassimoPunti == null || lista.size < limiteMassimoPunti!!)
                ) {
                    // Aggiungere un nuovo vertice (tracciamento di un locale nuovo) resta
                    // immediato al tocco: la pressione prolungata serve solo per correggere
                    // un vertice già esistente.
                    val puntoImmagine = schermoAImmagine(event.x, event.y)
                    registraStato()
                    lista.add(puntoImmagine)
                    indiceTrascinato = lista.size - 1
                    trascinatoNuovo = true
                    mostraLente = true
                    puntoLenteImmagine = puntoImmagine
                    puntoLenteSchermo = PointF(event.x, event.y)
                    invalidate()
                } else if (modoTocco == ModoTocco.SOLO_TRASCINA) {
                    // Tocco su un punto vuoto durante la correzione (nessun vertice del locale
                    // corrente lì vicino): può diventare uno scorrimento libero a un dito, per
                    // muoversi su tutta la planimetria e non restare bloccati vicino al solo
                    // poligono in correzione, oppure, se il dito resta fermo abbastanza a lungo
                    // sopra un ALTRO locale già confermato, un cambio del locale in modifica.
                    indiceTrascinato = null
                    panCandidato = true
                    puntoPanPrecedenteX = event.x
                    puntoPanPrecedenteY = event.y
                    if (onLocaleAltroTenutoPremuto != null) {
                        handlerPressioneLunga.postDelayed(pressioneLungaCambioLocaleRunnable, RITARDO_CAMBIO_LOCALE_MS)
                    }
                } else {
                    indiceTrascinato = null
                    return super.onTouchEvent(event)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (indiceTrascinato == null && indiceCandidatoPressioneLunga != null) {
                    // In attesa della pressione prolungata su un vertice esistente: se il dito
                    // si sposta troppo non è un tentativo di trascinarlo ma probabilmente
                    // l'inizio di un pizzico/scorrimento con un secondo dito in arrivo, quindi
                    // si annulla l'attesa senza spostare nulla.
                    val sogliaSpostamento = 18f * resources.displayMetrics.density
                    if (hypot((event.x - downX).toDouble(), (event.y - downY).toDouble()) > sogliaSpostamento) {
                        cancellaPressioneLunga()
                    }
                    return true
                }
                if (indiceTrascinato == null && panCandidato) {
                    val spostX = event.x - puntoPanPrecedenteX
                    val spostY = event.y - puntoPanPrecedenteY
                    if (!panInCorso) {
                        val sogliaSpostamento = 18f * resources.displayMetrics.density
                        if (hypot(spostX.toDouble(), spostY.toDouble()) > sogliaSpostamento) {
                            panInCorso = true
                            cancellaPressioneLunga()
                        }
                    }
                    if (panInCorso) {
                        if (fattoreZoomCorrente > 1.001f) {
                            matriceVista.postTranslate(spostX, spostY)
                            matriceVista.invert(matriceVistaInversa)
                            invalidate()
                        }
                        puntoPanPrecedenteX = event.x
                        puntoPanPrecedenteY = event.y
                    }
                    return true
                }
                val indice = indiceTrascinato ?: return super.onTouchEvent(event)
                val puntoImmagine = schermoAImmagine(event.x, event.y)
                lista[indice] = puntoImmagine
                puntoLenteImmagine = puntoImmagine
                puntoLenteSchermo = PointF(event.x, event.y)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                cancellaPressioneLunga()
                panCandidato = false
                panInCorso = false
                val indice = indiceTrascinato
                val eraNuovo = trascinatoNuovo
                mostraLente = false
                indiceTrascinato = null
                invalidate()
                if (indice != null) {
                    // Vertice preso ma non spostato: nessuna modifica da poter annullare.
                    val inizio = puntoInizioTrascinamento
                    val attuale = lista.getOrNull(indice)
                    if (!eraNuovo && inizio != null && attuale != null && inizio.x == attuale.x && inizio.y == attuale.y) {
                        storiaModifiche.removeLastOrNull()
                        onStoriaCambiata?.invoke()
                    }
                    puntoInizioTrascinamento = null
                    if (eraNuovo) onNuovoPunto?.invoke() else onPuntoSpostato?.invoke()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                cancellaPressioneLunga()
                panCandidato = false
                panInCorso = false
                annullaTrascinamentoInCorso()
            }
            else -> return super.onTouchEvent(event)
        }
        return true
    }

    /** Scaduto il tempo di pressione prolungata su un vertice candidato senza che il dito si sia spostato troppo: lo arma per il trascinamento. */
    private fun avviaTrascinamentoCandidato() {
        val indice = indiceCandidatoPressioneLunga ?: return
        val lista = listaAttiva ?: return
        val punto = lista.getOrNull(indice) ?: return
        indiceCandidatoPressioneLunga = null
        registraStato()
        puntoInizioTrascinamento = PointF(punto.x, punto.y)
        indiceTrascinato = indice
        trascinatoNuovo = false
        mostraLente = true
        puntoLenteImmagine = punto
        puntoLenteSchermo = PointF(downX, downY)
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        invalidate()
    }

    /** Annulla un'eventuale attesa di pressione prolungata ancora in corso (sia per il trascinamento di un vertice sia per il cambio locale), senza toccare un trascinamento già avviato. */
    private fun cancellaPressioneLunga() {
        handlerPressioneLunga.removeCallbacks(pressioneLungaRunnable)
        handlerPressioneLunga.removeCallbacks(pressioneLungaCambioLocaleRunnable)
        handlerPressioneLunga.removeCallbacks(pressioneLungaVisualizzazioneRunnable)
        indiceCandidatoPressioneLunga = null
    }

    /** Scaduta la pressione prolungata su un punto vuoto: se il dito non si è spostato (non è diventato uno scorrimento) e cade dentro un locale già confermato, segnala il cambio locale. */
    private fun tentaCambioLocale() {
        if (!panCandidato || panInCorso) return
        val callback = onLocaleAltroTenutoPremuto ?: return
        val puntoImmagine = schermoAImmagine(puntoPanPrecedenteX, puntoPanPrecedenteY)
        val indiceLocale = locali.indexOfFirst { GeometriaUtils.puntoDentroPoligono(puntoImmagine, it.verticiPx) }
        panCandidato = false
        if (indiceLocale >= 0) {
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            callback(indiceLocale)
        }
    }

    /** Annulla un trascinamento in corso: se era un punto nuovo (aggiunto in coda o inserito sul bordo) non ancora rilasciato, lo rimuove; nasconde la lente. */
    private fun annullaTrascinamentoInCorso() {
        val lista = listaAttiva
        val indice = indiceTrascinato
        if (lista != null && indice != null && trascinatoNuovo && indice < lista.size) {
            lista.removeAt(indice)
            // Il punto nuovo non è stato confermato: non resta nulla da annullare per lui.
            storiaModifiche.removeLastOrNull()
            onStoriaCambiata?.invoke()
        }
        mostraLente = false
        indiceTrascinato = null
        invalidate()
    }

    /**
     * Trova, tra tutti i lati del poligono [lista] (chiuso: l'ultimo punto si considera
     * collegato al primo), il punto più vicino a [puntoTocco] (coordinate nello spazio
     * dell'immagine) che giace su uno di quei lati. Restituisce l'indice in cui inserire il
     * nuovo vertice (così da restare nell'ordine corretto lungo il perimetro) insieme al punto
     * stesso, oppure null se la lista ha meno di 2 punti.
     */
    private fun trovaProiezioneSulBordoPiuVicino(lista: List<PointF>, puntoTocco: PointF): Pair<Int, PointF>? {
        val n = lista.size
        if (n < 2) return null
        var migliorIndice = -1
        var migliorPunto: PointF? = null
        var migliorDistanzaQuadra = Float.MAX_VALUE
        for (i in 0 until n) {
            val a = lista[i]
            val b = lista[(i + 1) % n]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val lunghezzaQuadra = dx * dx + dy * dy
            val t = if (lunghezzaQuadra > 0f) {
                (((puntoTocco.x - a.x) * dx + (puntoTocco.y - a.y) * dy) / lunghezzaQuadra).coerceIn(0f, 1f)
            } else {
                0f
            }
            val px = a.x + t * dx
            val py = a.y + t * dy
            val distX = puntoTocco.x - px
            val distY = puntoTocco.y - py
            val distanzaQuadra = distX * distX + distY * distY
            if (distanzaQuadra < migliorDistanzaQuadra) {
                migliorDistanzaQuadra = distanzaQuadra
                migliorIndice = i + 1
                migliorPunto = PointF(px, py)
            }
        }
        val punto = migliorPunto ?: return null
        return migliorIndice to punto
    }

    companion object {
        private const val RITARDO_PRESSIONE_LUNGA_MS = 450L
        private const val RITARDO_CAMBIO_LOCALE_MS = 2000L
    }
}
