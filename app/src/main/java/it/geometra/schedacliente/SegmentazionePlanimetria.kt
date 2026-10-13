package it.geometra.schedacliente

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
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
    metriPerPixel: Double?,
    /** Modello dei muri: AUTO lo sceglie dal disegno; PIENI/LINEE lo impongono (vedi [AnalizzatoreVani]). */
    private val tipo: TipoMuri = TipoMuri.AUTO
) {
    enum class TipoMuri { AUTO, PIENI, LINEE }

    companion object {
        const val LATO_LAVORO = 1400
        /** Sopra questa quota d'inchiostro in tratti spessi il disegno si tratta a muri pieni. */
        const val SOGLIA_QUOTA_PIENI = 0.35
        /** Solo per i test: stampa perché un riempimento è stato scartato. */
        @JvmField var DEBUG = false
    }

    private val regione = Rect(regione).apply { intersect(0, 0, bitmap.width, bitmap.height) }
    /** Fattore immagine originale → griglia di lavoro. */
    private val f: Double = LATO_LAVORO.toDouble() / max(this.regione.width(), this.regione.height())
    private val w: Int = max(1, (this.regione.width() * f).roundToInt())
    private val h: Int = max(1, (this.regione.height() * f).roundToInt())

    /** Muri (true = muro) dopo l'eliminazione delle linee sottili. */
    private val muri: BooleanArray
    /** Muri "veri" (senza le soglie e le finestre aggiunte): per distinguere una parete da un vano di porta. */
    private val muriBase: BooleanArray
    /** true = muri pieni (campiti); false = muri a doppia linea sottile. */
    private val muriPieniRiconosciuti: Boolean
    /** Muri allargati per chiudere i vani delle porte (raggio base [raggioPorte]). */
    private val muriChiusi: BooleanArray
    /** Muri chiusi con raggi più grandi (portefinestre, aperture ampie), calcolati solo se servono. */
    private val muriChiusiPerRaggio = HashMap<Int, BooleanArray>()
    /** Raggio di chiusura delle porte, in pixel di lavoro. */
    val raggioPorte: Int
    private val metriPerPixelLavoro: Double? = metriPerPixel?.let { it / f }

    /** Frazione dell'area occupata dai muri: se troppo bassa il disegno non ha muri "pieni" e il metodo non si applica. */
    val frazioneMuri: Double
    /** true se nel riquadro è stato riconosciuto (e tolto) un reticolo regolare tipo carta millimetrata. */
    var reticoloTolto = false
        private set

    /** Quota dell'inchiostro in tratti spessi (0..1): alta = muri campiti, bassa = muri a linee sottili. */
    val quotaInchiostroSpesso: Double


    init {
        val scalato = Bitmap.createScaledBitmap(
            Bitmap.createBitmap(bitmap, this.regione.left, this.regione.top, this.regione.width(), this.regione.height()),
            w, h, true
        )
        val pixel = IntArray(w * h)
        scalato.getPixels(pixel, 0, w, 0, 0, w, h)
        val scuroGrezzo = BooleanArray(w * h) { i ->
            val c = pixel[i]
            (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000 < 140
        }
        // Carta millimetrata / reticolo regolare stampato in nero: si toglie prima di tutto.
        val scuro = togliReticolo(scuroGrezzo)
        // Apertura: via linee e scritte più sottili di ~5 px di lavoro (i tramezzi restano: a questa
        // risoluzione sono più spessi).
        val isola = (LATO_LAVORO * 0.07).roundToInt()
        // Disegni misti: muri campiti e, nello stesso foglio, muri a doppia linea sottile (spesso i
        // muri esterni con le finestre). Le coppie di linee lunghe parallele vicine si riempiono e
        // contano come muro pieno, così l'apertura non le cancella.
        val apertiSoli = dilata(erodi(scuro, 2), 2)
        // Scritte a mano grandi (es. "ripost", "negozio"): tratti più sottili di un muro. Si
        // tolgono anche isole più estese purché quasi nulla resista a un'erosione di 3 px, cosa
        // che un tronco di muro vero (anche staccato tra due porte) invece supera.
        fun pulisci(m: BooleanArray) = togliIsole(m, isola, erodi(m, 3), (LATO_LAVORO * 0.15).roundToInt())
        val pieniSoli = pulisci(apertiSoli)
        // Il tipo di disegno si decide sui soli muri campiti: quota dell'inchiostro che sta in
        // tratti spessi (muri campiti) rispetto a tutto l'inchiostro del riquadro. Non dipende da
        // quanta carta bianca c'è attorno alla pianta (la frazione sull'area sì).
        val nScuro = scuro.count { it }.coerceAtLeast(1)
        quotaInchiostroSpesso = pieniSoli.count { it }.toDouble() / nScuro
        val usaPieni = when (tipo) {
            TipoMuri.PIENI -> true
            TipoMuri.LINEE -> false
            TipoMuri.AUTO -> quotaInchiostroSpesso >= SOGLIA_QUOTA_PIENI
        }
        val frazionePieni = if (usaPieni) 1.0 else 0.0
        // Disegni misti: muri campiti e, nello stesso foglio, muri a doppia linea sottile (spesso i
        // muri esterni con le finestre). Le coppie di linee lunghe parallele vicine si riempiono e
        // contano come muro pieno, così l'apertura non le cancella.
        val muriPieni = if (frazionePieni >= 0.03) {
            val doppie = muriDoppiaLinea(scuro)
            pulisci(BooleanArray(w * h) { apertiSoli[it] || doppie[it] })
        } else pieniSoli
        // Disegni coi muri a doppia linea sottile (molte planimetrie catastali): l'apertura li
        // cancellerebbe. Allora i muri sono le linee stesse (leggermente ingrossate per sigillare
        // i tratti), tolte solo le scritte isolate: la linea interna del muro delimita il vano.
        muriPieniRiconosciuti = frazionePieni >= 0.03
        val lineeSenzaScritte = if (frazionePieni >= 0.03) null else togliScritte(togliIsole(erodi(dilata(scuro, 2), 1), isola), scuro)
        muriBase = lineeSenzaScritte ?: muriPieni
        muri = lineeSenzaScritte ?: conSoglieEFinestre(muriPieni, scuro)
        frazioneMuri = muri.count { it }.toDouble() / muri.size

        // Mezza larghezza di una porta (0,9 m) se la scala è nota, altrimenti l'1,2% del riquadro.
        // Si parte stretti (25 cm: chiude i varchi fino a 50 cm) per non far sparire i vani piccoli
        // (wc, disimpegni, ripostigli) sotto i muri ingrossati; le porte vere si chiudono nei
        // tentativi successivi a raggio crescente (vedi poligonoDaPunto).
        raggioPorte = metriPerPixelLavoro?.let { (0.25 / it).roundToInt() }?.coerceIn(4, 60)
            ?: (LATO_LAVORO * 0.012).roundToInt()
        muriChiusi = dilata(muri, raggioPorte)
        muriChiusiPerRaggio[raggioPorte] = muriChiusi
    }

    /**
     * Carta millimetrata o quadrettata (le vecchie schede disegnate a mano su carta tecnica): tante
     * linee sottili lunghe, parallele ed equidistanti, in orizzontale e/o in verticale. Si
     * riconoscono dalla spaziatura regolare e si cancellano solo dove sono sottili (dove passano
     * sopra un muro campito, il muro resta: si cancella solo dove l'inchiostro è spesso al massimo 5 px).
     */
    private fun togliReticolo(scuro: BooleanArray): BooleanArray {
        val out = scuro.copyOf()
        for (orizz in booleanArrayOf(true, false)) {
            val lungo = (if (orizz) w else h) / 4                 // linee lunghe almeno metà riquadro
            val base = filtroAsse(scuro, 1, true, !orizz)
            val linee = filtroAsse(filtroAsse(base, lungo, false, orizz), lungo, true, orizz)
            val n1 = if (orizz) h else w; val n2 = if (orizz) w else h
            // righe (o colonne) occupate per buona parte da una linea lunga
            val piene = BooleanArray(n1) { a ->
                var c = 0
                for (b in 0 until n2) if (linee[if (orizz) a * w + b else b * w + a]) c++
                c > n2 * 0.4
            }
            val centri = ArrayList<Int>()
            var a = 0
            while (a < n1) {
                if (piene[a]) { var z = a; while (z + 1 < n1 && piene[z + 1]) z++; centri += (a + z) / 2; a = z + 1 } else a++
            }
            if (centri.size < 8) continue
            val passi = centri.zipWithNext { x, y -> y - x }.sorted()
            val mediana = passi[passi.size / 2].toDouble()
            if (mediana < 3) continue
            val regolari = passi.count { abs(it - mediana) <= mediana * 0.3 }
            if (regolari < passi.size * 0.7) continue
            reticoloTolto = true
            // cancella i pixel di linea lunga dove l'inchiostro è sottile (≤ 3 px di traverso)
            for (i in 0 until w * h) {
                if (!linee[i] || !scuro[i]) continue
                val x = i % w; val y = i / w
                var spessore = 1
                var t = 1
                while (t <= 6) {
                    val xx = if (orizz) x else x + t; val yy = if (orizz) y + t else y
                    if (xx >= w || yy >= h || !scuro[yy * w + xx]) break
                    spessore++; t++
                }
                t = 1
                while (t <= 6) {
                    val xx = if (orizz) x else x - t; val yy = if (orizz) y - t else y
                    if (xx < 0 || yy < 0 || !scuro[yy * w + xx]) break
                    spessore++; t++
                }
                if (spessore <= 5) out[i] = false
            }
        }
        return out
    }

    fun muriPieniRiconosciuti(): Boolean = muriPieniRiconosciuti

    /** Breve descrizione del tipo di disegno riconosciuto e dei parametri scelti (diagnosi). */
    fun descrizioneTipo(): String =
        "muri=${if (muriPieniRiconosciuti) "pieni" else "doppia-linea"} quotaSpessa=${"%.2f".format(quotaInchiostroSpesso)} frazione=${"%.3f".format(frazioneMuri)} reticolo=$reticoloTolto raggioPorte=$raggioPorte lavoro=${w}x$h"

    /** Solo per diagnosi nei test: muri (nero), muri chiusi col raggio [r] (rosso). */
    fun mascheraDiagnosi(r: Int): Bitmap {
        val c = chiusiCon(r)
        val px = IntArray(w * h) { i -> when { muri[i] -> Color.BLACK; c[i] -> Color.rgb(255, 150, 150); else -> Color.WHITE } }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Muri spessi almeno ~5 px: le scritte e i segni sottili si possono attraversare, le pareti no. */
    private val muriSpessi: BooleanArray by lazy { dilata(erodi(muri, 2), 2) }

    private fun chiusiCon(r: Int): BooleanArray = muriChiusiPerRaggio.getOrPut(r) { dilata(muri, r) }

    /** Il disegno ha muri pieni riconoscibili (altrimenti, es. muri a doppia linea sottile, il metodo non è affidabile). */
    val utilizzabile: Boolean get() = frazioneMuri in 0.01..0.6

    private fun areaMinimaPx(): Int = metriPerPixelLavoro?.let { (1.0 / (it * it)).roundToInt() } ?: (w * h / 600)
    private fun areaMassimaPx(): Int = metriPerPixelLavoro?.let { (400.0 / (it * it)).roundToInt() }?.coerceAtMost(w * h / 2) ?: (w * h / 2)

    /**
     * Contorno del vano che contiene [puntoImmagine] (coordinate dell'immagine originale), oppure
     * null se il vano non è chiuso dai muri o ha un'area non plausibile.
     */
    fun poligonoDaPunto(puntoImmagine: PointF, areaAttesaImmagine: Double? = null): List<PointF>? {
        if (!utilizzabile) return null
        val sx = ((puntoImmagine.x - regione.left) * f).roundToInt()
        val sy = ((puntoImmagine.y - regione.top) * f).roundToInt()
        // Si parte dalla larghezza di una porta normale; se il riempimento "esce" (portafinestra
        // verso un terrazzo, apertura ampia verso un corridoio) si riprova chiudendo aperture via
        // via più larghe, fino a circa 2,5 m.
        val moltiplicatori = if (metriPerPixelLavoro != null) doubleArrayOf(1.0, 1.6, 2.2, 3.0, 4.0) else doubleArrayOf(1.0, 1.6, 2.3, 3.0)
        val raggi = moltiplicatori.map { (raggioPorte * it).roundToInt() }.toMutableList()
        // Vani piccoli (ripostigli, wc, sottoscala): si comincia con un raggio proporzionato al
        // vano, altrimenti i muri ingrossati e la scritta all'interno lo riempiono del tutto.
        if (areaAttesaImmagine != null && areaAttesaImmagine > 0) {
            val latoLavoro = sqrt(areaAttesaImmagine) * f
            val r0 = (latoLavoro * 0.12).roundToInt().coerceAtLeast(3)
            if (r0 < raggioPorte) raggi.add(0, r0)
        }
        for (r in raggi) {
            val chiusi = chiusiCon(r)
            val seme = puntoLiberoVicino(sx, sy, r * 2, chiusi) ?: run { if (DEBUG) println("DBG r=$r nessun seme ($sx,$sy) muro=${muri.getOrNull(sy * w + sx)} spesso=${muriSpessi.getOrNull(sy * w + sx)}"); null } ?: continue
            val nucleo = riempi(seme.first, seme.second, chiusi) ?: run { if (DEBUG) { val vis = BooleanArray(w*h); val c = componente(seme.first, seme.second, vis, muriChiusi = chiusi); println("DBG r=$r null area=${c.size} max=${areaMassimaPx()} min=${areaMinimaPx()} bordo=${c.any { val px = it % w; val py = it / w; px == 0 || py == 0 || px == w - 1 || py == h - 1 }}") }; null } ?: continue
            val pol = poligonoDaNucleo(nucleo, r) ?: run { if (DEBUG) println("DBG r=$r poligono null"); null } ?: continue
            // Con la stima dell'AI: se il vano trovato è molto più grande (ha inglobato un vano
            // accanto passando da una porta) si riprova chiudendo aperture più larghe.
            if (areaAttesaImmagine != null && areaAttesaImmagine > 0) {
                val rapporto = abs(areaConSegno(pol)) / areaAttesaImmagine
                if (DEBUG) println("DBG r=$r rapporto=${"%.2f".format(rapporto)}")
                if (rapporto > 1.4) continue
            }
            return pol
        }
        return null
    }

    /**
     * Quando il vano non si chiude: allinea i lati orizzontali e verticali del poligono stimato
     * dall'AI alla faccia del muro più vicina (entro circa 90 cm), così la forma resta quella
     * dell'AI ma i lati cadono sui muri veri invece che spostati. I lati obliqui non si toccano
     * (in quel caso il poligono si restituisce invariato).
     */
    fun agganciaAiMuri(verticiImmagine: List<PointF>): List<PointF> {
        if (!utilizzabile || verticiImmagine.size < 3) return verticiImmagine
        // Prima si tolgono i vertici superflui dell'AI (in linea coi vicini, o doppi), che
        // altrimenti farebbero rinunciare all'aggancio.
        val pts = togliCollineari(
            verticiImmagine.map { PointF(((it.x - regione.left) * f).toFloat(), ((it.y - regione.top) * f).toFloat()) }.toMutableList(),
            tolleranza = max(3.0, raggioPorte * 0.4)
        )
        if (pts.size < 4) return verticiImmagine
        val n = pts.size
        // Orientamento di ogni lato: true = orizzontale, false = verticale; obliqui → rinuncia.
        val orizz = BooleanArray(n)
        for (i in 0 until n) {
            val a = pts[i]; val b = pts[(i + 1) % n]
            val dx = abs(b.x - a.x); val dy = abs(b.y - a.y)
            when {
                dy <= dx * 0.2f -> orizz[i] = true
                dx <= dy * 0.2f -> orizz[i] = false
                else -> return verticiImmagine
            }
        }
        // Lati consecutivi con lo stesso orientamento non sono gestibili (vertice inutile).
        for (i in 0 until n) if (orizz[i] == orizz[(i + 1) % n]) return verticiImmagine
        val orario = areaConSegno(pts) > 0
        val raggio = metriPerPixelLavoro?.let { (0.9 / it).roundToInt() }?.coerceIn(6, 120) ?: (LATO_LAVORO * 0.06).roundToInt()
        val coord = DoubleArray(n)
        for (i in 0 until n) {
            val a = pts[i]; val b = pts[(i + 1) % n]
            if (orizz[i]) {
                // Interno del poligono: per un lato percorso verso destra in senso orario (y verso il basso) è sotto.
                val versoInterno = if ((b.x > a.x) == orario) 1 else -1
                coord[i] = migliorePosizioneLato(true, min(a.x, b.x), max(a.x, b.x), (a.y + b.y) / 2f, versoInterno, raggio)
            } else {
                val versoInterno = if ((b.y > a.y) == orario) -1 else 1
                coord[i] = migliorePosizioneLato(false, min(a.y, b.y), max(a.y, b.y), (a.x + b.x) / 2f, versoInterno, raggio)
            }
        }
        // Nuovi vertici: incrocio di ciascun lato col precedente.
        return (0 until n).map { i ->
            val prec = (i - 1 + n) % n
            val x = if (orizz[i]) coord[prec] else coord[i]
            val y = if (orizz[i]) coord[i] else coord[prec]
            PointF((x / f + regione.left).toFloat(), (y / f + regione.top).toFloat())
        }
    }

    /**
     * Toglie dal contorno gli sguanci di porte e finestre: piccoli "gradini" verso l'esterno
     * profondi quanto lo spessore di un muro (fino a ~40 cm) tra due lati paralleli. Il lato più
     * corto dei due viene portato sulla linea del più lungo, solo se così l'area diminuisce
     * (sporgenza verso l'esterno); le rientranze vere del vano (pilastri, nicchie verso l'interno)
     * restano.
     */
    private fun togliSguanci(poligono: List<PointF>): List<PointF> {
        val prof = metriPerPixelLavoro?.let { (0.4 / it).toFloat() } ?: (LATO_LAVORO * 0.025f)
        val latoMax = metriPerPixelLavoro?.let { (3.0 / it).toFloat() } ?: (LATO_LAVORO * 0.2f)
        val p = poligono.map { PointF(it.x, it.y) }.toMutableList()
        fun len(a: PointF, b: PointF) = hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()).toFloat()
        fun orizz(a: PointF, b: PointF) = abs(b.y - a.y) <= abs(b.x - a.x) * 0.15f
        fun vert(a: PointF, b: PointF) = abs(b.x - a.x) <= abs(b.y - a.y) * 0.15f
        var cambiato = true
        var giri = 0
        while (cambiato && p.size > 4 && giri++ < 50) {
            cambiato = false
            val n = p.size
            for (i in 0 until n) {
                val a0 = p[(i - 1 + n) % n]; val a1 = p[i]; val b0 = p[(i + 1) % n]; val b1 = p[(i + 2) % n]
                val parOrizz = orizz(a0, a1) && orizz(b0, b1) && (a1.x - a0.x) * (b1.x - b0.x) > 0
                val parVert = vert(a0, a1) && vert(b0, b1) && (a1.y - a0.y) * (b1.y - b0.y) > 0
                if (!parOrizz && !parVert) continue
                val gradino = len(a1, b0)
                if (gradino > prof || gradino < 1f) continue
                val la = len(a0, a1); val lb = len(b0, b1)
                // Gradini minimi (pochi cm, residui del tracciamento) si tolgono in ogni caso.
                val minimo = gradino < prof * 0.35f
                fun spostaA(): MutableList<PointF> {
                    val prova = p.toMutableList()
                    if (parOrizz) { prova[(i - 1 + n) % n] = PointF(a0.x, b0.y); prova[i] = PointF(a1.x, b0.y) }
                    else { prova[(i - 1 + n) % n] = PointF(b0.x, a0.y); prova[i] = PointF(b0.x, a1.y) }
                    prova.removeAt(i)
                    return prova
                }
                fun spostaB(): MutableList<PointF> {
                    val prova = p.toMutableList()
                    if (parOrizz) { prova[(i + 1) % n] = PointF(b0.x, a1.y); prova[(i + 2) % n] = PointF(b1.x, a1.y) }
                    else { prova[(i + 1) % n] = PointF(a1.x, b0.y); prova[(i + 2) % n] = PointF(a1.x, b1.y) }
                    prova.removeAt((i + 1) % n)
                    return prova
                }
                // Prima si prova a spostare il lato più corto; si accetta solo se l'area cala.
                // Un lato che poggia su una parete vera (muro pieno subito fuori) non è uno sguancio:
                // è un risalto reale del muro e non va raddrizzato.
                fun suParete(q0: PointF, q1: PointF): Boolean {
                    val lx = q1.x - q0.x; val ly = q1.y - q0.y
                    val l = hypot(lx.toDouble(), ly.toDouble()).toFloat()
                    if (l < 1f) return false
                    val nx = -ly / l; val ny = lx / l
                    var su = 0; var tot = 0
                    for (t in 1..9) {
                        val px = q0.x + lx * t / 10f; val py = q0.y + ly * t / 10f
                        // verso esterno: quello in cui, a 2 px, si esce dal poligono
                        val dentroPiu = GeometriaUtils.puntoDentroPoligono(PointF(px + nx * 2, py + ny * 2), p)
                        val sx = if (dentroPiu) -nx else nx; val sy = if (dentroPiu) -ny else ny
                        var muroTrovato = false
                        for (d in 1..4) {
                            val x = (px + sx * d).roundToInt(); val y = (py + sy * d).roundToInt()
                            if (x in 0 until w && y in 0 until h && muriBase[y * w + x]) { muroTrovato = true; break }
                        }
                        tot++; if (muroTrovato) su++
                    }
                    return su >= tot * 0.7
                }
                // Sguancio "a dente": il lato spostato ha un gradino anche all'altro capo, e oltre
                // quello la parete riprende sulla stessa linea del lato di arrivo.
                fun aDente(spostaPrimo: Boolean): Boolean {
                    val tol = max(3f, prof * 0.3f)
                    return if (spostaPrimo) {
                        val q = p[(i - 2 + 2 * n) % n]; val qq = p[(i - 3 + 3 * n) % n]
                        if (parOrizz) abs(q.y - b0.y) < tol && abs(qq.y - b0.y) < tol else abs(q.x - b0.x) < tol && abs(qq.x - b0.x) < tol
                    } else {
                        val q = p[(i + 3) % n]; val qq = p[(i + 4) % n]
                        if (parOrizz) abs(q.y - a1.y) < tol && abs(qq.y - a1.y) < tol else abs(q.x - a1.x) < tol && abs(qq.x - a1.x) < tol
                    }
                }
                // Muri pieni: si guarda se il lato poggia su una parete vera. Muri a doppia linea
                // (dove anche telai e soglie sono linee): si raddrizzano solo i "denti".
                val aSuParete = if (muriPieniRiconosciuti) suParete(a0, a1) else !aDente(true)
                val bSuParete = if (muriPieniRiconosciuti) suParete(b0, b1) else !aDente(false)
                val candidati = ArrayList<MutableList<PointF>>()
                // Il lato più lungo si sposta solo se i due sono simili (sguancio largo quanto il
                // tratto di parete accanto); altrimenti si raddrizzerebbe una parete vera.
                val simili = max(la, lb) <= min(la, lb) * 2f
                val puoA = (minimo || !aSuParete) && la <= latoMax
                val puoB = (minimo || !bSuParete) && lb <= latoMax
                if (la <= lb) { if (puoA) candidati += spostaA(); if (simili && puoB) candidati += spostaB() }
                else { if (puoB) candidati += spostaB(); if (simili && puoA) candidati += spostaA() }
                val areaOra = abs(areaConSegno(p))
                val scelto = candidati.firstOrNull { minimo || abs(areaConSegno(it)) < areaOra } ?: continue
                p.clear(); p.addAll(togliCollineari(scelto)); cambiato = true
                break
            }
        }
        return p
    }

    /**
     * Regolarizzazione del contorno (come nei metodi di "room segmentation" che assumono pareti
     * ortogonali): i segni convenzionali attaccati ai muri (croci delle porte, frecce, archi,
     * lettere a contatto) lasciano piccoli "denti" e angoli smussati, e il contorno ci girava
     * intorno con tanti punti invece di andare dritto allo spigolo. Qui:
     *  1. si tolgono i denti piccoli (verso l'interno o l'esterno) lungo una parete dritta;
     *  2. agli angoli, i piccoli tratti tra due pareti si sostituiscono con lo spigolo vero
     *     (incrocio delle due pareti);
     *  3. i lati quasi orizzontali/verticali si rendono esattamente tali.
     */
    private fun regolarizza(poligono: List<PointF>): List<PointF> {
        val dente = metriPerPixelLavoro?.let { (0.45 / it).toFloat() } ?: (LATO_LAVORO * 0.03f)
        val larghezzaDente = metriPerPixelLavoro?.let { (1.2 / it).toFloat() } ?: (LATO_LAVORO * 0.07f)
        val p = poligono.map { PointF(it.x, it.y) }.toMutableList()
        fun len(a: PointF, b: PointF) = hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()).toFloat()
        fun cos(a0: PointF, a1: PointF, b0: PointF, b1: PointF): Float {
            val ax = a1.x - a0.x; val ay = a1.y - a0.y; val bx = b1.x - b0.x; val by = b1.y - b0.y
            val l = hypot(ax.toDouble(), ay.toDouble()) * hypot(bx.toDouble(), by.toDouble())
            return if (l < 1e-6) 0f else ((ax * bx + ay * by) / l).toFloat()
        }
        fun distRetta(q: PointF, r0: PointF, r1: PointF): Float {
            val l = len(r0, r1); if (l < 1e-3) return len(q, r0)
            return abs((r1.x - r0.x) * (r0.y - q.y) - (r0.x - q.x) * (r1.y - r0.y)) / l
        }
        // 1. denti
        var cambiato = true; var giri = 0
        while (cambiato && p.size >= 6 && giri++ < 60) {
            cambiato = false
            val n = p.size
            for (i in 0 until n) {
                val prev = p[(i - 1 + n) % n]; val a = p[i]; val b = p[(i + 1) % n]; val c = p[(i + 2) % n]; val d = p[(i + 3) % n]
                if (len(a, b) > dente || len(c, d) > dente || len(b, c) > larghezzaDente) continue
                if (cos(a, b, c, d) > -0.7f) continue                 // i due gradini vanno in versi opposti
                if (abs(cos(prev, a, a, b)) > 0.5f) continue          // il gradino è di traverso alla parete
                if (len(prev, a) < len(b, c)) continue                // la parete è più lunga del dente
                if (distRetta(d, prev, a) > max(3f, dente * 0.35f)) continue // la parete riprende sulla stessa linea
                val i1 = (i + 1) % n; val i2 = (i + 2) % n
                if (i2 > i1) { p.removeAt(i2); p.removeAt(i1) } else { p.removeAt(i1); p.removeAt(i2) }
                cambiato = true; break
            }
        }
        // 1b. scalini lungo una parete: tra due tratti lunghi paralleli quasi allineati, fino a 5
        //     tratti corti (il contorno che gira attorno a una croce di porta o a una scritta
        //     attaccata al muro) si tolgono e la parete torna dritta.
        cambiato = true; giri = 0
        while (cambiato && p.size >= 6 && giri++ < 60) {
            cambiato = false
            val n = p.size
            loop@ for (i in 0 until n) {
                val e0 = p[(i - 1 + n) % n]; val e1 = p[i]
                if (len(e0, e1) < dente * 2) continue
                var percorso = 0f
                for (k in 1..5) {
                    if (n - (k - 1) < 4) break
                    percorso += len(p[(i + k - 1) % n], p[(i + k) % n])
                    if (percorso > larghezzaDente * 1.5f) break
                    val f0 = p[(i + k) % n]; val f1 = p[(i + k + 1) % n]
                    if (len(f0, f1) < dente * 2) continue
                    if (k < 2) break
                    if (cos(e0, e1, f0, f1) < 0.95f) break
                    if (distRetta(f0, e0, e1) > dente * 0.5f) break
                    val rimuovi = (1 until k).map { (i + it) % n }.sortedDescending()
                    rimuovi.forEach { p.removeAt(it) }
                    cambiato = true; break@loop
                }
            }
        }
        // 2. angoli smussati o "girati intorno" a un segno: tra due pareti lunghe quasi perpendicolari,
        //    fino a 3 tratti corti si sostituiscono con l'incrocio delle due pareti.
        cambiato = true; giri = 0
        while (cambiato && p.size >= 5 && giri++ < 60) {
            cambiato = false
            val n = p.size
            loop@ for (i in 0 until n) {
                val e0 = p[(i - 1 + n) % n]; val e1 = p[i]
                if (len(e0, e1) < dente * 2) continue
                for (k in 1..3) {
                    if (p.size - k < 4) break
                    val f0 = p[(i + k) % n]; val f1 = p[(i + k + 1) % n]
                    if (len(f0, f1) < dente * 2) continue
                    var percorso = 0f
                    for (t in 0 until k) percorso += len(p[(i + t) % n], p[(i + t + 1) % n])
                    if (percorso > dente * 1.5f) break
                    if (abs(cos(e0, e1, f0, f1)) > 0.3f) break
                    val x = incrocio(e0, e1, f0, f1) ?: break
                    if (len(x, e1) > dente * 1.5f || len(x, f0) > dente * 1.5f) break
                    // sostituisce i vertici i..i+k con l'incrocio
                    val indici = (0..k).map { (i + it) % n }.sortedDescending()
                    val pos = (0..k).map { (i + it) % n }.minOrNull()!!
                    indici.forEach { p.removeAt(it) }
                    p.add(min(pos, p.size), x)
                    cambiato = true; break@loop
                }
            }
        }
        // 3. ortogonalità: lati entro ~6° dall'orizzontale/verticale resi esatti
        val n = p.size
        if (n >= 4) {
            val tipo = IntArray(n) { i ->
                val a = p[i]; val b = p[(i + 1) % n]
                val dx = abs(b.x - a.x); val dy = abs(b.y - a.y)
                when { dy <= dx * 0.21f -> 1; dx <= dy * 0.21f -> 2; else -> 0 }
            }
            val coord = FloatArray(n) { i ->
                val a = p[i]; val b = p[(i + 1) % n]
                when (tipo[i]) { 1 -> (a.y + b.y) / 2; 2 -> (a.x + b.x) / 2; else -> 0f }
            }
            for (i in 0 until n) {
                val prec = (i - 1 + n) % n
                var x = p[i].x; var y = p[i].y
                if (tipo[prec] == 1) y = coord[prec]
                if (tipo[i] == 1) y = coord[i]
                if (tipo[prec] == 2) x = coord[prec]
                if (tipo[i] == 2) x = coord[i]
                p[i] = PointF(x, y)
            }
        }
        val pulito = togliCollineari(p)
        return if (pulito.size >= 3) pulito else poligono
    }

    private fun incrocio(a0: PointF, a1: PointF, b0: PointF, b1: PointF): PointF? {
        val d = (a1.x - a0.x) * (b1.y - b0.y) - (a1.y - a0.y) * (b1.x - b0.x)
        if (abs(d) < 1e-6) return null
        val t = ((b0.x - a0.x) * (b1.y - b0.y) - (b0.y - a0.y) * (b1.x - b0.x)) / d
        return PointF(a0.x + t * (a1.x - a0.x), a0.y + t * (a1.y - a0.y))
    }

    private fun togliCollineari(p: MutableList<PointF>, tolleranza: Double = 1.5): MutableList<PointF> {
        var i = 0
        while (p.size > 3 && i < p.size) {
            val a = p[(i - 1 + p.size) % p.size]; val b = p[i]; val c = p[(i + 1) % p.size]
            val cross = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
            val l = hypot((c.x - a.x).toDouble(), (c.y - a.y).toDouble())
            if (l < 1e-3 || abs(cross) / l < tolleranza) p.removeAt(i) else i++
        }
        return p
    }

    private fun areaConSegno(p: List<PointF>): Double {
        var s = 0.0
        for (i in p.indices) { val a = p[i]; val b = p[(i + 1) % p.size]; s += a.x.toDouble() * b.y - b.x.toDouble() * a.y }
        return s / 2
    }

    /**
     * Cerca, attorno alla posizione stimata di un lato, la riga (o colonna) in cui dal lato interno
     * c'è spazio libero e dal lato esterno c'è muro, sulla maggior parte della lunghezza del lato.
     */
    private fun migliorePosizioneLato(orizzontale: Boolean, da: Float, a: Float, pos: Float, versoInterno: Int, raggio: Int): Double {
        val margine = ((a - da) * 0.15f).toInt()
        val s0 = (da + margine).toInt(); val s1 = (a - margine).toInt()
        if (s1 - s0 < 3) return pos.toDouble()
        fun muro(t: Int, s: Int): Boolean {
            val x = if (orizzontale) s else t; val y = if (orizzontale) t else s
            return x in 0 until w && y in 0 until h && muri[y * w + x]
        }
        val p0 = pos.roundToInt()
        var migliore = pos.toDouble(); var punteggioMigliore = 0.0; var distMigliore = Int.MAX_VALUE
        for (d in -raggio..raggio) {
            val t = p0 + d
            var buoni = 0; var tot = 0
            var s = s0
            while (s <= s1) {
                tot++
                // Faccia del muro: in t libero, appena fuori (t - versoInterno) muro.
                if (!muro(t, s) && muro(t - versoInterno, s)) buoni++
                s += 2
            }
            val punteggio = buoni.toDouble() / tot
            if (punteggio < 0.5) continue
            val meglio = punteggio > punteggioMigliore + 0.08 ||
                (punteggio >= punteggioMigliore - 0.08 && abs(d) < distMigliore)
            if (meglio) {
                migliore = t.toDouble() + if (versoInterno > 0) 0.0 else 1.0
                punteggioMigliore = max(punteggio, punteggioMigliore)
                distMigliore = abs(d)
            }
        }
        return migliore
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
        // Porte normali chiuse (fino a ~1,1 m), così due vani comunicanti non tornano come uno solo.
        val r = (raggioPorte * 2.2).roundToInt()
        val chiusi = chiusiCon(r)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (visitato[i] || chiusi[i]) continue
            val componente = componente(x, y, visitato, muriChiusi = chiusi)
            val area = componente.size
            if (area < areaMinimaPx() / 3 || area > areaMassimaPx()) continue
            if (componente.any { val px = it % w; val py = it / w; px == 0 || py == 0 || px == w - 1 || py == h - 1 }) continue
            val giaCoperti = componente.count { coperto[it] }
            if (giaCoperti > area * 0.15) continue
            val nucleo = BooleanArray(w * h).also { m -> componente.forEach { m[it] = true } }
            poligonoDaNucleo(nucleo, r)?.let { risultati += it }
        }
        return risultati
    }

    // ---------------------------------------------------------------------

    private fun poligonoDaNucleo(nucleo: BooleanArray, raggio: Int): List<PointF>? {
        // Si torna a filo dei muri: crescita geodetica di raggioPorte dentro lo spazio non murato.
        // Arrotondamento leggero: toglie le punte lasciate da stipiti e battute delle porte.
        // Muri con i vani delle porte "tappati" a filo del muro (chiusura morfologica): la regione
        // torna fino alle facce dei muri ma non entra negli sguanci di porte e finestre, che non
        // fanno parte della superficie netta del vano.
        // Chiusura per righe e per colonne (non quadrata): tappa i vani tra due tronchi di muro
        // allineati, ma non riempie le "tasche" dello spazio vicino a simboli o arredi disegnati
        // (es. la freccia d'ingresso davanti a una vetrina), che con la chiusura quadrata
        // venivano tolte dal vano.
        val tappati = chiusura1D(raggio, orizzontale = true).let { o ->
            val v = chiusura1D(raggio, orizzontale = false)
            BooleanArray(w * h) { o[it] || v[it] }
        }
        val aperta = dilata(erodi(cresciGeodetico(nucleo, raggio), 3), 3).let { r ->
            BooleanArray(w * h) { r[it] && !muri[it] && !tappati[it] }
        }
        // Chiusura (elemento quadrato: gli angoli retti, anche rientranti, restano netti): riempie le
        // tacche strette lasciate da stipiti e mozziconi di tramezzo che sporgono nel vano.
        val k = max(3, raggioPorte)
        val regioneVano = erodi(dilata(aperta, k), k)
        val contorno = tracciaContorno(regioneVano) ?: return null
        val tolleranza = max(2.0, raggioPorte * 0.35)
        val semplificato = regolarizza(togliSguanci(douglasPeucker(contorno, tolleranza)))
        if (semplificato.size < 3) return null
        return semplificato.map { PointF((it.x / f + regione.left).toFloat(), (it.y / f + regione.top).toFloat()) }
    }

    /**
     * Toglie dai muri le "isole" piccole non collegate alla rete dei muri: lettere delle scritte
     * (es. "LOGGIA", "CUCINA mq 12"), simboli, numeri, che altrimenti spezzerebbero un vano in due.
     */
    /**
     * Nei disegni a linee sottili le scritte (destinazioni come "Terrazza", "Bagno", "Rip.", sigle,
     * altezze) hanno lo stesso spessore dei muri e non si distinguono per spessore: si riconoscono
     * dalla forma. Una scritta isolata è un gruppo compatto di tratti (ingombro piccolo, non una
     * linea dritta, inchiostro sparso nel suo rettangolo); un pezzo di muro staccato è invece una
     * linea lunga e sottile o un rettangolo pieno.
     */
    private fun togliScritte(m: BooleanArray, scuro: BooleanArray): BooleanArray {
        val out = m.copyOf()
        val visto = BooleanArray(w * h)
        val coda = IntArray(w * h)
        val latoMax = (LATO_LAVORO * 0.15).roundToInt()
        val latoMinTesto = (LATO_LAVORO * 0.006).roundToInt().coerceAtLeast(6)
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
            val bw = maxX - minX + 1; val bh = maxY - minY + 1
            // inchiostro originale (non ingrossato) dentro il rettangolo d'ingombro
            var nScuro = 0
            for (k in 0 until fine) if (scuro[coda[k]]) nScuro++
            val riempimento = nScuro.toDouble() / (bw.toLong() * bh)
            val scritta = max(bw, bh) < latoMax && min(bw, bh) >= latoMinTesto && riempimento < 0.6
            if (scritta) for (k in 0 until fine) out[coda[k]] = false
        }
        return out
    }

    private fun togliIsole(m: BooleanArray, latoMinimo: Int, erosa: BooleanArray? = null, latoTesto: Int = 0): BooleanArray {
        val out = m.copyOf()
        val visto = BooleanArray(w * h)
        val coda = IntArray(w * h)
        for (inizio in 0 until w * h) {
            if (!m[inizio] || visto[inizio]) continue
            var testa = 0; var fine = 0
            coda[fine++] = inizio; visto[inizio] = true
            var minX = w; var maxX = 0; var minY = h; var maxY = 0; var resistenti = 0
            while (testa < fine) {
                val i = coda[testa++]
                if (erosa != null && erosa[i]) resistenti++
                val x = i % w; val y = i / w
                minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx; val ny = y + dy
                    if (nx !in 0 until w || ny !in 0 until h) continue
                    val j = ny * w + nx
                    if (m[j] && !visto[j]) { visto[j] = true; coda[fine++] = j }
                }
            }
            val lato = max(maxX - minX, maxY - minY)
            val testo = erosa != null && lato < latoTesto && resistenti < fine * 0.12
            if (lato < latoMinimo || testo) {
                for (k in 0 until fine) out[coda[k]] = false
            }
        }
        return out
    }

    /**
     * Punto libero (fuori dai muri ingrossati) più vicino al seme, raggiungibile SENZA attraversare
     * un muro vero: prima poteva "saltare" oltre una parete sottile nel vano accanto quando il seme
     * cadeva vicino a una scritta (es. "W.C." in un bagno stretto).
     */
    private fun puntoLiberoVicino(x0: Int, y0: Int, raggio: Int, muriChiusi: BooleanArray): Pair<Int, Int>? {
        if (x0 !in 0 until w || y0 !in 0 until h) return null
        if (!muriChiusi[y0 * w + x0]) return x0 to y0
        val visto = HashSet<Int>()
        val coda = ArrayDeque<Int>()
        var inizio = y0 * w + x0
        // Seme caduto sopra un segno spesso (freccia, pilastro, scritta in grassetto): si parte dal
        // punto non murato più vicino.
        if (muriSpessi[inizio]) {
            var trovato = -1
            loop@ for (r in 1..raggio) {
                for (dy in -r..r) for (dx in -r..r) {
                    if (max(abs(dx), abs(dy)) != r) continue
                    val x = x0 + dx; val y = y0 + dy
                    if (x in 0 until w && y in 0 until h && !muriSpessi[y * w + x]) { trovato = y * w + x; break@loop }
                }
            }
            if (trovato < 0) return null
            inizio = trovato
        }
        coda.add(inizio); visto.add(inizio)
        val r2 = (raggio * 1.5).let { it * it }
        while (coda.isNotEmpty()) {
            val i = coda.removeFirst()
            val x = i % w; val y = i / w
            if (!muriChiusi[i]) return x to y
            for ((nx, ny) in arrayOf(x - 1 to y, x + 1 to y, x to y - 1, x to y + 1)) {
                if (nx !in 0 until w || ny !in 0 until h) continue
                val dx = (nx - x0).toDouble(); val dy = (ny - y0).toDouble()
                if (dx * dx + dy * dy > r2) continue
                val j = ny * w + nx
                // si può passare sulle scritte e sui muri ingrossati, non sui muri veri
                if (j in visto || muriSpessi[j]) continue
                visto.add(j); coda.add(j)
            }
        }
        return null
    }

    /** Riempimento dallo spazio libero (muri chiusi); null se esce dal riquadro o area non plausibile. */
    private fun riempi(x0: Int, y0: Int, muriChiusi: BooleanArray): BooleanArray? {
        val visitato = BooleanArray(w * h)
        val componente = componente(x0, y0, visitato, limite = areaMassimaPx() + 1, muriChiusi = muriChiusi)
        if (componente.size > areaMassimaPx() || componente.size < areaMinimaPx() / 5) return null // il nucleo è ristretto dai muri ingrossati: soglia larga
        if (componente.any { val px = it % w; val py = it / w; px == 0 || py == 0 || px == w - 1 || py == h - 1 }) return null
        return BooleanArray(w * h).also { m -> componente.forEach { m[it] = true } }
    }

    private fun componente(
        x0: Int, y0: Int, visitato: BooleanArray, limite: Int = Int.MAX_VALUE,
        muriChiusi: BooleanArray = this.muriChiusi
    ): IntArray {
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

    /**
     * Finestre e soglie delle porte sono disegnate con linee sottili DENTRO lo spessore del muro,
     * che l'apertura cancella: il vano resterebbe "aperto" verso l'esterno o verso il vano accanto.
     * Si rimettono come muro le linee scure che cadono nella fascia tra due tronchi dello stesso
     * muro allineati (orizzontali con orizzontali, verticali con verticali), cioè proprio dentro
     * il vano della finestra o della porta, e non quelle sparse nelle stanze (scritte, scale).
     */
    private fun conSoglieEFinestre(muriPieni: BooleanArray, scuro: BooleanArray): BooleanArray {
        val tratto = (LATO_LAVORO * 0.015).roundToInt()   // un muro "vero" è lungo almeno ~2·tratto
        val ponte = (LATO_LAVORO * 0.08).roundToInt()     // vani fino a ~2·ponte (finestre larghe)
        val orizzontali = filtroAsse(filtroAsse(muriPieni, tratto, false, true), tratto, true, true)
        val verticali = filtroAsse(filtroAsse(muriPieni, tratto, false, false), tratto, true, false)
        val fasciaO = filtroAsse(filtroAsse(orizzontali, ponte, true, true), ponte, false, true)
        val fasciaV = filtroAsse(filtroAsse(verticali, ponte, true, false), ponte, false, false)
        val linee = dilata(scuro, 1)
        return BooleanArray(w * h) { i -> muriPieni[i] || (linee[i] && (fasciaO[i] || fasciaV[i])) }
    }

    /**
     * Muri disegnati con due linee sottili parallele: si cercano le linee lunghe (orizzontali e
     * verticali, almeno ~1/16 del riquadro) e si riempie lo spazio tra due di esse quando distano
     * quanto lo spessore di un muro. Una linea singola (reticolo, confine di proprietà) resta fuori.
     */
    private fun muriDoppiaLinea(scuro: BooleanArray): BooleanArray {
        val lungo = (LATO_LAVORO * 0.03).roundToInt()      // semi-lunghezza minima della linea
        val spessoreMax = (LATO_LAVORO * 0.02).roundToInt() // distanza massima tra le due linee
        val out = BooleanArray(w * h)
        for (orizz in booleanArrayOf(true, false)) {
            // tolleranza a lievi inclinazioni/interruzioni: ingrossa di 1 px di traverso, chiude buchi di 3 px
            val base = filtroAsse(scuro, 1, true, !orizz)
            val chiusa = filtroAsse(filtroAsse(base, 3, true, orizz), 3, false, orizz)
            val linee = filtroAsse(filtroAsse(chiusa, lungo, false, orizz), lungo, true, orizz)
            // accoppiamento: lungo la direzione trasversale, due tratti di linea separati da 2..spessoreMax px
            val n1 = if (orizz) w else h      // posizioni lungo la linea
            val n2 = if (orizz) h else w      // posizioni trasversali
            for (a in 0 until n1) {
                var fineTratto = -1000
                var t = 0
                while (t < n2) {
                    val idx = if (orizz) t * w + a else a * w + t
                    if (linee[idx]) {
                        var u = t
                        while (u + 1 < n2 && linee[if (orizz) (u + 1) * w + a else a * w + u + 1]) u++
                        val vuoto = t - fineTratto - 1
                        if (fineTratto >= 0 && vuoto in 2..spessoreMax) {
                            for (z in fineTratto..u) out[if (orizz) z * w + a else a * w + z] = true
                        }
                        fineTratto = u
                        t = u + 1
                    } else t++
                }
            }
        }
        return out
    }

    /** Chiusura dei muri lungo una sola direzione: riempie i tratti liberi lunghi meno di 2·r tra due muri sulla stessa riga/colonna. */
    private fun chiusura1D(r: Int, orizzontale: Boolean): BooleanArray {
        val dil = filtroAsse(muri, r, true, orizzontale)
        return filtroAsse(dil, r, false, orizzontale)
    }

    private fun filtroAsse(m: BooleanArray, r: Int, qualsiasi: Boolean, orizzontale: Boolean): BooleanArray {
        val out = BooleanArray(w * h)
        if (orizzontale) {
            val riga = IntArray(w + 1)
            for (y in 0 until h) {
                for (x in 0 until w) riga[x + 1] = riga[x] + if (m[y * w + x]) 1 else 0
                for (x in 0 until w) {
                    val a = max(0, x - r); val b = min(w - 1, x + r)
                    val n = riga[b + 1] - riga[a]
                    out[y * w + x] = if (qualsiasi) n > 0 else n == (b - a + 1)
                }
            }
        } else {
            val col = IntArray(h + 1)
            for (x in 0 until w) {
                for (y in 0 until h) col[y + 1] = col[y] + if (m[y * w + x]) 1 else 0
                for (y in 0 until h) {
                    val a = max(0, y - r); val b = min(h - 1, y + r)
                    val n = col[b + 1] - col[a]
                    out[y * w + x] = if (qualsiasi) n > 0 else n == (b - a + 1)
                }
            }
        }
        return out
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

    /** Aggancia il riquadro stimato dall'AI alle zone di disegno che interseca (lo allarga fino a comprendere il disegno vero). */
    fun agganciaRiquadro(stima: Rect, zone: List<Rect>, margine: Int): Rect {
        val coinvolte = zone.filter { z ->
            val inter = Rect(z)
            inter.intersect(stima) && inter.width().toLong() * inter.height() > 0.25 * z.width().toLong() * z.height()
        }
        if (coinvolte.isEmpty()) return stima
        val r = Rect(coinvolte[0])
        coinvolte.drop(1).forEach { r.union(it) }
        r.inset(-margine, -margine)
        // Solo allargamento: le zone si basano sui muri campiti e possono non comprendere parti
        // della pianta disegnate a linee sottili (es. una cantina sporgente), che un riquadro più
        // stretto taglierebbe fuori. Lo spazio bianco in più non disturba l'analisi.
        r.union(stima)
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

/**
 * Sceglie da solo il metodo migliore per il disegno caricato. Le planimetrie catastali hanno
 * grafiche molto diverse (muri campiti a mano, muri a doppia linea dei programmi CAD con o senza
 * tratteggio, disegni misti, scansioni vecchie con reticolo e scritte a mano), e nessun singolo
 * modello dei muri va bene per tutte. Qui si tengono pronti due modelli:
 *  - PIENI: muri = tratti spessi (le linee sottili, cioè scritte, croci delle porte, gradini, quote,
 *    reticolo, si scartano; finestre e soglie dentro lo spessore del muro si rimettono);
 *  - LINEE: muri = tutte le linee (per i disegni a doppia linea sottile, dove la linea interna del
 *    muro delimita il vano);
 * e per ogni vano si confrontano i due contorni, tenendo quello più coerente con l'area stimata
 * dall'AI e più "pulito" (meno vertici). Se uno dei due fallisce, vale l'altro.
 */
class AnalizzatoreVani(
    private val bitmap: Bitmap,
    private val regione: Rect,
    private val metriPerPixel: Double?
) {
    internal val pieni by lazy { SegmentazionePlanimetria(bitmap, regione, metriPerPixel, SegmentazionePlanimetria.TipoMuri.PIENI) }
    internal val linee by lazy { SegmentazionePlanimetria(bitmap, regione, metriPerPixel, SegmentazionePlanimetria.TipoMuri.LINEE) }

    /** Modello prevalente per il disegno (usato per l'aggancio delle stime AI e per i vani mancanti). */
    val principale: SegmentazionePlanimetria by lazy {
        if (pieni.quotaInchiostroSpesso >= SegmentazionePlanimetria.SOGLIA_QUOTA_PIENI) pieni else linee
    }

    /** Quante volte ha vinto ciascun modello (diagnosi). */
    val vittorie = HashMap<String, Int>()

    data class Esito(val vertici: List<PointF>, val metodo: String)

    fun misura(seme: PointF, areaAttesa: Double?): Esito? {
        val candidati = ArrayList<Pair<Esito, Double>>()
        for ((nome, modello) in listOf("pieni" to pieni, "linee" to linee)) {
            if (!modello.utilizzabile) continue
            val pol = modello.poligonoDaPunto(seme, areaAttesa) ?: continue
            val area = areaPoligono(pol)
            var punteggio = 0.05 * (pol.size - 4).coerceAtLeast(0)
            if (areaAttesa != null && areaAttesa > 0) {
                val r = area / areaAttesa
                if (r !in 0.55..1.5) continue
                punteggio += 2 * abs(ln(r))
            }
            // a parità, il modello prevalente per il disegno
            if (modello === principale) punteggio -= 0.02
            candidati += Esito(pol, nome) to punteggio
        }
        val migliore = candidati.minByOrNull { it.second }?.first ?: return null
        vittorie[migliore.metodo] = (vittorie[migliore.metodo] ?: 0) + 1
        return migliore
    }

    /** Diagnosi: risultato grezzo di ciascun modello (area/attesa e vertici). */
    fun dettaglio(seme: PointF, areaAttesa: Double?): String = listOf("pieni" to pieni, "linee" to linee).joinToString("  ") { (n, m) ->
        val pol = if (m.utilizzabile) m.poligonoDaPunto(seme, areaAttesa) else null
        if (pol == null) "$n=null" else "$n=${pol.size}v r=${"%.2f".format(areaPoligono(pol) / (areaAttesa ?: 1.0))}"
    }

    fun agganciaAiMuri(vertici: List<PointF>): List<PointF> = principale.agganciaAiMuri(vertici)

    /** Descrizione in italiano del tipo di disegno riconosciuto e del metodo usato, per l'utente. */
    fun descrizioneDisegno(): String {
        val p = vittorie["pieni"] ?: 0; val l = vittorie["linee"] ?: 0
        val tipo = when {
            p > 0 && l > 0 -> "disegno misto (muri campiti e a linee sottili): metodo scelto vano per vano"
            p > 0 -> "muri campiti: scritte, croci delle porte e gradini ignorati"
            l > 0 -> "muri a linee sottili: scritte e segni isolati ignorati"
            principale.muriPieniRiconosciuti() -> "muri campiti"
            else -> "muri a linee sottili"
        }
        val reticolo = if (pieni.reticoloTolto || linee.reticoloTolto) "; reticolo/carta millimetrata tolto" else ""
        return tipo + reticolo
    }

    /**
     * Spazi chiusi non indicati, ma solo quelli plausibili come vani dell'unità: non più grandi
     * del vano più grande già trovato (×1,3) e dentro l'ingombro dei vani trovati. Così corti,
     * resedi, "altre proprietà" e il riquadro del lotto non diventano "locali da verificare".
     */
    fun vaniChiusiMancanti(gia: List<List<PointF>>): List<List<PointF>> {
        if (gia.isEmpty()) return emptyList()
        val areaMax = gia.maxOf { areaPoligono(it) } * 1.3
        val minX = gia.minOf { p -> p.minOf { it.x } }; val maxX = gia.maxOf { p -> p.maxOf { it.x } }
        val minY = gia.minOf { p -> p.minOf { it.y } }; val maxY = gia.maxOf { p -> p.maxOf { it.y } }
        val mx = (maxX - minX) * 0.05f; val my = (maxY - minY) * 0.05f
        return principale.vaniChiusiMancanti(gia).filter { pol ->
            areaPoligono(pol) <= areaMax &&
                pol.all { it.x in (minX - mx)..(maxX + mx) && it.y in (minY - my)..(maxY + my) }
        }
    }

    private fun areaPoligono(p: List<PointF>): Double {
        var s = 0.0
        for (i in p.indices) { val a = p[i]; val b = p[(i + 1) % p.size]; s += a.x.toDouble() * b.y - b.x.toDouble() * a.y }
        return abs(s) / 2
    }
}
