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

        impostaSezioneNas()

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

    // ---------------------------------------------------------------------
    // NAS dello studio (v3.78)
    // ---------------------------------------------------------------------

    private fun impostaSezioneNas() {
        val dati = NasCredenzialiStore.leggi(this)
        binding.etNasPercorso.setText(dati.percorso)
        binding.etNasUtente.setText(dati.utente)
        binding.etNasPassword.setText(dati.password)
        binding.tvStatoNas.text = if (NasCredenzialiStore.accesso(this) != null)
            "NAS impostato: ${dati.percorso}" else "NAS non impostato."

        binding.btnSalvaNas.setOnClickListener {
            val percorsoTesto = binding.etNasPercorso.text?.toString()?.trim().orEmpty()
            val utente = binding.etNasUtente.text?.toString()?.trim().orEmpty()
            val password = binding.etNasPassword.text?.toString().orEmpty()
            val percorso = NasStudio.analizzaPercorso(percorsoTesto)
            if (percorso == null) {
                binding.etNasPercorso.error = "Scrivi indirizzo e cartella condivisa, es. \\\\192.168.1.20\\Pratiche"
                return@setOnClickListener
            }
            if (utente.isEmpty()) {
                binding.etNasUtente.error = "Serve l'utente del NAS"
                return@setOnClickListener
            }
            // Si salva sempre (anche se ora il NAS non è raggiungibile, es. fuori studio), poi si prova.
            // L'impronta del certificato accettata vale solo per lo stesso indirizzo.
            val precedente = NasCredenzialiStore.leggi(this)
            val impronta = if (NasStudio.analizzaPercorso(precedente.percorso)?.let { it.host == percorso.host && it.porta == percorso.porta } == true) precedente.impronta else ""
            NasCredenzialiStore.salva(this, percorso.toString(), utente, password, impronta)
            binding.etNasPercorso.setText(percorso.toString())
            provaNas(NasStudio.Accesso(percorso, utente, password, impronta = impronta))
        }

        binding.btnRimuoviNas.setOnClickListener {
            NasCredenzialiStore.rimuovi(this)
            binding.etNasPercorso.setText(""); binding.etNasUtente.setText(""); binding.etNasPassword.setText("")
            binding.tvStatoNas.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            binding.tvStatoNas.text = "Dati del NAS rimossi da questo telefono."
        }
    }

    private fun provaNas(accesso: NasStudio.Accesso) {
        binding.btnSalvaNas.isEnabled = false
        binding.progressNas.visibility = View.VISIBLE
        binding.tvStatoNas.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        binding.tvStatoNas.text = "Salvato. Provo a collegarmi a ${accesso.percorso}…"
        Thread {
            val esito = try {
                val n = NasStudio.prova(accesso)
                true to "✅ Collegamento riuscito: $n cartelle di pratiche in ${accesso.percorso}."
            } catch (e: Exception) {
                val daConfermare = (e as? NasStudio.ErroreNas)?.improntaDaConfermare
                if (daConfermare != null) {
                    runOnUiThread { chiediFiduciaCertificato(accesso, daConfermare) }
                    null
                } else false to "⚠️ Salvato, ma il collegamento non è riuscito: ${e.message}"
            }
            if (esito == null) return@Thread
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                binding.btnSalvaNas.isEnabled = true
                binding.progressNas.visibility = View.GONE
                binding.tvStatoNas.setTextColor(if (esito.first) ContextCompat.getColor(this, R.color.primary) else android.graphics.Color.parseColor("#B3261E"))
                binding.tvStatoNas.text = esito.second
            }
        }.start()
    }

    /** WebDAV/HTTPS: il NAS ha un certificato proprio. Si mostra l'impronta; se l'utente conferma si ricorda e si riprova. */
    private fun chiediFiduciaCertificato(accesso: NasStudio.Accesso, impronta: String) {
        binding.btnSalvaNas.isEnabled = true
        binding.progressNas.visibility = View.GONE
        binding.tvStatoNas.text = "Serve la tua conferma sul certificato del NAS."
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Certificato del NAS")
            .setMessage("Il NAS usa un certificato proprio (normale per un Zyxel). Controlla che questa impronta sia quella del tuo NAS " +
                "(nel pannello del NAS, impostazioni del certificato/HTTPS) e poi conferma. Da ora l'app accetterà solo questo certificato.\n\n$impronta")
            .setPositiveButton("È il mio NAS: fidati") { _, _ ->
                val d = NasCredenzialiStore.leggi(this)
                NasCredenzialiStore.salva(this, d.percorso, d.utente, d.password, impronta)
                provaNas(accesso.copy(impronta = impronta))
            }
            .setNegativeButton("Annulla") { _, _ -> binding.tvStatoNas.text = "Collegamento non confermato." }
            .show()
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
