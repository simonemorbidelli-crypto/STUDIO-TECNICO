package it.geometra.schedacliente

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.commons.EnumWithValue
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.Normalizer
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * Lettura delle cartelle delle pratiche sul NAS dello studio (cartella condivisa Windows/SMB,
 * es. Zyxel NAS326), per la sezione "Parere / relazione dai documenti" (v3.78).
 *
 * - Protocollo SMB2/SMB3 con la libreria smbj (il NAS326 supporta SMBv2; SMB1 non serve).
 * - Accesso con utente e password del NAS, salvati cifrati sul telefono ([NasCredenzialiStore]).
 * - In studio funziona sul Wi-Fi dell'ufficio; fuori studio serve una VPN accesa sul telefono:
 *   il NAS NON va mai esposto direttamente su Internet.
 * - Solo lettura: l'app non scrive, non sposta e non cancella nulla sul NAS.
 *
 * Il contenuto della cartella di un cliente viene restituito con le stesse strutture usate per
 * Mega ([MegaCartellaPubblica.Contenuto]), così la schermata di scelta dei file è la stessa.
 * Per il NAS l'"handle" di cartelle e file è il loro percorso dentro la cartella del cliente
 * ("/" = la cartella del cliente, "/Catasto", "/Catasto/visura.pdf").
 *
 * Tutte le funzioni di rete sono bloccanti: vanno chiamate su un thread in background.
 */
object NasStudio {

    /** Stesso limite di Mega: un file più grande non avrebbe senso per l'AI. */
    const val DIMENSIONE_MAX_FILE = MegaCartellaPubblica.DIMENSIONE_MAX_FILE
    /** Oltre questi elementi la cartella del cliente non si legge (protezione da cartelle enormi). */
    const val ELEMENTI_MAX = 5000
    const val PROFONDITA_MAX = 10

    /** Dove sono le pratiche: \\host\condivisione\cartella (cartella vuota = radice della condivisione). */
    data class Percorso(val host: String, val porta: Int, val condivisione: String, val cartella: String) {
        override fun toString(): String =
            "\\\\" + host + (if (porta != 445) ":$porta" else "") + "\\" + condivisione + (if (cartella.isEmpty()) "" else "\\$cartella")
    }

    data class Accesso(val percorso: Percorso, val utente: String, val password: String, val dominio: String = "")

    class ErroreNas(messaggio: String, val cartellaNonTrovata: Boolean = false, causa: Throwable? = null) : IOException(messaggio, causa)

    // ---------------------------------------------------------------------
    // Percorso scritto dall'utente
    // ---------------------------------------------------------------------

    /**
     * Riconosce il percorso della cartella delle pratiche scritto come in Esplora risorse
     * (\\192.168.1.20\Pratiche\Clienti), come indirizzo smb:// (smb://192.168.1.20/Pratiche) o
     * semplicemente 192.168.1.20/Pratiche. Serve almeno indirizzo e nome della condivisione.
     */
    fun analizzaPercorso(testo: String): Percorso? {
        var t = testo.trim().trim('"')
        if (t.lowercase().startsWith("smb://")) t = t.substring(6)
        t = t.replace('/', '\\').trimStart('\\').trimEnd('\\')
        val parti = t.split('\\').map { it.trim() }.filter { it.isNotEmpty() }
        if (parti.size < 2) return null
        val hostPorta = parti[0]
        val host = hostPorta.substringBefore(':')
        val porta = hostPorta.substringAfter(':', "445").toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        if (host.isBlank() || host.any { it.isWhitespace() }) return null
        return Percorso(host, porta, parti[1], parti.drop(2).joinToString("\\"))
    }

    // ---------------------------------------------------------------------
    // Operazioni sul NAS
    // ---------------------------------------------------------------------

    /** Prova il collegamento: restituisce quante cartelle (pratiche) ci sono nella cartella indicata. */
    fun prova(accesso: Accesso): Int = elencaCartelle(accesso).size

    /** Cartelle (una per cliente) nella cartella delle pratiche, in ordine alfabetico, senza quelle di sistema/nascoste. */
    fun elencaCartelle(accesso: Accesso): List<String> = conCondivisione(accesso) { share ->
        val base = accesso.percorso.cartella
        if (base.isNotEmpty() && !share.folderExists(base)) {
            throw ErroreNas("Sul NAS non trovo la cartella \"$base\" nella condivisione \"${accesso.percorso.condivisione}\"", cartellaNonTrovata = true)
        }
        share.list(base)
            .filter { eCartella(it.fileAttributes) && visibile(it.fileName, it.fileAttributes) }
            .map { it.fileName }
            .sortedBy { it.lowercase() }
    }

