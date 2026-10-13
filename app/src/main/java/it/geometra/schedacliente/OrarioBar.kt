package it.geometra.schedacliente

import java.util.Calendar

/**
 * Una fascia di apertura di un bar, per un singolo giorno della settimana ([giorno] è una delle
 * costanti [Calendar.SUNDAY]..[Calendar.SATURDAY]), espressa in minuti dalla mezzanotte di quel
 * giorno. Se il bar chiude dopo mezzanotte (es. venerdì 17:00–00:30), [fineMinuti] supera 1440
 * invece di tornare a un valore piccolo: "00:30 del giorno dopo" diventa 24:30, cioè 1470.
 */
data class FasciaOraria(val giorno: Int, val inizioMinuti: Int, val fineMinuti: Int)

/**
 * Orari di apertura noti per i bar dell'elenco predefinito della ruota del caffè (vedi
 * [RuotaCaffeStore]), raccolti da Google e verificati dall'utente. Un bar assente da questa
 * mappa (compreso qualunque bar aggiunto a mano dall'utente in "Modifica elenco bar", di cui
 * l'app non può conoscere l'orario) viene sempre considerato aperto, per non escluderlo mai per
 * errore dalla ruota. Gli orari vanno comunque sempre controllati di persona prima di uscire:
 * un bar può cambiarli o restare chiuso per ferie senza che l'app possa saperlo.
 */
object OrariBarNoti {
    private fun l(vararg fasce: Triple<Int, String, String>): List<FasciaOraria> =
        fasce.map { (giorno, inizio, fine) -> FasciaOraria(giorno, minutiDa(inizio), minutiDaConEventualeGiornoDopo(inizio, fine)) }

    /** Converte "HH:mm" in minuti dalla mezzanotte. */
    private fun minutiDa(ora: String): Int {
        val (h, m) = ora.split(":").map { it.toInt() }
        return h * 60 + m
    }

    /** Come [minutiDa], ma se l'orario di chiusura è "prima" di quello di apertura si somma un giorno intero (chiusura dopo mezzanotte). */
    private fun minutiDaConEventualeGiornoDopo(inizio: String, fine: String): Int {
        val minInizio = minutiDa(inizio)
        val minFine = minutiDa(fine)
        return if (minFine <= minInizio) minFine + 1440 else minFine
    }

