package it.geometra.schedacliente

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Rileva la scala delle planimetrie catastali scaricate dall'Agenzia delle Entrate (SISTER)
 * SENZA AI, misurando direttamente i pixel del disegno blu che il servizio aggiunge attorno
 * al foglio:
 *  - una cornice blu sottile lungo i bordi;
 *  - tacche blu perpendicolari alla cornice ogni 10 m (il "reticolo"/parametri);
 *  - una barra blu più spessa, sovrapposta alla cornice, con scritto accanto "10 metri".
 *
 * Esempio verificato su un PLN reale: barra lunga 141,73 pt (= 50 mm = 10 m a 1:200), tacche
 * distanziate esattamente 141,73 pt. La barra si riconosce perché è circa tre volte più
 * spessa della cornice su cui è disegnata; le tacche perché sporgono molto dalla cornice.
 *
 * Si analizzano solo quattro fasce lungo i bordi dell'immagine (dove stanno cornice, tacche e
 * barra), quindi è veloce anche su immagini grandi. Funziona su PDF renderizzati e anche su
 * screenshot/scansioni a colori del PDF, purché il blu sia riconoscibile.
 */
object RilevatoreScalaBlu {

    enum class Fonte { BARRA, TACCHE }

    class Risultato(
        /** Metri reali per pixel dell'immagine. */
        val metriPerPixel: Double,
        val fonte: Fonte,
        /** Estremi del segmento usato (barra, oppure prima e ultima tacca), in pixel dell'immagine. */
        val p1: PointF,
        val p2: PointF,
        /** Metri reali tra p1 e p2. */
        val metriTraPunti: Double,
        /** Zona attorno alla barra (per leggere con l'OCR il valore scritto accanto); null se da tacche. */
        val zonaEtichetta: Rect?,
        val numeroTacche: Int,
        val descrizione: String
    )

    /** Metri rappresentati da barra e intervallo tra tacche, se non si legge diversamente. */
    const val METRI_STANDARD = 10.0

    /** Frazione di larghezza/altezza esaminata lungo ogni bordo. */
    private const val FASCIA = 0.12

    fun rileva(bitmap: Bitmap, metriBarra: Double = METRI_STANDARD): Risultato? {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 200 || h < 200) return null
        val larghezzaFasciaV = (w * FASCIA).toInt()
        val altezzaFasciaO = (h * FASCIA).toInt()

        val analisi = listOfNotNull(
            analizzaFascia(bitmap, Rect(w - larghezzaFasciaV, 0, w, h), verticale = true),
            analizzaFascia(bitmap, Rect(0, 0, larghezzaFasciaV, h), verticale = true),
            analizzaFascia(bitmap, Rect(0, h - altezzaFasciaO, w, h), verticale = false),
            analizzaFascia(bitmap, Rect(0, 0, w, altezzaFasciaO), verticale = false)
        )
        if (analisi.isEmpty()) return null

        // Passo delle tacche: mediana delle stime di tutti i bordi che ne hanno abbastanza.
        val stimeTacche = analisi.mapNotNull { it.passoTacche }
        val passoTacche = stimeTacche.sorted().getOrNull(stimeTacche.size / 2)

        // La linea più lunga con tacche regolari (almeno 3): su di essa si prendono la prima e l'ultima
        // tacca, cioè i due segni dei parametri più lontani tra loro.
        val miglioreTacche = analisi.filter { it.passoTacche != null && it.tacche.size >= 3 }
            .maxByOrNull { it.tacche.last() - it.tacche.first() }

