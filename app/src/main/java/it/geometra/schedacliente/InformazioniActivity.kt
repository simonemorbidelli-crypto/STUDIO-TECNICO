package it.geometra.schedacliente

import android.content.Intent
import android.os.Bundle
import android.text.Html
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat

/**
 * Sezione "Informazioni": un piccolo menu (chi sono e storia del progetto, guida all'uso,
 * copyright) e le relative pagine di testo. La guida ha due livelli: utenti inesperti ed esperti.
 */
class InformazioniActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pagina = intent.getStringExtra(EXTRA_PAGINA) ?: PAGINA_MENU
        val d = resources.displayMetrics.density

        val radice = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val titolo = when (pagina) {
            PAGINA_STORIA -> "Chi sono e storia del progetto"
            PAGINA_GUIDA -> "Guida all'utilizzo"
            PAGINA_GUIDA_NOVIZI -> "Guida per utenti inesperti"
            PAGINA_GUIDA_ESPERTI -> "Guida per utenti esperti"
            PAGINA_COPYRIGHT -> "Copyright"
            else -> "Informazioni"
        }
        val barra = Toolbar(this).apply {
            setBackgroundColor(ContextCompat.getColor(this@InformazioniActivity, R.color.primary))
            setTitleTextColor(android.graphics.Color.WHITE)
            this.title = titolo
            setNavigationIcon(android.R.drawable.ic_menu_close_clear_cancel)
            setNavigationOnClickListener { finish() }
            elevation = 4 * d
        }
        radice.addView(barra, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (56 * d).toInt()))

        val contenuto = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (16 * d).toInt()
            setPadding(p, p, p, p)
        }
        when (pagina) {
            PAGINA_STORIA -> contenuto.addView(testo(InformazioniTesti.CHI_SONO))
            PAGINA_GUIDA_NOVIZI -> contenuto.addView(testo(InformazioniTesti.GUIDA_INESPERTI))
            PAGINA_GUIDA_ESPERTI -> contenuto.addView(testo(InformazioniTesti.GUIDA_ESPERTI))
            PAGINA_COPYRIGHT -> contenuto.addView(
                testo(InformazioniTesti.COPYRIGHT.replace("%VERSIONE%", BuildConfig.VERSION_NAME))
            )
            PAGINA_GUIDA -> {
                contenuto.addView(testo("<p>Scegli il livello della guida.</p>"))
                contenuto.addView(voce("👤  Utenti inesperti\nPasso per passo, con parole semplici", PAGINA_GUIDA_NOVIZI))
                contenuto.addView(voce("🛠  Utenti esperti\nRiepilogo operativo e dettagli tecnici", PAGINA_GUIDA_ESPERTI))
            }
            else -> {
                contenuto.addView(voce("1.  Chi sono e storia del progetto\nCome è nato SchedaCliente e le migliorie build per build", PAGINA_STORIA))
                contenuto.addView(voce("2.  Guida all'utilizzo\nPer utenti inesperti e per utenti esperti", PAGINA_GUIDA))
                contenuto.addView(voce("3.  Copyright\nDiritti, dati e componenti di terzi", PAGINA_COPYRIGHT))
                contenuto.addView(testo("<p><small>SchedaCliente v${BuildConfig.VERSION_NAME}</small></p>"))
            }
        }
        radice.addView(ScrollView(this).apply { addView(contenuto) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(radice)
        radice.setBackgroundColor(ContextCompat.getColor(this, R.color.background))
        NavigazioneIndietro.installa(this)
    }

    private fun testo(html: String): TextView = TextView(this).apply {
        text = Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT)
        setTextColor(ContextCompat.getColor(this@InformazioniActivity, R.color.text_primary))
        textSize = 15f
        setLineSpacing(0f, 1.15f)
        setTextIsSelectable(true)
    }

    private fun voce(etichetta: String, pagina: String): View = Button(this).apply {
        text = etichetta
        isAllCaps = false
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        textSize = 15f
        setTextColor(android.graphics.Color.WHITE)
        setBackgroundColor(ContextCompat.getColor(this@InformazioniActivity, R.color.primary))
        val d = resources.displayMetrics.density
        setPadding((16 * d).toInt(), (12 * d).toInt(), (16 * d).toInt(), (12 * d).toInt())
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (12 * d).toInt() }
        setOnClickListener {
            startActivity(Intent(this@InformazioniActivity, InformazioniActivity::class.java)
                .putExtra(EXTRA_PAGINA, pagina))
        }
    }

    companion object {
        const val EXTRA_PAGINA = "pagina"
        const val PAGINA_MENU = "menu"
        const val PAGINA_STORIA = "storia"
        const val PAGINA_GUIDA = "guida"
        const val PAGINA_GUIDA_NOVIZI = "guida_novizi"
        const val PAGINA_GUIDA_ESPERTI = "guida_esperti"
        const val PAGINA_COPYRIGHT = "copyright"
    }
}
