package it.geometra.schedacliente

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PointF
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.google.android.material.button.MaterialButton
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/**
 * v3.77: anteprime grafiche (in build/anteprime) e controlli di
 *  - nomi dei locali leggibili sul riempimento verde;
 *  - pulsanti "+ vertice / − vertice / annulla / nome" allineati;
 *  - avvisi in alto sotto la barra verde;
 *  - animazione della scala: punto 1, punto 2, poi linea intera con la misura in metri.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w393dp-h852dp-xxhdpi")
class AnteprimaPlanimetriaTest {

    private val looper get() = shadowOf(Looper.getMainLooper())
    private fun metodo(nome: String) = PlanimetriaActivity::class.java.declaredMethods.first { it.name == nome }.apply { isAccessible = true }
    private fun campo(a: Any, nome: String) = PlanimetriaActivity::class.java.getDeclaredField(nome).apply { isAccessible = true }

    private fun salva(bmp: Bitmap, nome: String) {
        java.io.File("build/anteprime").mkdirs()
        java.io.FileOutputStream("build/anteprime/$nome.png").use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun disegna(v: View, w: Int, h: Int, esatta: Boolean = true): Bitmap {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, if (esatta) View.MeasureSpec.EXACTLY else View.MeasureSpec.AT_MOST))
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        val bmp = Bitmap.createBitmap(v.measuredWidth, v.measuredHeight, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); c.drawColor(Color.WHITE); v.draw(c)
        return bmp
    }

    /** Pianta catastale reale del banco di prova con i vani misurati sui muri, come nell'app. */
    private fun piantaConLocali(): Pair<Bitmap, List<LocaleTracciato>> {
        val casi = JSONArray(javaClass.classLoader!!.getResourceAsStream("catasto/casi.json")!!.bufferedReader().readText())
        val caso = (0 until casi.length()).map { casi.getJSONObject(it) }.first { it.getString("id") == "t2a" }
        val bmp = BitmapFactory.decodeStream(javaClass.classLoader!!.getResourceAsStream("catasto/t2a.png")).copy(Bitmap.Config.ARGB_8888, true)
        val seg = AnalizzatoreVani(bmp, Rect(0, 0, bmp.width, bmp.height), caso.getDouble("mpp"))
        val nomi = mapOf("camera1" to "Camera", "camera2" to "Camera", "camera3" to "Camera", "ingresso" to "Ingresso",
            "wc" to "W.C.", "cucinaPranzo" to "Pranzo", "cucina" to "Cucina", "soggiorno" to "Soggiorno")
        val vani = caso.getJSONArray("vani")
        val locali = (0 until vani.length()).mapNotNull { i ->
            val v = vani.getJSONObject(i)
            val nome = nomi[v.getString("nome")] ?: return@mapNotNull null
            val pol = seg.misura(PointF(v.getDouble("x").toFloat(), v.getDouble("y").toFloat()), v.getDouble("area"))?.vertici ?: return@mapNotNull null
            LocaleTracciato(nome, pol, 0.0, emptyList())
        }
        return bmp to locali
    }

    @Test
    fun nomiDeiLocaliLeggibili() {
        val activity = Robolectric.buildActivity(PlanimetriaActivity::class.java).setup().get()
        val (bmp, locali) = piantaConLocali()
        assertTrue("servono vani per l'anteprima", locali.size >= 5)
        val v = SuperficieView(activity)
        v.bitmap = bmp
        v.locali.addAll(locali)
        val intera = disegna(v, 1179, 1500)
        salva(intera, "v377_nomi_locali")
        // Il testo dell'etichetta è scuro: al centro di ogni locale deve esserci almeno un pixel quasi nero.
        locali.forEach { l ->
            val c = GeometriaUtils.centroide(l.verticiPx)
            val m = floatArrayOf(c.x, c.y)
            val matrice = SuperficieView::class.java.getDeclaredField("matriceVista").apply { isAccessible = true }.get(v) as android.graphics.Matrix
            matrice.mapPoints(m)
            var scuri = 0
            for (dy in -12..12) for (dx in -40..40) {
                val x = (m[0] + dx).toInt().coerceIn(0, intera.width - 1); val y = (m[1] + dy).toInt().coerceIn(0, intera.height - 1)
                val p = intera.getPixel(x, y)
                if (Color.red(p) < 60 && Color.green(p) < 80 && Color.blue(p) < 60) scuri++
            }
            assertTrue("nome del locale \"${l.nome}\" non leggibile (nessun testo scuro al centro)", scuri > 10)
        }
        // Ingrandito, come quando si lavora a schermo intero.
        v.zoomCentratoSu(GeometriaUtils.centroide(locali[0].verticiPx), 2.5f)
        salva(disegna(v, 1179, 1500), "v377_nomi_locali_zoom")
    }

    @Test
    fun pulsantiDiModificaAllineatiEAvvisoInAlto() {
        for (larghezzaDp in listOf(393, 360)) {
            // La schermata si aggancia a una finestra vera (setContentView), come nell'app: solo così
            // Android risolve le icone "a inizio testo" dei pulsanti e le posizioni sullo schermo.
            val activity = Robolectric.buildActivity(PlanimetriaActivity::class.java).setup().get()
            val radice = activity.layoutInflater.inflate(R.layout.dialog_tracciamento_schermo_intero, null)
            activity.setContentView(radice)
            val barra = radice.findViewById<ViewGroup>(R.id.containerStrumentiModificaSchermoIntero)
            barra.visibility = View.VISIBLE
            radice.findViewById<android.widget.TextView>(R.id.tvIstruzioneSchermoIntero).text =
                "Correggi i vertici di \"Cucina\": tieni premuto un vertice e spostalo con la lente, poi premi \"Conferma modifiche\"."
            radice.findViewById<View>(R.id.btnConfermaModificaSchermoIntero).visibility = View.VISIBLE
            radice.findViewById<View>(R.id.btnAnnullaModificaSchermoIntero).visibility = View.VISIBLE
            radice.findViewById<View>(R.id.btnAnnullaUltimoPuntoSchermoIntero).visibility = View.GONE
            val avviso = radice.findViewById<android.widget.TextView>(R.id.tvAvvisoSchermoIntero)
            avviso.text = "Vertice rimosso"; avviso.visibility = View.VISIBLE
            val (bmp, locali) = piantaConLocali()
            val sv = SuperficieView(activity).apply { this.bitmap = bmp; this.locali.addAll(locali) }
            radice.findViewById<FrameLayout>(R.id.contenitoreSuperficie).addView(sv, FrameLayout.LayoutParams(-1, -1))
            looper.idle()
            salva(disegna(radice, larghezzaDp * 3, 852 * 3), "v377_schermo_intero_${larghezzaDp}dp")

            val pulsanti = listOf(R.id.btnAggiungiVerticeSchermoIntero, R.id.btnRimuoviVerticeSchermoIntero,
                R.id.btnAnnullaVerticeSchermoIntero, R.id.btnRinominaLocaleSchermoIntero).map { radice.findViewById<MaterialButton>(it) }
            assertEquals("pulsanti non allineati in alto (${larghezzaDp}dp)", 1, pulsanti.map { it.top }.toSet().size)
            assertEquals("pulsanti di altezze diverse (${larghezzaDp}dp)", 1, pulsanti.map { it.height }.toSet().size)
            pulsanti.forEach { b ->
                assertEquals("testo su più righe in \"${b.text}\" (${larghezzaDp}dp)", 1, b.lineCount)
                assertEquals("testo troncato in \"${b.text}\" (${larghezzaDp}dp)", 0, b.layout.getEllipsisCount(0))
                // L'icona occupa davvero spazio prima del testo (cioè viene disegnata).
                assertTrue("icona mancante in \"${b.text}\" (${larghezzaDp}dp)", b.compoundPaddingLeft > b.paddingLeft + 10)
            }
            // Tutte le icone alla stessa altezza.
            assertEquals(1, pulsanti.map { it.icon!!.bounds.top }.toSet().size)
            // L'avviso sta in alto (sotto la barra verde), sopra il disegno, lontano dai pulsanti di modifica.
            val posAvviso = IntArray(2).also { avviso.getLocationInWindow(it) }
            val posBarra = IntArray(2).also { barra.getLocationInWindow(it) }
            assertTrue("l'avviso deve stare sopra i pulsanti", posAvviso[1] + avviso.height < posBarra[1])
            assertTrue("l'avviso deve stare in alto", posAvviso[1] < 852 * 3 / 4)
        }
        // Schermata normale: avviso mostrato con la funzione dell'app, sotto la barra verde.
        val activity2 = Robolectric.buildActivity(PlanimetriaActivity::class.java).setup().get()
        metodo("avviso").invoke(activity2, "Vertice rimosso", false)
        looper.idleFor(Duration.ofMillis(300))
        val tv = activity2.findViewById<android.widget.TextView>(R.id.tvAvviso)
        assertEquals(View.VISIBLE, tv.visibility)
        salva(disegna(activity2.window.decorView, 393 * 3, 852 * 3), "v377_avviso_schermata")
        val toolbar = activity2.findViewById<View>(R.id.toolbar)
        val posTv = IntArray(2).also { tv.getLocationInWindow(it) }
        val posTb = IntArray(2).also { toolbar.getLocationInWindow(it) }
        assertTrue("l'avviso deve stare subito sotto la barra verde (avviso y=${posTv[1]}, barra ${posTb[1]}+${toolbar.height})",
            posTv[1] >= posTb[1] + toolbar.height && posTv[1] < posTb[1] + toolbar.height + 60)
        // Pulsanti arancioni con testo su due righe: il testo deve entrare tutto (prima era tagliato).
        listOf(R.id.btnAnalizza, R.id.btnRilevamentoAutomatico, R.id.btnCaricaImmagine).forEach { id ->
            val b = activity2.findViewById<MaterialButton>(id)
            assertTrue("testo tagliato nel pulsante \"${b.text}\"", b.layout.height <= b.height - b.compoundPaddingTop - b.compoundPaddingBottom + 1)
        }
        // Sparisce da solo.
        looper.idleFor(Duration.ofSeconds(3))
        assertEquals(View.GONE, tv.visibility)
    }

    @Test
    fun animazioneDellaScala() {
        val controller = Robolectric.buildActivity(PlanimetriaActivity::class.java).setup()
        val activity = controller.get()
        val (bmp, locali) = piantaConLocali()
        val sv = activity.findViewById<SuperficieView>(R.id.superficieView)
        sv.bitmap = bmp
        // Dimensioni da schermo intero (la vista si misura così nello schermo intero).
        sv.measure(View.MeasureSpec.makeMeasureSpec(1179, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2100, View.MeasureSpec.EXACTLY))
        sv.layout(0, 0, 1179, 2100)
        campo(activity, "rilevamentoAutomaticoInCorso").setBoolean(activity, true)
        val sessione = campo(activity, "sessioneRilevamento").getInt(activity) + 1
        campo(activity, "sessioneRilevamento").setInt(activity, sessione)

        // Due punti sul muro inferiore dell'unità (come le tacche di un tratto di scala).
        val tutti = locali.flatMap { it.verticiPx }
        val yMuro = tutti.maxOf { it.y }
        val p1 = PointF(tutti.minOf { it.x }, yMuro)
        val p2 = PointF(tutti.maxOf { it.x }, yMuro)
        var proseguito = false
        val poi: () -> Unit = { proseguito = true }
        metodo("mostraTrattoScala").invoke(activity, p1, p2, 10.0, "barra blu di prova", sessione, poi)

        fun centro(): PointF = SuperficieView::class.java.getDeclaredMethod("schermoAImmagine", Float::class.java, Float::class.java)
            .apply { isAccessible = true }.invoke(sv, sv.width / 2f, sv.height / 2f) as PointF
        fun zoom() = SuperficieView::class.java.getDeclaredField("fattoreZoomCorrente").apply { isAccessible = true }.getFloat(sv)
        fun foto(nome: String) = salva(Bitmap.createBitmap(sv.width, sv.height, Bitmap.Config.ARGB_8888).also { sv.draw(Canvas(it)) }, nome)

        // 1) zoom sul primo punto
        assertEquals(p1.x, centro().x, 2f); assertEquals(p1.y, centro().y, 2f)
        assertNull(sv.etichettaScala)
        foto("v377_scala_1_primo_punto")
        // 2) zoom sul secondo punto
        looper.idleFor(Duration.ofMillis(1500))
        assertEquals(p2.x, centro().x, 2f); assertEquals(p2.y, centro().y, 2f)
        foto("v377_scala_2_secondo_punto")
        // 3) linea intera con la misura
        looper.idleFor(Duration.ofMillis(1500))
        assertEquals("10 m", sv.etichettaScala)
        assertEquals((p1.x + p2.x) / 2f, centro().x, 2f)
        val schermo = floatArrayOf(p1.x, p1.y, p2.x, p2.y)
        (SuperficieView::class.java.getDeclaredField("matriceVista").apply { isAccessible = true }.get(sv) as android.graphics.Matrix).mapPoints(schermo)
        assertTrue("la linea intera deve stare tutta nella vista", schermo.indices.step(2).all { schermo[it] in 0f..sv.width.toFloat() } && zoom() >= 1f)
        foto("v377_scala_3_linea_intera")
        assertTrue(!proseguito)
        // 4) fine: torna la vista intera e il rilevamento prosegue
        looper.idleFor(Duration.ofMillis(2600))
        assertTrue(proseguito)
        assertNull(sv.etichettaScala)
        assertTrue(sv.puntiScalaEvidenziati.isEmpty())
    }
}