        // 1) Barra blu: la scala più "dichiarata" (c'è scritto accanto quanti metri sono).
        val conBarra = analisi.filter { it.barra != null }.maxByOrNull { it.barra!!.lunghezza }
        if (conBarra != null) {
            val barra = conBarra.barra!!
            // Se le tacche ci sono e sono in netto disaccordo con la barra, la barra è sospetta.
            val coerente = passoTacche == null || abs(barra.lunghezza - passoTacche) / passoTacche < 0.04
            if (coerente) {
                val nTacche = analisi.sumOf { it.tacche.size }
                // Se le tacche confermano la barra, il loro passo misurato su tutto il bordo (molti
                // intervalli) è più preciso della sola barra: si usa quello.
                val pixelPerIntervallo = passoTacche ?: barra.lunghezza
                // Come tratto da mostrare (v3.81): non la sola barra di 10 m ma, se ci sono, le due tacche
                // più lontane sulla linea più lunga; la scala resta quella già calcolata.
                var mostraP1 = barra.p1; var mostraP2 = barra.p2; var mostraMetri = metriBarra
                if (miglioreTacche != null && passoTacche != null) {
                    val n = ((miglioreTacche.tacche.last() - miglioreTacche.tacche.first()) / passoTacche).roundToInt()
                    if (n >= 1) {
                        val (a, b) = estremiTacche(miglioreTacche)
                        mostraP1 = a; mostraP2 = b; mostraMetri = n * metriBarra
                    }
                }
                return Risultato(
                    metriPerPixel = metriBarra / pixelPerIntervallo,
                    fonte = Fonte.BARRA,
                    p1 = mostraP1, p2 = mostraP2,
                    metriTraPunti = mostraMetri,
                    zonaEtichetta = barra.zonaEtichetta,
                    numeroTacche = nTacche,
                    descrizione = "barra blu di scala sul bordo del foglio (${barra.lunghezza.roundToInt()} px = ${formatta(metriBarra)} m)" +
                        if (passoTacche != null) ", confermata dalle tacche del reticolo" else ""
                )
            }
        }

