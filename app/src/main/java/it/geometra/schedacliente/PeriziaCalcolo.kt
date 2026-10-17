package it.geometra.schedacliente

import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Calcolo della superficie commerciale e del valore di mercato di un'unità immobiliare.
 *
 * Coefficienti di ragguaglio: Manuale della Banca Dati Quotazioni OMI, Allegato 3 "Istruzioni
 * per la determinazione della superficie delle unità immobiliari" (ed. marzo 2025), basato sul
 * DPR 138/1998 Allegato C. Prospetti 1 (abitazioni), 2 (uffici), 3 (negozi), 4 (box/posti auto).
 * Le percentuali sono riportate qui sotto; dove il manuale non prevede un coefficiente per una
 * combinazione (es. balconi non comunicanti di uffici e negozi) lo si segnala nelle note.
 *
 * Regole generali del manuale applicate: muri interni e perimetrali computati per intero (fino a
 * 50 cm), esclusione dei locali con altezza utile < 1,50 m (categoria "Esclusa"), arrotondamento
 * finale al metro quadrato. Per le abitazioni e i box, accessori indiretti + pertinenze non
 * superano il 50% della superficie di vani principali e accessori diretti.
 */
object PeriziaCalcolo {

    enum class Destinazione(val etichetta: String, val utilizzoOmi: String, val tipologieOmi: List<String>) {
        ABITAZIONE("Abitazione", "Residenziale", listOf("Abitazioni civili", "Abitazioni di tipo economico", "Ville e Villini", "Abitazioni signorili")),
        UFFICIO("Ufficio", "Terziaria", listOf("Uffici", "Uffici strutturati")),
        NEGOZIO("Negozio", "Commerciale", listOf("Negozi")),
        BOX("Box / posto auto", "Residenziale", listOf("Box", "Posti auto coperti", "Posti auto scoperti", "Autorimesse"))
    }

    enum class Categoria(val etichetta: String) {
        PRINCIPALE("Vano principale"),
        ACCESSORIO_DIRETTO("Accessorio diretto (bagno, ingresso, corridoio…)"),
        INDIRETTO_COMUNICANTE("Accessorio indiretto comunicante (cantina, soffitta…)"),
        INDIRETTO_NON_COMUNICANTE("Accessorio indiretto non comunicante"),
        BALCONE_COMUNICANTE("Balcone / terrazza comunicante"),
        BALCONE_NON_COMUNICANTE("Balcone / terrazza non comunicante"),
        AREA_SCOPERTA("Area scoperta / giardino esclusivo"),
        ESCLUSA("Esclusa (h < 1,50 m o da valutare a parte)")
    }

    /**
     * @param ragguaglioPersonalizzato se impostato (0-100), sovrascrive il coefficiente standard
     *   della categoria per questa sola voce: l'utente ha scelto una percentuale a piacimento
     *   invece di quella prevista dal manuale OMI per la categoria. Si azzera scegliendo di nuovo
     *   una tipologia dal menu (vedi PeriziaUnitaActivity.scegliCategoria).
     */
    data class Voce(val nome: String, val mq: Double, val categoria: Categoria, val ragguaglioPersonalizzato: Double? = null)

    class RigaRisultato(val voce: Voce, val mqLordi: Double, val mqRagguagliati: Double, val regola: String)

    class Risultato(
        val righe: List<RigaRisultato>,
        val superficieCommerciale: Double,
        /** Superficie commerciale arrotondata al metro quadrato, come prevede il manuale OMI. */
        val superficieArrotondata: Long,
        val note: List<String>
    )

    /** Indovina la categoria dal nome del locale (modificabile poi dall'utente). */
    fun categoriaDaNome(nome: String): Categoria {
        val n = nome.lowercase()
        fun ha(vararg parole: String) = parole.any { n.contains(it) }
        return when {
            ha("balcon", "terrazz", "loggia", "logge", "lastrico") -> Categoria.BALCONE_COMUNICANTE
            ha("giardin", "corte", "resede", "area scop", "piazzal", "aia", "orto", "cortile") -> Categoria.AREA_SCOPERTA
            ha("garage", "autorim", "box", "posto auto") -> Categoria.ESCLUSA
            ha("cantin", "soffitt", "sottotett", "magazzin", "deposit", "locale tecnico", "centrale termica", "legnaia") ->
                Categoria.INDIRETTO_NON_COMUNICANTE
            ha("bagn", "wc", "w.c", "ingress", "corridoi", "disimpegn", "ripost", "antibagn", "scala", "guardarob",
                "cabina armadio", "dispensa", "lavanderia", "dis.", "rip.") -> Categoria.ACCESSORIO_DIRETTO
            else -> Categoria.PRINCIPALE
        }
    }

