package it.geometra.schedacliente

import android.graphics.Color

/**
 * Icone e colori delle categorie del sondaggio FDM, condivisi tra la schermata della ruota
 * (registro locale) e l'immagine di riepilogo per Drive. Gli stessi colori del sondaggio
 * condiviso su claude.ai, così i due "sondaggi" si somigliano. Nessun nome per esteso: si
 * mostrano solo le icone.
 */
object StileFdm {
    val ICONE = mapOf(
        "mondiale" to "💂",
        "fratello" to "🚴",
        "galli" to "🐓🐓",
        "celli" to "🔒",
        "generale" to "💩"
    )

    /** Colore del testo (numero) del chip. */
    val COLORE_TESTO = mapOf(
        "mondiale" to Color.parseColor("#2F6F62"),
        "fratello" to Color.parseColor("#6B4FA0"),
        "galli" to Color.parseColor("#8A5A00"),
        "celli" to Color.parseColor("#2B5C8A"),
        "generale" to Color.parseColor("#8A4B3B")
    )

    /** Colore di sfondo del chip. */
    val COLORE_SFONDO = mapOf(
        "mondiale" to Color.parseColor("#E3EFEA"),
        "fratello" to Color.parseColor("#ECE6F5"),
        "galli" to Color.parseColor("#F4E6C8"),
        "celli" to Color.parseColor("#E1ECF7"),
        "generale" to Color.parseColor("#F2E1D8")
    )

    val SFONDO_PAGINA = Color.parseColor("#F3F4EF")
    val SUPERFICIE = Color.WHITE
    val TESTO = Color.parseColor("#1E2A22")
    val TESTO_TENUE = Color.parseColor("#3F4B44")
    val BORDO = Color.parseColor("#D8DCD2")
    val ACCENTO = Color.parseColor("#C98A2B")
}