        // 2) Solo tacche del reticolo (una ogni 10 m): si usa la coppia più lontana per precisione.
        if (miglioreTacche == null) return null
        val passo = miglioreTacche.passoTacche!!
        val prima = miglioreTacche.tacche.first()
        val ultima = miglioreTacche.tacche.last()
        val intervalli = ((ultima - prima) / passo).roundToInt().coerceAtLeast(1)
        val distanzaPx = ultima - prima
        val metri = intervalli * metriBarra
        val (p1, p2) = estremiTacche(miglioreTacche)
        return Risultato(
            metriPerPixel = metri / distanzaPx,
            fonte = Fonte.TACCHE,
            p1 = p1, p2 = p2,
            metriTraPunti = metri,
            zonaEtichetta = null,
            numeroTacche = miglioreTacche.tacche.size,
            descrizione = "tacche blu del reticolo sul bordo del foglio ($intervalli intervalli da ${formatta(metriBarra)} m)"
        )
    }

    // ---------------------------------------------------------------------

    /** Prima e ultima tacca della linea, come punti dell'immagine (sulla linea di cornice). */
    private fun estremiTacche(a: AnalisiFascia): Pair<PointF, PointF> {
        val prima = a.tacche.first(); val ultima = a.tacche.last()
        return if (a.verticale) PointF(a.posizioneLinea, prima) to PointF(a.posizioneLinea, ultima)
        else PointF(prima, a.posizioneLinea) to PointF(ultima, a.posizioneLinea)
    }

    private class Barra(val p1: PointF, val p2: PointF, val lunghezza: Float, val zonaEtichetta: Rect)

    private class AnalisiFascia(
        val verticale: Boolean,
        /** Coordinata della linea di cornice (x per le linee verticali, y per le orizzontali). */
        val posizioneLinea: Float,
        val barra: Barra?,
        /** Posizioni (centri) delle tacche lungo la linea, ordinate. */
        val tacche: List<Float>,
        val passoTacche: Float?
    )

    /** Quanto un pixel è "blu del disegno" (0..1): blu acceso con rosso e verde bassi, anche sfumato. */
    private fun blu(colore: Int): Float {
        val r = Color.red(colore)
        val g = Color.green(colore)
        val b = Color.blue(colore)
        val differenza = b - maxOf(r, g)
        if (b < 140 || differenza < 40) return 0f
        return (differenza / 255f).coerceAtMost(1f)
    }

    private fun analizzaFascia(bitmap: Bitmap, zona: Rect, verticale: Boolean): AnalisiFascia? {
        val larghezza = zona.width()
        val altezza = zona.height()
        val pixel = IntArray(larghezza * altezza)
        bitmap.getPixels(pixel, 0, larghezza, zona.left, zona.top, larghezza, altezza)

        // Lungo = direzione della linea di cornice; trasversale = perpendicolare.
        val lungo = if (verticale) altezza else larghezza
        val trasv = if (verticale) larghezza else altezza
        fun valore(l: Int, t: Int): Float =
            blu(if (verticale) pixel[l * larghezza + t] else pixel[t * larghezza + l])

        // 1) La cornice: la "colonna" trasversale con più blu lungo tutta la fascia.
        val somme = FloatArray(trasv)
        for (l in 0 until lungo) for (t in 0 until trasv) somme[t] += valore(l, t)
        val centro = somme.indices.maxByOrNull { somme[it] } ?: return null
        if (somme[centro] < lungo * 0.3f) return null // nessuna linea blu lunga su questo bordo

        // 2) Per ogni posizione lungo la linea: spessore (copertura blu) ed estensione (pixel blu
        //    contigui) del tratto blu che attraversa la cornice.
        val copertura = FloatArray(lungo)
        val estensione = IntArray(lungo)
        // true se il tratto blu arriva fino al limite della fascia: non è una tacca ma una linea
        // della cornice che prosegue (tipico degli angoli, dove la cornice gira ad angolo retto).
        val finePerBordo = BooleanArray(lungo)
        for (l in 0 until lungo) {
            var partenza = -1
            for (d in intArrayOf(0, -1, 1, -2, 2)) {
                val t = centro + d
                if (t in 0 until trasv && valore(l, t) > 0f) { partenza = t; break }
            }
            if (partenza < 0) continue
            var a = partenza
            while (a - 1 >= 0 && valore(l, a - 1) > 0f) a--
            var z = partenza
            while (z + 1 < trasv && valore(l, z + 1) > 0f) z++
            var somma = 0f
            for (t in a..z) somma += valore(l, t)
            copertura[l] = somma
            estensione[l] = z - a + 1
            finePerBordo[l] = a == 0 || z == trasv - 1
        }
        val presenti = (0 until lungo).filter { copertura[it] > 0f }
        if (presenti.size < lungo * 0.3) return null
        val coperturaBase = presenti.map { copertura[it] }.sorted()[presenti.size / 2]
        val estensioneBase = presenti.map { estensione[it] }.sorted()[presenti.size / 2]

        // 3) Tacche: punti in cui il blu sporge molto dalla cornice (segmenti perpendicolari).
        val sogliaTacca = maxOf(5, estensioneBase * 4)
        val candidate = ArrayList<Pair<Float, Int>>() // (posizione, lunghezza della tacca in pixel)
        var inizioTacca = -1
        for (l in 0..lungo) {
            // Gli angoli della cornice non sono tacche del reticolo (v3.81): altrimenti il tratto di
            // scala andrebbe da un angolo all'altro invece che dalla prima all'ultima tacca.
            val eTacca = l < lungo && estensione[l] >= sogliaTacca && !finePerBordo[l]
            if (eTacca && inizioTacca < 0) inizioTacca = l
            if (!eTacca && inizioTacca >= 0) {
                // Le tacche sono sottili: un gruppo troppo lungo non è una tacca (es. scritte).
                if (l - inizioTacca <= maxOf(6, lungo / 150)) {
                    candidate.add(((inizioTacca + l - 1) / 2f) to (inizioTacca until l).maxOf { estensione[it] })
                }
                inizioTacca = -1
            }
        }
        // Un angolo della cornice (la linea che gira ad angolo retto) sporge molto più delle tacche
        // vere, anche quando l'antialiasing gli accorcia un po' il bordo: si scarta ciò che è
        // più di 2,5 volte la lunghezza tipica (mediana) delle tacche.
        val lunghezzaTipica = candidate.map { it.second }.sorted().let { if (it.isEmpty()) 0 else it[it.size / 2] }
        val tacche = ArrayList<Float>(candidate.filter { lunghezzaTipica == 0 || it.second <= lunghezzaTipica * 2.5f }.map { it.first })
        val passoTacche = passoCoerente(tacche)

        // 4) Barra: tratto lungo in cui la cornice è nettamente più spessa (ma non sporge come una tacca).
        val sogliaBarra = maxOf(coperturaBase * 1.4f, coperturaBase + 0.45f)
        val maxEstensioneBarra = maxOf(estensioneBase * 3 + 3, sogliaTacca - 1)
        var migliorInizio = -1
        var migliorFine = -1
        var inizio = -1
        var ultimoBuono = -1
        var buchi = 0
        for (l in 0 until lungo) {
            val spessa = copertura[l] >= sogliaBarra && estensione[l] <= maxEstensioneBarra
            val tacca = estensione[l] >= sogliaTacca // agli estremi della barra c'è spesso una tacca
            if (spessa || (tacca && inizio >= 0)) {
                if (inizio < 0) inizio = l
                ultimoBuono = l
                buchi = 0
            } else if (inizio >= 0) {
                buchi++
                if (buchi > 3) {
                    if (ultimoBuono - inizio > migliorFine - migliorInizio) { migliorInizio = inizio; migliorFine = ultimoBuono }
                    inizio = -1; buchi = 0
                }
            }
        }
        if (inizio >= 0 && ultimoBuono - inizio > migliorFine - migliorInizio) { migliorInizio = inizio; migliorFine = ultimoBuono }

        val posizioneLinea = (if (verticale) zona.left else zona.top) + centro.toFloat()
        val offsetLungo = if (verticale) zona.top else zona.left
        var barra: Barra? = null
        var migliorInizioPreciso = 0f
        var migliorFinePreciso = 0f
        if (migliorInizio >= 0) {
            // Estremi precisi: centro della tacca all'estremo se c'è (nei PLN la barra va da tacca
            // a tacca), altrimenti il punto in cui lo spessore passa a metà tra cornice e barra.
            val coperturaBarra = (migliorInizio..migliorFine)
                .filter { estensione[it] < sogliaTacca }.map { copertura[it] }.sorted()
                .let { if (it.isEmpty()) sogliaBarra else it[it.size / 2] }
            val meta = (coperturaBase + coperturaBarra) / 2f
            fun bordo(da: Int, verso: Int): Float {
                tacche.firstOrNull { abs(it - da) <= 4f }?.let { return it }
                // Dal primo punto "spesso" si va verso l'esterno fino a scendere sotto la metà.
                var l = da
                while (l + verso in 0 until lungo && copertura[l + verso] >= meta) l += verso
                val fuori = l + verso
                if (fuori !in 0 until lungo) return l.toFloat()
                val dentro = copertura[l]
                val esterno = copertura[fuori]
                val frazione = if (dentro > esterno) ((dentro - meta) / (dentro - esterno)).coerceIn(0f, 1f) else 0.5f
                // Bordo del pixel: metà strada tra i centri, spostata della frazione interpolata.
                return l + verso * frazione
            }
            val inizioPreciso = bordo(migliorInizio, -1)
            val finePreciso = bordo(migliorFine, +1)
            migliorInizioPreciso = inizioPreciso
            migliorFinePreciso = finePreciso
            val lunghezza = finePreciso - inizioPreciso
            // Una barra di scala è un tratto evidente ma non lunghissimo del bordo.
            if (lunghezza >= lungo * 0.03f && lunghezza <= lungo * 0.6f) {
                val a = offsetLungo + migliorInizioPreciso
                val b = offsetLungo + migliorFinePreciso
                val margine = (lunghezza * 0.25f).roundToInt()
                val zonaEtichetta = if (verticale)
                    Rect((posizioneLinea - margine).toInt().coerceAtLeast(0), a.toInt(), (posizioneLinea + margine).toInt().coerceAtMost(bitmap.width), b.toInt())
                else Rect(a.toInt(), (posizioneLinea - margine).toInt().coerceAtLeast(0), b.toInt(), (posizioneLinea + margine).toInt().coerceAtMost(bitmap.height))
                barra = if (verticale) Barra(PointF(posizioneLinea, a), PointF(posizioneLinea, b), lunghezza, zonaEtichetta)
                else Barra(PointF(a, posizioneLinea), PointF(b, posizioneLinea), lunghezza, zonaEtichetta)
            }
        }

        return AnalisiFascia(verticale, posizioneLinea, barra, tacche.map { it + offsetLungo }, passoTacche)
    }

    /**
     * Passo regolare delle tacche: la mediana delle distanze tra tacche consecutive, accettata
     * solo se la maggior parte delle distanze è un multiplo intero di essa (entro il 3%).
     * Se coerente, restituisce il passo medio misurato tra la prima e l'ultima tacca (più preciso).
     */
    private fun passoCoerente(tacche: List<Float>): Float? {
        if (tacche.size < 3) return null
        val distanze = tacche.zipWithNext { a, b -> b - a }.filter { it > 3f }
        if (distanze.size < 2) return null
        val mediana = distanze.sorted()[distanze.size / 2]
        val coerenti = distanze.count { d ->
            val multiplo = (d / mediana).roundToInt()
            multiplo >= 1 && abs(d - multiplo * mediana) / (multiplo * mediana) < 0.03f
        }
        if (coerenti < distanze.size * 0.7f) return null
        val intervalli = ((tacche.last() - tacche.first()) / mediana).roundToInt()
        return if (intervalli > 0) (tacche.last() - tacche.first()) / intervalli else null
    }

    private fun formatta(metri: Double) =
        if (metri == metri.toLong().toDouble()) metri.toLong().toString() else String.format(java.util.Locale.ITALY, "%.1f", metri)
}
