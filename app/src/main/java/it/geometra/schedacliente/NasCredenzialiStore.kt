package it.geometra.schedacliente

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Percorso, utente e password del NAS dello studio (v3.78). Cifrati sul telefono come la chiave
 * AI (EncryptedSharedPreferences, chiave di cifratura nell'Android Keystore); la password viene
 * usata solo per collegarsi al NAS e non viene mai inviata altrove.
 */
object NasCredenzialiStore {
    private const val PREFS_SICURE = "scheda_cliente_prefs_sicure"
    private const val PERCORSO = "nas_percorso"
    private const val UTENTE = "nas_utente"
    private const val PASSWORD = "nas_password"

    private fun prefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            context, PREFS_SICURE, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    data class Dati(val percorso: String, val utente: String, val password: String)

    fun leggi(context: Context): Dati = prefs(context).let {
        Dati(it.getString(PERCORSO, "") ?: "", it.getString(UTENTE, "") ?: "", it.getString(PASSWORD, "") ?: "")
    }

    /** Solo per i test automatici (nel simulatore non c'è il portachiavi cifrato di Android). */
    @JvmField @androidx.annotation.VisibleForTesting
    var accessoPerTest: NasStudio.Accesso? = null

    /** Accesso pronto per [NasStudio], oppure null se il NAS non è stato impostato nelle Impostazioni. */
    fun accesso(context: Context): NasStudio.Accesso? {
        accessoPerTest?.let { return it }
        val d = leggi(context)
        val percorso = NasStudio.analizzaPercorso(d.percorso) ?: return null
        if (d.utente.isBlank()) return null
        return NasStudio.Accesso(percorso, d.utente, d.password)
    }

    fun salva(context: Context, percorso: String, utente: String, password: String) {
        prefs(context).edit().putString(PERCORSO, percorso).putString(UTENTE, utente).putString(PASSWORD, password).apply()
    }

    fun rimuovi(context: Context) {
        prefs(context).edit().remove(PERCORSO).remove(UTENTE).remove(PASSWORD).apply()
    }
}
