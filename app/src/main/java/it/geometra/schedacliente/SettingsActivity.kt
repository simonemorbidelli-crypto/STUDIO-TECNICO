package it.geometra.schedacliente

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import it.geometra.schedacliente.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val tipiPratica = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        aggiornaStato()
        binding.etApiKey.setText(ChiaveApiStore.leggiChiave(this))

        binding.btnSalvaChiave.setOnClickListener {
            val chiave = binding.etApiKey.text?.toString()?.trim() ?: ""
            if (chiave.isEmpty()) {
                Toast.makeText(this, "Inserisci una chiave prima di salvare", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            ChiaveApiStore.salvaChiave(this, chiave)
            Toast.makeText(this, "Chiave salvata (cifrata) su questo telefono", Toast.LENGTH_SHORT).show()
            aggiornaStato()
        }

        binding.btnRimuoviChiave.setOnClickListener {
            ChiaveApiStore.rimuoviChiave(this)
            binding.etApiKey.setText("")
            Toast.makeText(this, "Chiave rimossa. La dettatura con AI è disattivata.", Toast.LENGTH_SHORT).show()
            aggiornaStato()
        }

        tipiPratica.addAll(TipiPraticaStore.leggiTipi(this))
        disegnaElencoTipi()

        binding.btnAggiungiTipo.setOnClickListener {
            val nuovo = binding.etNuovoTipo.text?.toString()?.trim() ?: ""
            if (nuovo.isEmpty()) {
                Toast.makeText(this, "Scrivi il nome del tipo di pratica", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (tipiPratica.any { it.equals(nuovo, ignoreCase = true) }) {
                Toast.makeText(this, "Esiste già un tipo di pratica con questo nome", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            tipiPratica.add(nuovo)
            TipiPraticaStore.salvaTipi(this, tipiPratica)
            binding.etNuovoTipo.setText("")
            disegnaElencoTipi()
        }

        binding.btnRipristinaTipi.setOnClickListener {
            tipiPratica.clear()
            tipiPratica.addAll(TipiPraticaStore.elencoPredefinito)
            TipiPraticaStore.salvaTipi(this, tipiPratica)
            disegnaElencoTipi()
            Toast.makeText(this, "Elenco tipi di pratica ripristinato", Toast.LENGTH_SHORT).show()
        }
    }

    private fun aggiornaStato() {
        val attiva = ChiaveApiStore.leggiChiave(this).isNotBlank()
        binding.tvStatoChiave.text = if (attiva) {
            "Dettatura con AI attiva su questo telefono."
        } else {
            "Dettatura con AI non attiva: inserisci una chiave per usarla."
        }
    }

    /** Ricostruisce l'elenco dei tipi di pratica come righe testo + pulsante elimina. */
    private fun disegnaElencoTipi() {
        binding.containerTipiPratica.removeAllViews()

        if (tipiPratica.isEmpty()) {
            val vuoto = TextView(this).apply {
                text = "Nessun tipo di pratica: aggiungine uno qui sotto."
                setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_secondary))
                textSize = 13f
            }
            binding.containerTipiPratica.addView(vuoto)
            return
        }

        val padding8 = (8 * resources.displayMetrics.density).toInt()

        tipiPratica.forEachIndexed { indice, tipo ->
            val riga = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, padding8 / 2, 0, padding8 / 2)
            }

            val testo = TextView(this).apply {
                text = tipo
                setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_primary))
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val btnElimina = ImageButton(this).apply {
                setImageResource(android.R.drawable.ic_menu_delete)
                background = null
                contentDescription = "Elimina $tipo"
                layoutParams = LinearLayout.LayoutParams(
                    (36 * resources.displayMetrics.density).toInt(),
                    (36 * resources.displayMetrics.density).toInt()
                )
                setOnClickListener {
                    tipiPratica.removeAt(indice)
                    TipiPraticaStore.salvaTipi(this@SettingsActivity, tipiPratica)
                    disegnaElencoTipi()
                }
            }

            riga.addView(testo)
            riga.addView(btnElimina)
            binding.containerTipiPratica.addView(riga)
        }
    }
}