    /**
     * @param maggiorazioneMuriPerc maggiorazione da applicare alle superfici dei locali coperti se le
     *   superfici tracciate sono nette (interne): la superficie commerciale OMI comprende i muri.
     * @param villa per le abitazioni: villa/villino (regola speciale per le aree scoperte).
     */
    fun calcola(
        destinazione: Destinazione,
        voci: List<Voce>,
        maggiorazioneMuriPerc: Double = 0.0,
        villa: Boolean = false
    ): Risultato {
        val note = ArrayList<String>()
        val fattoreMuri = 1.0 + maggiorazioneMuriPerc / 100.0
        fun coperta(c: Categoria) = c == Categoria.PRINCIPALE || c == Categoria.ACCESSORIO_DIRETTO ||
            c == Categoria.INDIRETTO_COMUNICANTE || c == Categoria.INDIRETTO_NON_COMUNICANTE
        fun lordi(v: Voce) = if (coperta(v.categoria)) v.mq * fattoreMuri else v.mq

        val righe = ArrayList<RigaRisultato>()
        // Superficie di riferimento: vani principali + accessori diretti (lordi).
        val sRif = voci.filter { it.categoria == Categoria.PRINCIPALE || it.categoria == Categoria.ACCESSORIO_DIRETTO }.sumOf { lordi(it) }

        val coeffDiretti = when (destinazione) {
            Destinazione.ABITAZIONE, Destinazione.UFFICIO -> 1.0
            Destinazione.NEGOZIO, Destinazione.BOX -> 0.5
        }

        // Voci a coefficiente fisso.
        voci.filter { it.categoria !in setOf(Categoria.BALCONE_COMUNICANTE, Categoria.BALCONE_NON_COMUNICANTE, Categoria.AREA_SCOPERTA) }
            .forEach { v ->
                val l = lordi(v)
                val (coeff, regola) = when (v.categoria) {
                    Categoria.PRINCIPALE -> 1.0 to "100%"
                    Categoria.ACCESSORIO_DIRETTO -> coeffDiretti to "${(coeffDiretti * 100).toInt()}%"
                    Categoria.INDIRETTO_COMUNICANTE -> 0.5 to "50%"
                    Categoria.INDIRETTO_NON_COMUNICANTE -> 0.25 to "25%"
                    else -> 0.0 to "esclusa"
                }
                righe += RigaRisultato(v, l, l * coeff, regola)
            }

        // Balconi e terrazze: per abitazioni a scaglioni (fino a 25 mq / oltre) sul totale della categoria.
        fun scaglioni(cat: Categoria, primo: Double, oltre: Double) {
            val elenco = voci.filter { it.categoria == cat }
            var residuoPrimoScaglione = 25.0
            elenco.forEach { v ->
                val nelPrimo = min(v.mq, residuoPrimoScaglione)
                residuoPrimoScaglione -= nelPrimo
                val ragg = nelPrimo * primo + (v.mq - nelPrimo) * oltre
                righe += RigaRisultato(v, v.mq, ragg, "${fmt(primo * 100)}% fino a 25 mq, ${fmt(oltre * 100)}% oltre")
            }
        }
        fun fisso(cat: Categoria, coeff: Double, regola: String) =
            voci.filter { it.categoria == cat }.forEach { v -> righe += RigaRisultato(v, v.mq, v.mq * coeff, regola) }

        when (destinazione) {
            Destinazione.ABITAZIONE -> {
                scaglioni(Categoria.BALCONE_COMUNICANTE, 0.30, 0.10)
                scaglioni(Categoria.BALCONE_NON_COMUNICANTE, 0.15, 0.05)
            }
            Destinazione.UFFICIO, Destinazione.NEGOZIO -> {
                fisso(Categoria.BALCONE_COMUNICANTE, 0.10, "10%")
                if (voci.any { it.categoria == Categoria.BALCONE_NON_COMUNICANTE }) {
                    fisso(Categoria.BALCONE_NON_COMUNICANTE, 0.10, "10% (non previsto dal manuale: verificare)")
                    note += "Il manuale OMI non prevede un coefficiente per balconi non comunicanti di uffici/negozi: applicato 10% come i comunicanti, da verificare."
                }
            }
            Destinazione.BOX -> {
                if (voci.any { it.categoria == Categoria.BALCONE_COMUNICANTE || it.categoria == Categoria.BALCONE_NON_COMUNICANTE }) {
                    fisso(Categoria.BALCONE_COMUNICANTE, 0.0, "non previsto per box")
                    fisso(Categoria.BALCONE_NON_COMUNICANTE, 0.0, "non previsto per box")
                    note += "Balconi/terrazze non sono previsti per i box: esclusi dal calcolo."
                }
            }
        }

        // Aree scoperte.
        val aree = voci.filter { it.categoria == Categoria.AREA_SCOPERTA }
        if (aree.isNotEmpty()) {
            when (destinazione) {
                Destinazione.UFFICIO -> fisso(Categoria.AREA_SCOPERTA, 0.10, "10%")
                Destinazione.NEGOZIO -> fisso(Categoria.AREA_SCOPERTA, 0.20, "20%")
                Destinazione.ABITAZIONE, Destinazione.BOX -> {
                    val totale = aree.sumOf { it.mq }
                    val raggTotale = if (destinazione == Destinazione.ABITAZIONE && villa) {
                        // Ville e villini: l'area conta solo oltre 5 volte S; 10% fino a 6 volte S, 2% oltre.
                        val eccedente = (totale - 5 * sRif).coerceAtLeast(0.0)
                        min(eccedente, sRif) * 0.10 + (eccedente - min(eccedente, sRif)) * 0.02
                    } else {
                        min(totale, sRif) * 0.10 + (totale - min(totale, sRif)) * 0.02
                    }
                    val regola = if (destinazione == Destinazione.ABITAZIONE && villa)
                        "villa: solo oltre 5×S; 10% fino a 6×S, 2% oltre (S = ${fmt(sRif)} mq)"
                    else "10% fino a S, 2% oltre (S = ${fmt(sRif)} mq)"
                    aree.forEach { v ->
                        val quota = if (totale > 0) v.mq / totale else 0.0
                        righe += RigaRisultato(v, v.mq, raggTotale * quota, regola)
                    }
                }
            }
        }

        // Limite del 50% (abitazioni e box): accessori indiretti + pertinenze ≤ 50% di S.
        if (destinazione == Destinazione.ABITAZIONE || destinazione == Destinazione.BOX) {
            val categorieLimitate = setOf(
                Categoria.INDIRETTO_COMUNICANTE, Categoria.INDIRETTO_NON_COMUNICANTE,
                Categoria.BALCONE_COMUNICANTE, Categoria.BALCONE_NON_COMUNICANTE, Categoria.AREA_SCOPERTA
            )
            val soggette = righe.filter { it.voce.categoria in categorieLimitate }
            val somma = soggette.sumOf { it.mqRagguagliati }
            val limite = 0.5 * sRif
            if (somma > limite && somma > 0) {
                val riduzione = limite / somma
                val nuove = righe.map { r ->
                    if (r.voce.categoria in categorieLimitate)
                        RigaRisultato(r.voce, r.mqLordi, r.mqRagguagliati * riduzione, r.regola + " · ridotta per il limite del 50%")
                    else r
                }
                righe.clear(); righe.addAll(nuove)
                note += "Accessori indiretti e pertinenze superano il 50% della superficie dei vani principali e accessori diretti: ridotti a ${fmt(limite)} mq come prevede il manuale OMI."
            }
        }

        // Ragguaglio personalizzato: sovrascrive, per le sole voci su cui l'utente lo ha impostato
        // esplicitamente, qualunque coefficiente/regola calcolata sopra (anche l'eventuale
        // riduzione per il limite del 50%, perché qui la percentuale è stata scelta a piacimento
        // dall'utente e non deve essere corretta automaticamente).
        voci.filter { it.ragguaglioPersonalizzato != null }.forEach { v ->
            val i = righe.indexOfFirst { it.voce === v }
            if (i >= 0) {
                val l = lordi(v)
                val perc = v.ragguaglioPersonalizzato!!
                righe[i] = RigaRisultato(v, l, l * perc / 100.0, "ragguaglio personalizzato: ${fmt(perc)}%")
            }
        }

        if (voci.any { it.categoria == Categoria.ESCLUSA }) {
            note += "Le superfici \"escluse\" (altezza < 1,50 m o da valutare a parte, es. box con propria quotazione) non entrano nel totale."
        }
        if (maggiorazioneMuriPerc > 0) {
            note += "Ai locali coperti è stata aggiunta una maggiorazione del ${fmt(maggiorazioneMuriPerc)}% per i muri (superfici tracciate nette)."
        }

        // Ordine originale delle voci.
        val ordinate = voci.mapNotNull { v -> righe.firstOrNull { it.voce === v } }
        val totale = ordinate.sumOf { it.mqRagguagliati }
        return Risultato(ordinate, totale, totale.roundToLong(), note)
    }

    /** Correttivi percentuali applicati in cascata: (1 + p1/100) × (1 + p2/100) × … */
    fun fattoreCorrettivi(percentuali: List<Double>): Double =
        percentuali.fold(1.0) { acc, p -> acc * (1.0 + p / 100.0) }

    fun valoreDiMercato(superficie: Double, valoreUnitario: Double, correttivi: List<Double>): Double =
        superficie * valoreUnitario * fattoreCorrettivi(correttivi)

    fun fmt(x: Double): String =
        if (x == x.roundToLong().toDouble()) x.roundToLong().toString()
        else String.format(java.util.Locale.ITALY, "%.2f", x)
}
