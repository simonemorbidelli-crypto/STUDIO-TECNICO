package it.geometra.schedacliente

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/**
 * v3.84 — Verifica di conformità assistita: confronto tra le tre "fonti" di un'unità
 * (planimetria catastale, titolo edilizio, stato di fatto) e tra atto e visura.
 *
 * IMPORTANTE: l'app calcola e segnala; NON dichiara mai che un immobile è "conforme". Le percentuali
 * di tolleranza (art. 34-bis DPR 380/2001, art. 198 L.R. Toscana 65/2014) si applicano solo quando il
 * riferimento è il TITOLO EDILIZIO e vanno sempre ricontrollate sul testo vigente. Per il catasto non
 * si applica nessuna soglia di legge: si mostrano solo gli scostamenti.
 */
object ConformitaCalcolo {

    enum class Fonte(val etichetta: String) {
        CATASTO("Planimetria catastale"),
        TITOLO("Titolo edilizio"),
        RILIEVO("Stato di fatto")
    }

    data class Vano(
        val nome: String,
        val areaM2: Double? = null,
        val altezzaM: Double? = null,
        /** Locale abitabile: si controlla anche l'altezza minima di legge (2,70 m, tolleranza 2%). */
        val abitabile: Boolean = false
    )

    enum class Esito(val etichetta: String) {
        UGUALE("uguale"),
        ENTRO_SOGLIA("entro la soglia"),
        OLTRE_SOGLIA("oltre la soglia"),
        DIFFERENZA("differenza (nessuna soglia di legge applicata)"),
        NON_CONFRONTABILE("dato mancante")
    }

    /** Una riga del confronto vano per vano. [riferimento] è la fonte A, [misurato] la fonte B. */
    data class RigaVano(
        val nome: String,
        val riferimento: Vano?,
        val misurato: Vano?,
        val deltaAreaM2: Double?,
        val deltaAreaPerc: Double?,
        val deltaAltezzaM: Double?,
        val deltaAltezzaPerc: Double?
    ) {
        val soloInRiferimento get() = riferimento != null && misurato == null
        val soloInMisurato get() = riferimento == null && misurato != null
    }

    /** Un parametro valutato a livello di unità immobiliare (superficie, volume, altezze…). */
    data class Parametro(
        val nome: String,
        val riferimento: Double,
        val misurato: Double,
        val unita: String,
        val scostamentoPerc: Double,
        /** null = nessuna soglia applicata (riferimento non edilizio). */
        val tolleranzaPerc: Double?,
        val esito: Esito,
        val nota: String = ""
    )

    data class Risultato(
        val riferimento: Fonte,
        val confrontata: Fonte,
        val righe: List<RigaVano>,
        val parametri: List<Parametro>,
        val avvisi: List<String>
    )

    // ------------------------------------------------------------------
    // Tolleranze (art. 34-bis DPR 380/2001)
    // ------------------------------------------------------------------

    const val SOGLIA_ALTEZZA_MIN_ABITABILE_M = 2.70
    const val TOLLERANZA_FISSA_PERC = 2.0

    /**
     * Percentuale di tolleranza sui parametri di progetto (comma 1 e 1-bis).
     *  - comma 1: 2% sempre;
     *  - comma 1-bis (solo lavori conclusi entro il 24/5/2024): >500 mq 2%, 300-500 mq 3%, 100-300 mq 4%,
     *    60-100 mq 5%, <60 mq 6%, secondo la superficie utile assentita dal titolo originario.
     * Sui valori esattamente al limite di due fasce si applica la percentuale più prudente (più bassa).
     */
    fun percentualeTolleranza(superficieUtileAssentitaMq: Double?, lavoriConclusiEntro24052024: Boolean): Double {
        if (!lavoriConclusiEntro24052024 || superficieUtileAssentitaMq == null || superficieUtileAssentitaMq <= 0.0) return TOLLERANZA_FISSA_PERC
        val s = superficieUtileAssentitaMq
        return when {
            s > 500.0 -> 2.0
            s >= 500.0 -> 2.0   // esattamente al limite: la più prudente
            s > 300.0 -> 3.0
            s >= 300.0 -> 3.0
            s > 100.0 -> 4.0
            s >= 100.0 -> 4.0
            s > 60.0 -> 5.0
            s >= 60.0 -> 5.0
            else -> 6.0
        }
    }

