package it.geometra.schedacliente

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

class BollettaEnergiaAdapter(
    private var bollette: List<BollettaEnergia>,
    private val onClick: (BollettaEnergia) -> Unit,
    private val onLongClick: (BollettaEnergia) -> Unit
) : RecyclerView.Adapter<BollettaEnergiaAdapter.BollettaViewHolder>() {

    class BollettaViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvPeriodo: TextView = view.findViewById(R.id.tvPeriodoItemBolletta)
        val tvRiepilogo: TextView = view.findViewById(R.id.tvRiepilogoItemBolletta)
        val tvQuotaStudio: TextView = view.findViewById(R.id.tvQuotaStudioItemBolletta)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BollettaViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_bolletta_energia, parent, false)
        return BollettaViewHolder(view)
    }

    override fun onBindViewHolder(holder: BollettaViewHolder, position: Int) {
        val bolletta = bollette[position]
        holder.tvPeriodo.text = "Registrata il ${bolletta.dataCreazioneFormattata()}"

        var riepilogo = ""
        var quotaStudioTesto = ""
        try {
            RipartizioneBollettaCalcolatore.valida(
                bolletta.consumoTotaleKwh, bolletta.importoTotale, bolletta.canoneFisso,
                bolletta.costoPotenzaUnaTantum, bolletta.letturaIniziale, bolletta.letturaFinale
            )
            val esito = RipartizioneBollettaCalcolatore.calcola(
                bolletta.consumoTotaleKwh, bolletta.importoTotale, bolletta.canoneFisso,
                bolletta.costoPotenzaUnaTantum, bolletta.letturaIniziale, bolletta.letturaFinale
            )
            riepilogo = "${bolletta.nomeProprietario} · Totale ${euro(bolletta.importoTotale)}"
            quotaStudioTesto = "Studio ${euro(esito.quotaStudio)}"
        } catch (e: BollettaValidazioneException) {
            riepilogo = "${bolletta.nomeProprietario} · dati da correggere"
            quotaStudioTesto = "—"
        }
        holder.tvRiepilogo.text = riepilogo
        holder.tvQuotaStudio.text = quotaStudioTesto

        holder.itemView.setOnClickListener { onClick(bolletta) }
        holder.itemView.setOnLongClickListener {
            onLongClick(bolletta)
            true
        }
    }

    override fun getItemCount(): Int = bollette.size

    fun aggiorna(nuovaLista: List<BollettaEnergia>) {
        bollette = nuovaLista
        notifyDataSetChanged()
    }

    private fun euro(v: Double): String = String.format(Locale.ITALY, "%.2f €", v)
}
