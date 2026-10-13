package it.geometra.schedacliente

import android.app.AlertDialog
import android.content.Context
import androidx.core.content.ContextCompat

/**
 * Forza un colore di testo ben leggibile sui pulsanti delle finestre di
 * conferma (es. "Elimina" / "Annulla"), indipendentemente da come il tema
 * del telefono o di Android risolve gli attributi di stile dei pulsanti di
 * un AlertDialog, che in alcuni casi può renderli invisibili.
 */
fun AlertDialog.rendiPulsantiLeggibili(context: Context) {
    val coloreAzione = ContextCompat.getColor(context, R.color.button_salva)
    val coloreNeutro = ContextCompat.getColor(context, R.color.text_secondary)

    getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(coloreAzione)
    getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(coloreNeutro)
    getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(coloreNeutro)
}