    /** true se la superficie cade esattamente al limite tra due fasce (si avvisa che è stata scelta la più prudente). */
    fun alLimiteDiFascia(superficieUtileAssentitaMq: Double?): Boolean =
        superficieUtileAssentitaMq != null && listOf(60.0, 100.0, 300.0, 500.0).any { abs(it - superficieUtileAssentitaMq) < 1e-9 }

    // ------------------------------------------------------------------
    // Abbinamento e confronto dei vani
    // ------------------------------------------------------------------

    internal fun normalizza(testo: String): String =
        Normalizer.normalize(testo, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ITALY).replace(Regex("[^a-z0-9]+"), " ").trim()

    private fun scostPerc(riferimento: Double, misurato: Double): Double =
        if (riferimento == 0.0) (if (misurato == 0.0) 0.0 else Double.POSITIVE_INFINITY) else (misurato - riferimento) / riferimento * 100.0

    /** Abbina i vani per nome (ignorando maiuscole, accenti e punteggiatura). I non abbinati restano "solo in una fonte". */
    fun confrontaVani(riferimento: List<Vano>, misurato: List<Vano>): List<RigaVano> {
        val restanti = misurato.toMutableList()
        val righe = ArrayList<RigaVano>()
        for (r in riferimento) {
            val chiave = normalizza(r.nome)
            val trovato = restanti.firstOrNull { chiave.isNotEmpty() && normalizza(it.nome).replace(" ", "") == chiave.replace(" ", "") }
            if (trovato != null) restanti.remove(trovato)
            righe += riga(r.nome, r, trovato)
        }
        restanti.forEach { righe += riga(it.nome, null, it) }
        return righe
    }

    private fun riga(nome: String, r: Vano?, m: Vano?): RigaVano {
        val dA = if (r?.areaM2 != null && m?.areaM2 != null) m.areaM2 - r.areaM2 else null
        val pA = if (r?.areaM2 != null && m?.areaM2 != null) scostPerc(r.areaM2, m.areaM2) else null
        val dH = if (r?.altezzaM != null && m?.altezzaM != null) m.altezzaM - r.altezzaM else null
        val pH = if (r?.altezzaM != null && m?.altezzaM != null) scostPerc(r.altezzaM, m.altezzaM) else null
        return RigaVano(nome, r, m, dA, pA, dH, pH)
    }

