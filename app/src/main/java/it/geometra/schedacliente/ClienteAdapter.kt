package it.geometra.schedacliente

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ClienteAdapter(
    private var clienti: List<Cliente>,
    private val onClick: (Cliente) -> Unit,
    private val onLongClick: (Cliente) -> Unit
) : RecyclerView.Adapter<ClienteAdapter.ClienteViewHolder>() {

    class ClienteViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvNome: TextView = view.findViewById(R.id.tvNomeItem)
        val tvSottotitolo: TextView = view.findViewById(R.id.tvSottotitoloItem)
        val tvTipoPratica: TextView = view.findViewById(R.id.tvTipoPraticaItem)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ClienteViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_cliente, parent, false)
        return ClienteViewHolder(view)
    }

    override fun onBindViewHolder(holder: ClienteViewHolder, position: Int) {
        val cliente = clienti[position]
        holder.tvNome.text = cliente.nomeCompleto()
        val comune = if (cliente.comune.isNotBlank()) cliente.comune else "Comune non indicato"
        val pratica = cliente.praticaFormattata()
        val sottotitolo = if (pratica.isNotBlank()) {
            "$comune · Pratica $pratica"
        } else {
            "$comune · ${cliente.dataFormattata()}"
        }
        holder.tvSottotitolo.text = sottotitolo
        holder.tvTipoPratica.text = cliente.tipoPratica
        holder.itemView.setOnClickListener { onClick(cliente) }
        holder.itemView.setOnLongClickListener {
            onLongClick(cliente)
            true
        }
    }

    override fun getItemCount(): Int = clienti.size

    fun clienteAt(posizione: Int): Cliente? = clienti.getOrNull(posizione)

    fun aggiorna(nuovaLista: List<Cliente>) {
        clienti = nuovaLista
        notifyDataSetChanged()
    }
}