    /** Legge cartelle e file della cartella di un cliente (con le sottocartelle), per la navigazione e la scelta dei file. */
    fun leggiContenuto(accesso: Accesso, cartellaCliente: String): MegaCartellaPubblica.Contenuto = conCondivisione(accesso) { share ->
        val radiceSmb = unisci(accesso.percorso.cartella, cartellaCliente)
        if (!share.folderExists(radiceSmb)) {
            throw ErroreNas("Sul NAS non trovo più la cartella \"$cartellaCliente\" (rinominata o spostata?)", cartellaNonTrovata = true)
        }
        val radice = MegaCartellaPubblica.CartellaMega(RADICE, cartellaCliente.substringAfterLast('\\'), "", "")
        val cartelle = ArrayList<MegaCartellaPubblica.CartellaMega>()
        val file = ArrayList<MegaCartellaPubblica.FileMega>()
        fun visita(relativo: String, profondita: Int) {
            val handleCartella = if (relativo.isEmpty()) RADICE else "/$relativo"
            for (voce in share.list(unisci(radiceSmb, relativo.replace('/', '\\')))) {
                val nome = voce.fileName
                if (!visibile(nome, voce.fileAttributes)) continue
                if (cartelle.size + file.size >= ELEMENTI_MAX) {
                    throw ErroreNas("La cartella \"$cartellaCliente\" contiene troppi elementi (oltre $ELEMENTI_MAX): scegli una sottocartella più precisa")
                }
                val percorso = if (relativo.isEmpty()) nome else "$relativo/$nome"
                if (eCartella(voce.fileAttributes)) {
                    cartelle += MegaCartellaPubblica.CartellaMega("/$percorso", nome, handleCartella, percorso)
                    if (profondita < PROFONDITA_MAX) visita(percorso, profondita + 1)
                } else {
                    file += MegaCartellaPubblica.FileMega(
                        handle = "/$percorso", nome = nome, percorso = percorso, dimensione = voce.endOfFile,
                        chiaveAes = ByteArray(0), iv = ByteArray(0), genitore = handleCartella
                    )
                }
            }
        }
        visita("", 0)
        MegaCartellaPubblica.Contenuto(radice, cartelle, file)
    }

    /** Scarica (in memoria) un file della cartella del cliente. */
    fun scarica(accesso: Accesso, cartellaCliente: String, file: MegaCartellaPubblica.FileMega): ByteArray {
        if (file.dimensione > DIMENSIONE_MAX_FILE) {
            throw ErroreNas("\"${file.nome}\" è troppo grande (oltre ${DIMENSIONE_MAX_FILE / 1024 / 1024} MB)")
        }
        return conCondivisione(accesso) { share ->
            val percorso = unisci(accesso.percorso.cartella, cartellaCliente, file.percorso.replace('/', '\\'))
            share.openFile(
                percorso, EnumSet.of(AccessMask.GENERIC_READ), null,
                SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null
            ).use { f -> f.inputStream.use { it.readBytes() } }
        }
    }

    // ---------------------------------------------------------------------
    // Ricerca della cartella del cliente
    // ---------------------------------------------------------------------

    /**
     * Cartelle che corrispondono al cliente: devono contenere tutte le parole del cognome (o
     * ragione sociale); se qualcuna contiene anche tutte le parole del nome, restano solo quelle.
     * Maiuscole, accenti, trattini e ordine delle parole non contano ("ROSSI MARIO", "Mario Rossi",
     * "Rossi Mario - Via Roma 12" vanno bene per Rossi Mario). Ordinate dalla più corta.
     */
    fun cartellePerCliente(cartelle: List<String>, cognome: String, nome: String): List<String> {
        val paroleCognome = parole(cognome)
        if (paroleCognome.isEmpty()) return emptyList()
        val paroleNome = parole(nome)
        val conCognome = cartelle.map { it to parole(it) }.filter { (_, p) -> paroleCognome.all { it in p } }
        val conNome = conCognome.filter { (_, p) -> paroleNome.isNotEmpty() && paroleNome.all { it in p } }
        return (if (conNome.isNotEmpty()) conNome else conCognome).map { it.first }.sortedBy { it.length }
    }