    /**
     * Confronto completo tra due fonti. Le soglie di tolleranza si applicano SOLO se [riferimento] è il titolo
     * edilizio. [superficieUtileAssentitaMq] (se nota) decide la fascia del comma 1-bis; altrimenti si usa la somma
     * dei vani del titolo.
     */
    fun confronta(
        riferimento: Fonte,
        vaniRiferimento: List<Vano>,
        confrontata: Fonte,
        vaniConfrontati: List<Vano>,
        lavoriConclusiEntro24052024: Boolean,
        superficieUtileAssentitaMq: Double? = null
    ): Risultato {
        val righe = confrontaVani(vaniRiferimento, vaniConfrontati)
        val avvisi = ArrayList<String>()
        val edilizio = riferimento == Fonte.TITOLO
        val abbinati = righe.filter { it.riferimento != null && it.misurato != null }

        righe.filter { it.soloInRiferimento }.forEach { avvisi += "\"${it.nome}\" è presente solo in: ${riferimento.etichetta}." }
        righe.filter { it.soloInMisurato }.forEach { avvisi += "\"${it.nome}\" è presente solo in: ${confrontata.etichetta}." }

        val supRif = abbinati.mapNotNull { it.riferimento?.areaM2 }.sum()
        val supMis = abbinati.mapNotNull { it.misurato?.areaM2 }.sum()
        val sufficientiPerSuperficie = abbinati.any { it.riferimento?.areaM2 != null && it.misurato?.areaM2 != null }

        var tolleranza: Double? = null
        if (edilizio) {
            val base = superficieUtileAssentitaMq ?: vaniRiferimento.mapNotNull { it.areaM2 }.sum().takeIf { it > 0.0 }
            tolleranza = percentualeTolleranza(base, lavoriConclusiEntro24052024)
            if (lavoriConclusiEntro24052024 && base == null) avvisi += "Superficie utile assentita non nota: applicato il 2% (comma 1)."
            if (lavoriConclusiEntro24052024 && alLimiteDiFascia(base)) avvisi += "Superficie utile esattamente al limite tra due fasce: applicata la percentuale più prudente."
            if (!lavoriConclusiEntro24052024) avvisi += "Lavori dopo il 24/5/2024 (o non indicati): vale il 2% fisso del comma 1, non le fasce del comma 1-bis."
        } else {
            avvisi += "Il confronto con il catasto/stato di fatto non applica soglie di tolleranza edilizia: valutazione a cura del professionista."
        }

        fun esitoDi(delta: Double, tol: Double?): Esito = when {
            abs(delta) < 0.05 -> Esito.UGUALE
            tol == null -> Esito.DIFFERENZA
            abs(delta) <= tol + 1e-9 -> Esito.ENTRO_SOGLIA
            else -> Esito.OLTRE_SOGLIA
        }

        val parametri = ArrayList<Parametro>()
        if (sufficientiPerSuperficie && supRif > 0.0) {
            val p = scostPerc(supRif, supMis)
            parametri += Parametro("Superficie dei vani confrontati", supRif, supMis, "mq", p, tolleranza, esitoDi(p, tolleranza),
                if (edilizio) "Tolleranza riferita all'unità e al singolo parametro, sulle misure del titolo." else "")
        }
        // Volume dei vani con area e altezza in entrambe le fonti.
        val conVolume = abbinati.filter { it.riferimento?.areaM2 != null && it.riferimento.altezzaM != null && it.misurato?.areaM2 != null && it.misurato.altezzaM != null }
        if (conVolume.isNotEmpty()) {
            val vRif = conVolume.sumOf { it.riferimento!!.areaM2!! * it.riferimento.altezzaM!! }
            val vMis = conVolume.sumOf { it.misurato!!.areaM2!! * it.misurato.altezzaM!! }
            if (vRif > 0.0) {
                val p = scostPerc(vRif, vMis)
                parametri += Parametro("Volume dei vani con area e altezza", vRif, vMis, "mc", p, tolleranza, esitoDi(p, tolleranza))
            }
        }
        // Altezze, vano per vano.
        abbinati.filter { it.deltaAltezzaPerc != null }.forEach { r ->
            val p = r.deltaAltezzaPerc!!
            parametri += Parametro("Altezza – ${r.nome}", r.riferimento!!.altezzaM!!, r.misurato!!.altezzaM!!, "m", p, tolleranza, esitoDi(p, tolleranza))
        }
        // Minimi igienico-sanitari: sempre 2% fisso, anche se per gli altri parametri vale una fascia più alta.
        if (edilizio) {
            abbinati.filter { (it.misurato?.abitabile == true || it.riferimento?.abitabile == true) && it.misurato?.altezzaM != null }.forEach { r ->
                val h = r.misurato!!.altezzaM!!
                val minimoTollerato = SOGLIA_ALTEZZA_MIN_ABITABILE_M * (1 - TOLLERANZA_FISSA_PERC / 100.0)
                val sotto = scostPerc(SOGLIA_ALTEZZA_MIN_ABITABILE_M, h)
                val esito = when {
                    h >= SOGLIA_ALTEZZA_MIN_ABITABILE_M - 1e-9 -> Esito.UGUALE
                    h >= minimoTollerato - 1e-9 -> Esito.ENTRO_SOGLIA
                    else -> Esito.OLTRE_SOGLIA
                }
                parametri += Parametro("Altezza minima di legge – ${r.nome}", SOGLIA_ALTEZZA_MIN_ABITABILE_M, h, "m", sotto, TOLLERANZA_FISSA_PERC, esito,
                    "Minimo 2,70 m con tolleranza fissa del 2% (min. ${"%.3f".format(Locale.ITALY, minimoTollerato)} m), anche se per gli altri parametri vale una fascia più alta.")
            }
        }
        if (righe.isNotEmpty() && parametri.isEmpty()) avvisi += "Mancano superfici o altezze in entrambe le fonti: inseriscile per ottenere gli scostamenti."
        return Risultato(riferimento, confrontata, righe, parametri, avvisi)
    }

