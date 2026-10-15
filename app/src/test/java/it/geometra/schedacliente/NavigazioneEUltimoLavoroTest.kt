package it.geometra.schedacliente

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.PointF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NavigazioneEUltimoLavoroTest {

    private fun titoloDialogo(d: AlertDialog): String =
        d.findViewById<android.widget.TextView>(androidx.appcompat.R.id.alertTitle)?.text?.toString()
            ?: shadowOf(d).title.toString()

    @Test
    fun indietroDallaPaginaInizialeChiedeConfermaDiUscita() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        a.onBackPressedDispatcher.onBackPressed()
        val d = ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull(d)
        assertTrue(titoloDialogo(d), titoloDialogo(d).contains("Uscire"))
        assertTrue(!a.isFinishing)
    }

    @Test
    fun indietroInUnaSezioneChiedeSeTornareAllaPaginaIniziale() {
        val a = Robolectric.buildActivity(CalcoliActivity::class.java).setup().get()
        a.onBackPressedDispatcher.onBackPressed()
        val d = ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull(d)
        assertTrue(titoloDialogo(d), titoloDialogo(d).contains("pagina iniziale"))
        // "Pagina iniziale" apre MainActivity con CLEAR_TOP
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        val intent = shadowOf(a).nextStartedActivity
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertTrue(intent.flags and android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
    }

    @Test
    fun ultimoLavoroSalvatoERipreso() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        java.io.File(ctx.filesDir, "ultimo_lavoro_planimetria").deleteRecursively()
        assertNull(ProgettoStore.riepilogoUltimo(ctx))
        val bmp = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888)
        val locali = listOf(
            LocaleTracciato("Cucina", listOf(PointF(1f, 1f), PointF(10f, 1f), PointF(10f, 8f)), 12.5, listOf(3.0, 4.0, 5.0))
        )
        ProgettoStore.salvaUltimo(ctx, bmp, 0.02, 0.02, locali, true)
        val r = ProgettoStore.riepilogoUltimo(ctx)
        assertNotNull(r)
        assertEquals(1, r!!.numeroLocali)
        // non compare nell'elenco dei progetti salvati
        assertTrue(ProgettoStore.elenca(ctx).none { it.nome == "Ultimo lavoro" })
        // seconda scrittura senza immagine: l'immagine resta
        ProgettoStore.salvaUltimo(ctx, bmp, 0.02, 0.02, locali + locali, false)
        val p = ProgettoStore.caricaUltimo(ctx)
        assertNotNull(p)
        assertEquals(2, p!!.locali.size)
        assertEquals("Cucina", p.locali[0].nome)
        assertEquals(0.02, p.scalaX!!, 1e-9)
        assertEquals(40, p.bitmap.width)
    }

    @Test
    fun sezioneInformazioniHaITreSottomenu() {
        val a = Robolectric.buildActivity(InformazioniActivity::class.java).setup().get()
        val testo = StringBuilder()
        fun giu(v: android.view.View) {
            if (v is android.widget.TextView) testo.append(v.text).append('\n')
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) giu(v.getChildAt(i))
        }
        giu(a.window.decorView)
        val t = testo.toString()
        assertTrue(t, t.contains("Chi sono e storia del progetto"))
        assertTrue(t, t.contains("Guida all'utilizzo"))
        assertTrue(t, t.contains("Copyright"))
    }
}