    /** Testo senza maiuscole né accenti, con la sola punteggiatura trasformata in spazi (per confronti e filtri). */
    fun normalizza(testo: String): String =
        Normalizer.normalize(testo, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
            .lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    private fun parole(testo: String): Set<String> = normalizza(testo).split(' ').filter { it.length >= 2 }.toSet()

    // ---------------------------------------------------------------------

    private const val RADICE = "/"

    private val NOMI_NASCOSTI = setOf("thumbs.db", "desktop.ini", ".ds_store", "\$recycle.bin", "#recycle", "@recycle", "lost+found")

    private fun visibile(nome: String, attributi: Long): Boolean {
        if (nome == "." || nome == "..") return false
        val n = nome.lowercase()
        if (n in NOMI_NASCOSTI || n.startsWith(".") || n.startsWith("@") || n.startsWith("~$")) return false
        return !EnumWithValue.EnumUtils.isSet(attributi, FileAttributes.FILE_ATTRIBUTE_HIDDEN) &&
            !EnumWithValue.EnumUtils.isSet(attributi, FileAttributes.FILE_ATTRIBUTE_SYSTEM)
    }

    private fun eCartella(attributi: Long) = EnumWithValue.EnumUtils.isSet(attributi, FileAttributes.FILE_ATTRIBUTE_DIRECTORY)

    private fun unisci(vararg parti: String): String = parti.filter { it.isNotEmpty() }.joinToString("\\")

    private fun configurazione(): SmbConfig = SmbConfig.builder()
        .withTimeout(20, TimeUnit.SECONDS)
        .withSoTimeout(40, TimeUnit.SECONDS)
        // BouncyCastle "leggero": su Android il provider di cifratura di sistema non ha MD4 (serve all'accesso NTLM).
        .withSecurityProvider(BCSecurityProvider())
        .build()

    private fun <T> conCondivisione(accesso: Accesso, blocco: (DiskShare) -> T): T {
        val p = accesso.percorso
        try {
            SMBClient(configurazione()).use { client ->
                client.connect(p.host, p.porta).use { connessione ->
                    val sessione = connessione.authenticate(
                        AuthenticationContext(accesso.utente, accesso.password.toCharArray(), accesso.dominio)
                    )
                    try {
                        val share = sessione.connectShare(p.condivisione) as? DiskShare
                            ?: throw ErroreNas("\"${p.condivisione}\" sul NAS non è una cartella condivisa di file")
                        return share.use { blocco(it) }
                    } finally {
                        try { sessione.close() } catch (_: Exception) { }
                    }
                }
            }
        } catch (e: ErroreNas) {
            throw e
        } catch (e: Exception) {
            throw traduci(e, p)
        }
    }

    /** Messaggi d'errore comprensibili (in italiano) per i casi tipici. */
    internal fun traduci(e: Throwable, p: Percorso): ErroreNas {
        var causa: Throwable? = e
        var guardia = 0
        while (causa != null && guardia++ < 10) {
            when (causa) {
                is SMBApiException -> return when (causa.status) {
                    NtStatus.STATUS_LOGON_FAILURE ->
                        ErroreNas("Utente o password del NAS non corretti", causa = e)
                    NtStatus.STATUS_ACCOUNT_DISABLED, NtStatus.STATUS_PASSWORD_EXPIRED, NtStatus.STATUS_LOGON_TYPE_NOT_GRANTED ->
                        ErroreNas("L'utente del NAS è disattivato, bloccato o ha la password scaduta", causa = e)
                    NtStatus.STATUS_BAD_NETWORK_NAME ->
                        ErroreNas("Sul NAS non esiste la cartella condivisa \"${p.condivisione}\"", causa = e)
                    NtStatus.STATUS_OBJECT_NAME_NOT_FOUND, NtStatus.STATUS_OBJECT_PATH_NOT_FOUND ->
                        ErroreNas("Cartella o file non trovati sul NAS (rinominati o spostati?)", cartellaNonTrovata = true, causa = e)
                    NtStatus.STATUS_ACCESS_DENIED ->
                        ErroreNas("L'utente del NAS non ha il permesso di leggere questa cartella", causa = e)
                    else -> ErroreNas("Il NAS ha risposto con un errore (${causa.status})", causa = e)
                }
                is UnknownHostException -> return ErroreNas(
                    "Non trovo il NAS \"${p.host}\": scrivi il suo indirizzo IP (es. 192.168.1.20), che trovi nel pannello del NAS", causa = e
                )
                is ConnectException, is NoRouteToHostException, is SocketTimeoutException -> return ErroreNas(
                    "NAS non raggiungibile (${p.host}). Sei sul Wi-Fi dello studio, oppure con la VPN accesa? L'indirizzo è giusto?", causa = e
                )
            }
            causa = causa.cause
        }
        return ErroreNas("Collegamento al NAS non riuscito: ${e.message ?: e.javaClass.simpleName}", causa = e)
    }
}
