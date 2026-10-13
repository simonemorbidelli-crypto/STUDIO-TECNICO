package it.geometra.schedacliente

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Salva la chiave API personale (Anthropic/Claude) usata per la dettatura
 * vocale assistita da AI. La chiave viene cifrata sul disco del telefono
 * (EncryptedSharedPreferences, chiave di cifratura nell'Android Keystore)
 * e non viene mai inviata altrove se non, ovviamente, al servizio AI stesso
 * quando l'utente detta una pratica.
 */
object ChiaveApiStore {
    private const val PREFS_SICURE = "scheda_cliente_prefs_sicure"
    private const val PREFS_VECCHIE = "scheda_cliente_prefs"
    private const val CHIAVE_API = "anthropic_api_key"

    private fun prefsSicure(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            PREFS_SICURE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /**
     * Sposta, una tantum, una eventuale chiave salvata dalle versioni
     * precedenti dell'app (non cifrata) nel nuovo archivio cifrato, poi la
     * rimuove da dove si trovava prima. Così chi aggiorna l'app non perde
     * la chiave già inserita.
     */
    private fun migraSeNecessario(context: Context, sicure: SharedPreferences) {
        if (sicure.contains(CHIAVE_API)) return

        val vecchie = context.getSharedPreferences(PREFS_VECCHIE, Context.MODE_PRIVATE)
        val chiaveVecchia = vecchie.getString(CHIAVE_API, "") ?: ""
        if (chiaveVecchia.isNotBlank()) {
            sicure.edit().putString(CHIAVE_API, chiaveVecchia).apply()
            vecchie.edit().remove(CHIAVE_API).apply()
        }
    }

    fun leggiChiave(context: Context): String {
        val sicure = prefsSicure(context)
        migraSeNecessario(context, sicure)
        return sicure.getString(CHIAVE_API, "") ?: ""
    }

    fun salvaChiave(context: Context, chiave: String) {
        prefsSicure(context).edit().putString(CHIAVE_API, chiave).apply()
    }

    fun rimuoviChiave(context: Context) {
        prefsSicure(context).edit().remove(CHIAVE_API).apply()
    }
}
