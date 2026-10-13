package it.geometra.schedacliente

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import org.robolectric.RuntimeEnvironment
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/** Non verifica nulla: produce PNG di anteprima delle grafiche per controllarle a occhio. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
class AnteprimaGraficheTest {

    private val uscita = File(System.getProperty("user.dir"), "build/anteprime").apply { mkdirs() }

    @Test
    fun riepilogoDrive() {
        val ctx = RuntimeEnvironment.getApplication()
        val ora = System.currentTimeMillis()
        val voci = listOf(
            AvvistamentiFdmLocaliStore.Avvistamento("mondiale", ora - 60_000),
            AvvistamentiFdmLocaliStore.Avvistamento("galli", ora - 120_000),
            AvvistamentiFdmLocaliStore.Avvistamento("generale", ora - 180_000),
            AvvistamentiFdmLocaliStore.Avvistamento("generale", ora - 86_400_000L * 2),
            AvvistamentiFdmLocaliStore.Avvistamento("celli", ora - 86_400_000L * 3),
            AvvistamentiFdmLocaliStore.Avvistamento("fratello", ora - 86_400_000L * 4)
        )
        val oggi = voci.filter { RiepilogoFdmImmagine.eDiOggi(it.ts) }
        val png = RiepilogoFdmImmagine.genera(ctx, "Bar Pasticceria La Dolce Via", oggi, voci, voci)
        File(uscita, "riepilogo.png").writeBytes(png)
    }

    @Test
    fun schermataRuota() {
        val ctx = RuntimeEnvironment.getApplication()
        listOf("galli", "galli", "mondiale", "generale", "celli", "fratello").forEach {
            AvvistamentiFdmLocaliStore.aggiungi(ctx, it)
        }
        val activity = Robolectric.buildActivity(RuotaCaffeActivity::class.java).setup().get()
        val root = activity.window.decorView.rootView
        val w = 1233; val h = 7000
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.AT_MOST))
        // Disegna il contenuto scorrevole per intero.
        val contenuto = activity.findViewById<View>(android.R.id.content)
        val scroll = (contenuto as android.view.ViewGroup).getChildAt(0)
        val inner = ((scroll as android.view.ViewGroup).getChildAt(1) as android.view.ViewGroup).getChildAt(0)
        inner.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        inner.layout(0, 0, inner.measuredWidth, inner.measuredHeight)
        val bmp = Bitmap.createBitmap(inner.measuredWidth, inner.measuredHeight, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); c.drawColor(Color.parseColor("#F5F3EE")); inner.draw(c)
        FileOutputStream(File(uscita, "schermata.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun schermataParereMega() {
        val ctx = RuntimeEnvironment.getApplication()
        val cliente = Cliente(
            cognome = "Rossi", nome = "Mario", codiceFiscale = "", partitaIva = "", telefono = "", email = "",
            indirizzo = "Via Roma 1", provincia = "AR", comune = "Castelfranco Piandiscò", tipoPratica = "Visura",
            numeroPratica = "12", annoPratica = "2026", foglio = "15", particella = "281", subalterno = "3", note = "",
            linkMega = "https://mega.nz/folder/H1h2H3h4#AbCdEfGhIjKlMnOpQrStUv"
        )
        ClienteStore(ctx).salva(cliente)
        val intent = android.content.Intent(ctx, ParereMegaActivity::class.java).putExtra(ParereMegaActivity.EXTRA_CLIENTE_ID, cliente.id)
        val activity = Robolectric.buildActivity(ParereMegaActivity::class.java, intent).setup().get()
        // Contenuto finto (senza rete), per vedere l'aspetto della navigazione.
        val c = MegaCartellaPubblica.FileMega::class.java.declaredConstructors.first { it.parameterCount == 7 }
        fun f(h: String, nome: String, percorso: String, kb: Long, gen: String) =
            c.newInstance(h, nome, percorso, kb * 1024, ByteArray(16), ByteArray(16), gen) as MegaCartellaPubblica.FileMega
        val radice = MegaCartellaPubblica.CartellaMega("R", "Pratica Rossi", "", "")
        val cartelle = listOf(
            MegaCartellaPubblica.CartellaMega("C", "Catasto", "R", "Catasto"),
            MegaCartellaPubblica.CartellaMega("O", "Corrispondenza", "R", "Corrispondenza"),
            MegaCartellaPubblica.CartellaMega("F", "Foto", "R", "Foto")
        )
        val file = listOf(
            f("a", "visura catastale.pdf", "Catasto/visura catastale.pdf", 180, "C"),
            f("b", "planimetria.jpg", "Catasto/planimetria.jpg", 2300, "C"),
            f("g", "estratto di mappa.pdf", "Catasto/estratto di mappa.pdf", 320, "C"),
            f("d", "lettera Comune.doc", "Corrispondenza/lettera Comune.doc", 30, "O"),
            f("e", "foto facciata.jpg", "Foto/foto facciata.jpg", 4100, "F"),
            f("c", "incarico firmato.pdf", "incarico firmato.pdf", 95, "R")
        )
        val contenuto = MegaCartellaPubblica.Contenuto(radice, cartelle, file)
        ParereMegaActivity::class.java.getDeclaredField("contenuto").apply { isAccessible = true }.set(activity, contenuto)
        @Suppress("UNCHECKED_CAST")
        (ParereMegaActivity::class.java.getDeclaredField("selezionati").apply { isAccessible = true }.get(activity) as MutableSet<String>).addAll(listOf("a", "b"))
        val apri = ParereMegaActivity::class.java.getDeclaredMethod("apriCartella", String::class.java).apply { isAccessible = true }
        activity.findViewById<View>(R.id.progressCartella).visibility = View.GONE
        activity.findViewById<android.widget.TextView>(R.id.tvStatoCartella).text = "Apri le cartelle e spunta solo i file utili al documento."
        activity.findViewById<View>(R.id.rigaSelezione).visibility = View.VISIBLE
        apri.invoke(activity, "R")
        salvaSchermata(activity, "parere_mega_radice.png")
        apri.invoke(activity, "C")
        salvaSchermata(activity, "parere_mega_catasto.png")
    }

    @Test
    fun schermataPerizia() {
        val ctx = RuntimeEnvironment.getApplication()
        val locali = listOf(
            LocaleTracciato("Soggiorno", emptyList(), 20.4, emptyList()),
            LocaleTracciato("Camera", emptyList(), 14.1, emptyList()),
            LocaleTracciato("Cucina", emptyList(), 10.0, emptyList()),
            LocaleTracciato("Bagno", emptyList(), 5.2, emptyList()),
            LocaleTracciato("Disimpegno", emptyList(), 4.0, emptyList()),
            LocaleTracciato("Terrazza", emptyList(), 30.0, emptyList()),
            LocaleTracciato("Cantina", emptyList(), 8.0, emptyList())
        )
        val intent = android.content.Intent(ctx, PeriziaUnitaActivity::class.java)
            .putExtra(PeriziaUnitaActivity.EXTRA_LOCALI, PeriziaUnitaActivity.localiInJson(locali))
        val activity = Robolectric.buildActivity(PeriziaUnitaActivity::class.java, intent).setup().get()
        activity.findViewById<android.widget.EditText>(R.id.etMuri).setText("10")
        activity.findViewById<android.widget.EditText>(R.id.etValoreUnitario).setText("1375")
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        salvaSchermata(activity, "perizia.png")
    }

    private fun salvaSchermata(activity: android.app.Activity, nome: String) {
        val scroll = (activity.findViewById<View>(android.R.id.content) as android.view.ViewGroup).getChildAt(0) as android.view.ViewGroup
        val inner = (scroll.getChildAt(1) as android.view.ViewGroup).getChildAt(0)
        val w = 1233
        inner.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        inner.layout(0, 0, inner.measuredWidth, inner.measuredHeight)
        val bmp = Bitmap.createBitmap(inner.measuredWidth, inner.measuredHeight, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp); cv.drawColor(Color.parseColor("#F5F3EE")); inner.draw(cv)
        FileOutputStream(File(uscita, nome)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
