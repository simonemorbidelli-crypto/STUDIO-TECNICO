package it.geometra.schedacliente

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class SegmentazioneTest {

    private fun area(p: List<PointF>): Double {
        var s = 0.0
        for (i in p.indices) { val a = p[i]; val b = p[(i + 1) % p.size]; s += a.x * b.y - b.x * a.y }
        return abs(s) / 2
    }

    /**
     * Pianta sintetica 1400×1000 (1 px = 1 cm, quindi 0,01 m/px), muri spessi 12 px:
     *  A: rettangolo interno 400×300 (x 112..512, y 112..412) = 12 m²
     *  B: a L a destra di A, collegata ad A da una porta aperta di 90 px
     *  C: piccolo vano 200×150 in basso a sinistra
     *  + una linea sottile di reticolo (2 px) che attraversa tutto, e scritte sottili.
     */
    private fun pianta(): Bitmap {
        val bmp = Bitmap.createBitmap(1400, 1000, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); c.drawColor(Color.WHITE)
        val muro = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
        fun r(l: Int, t: Int, rr: Int, b: Int) = c.drawRect(l.toFloat(), t.toFloat(), rr.toFloat(), b.toFloat(), muro)
        // perimetro esterno 100..1300 x 100..900
        r(100, 100, 1300, 112); r(100, 888, 1300, 900); r(100, 100, 112, 900); r(1288, 100, 1300, 900)
        // muro verticale tra A e B a x=512..524 con porta da y=200 a 290
        r(512, 112, 524, 200); r(512, 290, 524, 412)
        // muro orizzontale sotto A a y=412..424 (da 112 a 1000), poi la L di B scende a destra
        r(112, 412, 1000, 424)
        // muro verticale x=1000..1012 da 424 a 888: B è a L (sopra fino a 1288, sotto solo x>1012)
        r(1000, 424, 1012, 888)
        // vano C: muro orizzontale y=650..662 da 112 a 324 e verticale x=312..324 da 650 a 888
        r(112, 650, 324, 662); r(312, 650, 324, 888)
        // reticolo sottile e "scritte"
        val sottile = Paint().apply { color = Color.BLACK; strokeWidth = 2f }
        c.drawLine(0f, 300f, 1400f, 300f, sottile)
        c.drawLine(700f, 0f, 700f, 1000f, sottile)
        val testo = Paint().apply { color = Color.BLACK; textSize = 28f }
        c.drawText("CUCINA", 250f, 260f, testo)
        return bmp
    }

    @Test
    fun contorniEsatti() {
        val bmp = pianta()
        val seg = SegmentazionePlanimetria(bmp, Rect(0, 0, bmp.width, bmp.height), 0.01)
        assertTrue(seg.utilizzabile)
        val a = seg.poligonoDaPunto(PointF(300f, 350f))
        assertNotNull("vano A", a)
        println("A: ${a!!.size} vertici, area=${area(a)} " + a.joinToString { "(${it.x.toInt()},${it.y.toInt()})" })
        assertEquals(400.0 * 300.0, area(a), 400.0 * 300.0 * 0.06) // entro il 6% (porta)
        val b = seg.poligonoDaPunto(PointF(1150f, 700f))
        assertNotNull("vano B a L", b)
        println("B: ${b!!.size} vertici, area=${area(b)} " + b.joinToString { "(${it.x.toInt()},${it.y.toInt()})" })
        val areaB = (1288 - 524) * (412 - 112) + (1288 - 1012) * (888 - 412) // parte alta + gamba a destra
        assertEquals(areaB.toDouble(), area(b), areaB * 0.06)
        val cc = seg.poligonoDaPunto(PointF(200f, 780f))
        assertNotNull("vano C", cc)
        println("C: ${cc!!.size} vertici, area=${area(cc)}")
        assertEquals((312 - 112) * (888 - 662).toDouble(), area(cc), 200.0 * 226 * 0.08)
    }

    @Test
    fun vaniMancantiTrovati() {
        val bmp = pianta()
        val seg = SegmentazionePlanimetria(bmp, Rect(0, 0, bmp.width, bmp.height), 0.01)
        val a = seg.poligonoDaPunto(PointF(300f, 350f))!!
        val mancanti = seg.vaniChiusiMancanti(listOf(a))
        println("mancanti: ${mancanti.size} " + mancanti.map { area(it).toInt() })
        // B (L), C e il vano centrale in basso (tra C, A e B)
        assertTrue(mancanti.size >= 3)
    }

    @Test
    fun vanoApertoNonInventato() {
        // Un vano senza un lato (es. loggia aperta) non è chiuso: niente poligono.
        val bmp = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); c.drawColor(Color.WHITE)
        val muro = Paint().apply { color = Color.BLACK }
        c.drawRect(100f, 100f, 700f, 112f, muro); c.drawRect(100f, 100f, 112f, 500f, muro); c.drawRect(688f, 100f, 700f, 500f, muro)
        val seg = SegmentazionePlanimetria(bmp, Rect(0, 0, 800, 600), 0.01)
        assertNull(seg.poligonoDaPunto(PointF(400f, 300f)))
    }

    /** Vano con portafinestra larga 150 cm verso un terrazzo aperto: deve chiudersi comunque. */
    @Test
    fun portafinestraLargaChiusa() {
        val bmp = Bitmap.createBitmap(900, 900, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); c.drawColor(Color.WHITE)
        val muro = Paint().apply { color = Color.BLACK }
        c.drawRect(100f, 100f, 700f, 112f, muro); c.drawRect(100f, 100f, 112f, 600f, muro)
        c.drawRect(688f, 100f, 700f, 600f, muro)
        // muro in basso con apertura di 150 px (x 330..480), sotto il terrazzo aperto
        c.drawRect(100f, 588f, 330f, 600f, muro); c.drawRect(480f, 588f, 700f, 600f, muro)
        val seg = SegmentazionePlanimetria(bmp, Rect(0, 0, 900, 900), 0.01)
        val pol = seg.poligonoDaPunto(PointF(400f, 300f), 576.0 * 476.0)
        assertNotNull("vano con portafinestra", pol)
        println("portafinestra: ${pol!!.size} vertici, area=${area(pol)}")
        assertEquals(576.0 * 476.0, area(pol), 576.0 * 476.0 * 0.06)
    }

    /** Poligono AI spostato di 30 px: i lati vengono riportati sulle facce dei muri. */
    @Test
    fun agganciaPoligonoSpostato() {
        val bmp = pianta()
        val seg = SegmentazionePlanimetria(bmp, Rect(0, 0, bmp.width, bmp.height), 0.01)
        val ai = listOf(PointF(142f, 82f), PointF(542f, 82f), PointF(542f, 382f), PointF(142f, 382f))
        val ok = seg.agganciaAiMuri(ai)
        println("agganciato: " + ok.joinToString { "(${it.x.toInt()},${it.y.toInt()})" })
        val atteso = listOf(PointF(112f, 112f), PointF(512f, 112f), PointF(512f, 412f), PointF(112f, 412f))
        ok.zip(atteso).forEach { (p, q) -> assertTrue("$p vs $q", abs(p.x - q.x) <= 3 && abs(p.y - q.y) <= 3) }
    }

    /** Screenshot reale (ingresso a L con portafinestra sul terrazzo): anteprima di controllo. */
    @Test
    fun ingressoReale() {
        val stream = javaClass.classLoader!!.getResourceAsStream("ingresso_l.jpg") ?: return
        val letta = android.graphics.BitmapFactory.decodeStream(stream)
        // Toglie la sovrapposizione verde disegnata dall'app nello screenshot (contorni e campiture):
        // l'analisi vera lavora sull'immagine originale, senza.
        val bmp = letta.copy(Bitmap.Config.ARGB_8888, true)
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        for (i in px.indices) {
            val c = px[i]; val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
            if (g - r >= 20) px[i] = Color.WHITE
        }
        bmp.setPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val k = bmp.width / 923f // coordinate prese dallo screenshot visualizzato a 923 px
        fun P(x: Float, y: Float) = PointF(x * k, y * k)
        val regione = Rect(0, (225 * k).toInt(), bmp.width, (1845 * k).toInt())
        val seg = SegmentazionePlanimetria(bmp, regione, null)
        val out = bmp.copy(Bitmap.Config.ARGB_8888, true)
        val cv = Canvas(out)
        val pen = Paint().apply { color = Color.RED; style = Paint.Style.STROKE; strokeWidth = 6f }
        fun disegna(p: List<PointF>?, col: Int) {
            if (p == null) return
            pen.color = col
            for (i in p.indices) { val a = p[i]; val b = p[(i + 1) % p.size]; cv.drawLine(a.x, a.y, b.x, b.y, pen) }
        }
        val ingr = seg.poligonoDaPunto(P(500f, 1350f), 600.0 * 1000.0 * k * k)
        println("INGRESSO: ${ingr?.size} " + ingr?.joinToString { "(${it.x.toInt()},${it.y.toInt()})" })
        disegna(ingr, Color.RED)
        // stima AI spostata come nello screenshot (fondo a 1645 invece di ~1715)
        val ai = listOf(P(420f, 675f), P(810f, 675f), P(810f, 1645f), P(195f, 1645f), P(195f, 1125f), P(300f, 1125f), P(300f, 900f), P(420f, 900f))
        val agg = seg.agganciaAiMuri(ai)
        println("AGGANCIO: " + agg.joinToString { "(${it.x.toInt()},${it.y.toInt()})" })
        disegna(agg, Color.BLUE)
        disegna(seg.poligonoDaPunto(P(170f, 600f)), Color.MAGENTA)
        java.io.File("build/anteprime").mkdirs()
        java.io.FileOutputStream("build/anteprime/ingresso_reale.png").use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun screenshotPulito(nome: String): Bitmap? {
        val stream = javaClass.classLoader!!.getResourceAsStream(nome) ?: return null
        val bmp = android.graphics.BitmapFactory.decodeStream(stream).copy(Bitmap.Config.ARGB_8888, true)
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        for (i in px.indices) { val c = px[i]; if (Color.green(c) - Color.red(c) >= 20) px[i] = Color.WHITE }
        bmp.setPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return bmp
    }

    /** Negozio a L con vetrina larga su strada e ripostiglio sopra: anteprima di controllo. */
    @Test
    fun negozioReale() {
        val bmp = screenshotPulito("negozio.jpg") ?: return
        val k = bmp.width / 923f
        fun P(x: Float, y: Float) = PointF(x * k, y * k)
        // Toglie l'etichetta "ripost. - ripostiglio" disegnata dall'app (testo bianco con ombra scura).
        Canvas(bmp).drawRect(430 * k, 660 * k, 634 * k, 688 * k, Paint().apply { color = Color.WHITE })
        val regione = Rect(0, (225 * k).toInt(), bmp.width, (1880 * k).toInt())
        val seg = SegmentazionePlanimetria(bmp, regione, null)
        println("NEGOZIO utilizzabile=${seg.utilizzabile} frazione=${seg.frazioneMuri} raggio=${seg.raggioPorte}")
        val ai = listOf(P(342f, 812f), P(480f, 813f), P(612f, 812f), P(615f, 1572f), P(218f, 1572f), P(218f, 1245f), P(340f, 1240f))
        val areaAi = area(ai).toDouble()
        val pol = seg.poligonoDaPunto(P(470f, 1000f), areaAi)
        println("NEGOZIO fill: ${pol?.size} " + pol?.joinToString { "(${(it.x / k).toInt()},${(it.y / k).toInt()})" })
        val agg = seg.agganciaAiMuri(ai)
        println("NEGOZIO aggancio: " + agg.joinToString { "(${(it.x / k).toInt()},${(it.y / k).toInt()})" })
        val rip = seg.poligonoDaPunto(P(530f, 690f))
        println("RIPOST: " + rip?.joinToString { "(${(it.x / k).toInt()},${(it.y / k).toInt()})" })
        val out = bmp.copy(Bitmap.Config.ARGB_8888, true); val cv = Canvas(out)
        val pen = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 5f }
        fun disegna(p: List<PointF>?, col: Int) { if (p == null) return; pen.color = col
            for (i in p.indices) { val a = p[i]; val b = p[(i + 1) % p.size]; cv.drawLine(a.x, a.y, b.x, b.y, pen) } }
        disegna(pol, Color.RED); disegna(agg, Color.BLUE); disegna(rip, Color.MAGENTA)
        java.io.File("build/anteprime").mkdirs()
        java.io.FileOutputStream("build/anteprime/negozio_reale.png").use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Appartamento con molte porte (disimpegno centrale): anteprima di controllo. */
    @Test
    fun porteReale() {
        val bmp = screenshotPulito("porte.jpg") ?: return
        val k = bmp.width / 923f
        fun P(x: Float, y: Float) = PointF(x * k, y * k)
        // via le etichette dell'app (testo bianco con ombra) nei vani
        val bianco = Paint().apply { color = Color.WHITE }
        val cv0 = Canvas(bmp)
        listOf(floatArrayOf(340f, 608f, 450f, 638f), floatArrayOf(670f, 655f, 785f, 685f), floatArrayOf(130f, 903f, 240f, 933f),
            floatArrayOf(435f, 923f, 550f, 953f), floatArrayOf(470f, 1040f, 640f, 1070f), floatArrayOf(735f, 1130f, 850f, 1160f),
            floatArrayOf(50f, 1190f, 300f, 1220f), floatArrayOf(370f, 1213f, 580f, 1243f), floatArrayOf(850f, 903f, 923f, 930f)
        ).forEach { r -> cv0.drawRect(r[0] * k, r[1] * k, r[2] * k, r[3] * k, bianco) }
        val regione = Rect(0, (440 * k).toInt(), bmp.width, (1460 * k).toInt())
        val seg = SegmentazionePlanimetria(bmp, regione, null)
        println("PORTE utilizzabile=${seg.utilizzabile} frazione=${seg.frazioneMuri} raggio=${seg.raggioPorte}")
        val semi = linkedMapOf("L1" to P(390f, 700f), "L2" to P(760f, 720f), "L8" to P(190f, 960f), "L4" to P(520f, 900f),
            "rip" to P(600f, 1080f), "cucina" to P(480f, 1320f), "L7" to P(790f, 1250f), "wc" to P(850f, 960f), "scala" to P(180f, 1380f))
        val out = bmp.copy(Bitmap.Config.ARGB_8888, true); val cv = Canvas(out)
        val pen = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 5f; color = Color.RED }
        semi.forEach { (nome, p) ->
            val pol = seg.poligonoDaPunto(p)
            println("PORTE $nome: ${pol?.size} " + (pol?.joinToString { "(${(it.x / k).toInt()},${(it.y / k).toInt()})" } ?: ""))
            if (pol != null) for (i in pol.indices) { val a = pol[i]; val b = pol[(i + 1) % pol.size]; cv.drawLine(a.x, a.y, b.x, b.y, pen) }
        }
        java.io.File("build/anteprime").mkdirs()
        java.io.FileOutputStream("build/anteprime/porte_reale.png").use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Piano inferiore piccolo dentro un foglio con due piante: anteprima di controllo. */
    @Test
    fun pianoSottoReale() {
        val bmp = screenshotPulito("piano_sotto.jpg") ?: return
        val k = bmp.width / 923f
        fun P(x: Float, y: Float) = PointF(x * k, y * k)
        val bianco = Paint().apply { color = Color.WHITE }
        val cv0 = Canvas(bmp)
        listOf(floatArrayOf(255f, 1352f, 460f, 1382f), floatArrayOf(295f, 1443f, 395f, 1473f), floatArrayOf(432f, 1440f, 532f, 1470f),
            floatArrayOf(245f, 1552f, 600f, 1582f)).forEach { r -> cv0.drawRect(r[0] * k, r[1] * k, r[2] * k, r[3] * k, bianco) }
        println("ZONE: " + RilevatoreZoneDisegno.zone(bmp).joinToString { "(${(it.left / k).toInt()},${(it.top / k).toInt()})-(${(it.right / k).toInt()},${(it.bottom / k).toInt()})" })
        val regione = Rect((60 * k).toInt(), (1230 * k).toInt(), (880 * k).toInt(), (1720 * k).toInt())
        val seg = SegmentazionePlanimetria(bmp, regione, null)
        println("SOTTO utilizzabile=${seg.utilizzabile} frazione=${seg.frazioneMuri} raggio=${seg.raggioPorte}")
        val semi = linkedMapOf("cantina" to P(355f, 1300f), "loc1" to P(345f, 1480f), "loc2" to P(480f, 1480f),
            "legnaia" to P(350f, 1620f), "scala" to P(480f, 1660f))
        val out = bmp.copy(Bitmap.Config.ARGB_8888, true); val cv = Canvas(out)
        val pen = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.RED }
        semi.forEach { (nome, p) ->
            val pol = seg.poligonoDaPunto(p)
            println("SOTTO $nome: ${pol?.size} " + (pol?.joinToString { "(${(it.x / k).toInt()},${(it.y / k).toInt()})" } ?: ""))
            if (pol != null) for (i in pol.indices) { val a = pol[i]; val b = pol[(i + 1) % pol.size]; cv.drawLine(a.x, a.y, b.x, b.y, pen) }
        }
        java.io.File("build/anteprime").mkdirs()
        java.io.FileOutputStream("build/anteprime/sotto_reale.png").use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** PDF catastale reale (garage a comune) reso a 4000 px come nell'app. */
    @Test
    fun garagePdf() {
        val stream = javaClass.classLoader!!.getResourceAsStream("pdf_garage.png") ?: return
        val bmp = android.graphics.BitmapFactory.decodeStream(stream).copy(Bitmap.Config.ARGB_8888, true)
        println("GARAGE zone: " + RilevatoreZoneDisegno.zone(bmp).joinToString())
        val regione = Rect(900, 1050, 2200, 2200)
        val seg = SegmentazionePlanimetria(bmp, regione, null)
        println("GARAGE utilizzabile=${seg.utilizzabile} frazione=${seg.frazioneMuri} raggio=${seg.raggioPorte}")
        // coordinate nel ritaglio 900x796 (scala 1300/900) -> immagine
        val s = 1300f / 900f
        fun P(x: Float, y: Float) = PointF(900 + x * s, 1050 + y * s)
        val pol = seg.poligonoDaPunto(P(110f, 380f))
        println("GARAGE: " + pol?.joinToString { "(${((it.x - 900) / s).toInt()},${((it.y - 1050) / s).toInt()})" })
        val out = bmp.copy(Bitmap.Config.ARGB_8888, true); val cv = Canvas(out)
        val pen = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 6f; color = Color.RED }
        if (pol != null) for (i in pol.indices) { val a = pol[i]; val b = pol[(i + 1) % pol.size]; cv.drawLine(a.x, a.y, b.x, b.y, pen) }
        java.io.File("build/anteprime").mkdirs()
        java.io.FileOutputStream("build/anteprime/garage.png").use { Bitmap.createBitmap(out, 900, 1050, 1300, 1150).compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Foglio con due piante (screenshot v3.70): pianta superiore e inferiore. */
    @Test
    fun duePianiReale() {
        val bmp = screenshotPulito("due_piani.jpg") ?: return
        val k = bmp.width / 923f
        fun P(x: Float, y: Float) = PointF(x * k, y * k)
        // etichette dell'app: si cancellano le zone di testo bianco con ombra (approssimate)
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        println("ZONE2: " + RilevatoreZoneDisegno.zone(bmp).joinToString { "(${(it.left / k).toInt()},${(it.top / k).toInt()})-(${(it.right / k).toInt()},${(it.bottom / k).toInt()})" })
        val out = bmp.copy(Bitmap.Config.ARGB_8888, true); val cv = Canvas(out)
        val pen = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.RED }
        fun prova(regione: Rect, semi: Map<String, PointF>) {
            val seg = SegmentazionePlanimetria(bmp, regione, null)
            println("DUE utilizzabile=${seg.utilizzabile} frazione=${seg.frazioneMuri}")
            semi.forEach { (nome, p) ->
                val pol = seg.poligonoDaPunto(p)
                println("DUE $nome: ${pol?.size} " + (pol?.joinToString { "(${(it.x / k).toInt()},${(it.y / k).toInt()})" } ?: ""))
                if (pol != null) for (i in pol.indices) { val a = pol[i]; val b = pol[(i + 1) % pol.size]; cv.drawLine(a.x, a.y, b.x, b.y, pen) }
            }
        }
        prova(Rect((100 * k).toInt(), (460 * k).toInt(), (900 * k).toInt(), (980 * k).toInt()), linkedMapOf(
            "L1" to P(220f, 600f), "L2" to P(400f, 600f), "wc" to P(205f, 705f), "disimp" to P(350f, 712f), "L9" to P(495f, 700f),
            "rip" to P(310f, 752f), "L6" to P(215f, 840f), "cucina" to P(370f, 870f), "scala" to P(500f, 850f)))
        prova(Rect((100 * k).toInt(), (1320 * k).toInt(), (900 * k).toInt(), (1800 * k).toInt()), linkedMapOf(
            "cantina" to P(390f, 1380f), "L1" to P(380f, 1555f), "legnaia" to P(380f, 1690f), "scala" to P(500f, 1700f)))
        java.io.File("build/anteprime").mkdirs()
        java.io.FileOutputStream("build/anteprime/due_piani.png").use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