    // ------------------------------------------------------------------
    // Atto e visura
    // ------------------------------------------------------------------

    enum class TipoCampo { NUMERO, CATEGORIA, TESTO, PERSONE }
    enum class EsitoCampo(val etichetta: String) { UGUALE("uguale"), DIVERSO("DIVERSO"), MANCANTE("dato mancante") }

    data class Campo(val etichetta: String, val tipo: TipoCampo, val atto: String = "", val visura: String = "")
    data class RigaCampo(val campo: Campo, val esito: EsitoCampo)

    /** I campi base da confrontare, vuoti. */
    fun campiBase(): List<Campo> = listOf(
        Campo("Foglio", TipoCampo.NUMERO), Campo("Particella", TipoCampo.NUMERO), Campo("Subalterno", TipoCampo.NUMERO),
        Campo("Categoria", TipoCampo.CATEGORIA), Campo("Classe", TipoCampo.TESTO), Campo("Consistenza", TipoCampo.NUMERO),
        Campo("Rendita (€)", TipoCampo.NUMERO), Campo("Indirizzo", TipoCampo.TESTO),
        Campo("Intestatari (uno per riga)", TipoCampo.PERSONE), Campo("Quota / diritto", TipoCampo.TESTO)
    )

    private fun numero(s: String): Double? {
        val t = s.replace("€", "").replace(" ", "").replace(".", "").replace(',', '.')
        return t.toDoubleOrNull()
    }

    internal fun normalizzaValore(tipo: TipoCampo, s: String): String = when (tipo) {
        TipoCampo.NUMERO -> numero(s)?.let { if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString() } ?: normalizza(s)
        TipoCampo.CATEGORIA -> s.uppercase(Locale.ITALY).replace(Regex("[^A-Z0-9]"), "")
        TipoCampo.TESTO -> normalizza(s)
        TipoCampo.PERSONE -> s.split('\n', ';').map { normalizza(it).split(' ').filter { p -> p.isNotEmpty() }.sorted().joinToString(" ") }
            .filter { it.isNotEmpty() }.sorted().joinToString("|")
    }

    fun confrontaCampi(campi: List<Campo>): List<RigaCampo> = campi.map { c ->
        val a = normalizzaValore(c.tipo, c.atto); val v = normalizzaValore(c.tipo, c.visura)
        RigaCampo(c, when {
            a.isEmpty() || v.isEmpty() -> EsitoCampo.MANCANTE
            a == v -> EsitoCampo.UGUALE
            else -> EsitoCampo.DIVERSO
        })
    }

    // ------------------------------------------------------------------
    // Testo del riepilogo
    // ------------------------------------------------------------------

    private fun n(x: Double, dec: Int = 2) = String.format(Locale.ITALY, "%.${dec}f", x)
    private fun sg(x: Double, dec: Int = 2) = (if (x > 0) "+" else "") + n(x, dec)

    fun riepilogo(titolo: String, r: Risultato): String = buildString {
        appendLine(titolo)
        appendLine("Riferimento: ${r.riferimento.etichetta} — confrontata con: ${r.confrontata.etichetta}")
        appendLine()
        appendLine("VANI")
        r.righe.forEach {
            when {
                it.soloInRiferimento -> appendLine("• ${it.nome}: solo in ${r.riferimento.etichetta}")
                it.soloInMisurato -> appendLine("• ${it.nome}: solo in ${r.confrontata.etichetta}")
                else -> {
                    val a = if (it.deltaAreaM2 != null) "area ${n(it.riferimento!!.areaM2!!)} → ${n(it.misurato!!.areaM2!!)} mq (${sg(it.deltaAreaM2)} mq, ${sg(it.deltaAreaPerc!!, 1)}%)" else "area n.d."
                    val h = if (it.deltaAltezzaM != null) "; altezza ${n(it.riferimento!!.altezzaM!!)} → ${n(it.misurato!!.altezzaM!!)} m (${sg(it.deltaAltezzaPerc!!, 1)}%)" else ""
                    appendLine("• ${it.nome}: $a$h")
                }
            }
        }
        if (r.parametri.isNotEmpty()) {
            appendLine(); appendLine("PARAMETRI DELL'UNITÀ")
            r.parametri.forEach {
                val t = it.tolleranzaPerc?.let { t -> " — soglia ${n(t, 1)}%" } ?: ""
                appendLine("• ${it.nome}: ${n(it.riferimento)} → ${n(it.misurato)} ${it.unita} (${sg(it.scostamentoPerc, 1)}%)$t: ${it.esito.etichetta}")
                if (it.nota.isNotBlank()) appendLine("   ${it.nota}")
            }
        }
        if (r.avvisi.isNotEmpty()) { appendLine(); appendLine("AVVISI"); r.avvisi.forEach { appendLine("• $it") } }
        appendLine()
        appendLine("Nota: elaborazione automatica di supporto, non una dichiarazione di conformità. Le soglie (art. 34-bis DPR 380/2001, art. 198 L.R. 65/2014) vanno verificate sul testo vigente; la valutazione resta al professionista.")
    }

