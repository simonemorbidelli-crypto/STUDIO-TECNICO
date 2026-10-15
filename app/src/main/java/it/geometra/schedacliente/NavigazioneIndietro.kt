package it.geometra.schedacliente

import android.app.AlertDialog
import android.content.Intent
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

/**
 * Comportamento del tasto "indietro" del telefono:
 *  - in ogni sezione chiede se si vuole tornare alla pagina iniziale di SchedaCliente (oppure
 *    uscire solo dalla sezione, oppure restare dove si è);
 *  - nella pagina iniziale chiede conferma prima di uscire dall'app.
 * Il tasto freccia della barra in alto resta com'era (torna subito alla schermata precedente).
 */
object NavigazioneIndietro {

    /** Da chiamare in onCreate di una sezione. Vince sui callback aggiunti PRIMA di questa chiamata. */
    fun installa(attivita: AppCompatActivity) {
        attivita.onBackPressedDispatcher.addCallback(attivita, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = chiediDaSezione(attivita)
        })
    }

    /** Callback per la pagina iniziale: conferma di uscita. */
    fun installaPaginaIniziale(attivita: AppCompatActivity) {
        attivita.onBackPressedDispatcher.addCallback(attivita, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = chiediUscita(attivita)
        })
    }

    fun chiediDaSezione(a: AppCompatActivity) {
        if (a.isFinishing || a.isDestroyed) return
        val dialog = AlertDialog.Builder(a, R.style.Theme_SchedaCliente_Dialog)
            .setTitle("Tornare alla pagina iniziale?")
            .setMessage("Vuoi tornare alla pagina iniziale di SchedaCliente prima di uscire da questa sezione?")
            .setPositiveButton("Pagina iniziale") { _, _ -> vaiAllaPaginaIniziale(a) }
            .setNeutralButton("Esci dalla sezione") { _, _ -> a.finish() }
            .setNegativeButton("Resta qui", null)
            .show()
        dialog.rendiPulsantiLeggibili(a)
    }

    fun chiediUscita(a: AppCompatActivity) {
        if (a.isFinishing || a.isDestroyed) return
        val dialog = AlertDialog.Builder(a, R.style.Theme_SchedaCliente_Dialog)
            .setTitle("Uscire da SchedaCliente?")
            .setMessage("Vuoi uscire definitivamente dall'applicazione?")
            .setPositiveButton("Esci") { _, _ -> a.finishAffinity() }
            .setNegativeButton("Resta", null)
            .show()
        dialog.rendiPulsantiLeggibili(a)
    }

    fun vaiAllaPaginaIniziale(a: AppCompatActivity) {
        a.startActivity(
            Intent(a, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }
}
