package it.geometra.schedacliente

import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ConformitaSchermataTest {

    private fun riempi(contenitore: LinearLayout, nome: String, area: String, h: String) {
        val riga = contenitore.getChildAt(0)
        riga.findViewById<EditText>(R.id.etNomeVano).setText(nome)
        riga.findViewById<EditText>(R.id.etAreaVano).setText(area)
        riga.findViewById<EditText>(R.id.etAltezzaVano).setText(h)
        riga.findViewById<CheckBox>(R.id.cbAbitabile).isChecked = true
    }

    @Test
    fun confrontoDaSchermata() {
        val a = Robolectric.buildActivity(ConformitaActivity::class.java).setup().get()
        val sezioni = a.findViewById<LinearLayout>(R.id.sezioniFonti)
        // ogni fonte: titolo, intestazione, contenitore, 2 pulsanti
        val titolo = sezioni.getChildAt(7) as LinearLayout
        val rilievo = sezioni.getChildAt(12) as LinearLayout
        riempi(titolo, "Soggiorno", "25,0", "2,70")
        riempi(rilievo, "Soggiorno", "27,0", "2,60")
        a.findViewById<View>(R.id.btnConfronta).performClick()
        val tv = a.findViewById<TextView>(R.id.tvRisultato)
        assertEquals(View.VISIBLE, tv.visibility)
        assertTrue(tv.text.toString(), tv.text.contains("oltre la soglia"))
        assertTrue(tv.text.contains("non una dichiarazione di conformità"))
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.btnCondividiRisultato).visibility)
    }

    @Test
    fun attoVisuraEPersistenza() {
        val c1 = Robolectric.buildActivity(ConformitaActivity::class.java).setup()
        val a = c1.get()
        val campi = a.findViewById<LinearLayout>(R.id.contenitoreCampi)
        campi.getChildAt(0).findViewById<EditText>(R.id.etAtto).setText("15")
        campi.getChildAt(0).findViewById<EditText>(R.id.etVisura).setText("015")
        campi.getChildAt(1).findViewById<EditText>(R.id.etAtto).setText("281")
        campi.getChildAt(1).findViewById<EditText>(R.id.etVisura).setText("282")
        a.findViewById<View>(R.id.btnConfrontaCampi).performClick()
        val t = a.findViewById<TextView>(R.id.tvRisultatoCampi).text.toString()
        assertTrue(t, t.contains("Foglio: uguale"))
        assertTrue(t, t.contains("Particella: DIVERSO"))
        // chiusura (onPause salva) e riapertura: i dati ci sono ancora
        c1.pause()
        val b = Robolectric.buildActivity(ConformitaActivity::class.java).setup().get()
        val campi2 = b.findViewById<LinearLayout>(R.id.contenitoreCampi)
        assertEquals("281", campi2.getChildAt(1).findViewById<EditText>(R.id.etAtto).text.toString())
        assertEquals("282", campi2.getChildAt(1).findViewById<EditText>(R.id.etVisura).text.toString())
    }
}
