package it.geometra.schedacliente

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.graphics.Rect
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Correzioni v3.76 del rilevamento automatico in PlanimetriaActivity:
 *  1. "Interrompi" ferma anche i risultati ancora in arrivo della fase iniziale (prima un calcolo
 *     o una risposta AI già partiti facevano ripartire il rilevamento da soli);
 *  2. la conferma automatica dopo 3 s non scorre con l'app in secondo piano.
 * Le funzioni interne dell'Activity si chiamano per riflessione (sono private).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RilevamentoInterruzioneTest {

    private fun campo(a: Any, nome: String) = PlanimetriaActivity::class.java.getDeclaredField(nome).apply { isAccessible = true }
    private fun leggi(a: Any, nome: String): Any? = campo(a, nome).get(a)
    private fun scrivi(a: Any, nome: String, v: Any?) = campo(a, nome).set(a, v)
    private fun metodo(nome: String) = PlanimetriaActivity::class.java.declaredMethods.first { it.name == nome }.apply { isAccessible = true }

    private fun proposta(nome: String): Any {
        val cls = Class.forName("it.geometra.schedacliente.PlanimetriaActivity\$PropostaLocaleAi")
        val c = cls.declaredConstructors.first { it.parameterCount == 4 }.apply { isAccessible = true }
        val quadrato = listOf(PointF(10f, 10f), PointF(110f, 10f), PointF(110f, 110f), PointF(10f, 110f))
        return c.newInstance(nome, quadrato, null, true)
    }

    /** Avvia la misura dei contorni in background come fa il rilevamento; restituisce un flag "risultato consegnato". */
    private fun avviaMisura(activity: PlanimetriaActivity): java.util.concurrent.atomic.AtomicBoolean {
        scrivi(activity, "rilevamentoAutomaticoInCorso", true)
        scrivi(activity, "sessioneRilevamento", (leggi(activity, "sessioneRilevamento") as Int) + 1)
        val consegnato = java.util.concurrent.atomic.AtomicBoolean(false)
        val bmp = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val fatto: (MutableList<Any>) -> Unit = { consegnato.set(true) }
        metodo("misuraLocaliSulDisegno").invoke(activity, bmp, Rect(0, 0, 300, 300), emptyList<Any>(), fatto)
        return consegnato
    }

    /** Lascia finire il thread di calcolo e consegna i messaggi al thread principale. */
    private fun attendi() {
        repeat(60) { Thread.sleep(50); shadowOf(Looper.getMainLooper()).idle() }
    }

    @Test
    fun senzaInterruzioneIlRisultatoDellaMisuraArriva() {
        val activity = Robolectric.buildActivity(PlanimetriaActivity::class.java).setup().get()
        val consegnato = avviaMisura(activity)
        attendi()
        assertTrue("controllo: senza interruzione il risultato deve arrivare", consegnato.get())
    }

    @Test
    fun interrompiDuranteLaMisuraScartaIlRisultato() {
        val activity = Robolectric.buildActivity(PlanimetriaActivity::class.java).setup().get()
        val consegnato = avviaMisura(activity)
        metodo("annullaRilevamentoAutomatico").invoke(activity)
        attendi()
        assertFalse("dopo \"Interrompi\" il rilevamento non deve ripartire da solo", consegnato.get())
        assertFalse(leggi(activity, "rilevamentoAutomaticoInCorso") as Boolean)
    }

    @Test
    fun saltaLocaleNellaFaseInizialeNonChiudeIlRilevamento() {
        val activity = Robolectric.buildActivity(PlanimetriaActivity::class.java).setup().get()
        avviaMisura(activity)
        metodo("saltaLocaleInElaborazione").invoke(activity)
        assertTrue("\"Salta locale\" senza un locale in esame non deve terminare tutto", leggi(activity, "rilevamentoAutomaticoInCorso") as Boolean)
        attendi()
    }

    @Test
    fun confermaAutomaticaFermaConAppInSecondoPiano() {
        val controller = Robolectric.buildActivity(PlanimetriaActivity::class.java).setup()
        val activity = controller.get()
        scrivi(activity, "rilevamentoAutomaticoInCorso", true)
        val locali = leggi(activity, "locali") as List<*>
        val generazione = leggi(activity, "generazioneElaborazioneLocale") as Int
        metodo("chiediCorrezioneManuale").invoke(activity, proposta("Cucina"), generazione)
        val looper = shadowOf(Looper.getMainLooper())

        // App in secondo piano: passano 10 secondi, il locale NON deve confermarsi da solo.
        controller.pause()
        looper.idleFor(Duration.ofSeconds(10))
        assertEquals(0, locali.size)

        // Di nuovo in primo piano: dopo i 3 secondi il locale si conferma.
        controller.resume()
        looper.idleFor(Duration.ofMillis(1500))
        assertEquals("a metà del conto non è ancora confermato", 0, locali.size)
        looper.idleFor(Duration.ofSeconds(2))
        assertEquals(1, locali.size)
    }
}
