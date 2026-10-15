package it.geometra.schedacliente

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import it.geometra.schedacliente.databinding.ActivityBollettaEnergiaListaBinding

/**
 * Storico delle bollette di energia elettrica calcolate (sezione "Ripartizione Bolletta Energia
 * Elettrica"): elenco consultabile e modificabile, con accesso al form per aggiungerne una nuova
 * o correggerne una esistente (tocco singolo) e all'eliminazione (tocco prolungato).
 */
class BollettaEnergiaListaActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBollettaEnergiaListaBinding
    private lateinit var adapter: BollettaEnergiaAdapter

    companion object {
        const val EXTRA_BOLLETTA_ID = "bolletta_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBollettaEnergiaListaBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }
        NavigazioneIndietro.installa(this)

        adapter = BollettaEnergiaAdapter(
            emptyList(),
            onClick = { bolletta ->
                startActivity(Intent(this, BollettaEnergiaFormActivity::class.java).putExtra(EXTRA_BOLLETTA_ID, bolletta.id))
            },
            onLongClick = { bolletta -> confermaEliminazione(bolletta) }
        )
        binding.recyclerBollette.layoutManager = LinearLayoutManager(this)
        binding.recyclerBollette.adapter = adapter

        binding.btnNuovaBolletta.setOnClickListener {
            startActivity(Intent(this, BollettaEnergiaFormActivity::class.java))
        }

        aggiornaLista()
    }

    override fun onResume() {
        super.onResume()
        aggiornaLista()
    }

    private fun aggiornaLista() {
        val lista = BollettaEnergiaStore.caricaTutte(this)
        adapter.aggiorna(lista)
        binding.tvVuotoBollette.visibility = if (lista.isEmpty()) View.VISIBLE else View.GONE
        binding.recyclerBollette.visibility = if (lista.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun confermaEliminazione(bolletta: BollettaEnergia) {
        val dialog = AlertDialog.Builder(this, R.style.Theme_SchedaCliente_Dialog)
            .setTitle("Eliminare questa bolletta?")
            .setMessage("La bolletta del ${bolletta.dataCreazioneFormattata()} (${bolletta.nomeProprietario}) e i relativi allegati verranno eliminati definitivamente.")
            .setPositiveButton("Elimina") { _, _ ->
                BollettaEnergiaStore.elimina(this, bolletta.id)
                aggiornaLista()
                Toast.makeText(this, "Bolletta eliminata", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Annulla", null)
            .show()
        dialog.rendiPulsantiLeggibili(this)
    }
}