    val orari: Map<String, List<FasciaOraria>> = mapOf(
        "Mr Jack's Bar" to l(
            Triple(Calendar.TUESDAY, "07:00", "20:30"),
            Triple(Calendar.WEDNESDAY, "07:00", "20:30"),
            Triple(Calendar.THURSDAY, "07:00", "20:30"),
            Triple(Calendar.FRIDAY, "07:00", "12:45"),
            Triple(Calendar.FRIDAY, "17:00", "00:30"),
            Triple(Calendar.SATURDAY, "07:00", "01:00"),
            Triple(Calendar.SUNDAY, "08:00", "20:30")
            // lunedì: chiuso (nessuna fascia)
        ),
        "Bar Pasticceria La Dolce Via" to l(
            Triple(Calendar.SUNDAY, "05:00", "13:00"),
            Triple(Calendar.SUNDAY, "15:45", "19:10"),
            Triple(Calendar.MONDAY, "05:00", "13:00"),
            Triple(Calendar.MONDAY, "15:45", "19:10"),
            // martedì: chiuso
            Triple(Calendar.WEDNESDAY, "05:00", "13:00"),
            Triple(Calendar.WEDNESDAY, "15:45", "19:10"),
            Triple(Calendar.THURSDAY, "05:00", "13:00"),
            Triple(Calendar.THURSDAY, "15:45", "19:10"),
            Triple(Calendar.FRIDAY, "05:00", "13:00"),
            Triple(Calendar.FRIDAY, "15:45", "19:10"),
            Triple(Calendar.SATURDAY, "05:00", "13:00"),
            Triple(Calendar.SATURDAY, "15:45", "19:10")
        ),
        "New California" to l(
            Triple(Calendar.SUNDAY, "17:00", "21:30"),
            // lunedì: chiuso
            Triple(Calendar.TUESDAY, "11:00", "14:00"),
            Triple(Calendar.TUESDAY, "17:00", "21:30"),
            Triple(Calendar.WEDNESDAY, "11:00", "14:00"),
            Triple(Calendar.WEDNESDAY, "17:00", "21:30"),
            Triple(Calendar.THURSDAY, "11:00", "14:00"),
            Triple(Calendar.THURSDAY, "17:00", "21:30"),
            Triple(Calendar.FRIDAY, "11:00", "14:00"),
            Triple(Calendar.FRIDAY, "17:00", "21:30"),
            Triple(Calendar.SATURDAY, "11:00", "14:00"),
            Triple(Calendar.SATURDAY, "17:00", "21:30")
        ),
        // Orari aggiornati il 12/10/2026 dalla scheda Google del locale (screenshot dell'utente):
        // orario continuato 06:00–20:30 da lunedì a sabato, giovedì chiuso, domenica su due fasce.
        "Gelateria Caffetteria Turismo" to l(
            Triple(Calendar.SUNDAY, "06:30", "13:00"),
            Triple(Calendar.SUNDAY, "15:00", "21:00"),
            Triple(Calendar.MONDAY, "06:00", "20:30"),
            Triple(Calendar.TUESDAY, "06:00", "20:30"),
            Triple(Calendar.WEDNESDAY, "06:00", "20:30"),
            // giovedì: chiuso tutto il giorno
            Triple(Calendar.FRIDAY, "06:00", "20:30"),
            Triple(Calendar.SATURDAY, "06:00", "20:30")
        ),
        "Circolo MCL" to l(
            Triple(Calendar.SUNDAY, "13:00", "24:00"),
            Triple(Calendar.MONDAY, "13:00", "24:00"),
            Triple(Calendar.TUESDAY, "13:00", "24:00"),
            Triple(Calendar.WEDNESDAY, "13:00", "24:00"),
            Triple(Calendar.THURSDAY, "13:00", "24:00"),
            Triple(Calendar.FRIDAY, "13:00", "24:00"),
            Triple(Calendar.SATURDAY, "13:00", "24:00")
        ),
        "Enoteca La Torre" to l(
            Triple(Calendar.SUNDAY, "06:30", "23:30"),
            // lunedì: chiuso
            Triple(Calendar.TUESDAY, "06:30", "23:30"),
            Triple(Calendar.WEDNESDAY, "06:30", "23:30"),
            Triple(Calendar.THURSDAY, "06:30", "23:30"),
            Triple(Calendar.FRIDAY, "06:30", "23:30"),
            Triple(Calendar.SATURDAY, "06:30", "23:30")
        ),
        "Casa del Popolo" to l(
            Triple(Calendar.SUNDAY, "07:00", "13:00"),
            Triple(Calendar.SUNDAY, "16:00", "23:30"),
            Triple(Calendar.MONDAY, "16:00", "23:30"),
            Triple(Calendar.TUESDAY, "07:00", "12:00"),
            Triple(Calendar.TUESDAY, "16:00", "23:30"),
            Triple(Calendar.WEDNESDAY, "07:00", "12:00"),
            Triple(Calendar.WEDNESDAY, "16:00", "23:30"),
            Triple(Calendar.THURSDAY, "07:00", "12:00"),
            Triple(Calendar.THURSDAY, "16:00", "23:30"),
            Triple(Calendar.FRIDAY, "07:00", "12:00"),
            Triple(Calendar.FRIDAY, "16:00", "23:30"),
            Triple(Calendar.SATURDAY, "07:00", "13:00"),
            Triple(Calendar.SATURDAY, "16:00", "23:30")
        )
    )

    /**
     * True se [nomeBar] è aperto adesso oppure aprirà prima di [limite] (es. 30 minuti dopo
     * l'ingresso nella schermata della ruota). Si controlla a passi di 5 minuti tra adesso e il
     * limite: basta un istante "aperto" nella finestra per tenere il bar in ruota.
     */
    fun isApertoOApreEntro(nomeBar: String, limite: Calendar, adesso: Calendar = Calendar.getInstance()): Boolean {
        if (isAperto(nomeBar, adesso)) return true
        val passo = adesso.clone() as Calendar
        while (passo.before(limite)) {
            passo.add(Calendar.MINUTE, 5)
            val istante = if (passo.after(limite)) limite else passo
            if (isAperto(nomeBar, istante)) return true
        }
        return false
    }

    /**
     * True se, secondo gli orari noti, [nomeBar] risulta aperto in questo istante ([adesso]).
     * Se il bar non è nella mappa [orari] (orario sconosciuto, es. un bar aggiunto a mano
     * dall'utente), restituisce sempre true: senza dati non lo si esclude mai dalla ruota.
     */
    fun isAperto(nomeBar: String, adesso: Calendar = Calendar.getInstance()): Boolean {
        val fasce = orari[nomeBar] ?: return true

        val giornoOggi = adesso.get(Calendar.DAY_OF_WEEK)
        val giornoIeri = if (giornoOggi == Calendar.SUNDAY) Calendar.SATURDAY else giornoOggi - 1
        val minutiAdesso = adesso.get(Calendar.HOUR_OF_DAY) * 60 + adesso.get(Calendar.MINUTE)

        val apertoOggi = fasce.any {
            it.giorno == giornoOggi && minutiAdesso >= it.inizioMinuti && minutiAdesso < minOf(it.fineMinuti, 1440)
        }
        if (apertoOggi) return true

        // Fasce di ieri che proseguono oltre la mezzanotte (es. venerdì 17:00–00:30): la parte
        // dopo mezzanotte ricade nelle prime ore di "oggi" e va controllata separatamente.
        return fasce.any {
            it.giorno == giornoIeri && it.fineMinuti > 1440 && minutiAdesso < (it.fineMinuti - 1440)
        }
    }
}
