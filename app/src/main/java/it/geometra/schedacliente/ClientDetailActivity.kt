package it.geometra.schedacliente

import android.app.AlertDialog
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import it.geometra.schedacliente.databinding.ActivityClientDetailBinding

class ClientDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityClientDetailBinding
    private lateinit var store: ClienteStore
    private var clienteId: Long = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityClientDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbarDetail)
        binding.toolbarDetail.setNavigationOnClickListener { finish() }

        store = ClienteStore(this)
        clienteId = intent.getLongExtra("cliente_id", -1)

        val cliente = store.caricaTutti().find { it.id == clienteId }
        if (cliente == null) {
            Toast.makeText(this, "Cliente non trovato", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        binding.tvDettaglioCompleto.text = buildString {
            append("Cognome / Ragione Sociale\n")
            append(cliente.cognome.ifBlank { "—" }).append("\n\n")

            if (cliente.nome.isNotBlank()) {
                append("Nome\n")
                append(cliente.nome).append("\n\n")
            }

            append("Codice Fiscale\n")
            append(cliente.codiceFiscale.ifBlank { "—" }).append("\n\n")

            append("Partita IVA\n")
            append(cliente.partitaIva.ifBlank { "—" }).append("\n\n")

            append("Telefono\n")
            append(cliente.telefono.ifBlank { "—" }).append("\n\n")

            append("Email\n")
            append(cliente.email.ifBlank { "—" }).append("\n\n")

            append("Indirizzo\n")
            append(cliente.indirizzo.ifBlank { "—" }).append("\n\n")

            append("Comune\n")
            append(cliente.comune.ifBlank { "—" }).append("\n\n")

            append("Tipo pratica\n")
            append(cliente.tipoPratica.ifBlank { "—" }).append("\n\n")

            append("N. pratica / Anno\n")
            append(cliente.praticaFormattata().ifBlank { "—" }).append("\n\n")

            append("Riferimenti catastali\n")
            val fpc = listOfNotNull(
                cliente.foglio.takeIf { it.isNotBlank() }?.let { "Foglio $it" },
                cliente.particella.takeIf { it.isNotBlank() }?.let { "Particella $it" },
                cliente.subalterno.takeIf { it.isNotBlank() }?.let { "Sub. $it" }
            )
            append(if (fpc.isEmpty()) "—" else fpc.joinToString(" · ")).append("\n\n")

            append("Note\n")
            append(cliente.note.ifBlank { "—" }).append("\n\n")

            append("Cartella Mega\n")
            append(if (cliente.linkMega.isBlank()) "— (link non impostato)" else cliente.linkMega).append("\n\n")

            append("Scheda creata il\n")
            append(cliente.dataFormattata())
        }

        val haLinkMega = cliente.linkMega.isNotBlank()
        binding.btnParereMega.isEnabled = haLinkMega
        binding.tvAvvisoMega.visibility = if (haLinkMega) android.view.View.GONE else android.view.View.VISIBLE
        binding.btnParereMega.setOnClickListener {
            startActivity(
                android.content.Intent(this, ParereMegaActivity::class.java)
                    .putExtra(ParereMegaActivity.EXTRA_CLIENTE_ID, cliente.id)
            )
        }

        binding.btnElimina.setOnClickListener {
            val dialog = AlertDialog.Builder(this, R.style.Theme_SchedaCliente_Dialog)
                .setTitle("Eliminare questa scheda?")
                .setMessage("L'operazione non può essere annullata.")
                .setPositiveButton("Elimina") { _, _ ->
                    store.elimina(cliente.id)
                    Toast.makeText(this, "Scheda eliminata", Toast.LENGTH_SHORT).show()
                    finish()
                }
                .setNegativeButton("Annulla", null)
                .show()
            dialog.rendiPulsantiLeggibili(this)
        }
    }
}