    fun riepilogoCampi(righe: List<RigaCampo>): String = buildString {
        appendLine("CONFRONTO ATTO / VISURA")
        righe.forEach { appendLine("• ${it.campo.etichetta}: ${it.esito.etichetta}" + if (it.esito == EsitoCampo.DIVERSO) " (atto: ${it.campo.atto.replace('\n', ';')} — visura: ${it.campo.visura.replace('\n', ';')})" else "") }
    }
}

/** Dati di lavoro della verifica, salvabili per cliente. */
data class DatiConformita(
    val vani: Map<ConformitaCalcolo.Fonte, List<ConformitaCalcolo.Vano>> = emptyMap(),
    val lavoriEntro24052024: Boolean = false,
    val superficieUtileAssentitaMq: Double? = null,
    val campi: List<ConformitaCalcolo.Campo> = ConformitaCalcolo.campiBase()
) {
    fun toJson(): String = JSONObject().apply {
        put("lavoriEntro24052024", lavoriEntro24052024)
        superficieUtileAssentitaMq?.let { put("superficieUtileAssentitaMq", it) }
        put("vani", JSONObject().apply {
            vani.forEach { (f, lista) ->
                put(f.name, JSONArray().apply {
                    lista.forEach { put(JSONObject().put("nome", it.nome).apply {
                        it.areaM2?.let { a -> put("areaM2", a) }; it.altezzaM?.let { h -> put("altezzaM", h) }; put("abitabile", it.abitabile) }) }
                })
            }
        })
        put("campi", JSONArray().apply {
            campi.forEach { put(JSONObject().put("etichetta", it.etichetta).put("tipo", it.tipo.name).put("atto", it.atto).put("visura", it.visura)) }
        })
    }.toString()

    companion object {
        fun daJson(testo: String): DatiConformita {
            val o = JSONObject(testo)
            val vani = HashMap<ConformitaCalcolo.Fonte, List<ConformitaCalcolo.Vano>>()
            o.optJSONObject("vani")?.let { vj ->
                ConformitaCalcolo.Fonte.values().forEach { f ->
                    vj.optJSONArray(f.name)?.let { a ->
                        vani[f] = (0 until a.length()).map { i ->
                            val v = a.getJSONObject(i)
                            ConformitaCalcolo.Vano(v.optString("nome"), if (v.has("areaM2")) v.optDouble("areaM2") else null,
                                if (v.has("altezzaM")) v.optDouble("altezzaM") else null, v.optBoolean("abitabile"))
                        }
                    }
                }
            }
            val campi = o.optJSONArray("campi")?.let { a ->
                (0 until a.length()).map { i ->
                    val c = a.getJSONObject(i)
                    ConformitaCalcolo.Campo(c.optString("etichetta"),
                        runCatching { ConformitaCalcolo.TipoCampo.valueOf(c.optString("tipo")) }.getOrDefault(ConformitaCalcolo.TipoCampo.TESTO),
                        c.optString("atto"), c.optString("visura"))
                }
            } ?: ConformitaCalcolo.campiBase()
            return DatiConformita(vani, o.optBoolean("lavoriEntro24052024"),
                if (o.has("superficieUtileAssentitaMq")) o.optDouble("superficieUtileAssentitaMq") else null, campi)
        }
    }
}
