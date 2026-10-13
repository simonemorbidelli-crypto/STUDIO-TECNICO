package it.geometra.schedacliente

import android.app.AlertDialog
import android.graphics.PointF
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

/** v3.81: "Elimina" per intero il poligono dalla finestra di modifica del locale (con conferma). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EliminaLocaleInModificaTest {

    private fun leggi(a: Any, nome: String): Any? = PlanimetriaActivity::class.java.getDeclaredField(nome).apply { isAccessible = true }.get(a)
    private fun metodo(nome: String) = PlanimetriaActivity::class.java.declaredMethods.first { it.name == nome && !it.isSynthetic }.apply { isAccessible = true }

    private fun locale(nome: String, x: Float) = LocaleTracciato(
        nome, listOf(PointF(x, 10f), PointF(x + 100, 10f), PointF(x + 100, 110f), PointF(x, 110f)), 25.0, listOf(5.0, 5.0, 5.0, 5.0)
    )

    private fun preparaConDueLocali(): Pair<PlanimetriaActivity, MutableList<LocaleTracciato>> {
        val a = Robolectric.buildActivity(PlanimetriaActivity::class.java).setup().get()
        @Suppress("UNCHECKED_CAST") val locali = leggi(a, "locali") as MutableList<LocaleTracciato>
        locali += locale("Cucina", 10f); locali += locale("Bagno", 200f)
        a.findViewById<it.geometra.schedacliente.SuperficieView>(R.id.superficieView).locali.addAll(locali)
        metodo("avviaModificaLocale").invoke(a, 0, false)
        return a to locali
    }

    @Test
    fun eliminaIlPoligonoDopoConferma() {
        val (a, locali) = preparaConDueLocali()
        assertTrue("il pulsante Elimina esiste nella finestra di modifica", a.findViewById<View>(R.id.btnEliminaLocale) != null)
        a.findViewById<View>(R.id.btnEliminaLocale).performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull("deve chiedere conferma", dialog)
        assertEquals(2, locali.size) // non è ancora stato eliminato
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(listOf("Bagno"), locali.map { it.nome })
        val vista = a.findViewById<it.geometra.schedacliente.SuperficieView>(R.id.superficieView)
        assertEquals(listOf("Bagno"), vista.locali.map { it.nome })
        assertEquals("la modifica è finita", "NESSUNA", leggi(a, "modalita").toString())
    }

    @Test
    fun annullandoLaConfermaIlLocaleResta() {
        val (a, locali) = preparaConDueLocali()
        a.findViewById<View>(R.id.btnEliminaLocale).performClick()
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(listOf("Cucina", "Bagno"), locali.map { it.nome })
        assertEquals("si è ancora in modifica", "MODIFICA_LOCALE", leggi(a, "modalita").toString())
        assertFalse(locali.isEmpty())
    }
}
