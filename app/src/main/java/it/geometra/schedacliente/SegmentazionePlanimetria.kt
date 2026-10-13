package it.geometra.schedacliente

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Analisi locale (sul telefono, senza AI) del disegno di una planimetria, per ricavare i
 * contorni ESATTI dei vani a partire dai muri disegnati.
 *
 * Funzionamento, dentro un riquadro (la pianta):
 *  1. si ricavano i muri: pixel scuri, tolte le linee sottili (reticolo, quote, scritte, linee
 *     tratteggiate, archi delle porte) con un'"apertura" morfologica;
 *  2. si chiudono i vani delle porte allargando i muri di mezza larghezza di porta;
 *  3. dal punto interno di un vano si riempie lo spazio libero come un "secchiello" fino ai muri;
 *  4. si riallarga la regione della stessa quantità (per tornare a filo dei muri) e se ne traccia
 *     il contorno, semplificato in un poligono con i soli vertici veri (anche a L, irregolare).
 *
 * Se il riempimento esce dal riquadro (vano aperto verso l'esterno, es. loggia o balcone) o ha
 * un'area non plausibile, si restituisce null e si usano i vertici stimati dall'AI.
 *
 * Il riquadro viene ridimensionato a un lato lungo di circa [LATO_LAVORO] pixel per velocità.
 */
class SegmentazionePlanimetria(
    bitmap: Bitmap,
    regione: Rect,
    /** Metri per pixel dell'immagine originale, se la scala è nota (serve a dimensionare porte e aree minime). */
    metriPerPixel: Double?
) {
    companion object {
        const val LATO_LAVORO = 1400
    }

    private val regione = Rect(regione).apply { intersect(0, 0, bitmap.width, bitmap.height) }
    /** Fattore immagine originale → griglia di lavoro. */
    private val f: Double = LATO_LAVORO.toDouble() / max(this.regione.width(), this.regione.height())
    private val w: Int = max(1, (this.regione.width() * f).roundToInt())
    private val h: Int = max(1, (this.regione.height() * f).roundToInt())

    /** Muri (true = muro) dopo l'eliminazione delle linee sottili. */
    private val muri: BooleanArray
    /** Muri allargati per chiudere i vani delle porte. */
    private val muriChiusi: BooleanArray
    /** Raggio di chiusura delle porte, in pixel di lavoro. */
    val raggioPorte: Int
    private val metriPerPixelLavoro: Double? = metriPerPixel?.let { it / f }

    /** Frazione dell'area occupata dai muri: se troppo bassa il disegno non ha muri "pieni" e il metodo non si applica. */
    val frazioneMuri: Double

    init {
        val scalato = Bitmap.createScaledBitmap(
            Bitmap.createBitmap(bitmap, this.regione.left, this.regione.top, this.regione.width(), this.regione.height()),
            w, h, true
        )
        val pixel = IntArray(w * h)
        scalato.getPixels(pixel, 0, w, 0, 0, w, h)
        val scuro = BooleanArray(w * h) { i ->
            val c = pixel[i]
            (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000 < 140
        }
        // Apertura: via linee e scritte più sottili di ~5 px di lavoro (i tramezzi restano: a questa
        // risoluzione sono più spessi).
        muri = togliIsole(dilata(erodi(scuro, 2), 2), (LATO_LAVORO * 0.07).roundToInt())
        frazioneMuri = muri.count { it }.toDouble() / muri.size

        // Mezza larghezza di una porta (0,9 m) se la scala è nota, altrimenti l'1,2% del riquadro.
        raggioPorte = metriPerPixelLavoro?.let { (0.45 / it).roundToInt() }?.coerceIn(4, 60)
            ?: (LATO_LAVORO * 0.012).roundToInt()
        muriChiusi = dilata(muri, raggioPorte)
    }

    /** Il disegno ha muri pieni riconoscibili (altrimenti, es. muri a doppia linea sottile, il metodo non è affidabile). */
    val utilizzabile: Boolean get() = frazioneMuri in 0.01..0.6

    private fun areaMinimaPx(): Int = metriPerPixelLavoro?.let { (1.0 / (it * it)).roundToInt() } ?: (w * h / 600)
    private fun areaMassimaPx(): Int = metriPerPixelLavoro?.let { (400.0 / (it * it)).roundToInt() }?.coerceAtMost(w * h / 2) ?: (w * h / 2)

    /**
     * Contorno del vano che contiene [puntoImmagine] (coordinate dell'immagine originale), oppure
     * null se il vano non è chiuso dai muri o ha un'area non plausibile.
     */
    fun poligonoDaPunto(puntoImmagine: PointF): List<PointF>? {
        if (!utilizzabile) return null
        val sx = ((puntoImmagine.x - regione.left) * f).roundToInt()
        val sy = ((puntoImmagine.y - regione.top) * f).roundToInt()
        val seme = puntoLiberoVicino(sx, sy, raggioPorte * 2) ?: return null
        val nucleo = riempi(seme.first, seme.second) ?: return null
        return poligonoDaNucleo(nucleo)
    }

    /**
     * Spazi chiusi dai muri dentro il riquadro, non toccati dai poligoni [gia] (coordinate immagine),
     * con area plausibile da vano: servono a non lasciar fuori vani evidenti non individuati.
     */
    fun vaniChiusiMancanti(gia: List<List<PointF>>): List<List<PointF>> {
        if (!utilizzabile) return emptyList()
        val visitato = BooleanArray(w * h)
        // Segna come già coperti i pixel interni ai poligoni esistenti (campionando).
        val coperto = BooleanArray(w * h)
        gia.forEach { pol ->
            val polLavoro = pol.map { PointF(((it.x - regione.left) * f).toFloat(), ((it.y - regione.top) * f).toFloat()) }
            val minX = polLavoro.minOf { it.x }.toInt().coerceIn(0, w - 1)
            val maxX = polLavoro.maxOf { it.x }.toInt().coerceIn(0, w - 1)
            val minY = polLavoro.minOf { it.y }.toInt().coerceIn(0, h - 1)
            val maxY = polLavoro.maxOf { it.y }.toInt().coerceIn(0, h - 1)
            for (y in minY..maxY) for (x in minX..maxX) {
                if (GeometriaUtils.puntoDentroPoligono(PointF(x.toFloat(), y.toFloat()), polLavoro)) coperto[y * w + x] = true
            }
        }
        val risultati = ArrayList<List<PointF>>()
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (visitato[i] || muriChiusi[i]) continue
            val componente = componente(x, y, visitato)
            val area = componente.size
            if (area < areaMinimaPx() || area > areaMassimaPx()) continue
            if (componente.any { val px = it % w; val py = it / w; px == 0 || py == 0 || px == w - 1 || py == h - 1 }) continue
            val giaCoperti = componente.count { coperto[it] }
            if (giaCoperti > area * 0.3) continue
            val nucleo = BooleanArray(w * h).also { m -> componente.forEach { m[it] = true } }
            poligonoDaNucleo(nucleo)?.let { risultati += it }
        }
        return risultati
    }

    // ---------------------------------------------------------------------

    private fun poligonoDaNucleo(nucleo: BooleanArray): List<PointF>? {
        // Si torna a filo dei muri: crescita geodetica di raggioPorte dentro lo spazio non murato.
        // Arrotondamento leggero: toglie le punte lasciate da stipiti e battute delle porte.
        val aperta = dilata(erodi(cresciGeodetico(nucleo, raggioPorte), 3), 3).let { r ->
            BooleanArray(w * h) { r[it] && !muri[it] }
        }
        // Chiusura (elemento quadrato: gli angoli retti, anche rientranti, restano netti): riempie le
        // tacche strette lasciate da stipiti e mozziconi di tramezzo che sporgono nel vano.
        val k = max(3, (raggioPorte * 0.6).roundToInt())
        val regioneVano = erodi(dilata(aperta, k), k)
        val contorno = tracciaContorno(regioneVano) ?: return null
        val tolleranza = max(2.0, raggioPorte * 0.35)
        val semplificato = douglasPeucker(contorno, tolleranza)
        if (semplificato.size < 3) return null
        return semplificato.map { PointF((it.x / f + regione.left).toFloat(), (it.y / f + regione.top).toFloat()) }
    }

    /**
     * Toglie dai muri le "isole" piccole non collegate alla rete dei muri: lettere delle scritte
     * (es. "LOGGIA", "CUCINA mq 12"), simboli, numeri, che altrimenti spezzerebbero un vano in due.
     */
    private fun togliIsole(m: BooleanArray, latoMinimo: Int): BooleanArray {
        val out = m.copyOf()
        val visto = BooleanArray(w * h)
        val coda = IntArray(w * h)
        for (inizio in 0 until w * h) {
            if (!m[inizio] || visto[inizio]) continue
            var testa = 0; var fine = 0
            coda[fine++] = inizio; visto[inizio] = true
            var minX = w; var maxX = 0; var minY = h; var maxY = 0
            while (testa < fine) {
                val i = coda[testa++]
                val x = i % w; val y = i / w
                minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx; val ny = y + dy
                    if (nx !in 0 until w || ny !in 0 until h) continue
                    val j = ny * w + nx
                    if (m[j] && !visto[j]) { visto[j] = true; coda[fine++] = j }
                }
            }
            if (max(maxX - minX, maxY - minY) < latoMinimo) {
                for (k in 0 until fine) out[coda[k]] = false
            }
        }
        return out
    }

    private fun puntoLiberoVicino(x0: Int, y0: Int, raggio: Int): Pair<Int, Int>? {
        if (x0 !in 0 until w || y0 !in 0 until h) return null
        if (!muriChiusi[y0 * w + x0]) return x0 to y0
        for (r in 1..raggio) {
            for (dy in -r..r) for (dx in -r..r) {
                if (max(abs(dx), abs(dy)) != r) continue
                val x = x0 + dx; val y = y0 + dy
                if (x in 0 until w && y in 0 until h && !muriChiusi[y * w + x]) return x to y
            }
        }
        return null
    }

    /** Riempimento dallo spazio libero (muri chiusi); null se esce dal riquadro o area non plausibile. */
    private fun riempi(x0: Int, y0: Int): BooleanArray? {
        val visitato = BooleanArray(w * h)
        val componente = componente(x0, y0, visitato, limite = areaMassimaPx() + 1)
        if (componente.size > areaMassimaPx() || componente.size < areaMinimaPx()) return null
        if (componente.any { val px = it % w; val py = it / w; px == 0 || py == 0 || px == w - 1 || py == h - 1 }) return null
        return BooleanArray(w * h).also { m -> componente.forEach { m[it] = true } }
    }

    private fun componente(x0: Int, y0: Int, visitato: BooleanArray, limite: Int = Int.MAX_VALUE): IntArray {
        val coda = IntArray(w * h)
        var testa = 0; var fine = 0
        val inizio = y0 * w + x0
        visitato[inizio] = true
        coda[fine++] = inizio
        while (testa < fine && fine <= limite) {
            val i = coda[testa++]
            val x = i % w; val y = i / w
            if (x > 0) { val j = i - 1; if (!visitato[j] && !muriChiusi[j]) { visitato[j] = true; coda[fine++] = j } }
            if (x < w - 1) { val j = i + 1; if (!visitato[j] && !muriChiusi[j]) { visitato[j] = true; coda[fine++] = j } }
            if (y > 0) { val j = i - w; if (!visitato[j] && !muriChiusi[j]) { visitato[j] = true; coda[fine++] = j } }
            if (y < h - 1) { val j = i + w; if (!visitato[j] && !muriChiusi[j]) { visitato[j] = true; coda[fine++] = j } }
        }
        return coda.copyOf(fine)
    }

    private fun cresciGeodetico(nucleo: BooleanArray, passi: Int): BooleanArray {
        val risultato = nucleo.copyOf()
        var fronte = (0 until w * h).filter { nucleo[it] }.toIntArray()
        repeat(passi) {
            val nuovo = ArrayList<Int>()
            for (i in fronte) {
                val x = i % w; val y = i / w
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx; val ny = y + dy
                    if (nx !in 0 until w || ny !in 0 until h) continue
                    val j = ny * w + nx
                    if (!risultato[j] && !muri[j]) { risultato[j] = true; nuovo += j }
                }
            }
            fronte = nuovo.toIntArray()
        }
        return risultato
    }

    /** Contorno esterno (Moore neighbour tracing) come sequenza di punti, in pixel di lavoro. */
    private fun tracciaContorno(m: BooleanArray): List<PointF>? {
        val inizio = m.indexOfFirst { it }
        if (inizio < 0) return null
        fun pieno(x: Int, y: Int) = x in 0 until w && y in 0 until h && m[y * w + x]
        // Direzioni in senso orario a partire da ovest.
        val dx = intArrayOf(-1, -1, 0, 1, 1, 1, 0, -1)
        val dy = intArrayOf(0, -1, -1, -1, 0, 1, 1, 1)
        val sx = inizio % w; val sy = inizio / w
        val punti = ArrayList<PointF>()
        var cx = sx; var cy = sy
        var dirIngresso = 0 // si arriva "da ovest" (pixel a sinistra vuoto, perché è il primo in scansione)
        var passi = 0
        val massimo = 4 * (w + h) * 4
        do {
            punti += PointF(cx.toFloat(), cy.toFloat())
            var trovato = false
            for (k in 0 until 8) {
                val d = (dirIngresso + k) % 8
                val nx = cx + dx[d]; val ny = cy + dy[d]
                if (pieno(nx, ny)) {
                    cx = nx; cy = ny
                    dirIngresso = (d + 6) % 8 // riparte dalla direzione "dietro a sinistra"
                    trovato = true
                    break
                }
            }
            if (!trovato) break
            passi++
        } while ((cx != sx || cy != sy) && passi < massimo)
        return if (punti.size >= 3) punti else null
    }

    private fun douglasPeucker(punti: List<PointF>, eps: Double): List<PointF> {
        if (punti.size < 4) return punti
        // Contorno chiuso: si divide nel punto più lontano dal primo.
        val primo = punti[0]
        val lontano = punti.indices.maxByOrNull { hypot((punti[it].x - primo.x).toDouble(), (punti[it].y - primo.y).toDouble()) } ?: return punti
        val a = semplifica(punti.subList(0, lontano + 1), eps)
        val b = semplifica(punti.subList(lontano, punti.size) + listOf(primo), eps)
        return (a.dropLast(1) + b.dropLast(1))
    }

    private fun semplifica(p: List<PointF>, eps: Double): List<PointF> {
        if (p.size < 3) return p
        val a = p.first(); val b = p.last()
        var indice = -1; var distMax = 0.0
        val lx = (b.x - a.x).toDouble(); val ly = (b.y - a.y).toDouble()
        val l = sqrt(lx * lx + ly * ly)
        for (i in 1 until p.size - 1) {
            val d = if (l < 1e-9) hypot((p[i].x - a.x).toDouble(), (p[i].y - a.y).toDouble())
            else abs(ly * (p[i].x - a.x) - lx * (p[i].y - a.y)) / l
            if (d > distMax) { distMax = d; indice = i }
        }
        return if (distMax > eps) {
            semplifica(p.subList(0, indice + 1), eps).dropLast(1) + semplifica(p.subList(indice, p.size), eps)
        } else listOf(a, b)
    }

    // --- morfologia su maschere booleane con finestra quadrata (separabile, somme prefisse) ---

    private fun dilata(m: BooleanArray, r: Int): BooleanArray = filtro(m, r, qualsiasi = true)
    private fun erodi(m: BooleanArray, r: Int): BooleanArray = filtro(m, r, qualsiasi = false)

    private fun filtro(m: BooleanArray, r: Int, qualsiasi: Boolean): BooleanArray {
        if (r <= 0) return m.copyOf()
        val temp = BooleanArray(w * h)
        val riga = IntArray(w + 1)
        for (y in 0 until h) {
            for (x in 0 until w) riga[x + 1] = riga[x] + if (m[y * w + x]) 1 else 0
            for (x in 0 until w) {
                val a = max(0, x - r); val b = min(w - 1, x + r)
                val n = riga[b + 1] - riga[a]
                temp[y * w + x] = if (qualsiasi) n > 0 else n == (b - a + 1)
            }
        }
        val out = BooleanArray(w * h)
        val col = IntArray(h + 1)
        for (x in 0 until w) {
            for (y in 0 until h) col[y + 1] = col[y] + if (temp[y * w + x]) 1 else 0
            for (y in 0 until h) {
                val a = max(0, y - r); val b = min(h - 1, y + r)
                val n = col[b + 1] - col[a]
                out[y * w + x] = if (qualsiasi) n > 0 else n == (b - a + 1)
            }
        }
        return out
    }
}

