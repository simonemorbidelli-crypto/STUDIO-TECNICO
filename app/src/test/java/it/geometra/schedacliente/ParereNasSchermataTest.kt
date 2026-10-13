package it.geometra.schedacliente

import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ListView
import android.widget.TextView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * Schermata "Parere / relazione dal NAS" (v3.78) collegata a un NAS di prova (vedi [NasStudioTest]
 * per le variabili d'ambiente; senza NAS di prova le prove vengono saltate). Produce anche le
 * anteprime in build/anteprime/v378_*.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w393dp-h852dp-xxhdpi")
class ParereNasSchermataTest {

    @After fun pulisci() { NasCredenzialiStore.accessoPerTest = null }

    private fun preparaNas() {
        val percorso = System.getenv("NAS_PROVA_PERCORSO")
        assumeTrue("NAS di prova non configurato: prova saltata", percorso != null)
        NasCredenzialiStore.accessoPerTest = NasStudio.Accesso(
            NasStudio.analizzaPercorso(percorso!!)!!, System.getenv("NAS_PROVA_UTENTE") ?: "", System.getenv("NAS_PROVA_PASSWORD") ?: ""
        )
    }

    private fun cliente(cognome: String, nome: String): Cliente {
        val c = Cliente(cognome = cognome, nome = nome, codiceFiscale = "", partitaIva = "", telefono = "", email = "",
            indirizzo = "Via Roma 1", provincia = "AR", comune = "Castelfranco Piandiscò", tipoPratica = "Visura",
            numeroPratica = "12", annoPratica = "2026", foglio = "15", particella = "281", subalterno = "3", note = "")
        ClienteStore(RuntimeEnvironment.getApplication()).salva(c)
        return c
    }

    private fun apri(c: Cliente?) = Robolectric.buildActivity(ParereMegaActivity::class.java,
        Intent(RuntimeEnvironment.getApplication(), ParereMegaActivity::class.java).apply {
            c?.let { putExtra(ParereMegaActivity.EXTRA_CLIENTE_ID, it.id) }
            putExtra(ParereMegaActivity.EXTRA_ORIGINE, ParereMegaActivity.ORIGINE_NAS)
        }).setup().get()

    /** Attende i thread di rete e consegna i loro risultati al thread principale. */
    private fun attendi(fino: () -> Boolean) {
        repeat(200) { if (fino()) return; Thread.sleep(50); shadowOf(Looper.getMainLooper()).idle() }
    }

    private fun testi(v: View): List<String> = when (v) {
        is TextView -> listOf(v.text.toString())
        is ViewGroup -> (0 until v.childCount).flatMap { testi(v.getChildAt(it)) }
        else -> emptyList()
    }

    private fun salva(v: View, nome: String) {
        val w = 393 * 3; val h = 852 * 3
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply { drawColor(Color.WHITE); v.draw(this) }
        java.io.File("build/anteprime").mkdirs()
        java.io.FileOutputStream("build/anteprime/$nome.png").use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun trovaDaSolaLaCartellaDelClienteELaMemorizza() {
        preparaNas()
        val c = cliente("Rossi", "Mario")
        val a = apri(c)
        val lista = a.findViewById<ViewGroup>(R.id.listaFile)
        attendi { testi(lista).any { it == "Catasto" } }
        assertEquals("📂 Rossi Mario", a.findViewById<TextView>(R.id.tvPercorso).text.toString())
        val voci = testi(lista)
        assertTrue(voci.toString(), voci.containsAll(listOf("Catasto", "Corrispondenza", "Foto sopralluogo", "note.txt")))
        assertTrue("Thumbs.db nascosto", voci.none { it == "Thumbs.db" })
        // Memorizzata nella scheda: la prossima volta si apre direttamente.
        assertEquals("Rossi Mario", ClienteStore(a).caricaTutti().first { it.id == c.id }.cartellaNas)
        salva(a.window.decorView, "v378_nas_cartella_cliente")

        // Si entra in "Catasto" e si spunta la visura.
        fun riga(testo: String): View {
            fun cerca(v: View): View? = when {
                v is TextView && v.text.toString() == testo -> v
                v is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { cerca(v.getChildAt(it)) }
                else -> null
            }
            var r: View = cerca(lista)!!
            while (r.parent !== lista) r = r.parent as View
            return r
        }
        riga("Catasto").performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("📂 Rossi Mario › Catasto", a.findViewById<TextView>(R.id.tvPercorso).text.toString())
        riga("visura catastale.pdf").performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val riepilogo = a.findViewById<TextView>(R.id.tvRiepilogoSelezione).text.toString()
        assertTrue(riepilogo, riepilogo.startsWith("✓ 1 file selezionati") && riepilogo.contains("Catasto/visura catastale.pdf"))
        assertTrue(a.findViewById<View>(R.id.btnGenera).isEnabled)
        salva(a.window.decorView, "v378_nas_file_scelto")
    }

    @Test
    fun piuCartelleCompatibiliSiSceglieDallElenco() {
        preparaNas()
        // "Rossi Anna" non esiste: compatibili col cognome sono Rossi Mario e Rossi Giuseppe.
        val c = cliente("Rossi", "Anna")
        val a = apri(c)
        attendi { ShadowDialog.getLatestDialog()?.isShowing == true }
        val dialog: Dialog = ShadowDialog.getLatestDialog()
        assertNotNull(dialog)
        val elenco = testi(dialog.window!!.decorView.rootView)
        assertTrue(elenco.toString(), elenco.any { it.startsWith("Non ho trovato una cartella") || it.startsWith("Ho trovato più cartelle") })
        val lv = (dialog.window!!.decorView as ViewGroup).let { radice ->
            fun cerca(v: View): ListView? = when (v) {
                is ListView -> v
                is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { cerca(v.getChildAt(it)) }
                else -> null
            }
            cerca(radice)!!
        }
        val voci = (0 until lv.adapter.count).map { lv.adapter.getItem(it).toString() }
        assertEquals("le compatibili in cima, con ★", listOf("★ Rossi Mario", "★ Rossi Giuseppe"), voci.take(2))
        assertTrue(voci.contains("Bianchi Luca - Via Roma 12"))
        salva(dialog.window!!.decorView, "v378_nas_scelta_cartella")
        // Scelta di "Rossi Giuseppe".
        lv.performItemClick(null, 1, 0)
        attendi { a.findViewById<TextView>(R.id.tvPercorso).text.toString() == "📂 Rossi Giuseppe" }
        assertEquals("📂 Rossi Giuseppe", a.findViewById<TextView>(R.id.tvPercorso).text.toString())
        assertEquals("Rossi Giuseppe", ClienteStore(a).caricaTutti().first { it.id == c.id }.cartellaNas)
    }

    @Test
    fun cartellaMemorizzataSparitaSiRicerca() {
        preparaNas()
        val c = cliente("De Santis", "Anna").copy(cartellaNas = "De Santis Anna (rinominata)")
        ClienteStore(RuntimeEnvironment.getApplication()).aggiorna(c)
        val a = apri(c)
        attendi { a.findViewById<TextView>(R.id.tvPercorso).text.toString() == "📂 De Santis Anna" || ShadowDialog.getLatestDialog()?.isShowing == true }
        // La cartella memorizzata non c'è più: si chiede quale usare, con De Santis Anna proposta in cima.
        val dialog = ShadowDialog.getLatestDialog()
        assertNotNull("deve chiedere di scegliere di nuovo", dialog)
        assertTrue(testi(dialog.window!!.decorView).any { it.contains("non c'è più sul NAS") })
    }

    @Test
    fun senzaNasImpostatoRimandaAlleImpostazioni() {
        NasCredenzialiStore.accessoPerTest = null
        // Nel simulatore il portachiavi cifrato non c'è: se la lettura delle impostazioni fallisce,
        // la prova non è significativa e viene saltata.
        val impostato = try { NasCredenzialiStore.accesso(RuntimeEnvironment.getApplication()) != null } catch (e: Throwable) { null }
        assumeTrue(impostato == false)
        apri(null)
        val d = ShadowDialog.getLatestDialog()
        assertTrue(testi(d.window!!.decorView).any { it == "NAS dello studio non impostato" })
    }
}
