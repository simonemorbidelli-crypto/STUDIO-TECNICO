package it.geometra.schedacliente

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import it.geometra.schedacliente.databinding.ActivityCalcoliBinding
import java.util.Locale
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Raccolta di calcoli utili per il lavoro quotidiano del geometra:
 * calcoli topografici, rendite catastali per IMU e compravendite, usufrutto.
 *
 * Le formule fiscali (IMU, prezzo-valore, usufrutto) sono quelle di legge
 * verificate al momento della stesura; aliquote comunali e coefficienti
 * annuali vanno comunque sempre controllati prima di un atto definitivo.
 */
class CalcoliActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCalcoliBinding

    // Moltiplicatori catastali per il calcolo della base imponibile IMU
    // (rendita rivalutata del 5% x moltiplicatore), D.L. 262/2006 e succ. modifiche.
    private val categorieImu = listOf(
        "Gruppo A (escluso A/10) — moltiplicatore 160" to 160.0,
        "A/10 (uffici e studi privati) — moltiplicatore 80" to 80.0,
        "Gruppo B — moltiplicatore 140" to 140.0,
        "C/1 (negozi e botteghe) — moltiplicatore 55" to 55.0,
        "C/2, C/6, C/7 — moltiplicatore 160" to 160.0,
        "C/3, C/4, C/5 — moltiplicatore 140" to 140.0,
        "Gruppo D (escluso D/5) — moltiplicatore 65" to 65.0,
        "D/5 (istituti di credito/cambio) — moltiplicatore 80" to 80.0
    )

    // Moltiplicatori "prezzo-valore" (art. 1, co. 497, L. 266/2005).
    private val tipiCompravendita = listOf(
        "Fabbricato, prima casa — moltiplicatore 110" to 110.0,
        "Fabbricato, altri immobili — moltiplicatore 120" to 120.0,
        "Terreno agricolo — moltiplicatore 90 (su reddito dominicale rivalutato 25%)" to 90.0
    )

    // Coefficienti per il calcolo del valore dell'usufrutto a vita, per fasce
    // d'età, al tasso di legge del 2,5% (art. 7, c. 1-ter, D.Lgs. 346/1990;
    // tabella in vigore anche per il 2026, poiché il tasso legale ordinario,
    // sceso all'1,6%, resta sotto il minimo fiscale del 2,5%).
    // Valore usufrutto = valore piena proprietà x 2,5% x coefficiente.
    private val fasceEtaUsufrutto = listOf(
        0..20 to 38,
        21..30 to 36,
        31..40 to 34,
        41..45 to 32,
        46..50 to 30,
        51..53 to 28,
        54..56 to 26,
        57..60 to 24,
        61..63 to 22,
        64..66 to 20,
        67..69 to 18,
        70..72 to 16,
        73..75 to 14,
        76..78 to 12,
        79..82 to 10,
        83..86 to 8,
        87..92 to 6,
        93..99 to 4
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCalcoliBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.spinnerCategoriaImu.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, categorieImu.map { it.first }
        )
        binding.spinnerTipoCompravendita.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, tipiCompravendita.map { it.first }
        )

        binding.btnMenuArea.setOnClickListener { mostraPannello(binding.panelArea) }
        binding.btnMenuDistanza.setOnClickListener { mostraPannello(binding.panelDistanza) }
        binding.btnMenuPendenza.setOnClickListener { mostraPannello(binding.panelPendenza) }
        binding.btnMenuImu.setOnClickListener { mostraPannello(binding.panelImu) }
        binding.btnMenuCompravendita.setOnClickListener { mostraPannello(binding.panelCompravendita) }
        binding.btnMenuUsufrutto.setOnClickListener { mostraPannello(binding.panelUsufrutto) }

        binding.btnCalcolaArea.setOnClickListener { calcolaArea() }
        binding.btnCalcolaDistanza.setOnClickListener { calcolaDistanzaAzimut() }
        binding.btnCalcolaPendenza.setOnClickListener { calcolaPendenza() }
        binding.btnCalcolaImu.setOnClickListener { calcolaImu() }
        binding.btnCalcolaCompravendita.setOnClickListener { calcolaCompravendita() }
        binding.btnCalcolaUsufrutto.setOnClickListener { calcolaUsufrutto() }
    }

    private fun mostraPannello(pannello: View) {
        listOf(
            binding.panelArea, binding.panelDistanza, binding.panelPendenza,
            binding.panelImu, binding.panelCompravendita, binding.panelUsufrutto
        ).forEach { it.visibility = if (it == pannello) View.VISIBLE else View.GONE }

        binding.scrollCalcoli.post {
            binding.scrollCalcoli.smoothScrollTo(0, pannello.top)
        }
    }

    private fun numero(testo: String?): Double? =
        testo?.trim()?.replace(",", ".")?.toDoubleOrNull()

    private fun formatta(valore: Double, decimali: Int = 2): String =
        String.format(Locale.ITALY, "%,.${decimali}f", valore)

    // ---------------------------------------------------------------
    // Area di un poligono da coordinate (formula di Gauss / shoelace)
    // ---------------------------------------------------------------
    private fun calcolaArea() {
        val righe = binding.etCoordinate.text?.toString()?.lines()
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() } ?: emptyList()

        if (righe.size < 3) {
            Toast.makeText(this, "Servono almeno 3 vertici, uno per riga", Toast.LENGTH_SHORT).show()
            return
        }

        val vertici = mutableListOf<Pair<Double, Double>>()
        for (riga in righe) {
            val parti = riga.split(",", ";")
            if (parti.size != 2) {
                Toast.makeText(this, "Riga non valida: \"$riga\" (usa Est,Nord)", Toast.LENGTH_LONG).show()
                return
            }
            val est = numero(parti[0])
            val nord = numero(parti[1])
            if (est == null || nord == null) {
                Toast.makeText(this, "Coordinate non valide nella riga: \"$riga\"", Toast.LENGTH_LONG).show()
                return
            }
            vertici.add(est to nord)
        }

        var somma = 0.0
        for (i in vertici.indices) {
            val (e1, n1) = vertici[i]
            val (e2, n2) = vertici[(i + 1) % vertici.size]
            somma += e1 * n2 - e2 * n1
        }
        val areaMq = kotlin.math.abs(somma) / 2.0

        binding.tvRisultatoArea.visibility = View.VISIBLE
        binding.tvRisultatoArea.text = buildString {
            append("Area: ${formatta(areaMq)} m²\n")
            append("(${formatta(areaMq / 10000.0, 4)} ettari — ${formatta(areaMq / 100.0, 2)} are)")
        }
    }

    // ---------------------------------------------------------------
    // Distanza e azimut tra due punti
    // ---------------------------------------------------------------
    private fun calcolaDistanzaAzimut() {
        val e1 = numero(binding.etE1.text?.toString())
        val n1 = numero(binding.etN1.text?.toString())
        val e2 = numero(binding.etE2.text?.toString())
        val n2 = numero(binding.etN2.text?.toString())

        if (e1 == null || n1 == null || e2 == null || n2 == null) {
            Toast.makeText(this, "Compila tutte e quattro le coordinate", Toast.LENGTH_SHORT).show()
            return
        }

        val deltaE = e2 - e1
        val deltaN = n2 - n1
        val distanza = hypot(deltaE, deltaN)

        var azimutGradi = Math.toDegrees(atan2(deltaE, deltaN))
        if (azimutGradi < 0) azimutGradi += 360.0

        val gradi = azimutGradi.toInt()
        val primiDecimali = (azimutGradi - gradi) * 60.0
        val primi = primiDecimali.toInt()
        val secondi = ((primiDecimali - primi) * 60.0).roundToInt()

        binding.tvRisultatoDistanza.visibility = View.VISIBLE
        binding.tvRisultatoDistanza.text = buildString {
            append("Distanza: ${formatta(distanza)} m\n")
            append("Azimut: ${formatta(azimutGradi, 4)}° ")
            append("(${gradi}° ${primi}' ${secondi}\")")
        }
    }

    // ---------------------------------------------------------------
    // Pendenza
    // ---------------------------------------------------------------
    private fun calcolaPendenza() {
        val dislivello = numero(binding.etDislivello.text?.toString())
        val distanza = numero(binding.etDistanzaPlanimetrica.text?.toString())

        if (dislivello == null || distanza == null || distanza == 0.0) {
            Toast.makeText(this, "Inserisci dislivello e distanza (distanza diversa da zero)", Toast.LENGTH_SHORT).show()
            return
        }

        val pendenzaPercento = (dislivello / distanza) * 100.0
        val angoloGradi = Math.toDegrees(atan(dislivello / distanza))

        binding.tvRisultatoPendenza.visibility = View.VISIBLE
        binding.tvRisultatoPendenza.text = buildString {
            append("Pendenza: ${formatta(pendenzaPercento)} %\n")
            append("Angolo: ${formatta(angoloGradi)}°")
        }
    }

    // ---------------------------------------------------------------
    // Rendita catastale → base imponibile e imposta IMU
    // ---------------------------------------------------------------
    private fun calcolaImu() {
        val rendita = numero(binding.etRenditaImu.text?.toString())
        if (rendita == null) {
            Toast.makeText(this, "Inserisci la rendita catastale", Toast.LENGTH_SHORT).show()
            return
        }
        val moltiplicatore = categorieImu[binding.spinnerCategoriaImu.selectedItemPosition].second
        val aliquota = numero(binding.etAliquotaImu.text?.toString())

        val baseImponibile = rendita * 1.05 * moltiplicatore

        binding.tvRisultatoImu.visibility = View.VISIBLE
        binding.tvRisultatoImu.text = buildString {
            append("Base imponibile IMU: ${formatta(baseImponibile)} €\n")
            append("(rendita × 1,05 × $moltiplicatore)")
            if (aliquota != null) {
                val imposta = baseImponibile * (aliquota / 100.0)
                append("\n\nImposta annua stimata (aliquota ${formatta(aliquota)}%): ${formatta(imposta)} €")
            }
        }
    }

    // ---------------------------------------------------------------
    // Valore catastale per compravendita ("prezzo-valore")
    // ---------------------------------------------------------------
    private fun calcolaCompravendita() {
        val rendita = numero(binding.etRenditaCompravendita.text?.toString())
        if (rendita == null) {
            Toast.makeText(this, "Inserisci la rendita catastale o il reddito dominicale", Toast.LENGTH_SHORT).show()
            return
        }
        val posizione = binding.spinnerTipoCompravendita.selectedItemPosition
        val moltiplicatore = tipiCompravendita[posizione].second
        val eTerreno = posizione == 2

        val rivalutazione = if (eTerreno) 1.25 else 1.05
        val valoreCatastale = rendita * rivalutazione * moltiplicatore

        binding.tvRisultatoCompravendita.visibility = View.VISIBLE
        binding.tvRisultatoCompravendita.text = buildString {
            append("Valore catastale: ${formatta(valoreCatastale)} €\n")
            if (eTerreno) {
                append("(reddito dominicale × 1,25 × $moltiplicatore)")
            } else {
                append("(rendita × 1,05 × $moltiplicatore)")
            }
        }
    }

    // ---------------------------------------------------------------
    // Usufrutto e nuda proprietà
    // ---------------------------------------------------------------
    private fun calcolaUsufrutto() {
        val valorePienaProprieta = numero(binding.etValorePienaProprieta.text?.toString())
        val eta = binding.etEtaUsufruttuario.text?.toString()?.trim()?.toIntOrNull()

        if (valorePienaProprieta == null || eta == null) {
            Toast.makeText(this, "Inserisci valore e età", Toast.LENGTH_SHORT).show()
            return
        }
        if (eta < 0 || eta > 99) {
            Toast.makeText(
                this,
                "Per età oltre i 99 anni la tabella standard non è definita: verifica il coefficiente aggiornato",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val coefficiente = fasceEtaUsufrutto.firstOrNull { eta in it.first }?.second
        if (coefficiente == null) {
            Toast.makeText(this, "Età non coperta dalla tabella", Toast.LENGTH_SHORT).show()
            return
        }

        val tassoLegaleFiscale = 0.025
        val valoreUsufrutto = (valorePienaProprieta * tassoLegaleFiscale * coefficiente)
            .coerceAtMost(valorePienaProprieta)
        val valoreNudaProprieta = valorePienaProprieta - valoreUsufrutto

        binding.tvRisultatoUsufrutto.visibility = View.VISIBLE
        binding.tvRisultatoUsufrutto.text = buildString {
            append("Coefficiente età (tasso 2,5%): $coefficiente\n\n")
            append("Valore usufrutto: ${formatta(valoreUsufrutto)} €\n")
            append("Valore nuda proprietà: ${formatta(valoreNudaProprieta)} €")
        }
    }
}