/**
 * Individua sull'immagine intera le zone con muri pieni (le piante), scartando reticolo, cornici
 * e scritte (linee sottili). Serve a inquadrare con precisione ogni pianta prima di mandarla
 * all'AI, invece di fidarsi solo del riquadro stimato dall'AI sull'immagine rimpicciolita.
 */
object RilevatoreZoneDisegno {

    fun zone(bitmap: Bitmap): List<Rect> {
        val lato = 1200.0
        val f = lato / max(bitmap.width, bitmap.height)
        val w = max(1, (bitmap.width * f).roundToInt())
        val h = max(1, (bitmap.height * f).roundToInt())
        val piccolo = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val pixel = IntArray(w * h)
        piccolo.getPixels(pixel, 0, w, 0, 0, w, h)
        val scuro = BooleanArray(w * h) { i ->
            val c = pixel[i]
            (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000 < 120
        }
        val muri = morfo(morfo(scuro, w, h, 1, false), w, h, 1, true)
        val unito = morfo(muri, w, h, (lato * 0.015).roundToInt(), true)
        val visitato = BooleanArray(w * h)
        val zone = ArrayList<Rect>()
        for (i in 0 until w * h) {
            if (!unito[i] || visitato[i]) continue
            var minX = w; var minY = h; var maxX = 0; var maxY = 0; var nMuri = 0
            val coda = ArrayDeque<Int>(); coda.add(i); visitato[i] = true
            while (coda.isNotEmpty()) {
                val j = coda.removeFirst()
                val x = j % w; val y = j / w
                if (muri[j]) nMuri++
                minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
                for ((nx, ny) in arrayOf(x - 1 to y, x + 1 to y, x to y - 1, x to y + 1)) {
                    if (nx !in 0 until w || ny !in 0 until h) continue
                    val k = ny * w + nx
                    if (unito[k] && !visitato[k]) { visitato[k] = true; coda.add(k) }
                }
            }
            val bw = maxX - minX + 1; val bh = maxY - minY + 1
            // Una pianta: abbastanza grande e con muri "pieni" in quantità, ma non tutta la pagina (cornici).
            if (bw * bh < w * h * 0.002 || nMuri < 150) continue
            if (bw > w * 0.95 && bh > h * 0.95) continue
            zone += Rect((minX / f).toInt(), (minY / f).toInt(), ((maxX + 1) / f).toInt(), ((maxY + 1) / f).toInt())
        }
        return zone
    }

    /** Aggancia il riquadro stimato dall'AI alle zone di disegno che interseca (lo allarga o stringe sul disegno vero). */
    fun agganciaRiquadro(stima: Rect, zone: List<Rect>, margine: Int): Rect {
        val coinvolte = zone.filter { z ->
            val inter = Rect(z)
            inter.intersect(stima) && inter.width().toLong() * inter.height() > 0.25 * z.width().toLong() * z.height()
        }
        if (coinvolte.isEmpty()) return stima
        val r = Rect(coinvolte[0])
        coinvolte.drop(1).forEach { r.union(it) }
        r.inset(-margine, -margine)
        return r
    }

    private fun morfo(m: BooleanArray, w: Int, h: Int, r: Int, qualsiasi: Boolean): BooleanArray {
        val temp = BooleanArray(w * h)
        val riga = IntArray(w + 1)
        for (y in 0 until h) {
            for (x in 0 until w) riga[x + 1] = riga[x] + if (m[y * w + x]) 1 else 0
            for (x in 0 until w) {
                val a = max(0, x - r); val b = min(w - 1, x + r)
                val n = riga[b + 1] - riga[a]
                temp[y * w + x] = if (qualsiasi) n > 0 else n == (b - a + 1)
            }
        }
        val out = BooleanArray(w * h)
        val col = IntArray(h + 1)
        for (x in 0 until w) {
            for (y in 0 until h) col[y + 1] = col[y] + if (temp[y * w + x]) 1 else 0
            for (y in 0 until h) {
                val a = max(0, y - r); val b = min(h - 1, y + r)
                val n = col[b + 1] - col[a]
                out[y * w + x] = if (qualsiasi) n > 0 else n == (b - a + 1)
            }
        }
        return out
    }
}
