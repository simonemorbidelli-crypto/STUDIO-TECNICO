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
    /** Elementi massimi mostrati in una singola cartella (protezione da cartelle enormi). */
    const val ELEMENTI_MAX = 20000
    /** Quanti livelli sotto la cartella delle pratiche si guardano per trovare la cartella del cliente. */
    const val PROFONDITA_RICERCA = 3
    private const val LETTURE_RICERCA_MAX = 400

    /** Dove sono le pratiche: \\host\condivisione\cartella (cartella vuota = radice della condivisione). */
    data class Percorso(
        val host: String, val porta: Int, val condivisione: String, val cartella: String,
        /** true = WebDAV (https://host:5003/webdav/Condivisione/cartella) invece di SMB (\\\\host\\Condivisione). */
        val webdav: Boolean = false,
        /** Solo WebDAV: true = HTTPS (porta 5003 sul NAS Zyxel), false = HTTP (5002, non cifrato). */
        val sicuro: Boolean = true
    ) {
        override fun toString(): String = if (webdav)
            (if (sicuro) "https://" else "http://") + host + ":" + porta + "/webdav/" + condivisione + (if (cartella.isEmpty()) "" else "/" + cartella.replace('\\', '/'))
        else "\\\\" + host + (if (porta != 445) ":$porta" else "") + "\\" + condivisione + (if (cartella.isEmpty()) "" else "\\$cartella")
    }

    /** [impronta]: solo WebDAV/HTTPS, impronta SHA-256 del certificato del NAS accettato dall'utente (vuota = accetta solo certificati riconosciuti). */
    data class Accesso(val percorso: Percorso, val utente: String, val password: String, val dominio: String = "", val impronta: String = "")

    class ErroreNas(messaggio: String, val cartellaNonTrovata: Boolean = false, causa: Throwable? = null, val improntaDaConfermare: String? = null) : IOException(messaggio, causa)

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
        if (t.lowercase().startsWith("http://") || t.lowercase().startsWith("https://")) return analizzaWebDav(t)
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

    /** https://host:5003/webdav/Condivisione/cartella (http = porta 5002). Serve almeno host e nome della condivisione. */
    private fun analizzaWebDav(testo: String): Percorso? {
        val sicuro = testo.lowercase().startsWith("https://")
        val resto = testo.substring(if (sicuro) 8 else 7).trim().trimEnd('/')
        val parti = resto.split('/').map { it.trim() }.filter { it.isNotEmpty() }
        if (parti.size < 3 || !parti[1].equals("webdav", ignoreCase = true)) return null
        val host = parti[0].substringBefore(':')
        val porta = parti[0].substringAfter(':', if (sicuro) "5003" else "5002").toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        if (host.isBlank() || host.any { it.isWhitespace() }) return null
        return Percorso(host, porta, parti[2], parti.drop(3).joinToString("\\"), webdav = true, sicuro = sicuro)
    }

    // ---------------------------------------------------------------------
    // Operazioni sul NAS
    // ---------------------------------------------------------------------

    /** Prova il collegamento: restituisce quante cartelle (pratiche) ci sono nella cartella indicata. */
    fun prova(accesso: Accesso): Int = elencaCartelle(accesso).size

    /** Cartelle (una per cliente) nella cartella delle pratiche, in ordine alfabetico, senza quelle di sistema/nascoste. */
    fun elencaCartelle(accesso: Accesso): List<String> = conCondivisione(accesso) { share ->
        val base = accesso.percorso.cartella
        if (base.isNotEmpty() && !share.cartellaEsiste(base)) {
            throw ErroreNas("Sul NAS non trovo la cartella \"$base\" nella condivisione \"${accesso.percorso.condivisione}\"", cartellaNonTrovata = true)
        }
        share.elenca(base)
            .filter { it.cartella && visibile(it) }
            .map { it.nome }
            .sortedBy { it.lowercase() }
    }

    /** Cartelle e file di UNA cartella (un solo livello), con gli handle già pronti per la schermata. */
    class Livello(val cartelle: List<MegaCartellaPubblica.CartellaMega>, val file: List<MegaCartellaPubblica.FileMega>)

    /**
     * Legge la cartella di un cliente: restituisce la radice e il suo primo livello. Le sottocartelle
     * si leggono una alla volta quando l'utente le apre ([leggiLivello]): così anche una cartella
     * con migliaia di file in totale (es. "LAVORI E DISEGNI IN CORSO") si apre subito.
     */
    fun leggiContenuto(accesso: Accesso, cartellaCliente: String): MegaCartellaPubblica.Contenuto {
        val radice = MegaCartellaPubblica.CartellaMega(RADICE, cartellaCliente.substringAfterLast('\\'), "", "")
        val livello = leggiLivello(accesso, cartellaCliente, "")
        return MegaCartellaPubblica.Contenuto(radice, livello.cartelle, livello.file)
    }

    /** Legge una sola sottocartella ([relativo], es. "Catasto/Visure"; vuoto = la cartella del cliente). */
    fun leggiLivello(accesso: Accesso, cartellaCliente: String, relativo: String): Livello = conCondivisione(accesso) { share ->
        val radiceSmb = unisci(accesso.percorso.cartella, cartellaCliente)
        val daLeggere = unisci(radiceSmb, relativo.replace('/', '\\'))
        if (!share.cartellaEsiste(daLeggere)) {
            throw ErroreNas(
                if (relativo.isEmpty()) "Sul NAS non trovo più la cartella \"$cartellaCliente\" (rinominata o spostata?)"
                else "Sul NAS non trovo più la cartella \"$relativo\" (rinominata o spostata?)",
                cartellaNonTrovata = relativo.isEmpty()
            )
        }
        val handleCartella = if (relativo.isEmpty()) RADICE else "/$relativo"
        val cartelle = ArrayList<MegaCartellaPubblica.CartellaMega>()
        val file = ArrayList<MegaCartellaPubblica.FileMega>()
        for (voce in share.elenca(daLeggere)) {
            val nome = voce.nome
            if (!visibile(voce)) continue
            if (cartelle.size + file.size >= ELEMENTI_MAX) {
                throw ErroreNas("Questa cartella contiene troppi elementi (oltre $ELEMENTI_MAX) per essere mostrata")
            }
            val percorso = if (relativo.isEmpty()) nome else "$relativo/$nome"
            if (voce.cartella) {
                cartelle += MegaCartellaPubblica.CartellaMega("/$percorso", nome, handleCartella, percorso)
            } else {
                file += MegaCartellaPubblica.FileMega(
                    handle = "/$percorso", nome = nome, percorso = percorso, dimensione = voce.dimensione,
                    chiaveAes = ByteArray(0), iv = ByteArray(0), genitore = handleCartella
                )
            }
        }
        Livello(cartelle, file)
    }

    /**
     * Cerca la cartella del cliente anche dentro le cartelle contenitore (es. "LAVORI E DISEGNI IN
     * CORSO\\Morbidelli Simone"), fino a [PROFONDITA_RICERCA] livelli sotto la cartella delle pratiche.
     * Non entra nelle cartelle già riconosciute come del cliente. Restituisce i percorsi relativi.
     */
    fun cercaInProfondita(accesso: Accesso, primoLivello: List<String>, cognome: String, nome: String): List<String> =
        conCondivisione(accesso) { share ->
            val trovati = ArrayList<String>()   // percorsi relativi alla cartella delle pratiche
            var corrente = primoLivello
            var letture = 0
            for (livello in 2..PROFONDITA_RICERCA) {
                val prossimo = ArrayList<String>()
                for (padre in corrente) {
                    if (letture++ >= LETTURE_RICERCA_MAX) break
                    val figli = try {
                        share.elenca(unisci(accesso.percorso.cartella, padre))
                            .filter { it.cartella && visibile(it) }
                            .map { it.nome }
                    } catch (e: Exception) { emptyList() }
                    val buoni = cartellePerCliente(figli, cognome, nome)
                    buoni.forEach { trovati += "$padre\\$it" }
                    figli.filter { it !in buoni }.forEach { prossimo += "$padre\\$it" }
                }
                if (trovati.isNotEmpty()) break
                corrente = prossimo
            }
            trovati
        }

    /** Scarica (in memoria) un file della cartella del cliente. */
    fun scarica(accesso: Accesso, cartellaCliente: String, file: MegaCartellaPubblica.FileMega): ByteArray {
        if (file.dimensione > DIMENSIONE_MAX_FILE) {
            throw ErroreNas("\"${file.nome}\" è troppo grande (oltre ${DIMENSIONE_MAX_FILE / 1024 / 1024} MB)")
        }
        return conCondivisione(accesso) { share ->
            val percorso = unisci(accesso.percorso.cartella, cartellaCliente, file.percorso.replace('/', '\\'))
share.leggi(percorso)
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

    private fun visibile(v: VoceArchivio): Boolean {
        val nome = v.nome
        if (nome == "." || nome == "..") return false
        val n = nome.lowercase()
        if (n in NOMI_NASCOSTI || n.startsWith(".") || n.startsWith("@") || n.startsWith("~$")) return false
        return !v.nascosto
    }

    /** Archivio SMB: elenca/legge dentro una condivisione Windows. */
    private class ArchivioSmb(private val share: DiskShare) : ArchivioRete {
        override fun elenca(percorso: String) = share.list(percorso).map {
            VoceArchivio(
                it.fileName, EnumWithValue.EnumUtils.isSet(it.fileAttributes, FileAttributes.FILE_ATTRIBUTE_DIRECTORY), it.endOfFile,
                EnumWithValue.EnumUtils.isSet(it.fileAttributes, FileAttributes.FILE_ATTRIBUTE_HIDDEN) ||
                    EnumWithValue.EnumUtils.isSet(it.fileAttributes, FileAttributes.FILE_ATTRIBUTE_SYSTEM)
            )
        }
        override fun cartellaEsiste(percorso: String) = share.folderExists(percorso)
        override fun leggi(percorso: String): ByteArray = share.openFile(
            percorso, EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null
        ).use { f -> f.inputStream.use { it.readBytes() } }
    }

    private fun unisci(vararg parti: String): String = parti.filter { it.isNotEmpty() }.joinToString("\\")

    private fun configurazione(): SmbConfig = SmbConfig.builder()
        .withTimeout(20, TimeUnit.SECONDS)
        .withSoTimeout(40, TimeUnit.SECONDS)
        // BouncyCastle "leggero": su Android il provider di cifratura di sistema non ha MD4 (serve all'accesso NTLM).
        .withSecurityProvider(BCSecurityProvider())
        .build()

    private fun <T> conCondivisione(accesso: Accesso, blocco: (ArchivioRete) -> T): T {
        val p = accesso.percorso
        try {
            if (p.webdav) return blocco(ArchivioWebDav(accesso))
            SMBClient(configurazione()).use { client ->
                client.connect(p.host, p.porta).use { connessione ->
                    val sessione = connessione.authenticate(
                        AuthenticationContext(accesso.utente, accesso.password.toCharArray(), accesso.dominio)
                    )
                    try {
                        val share = sessione.connectShare(p.condivisione) as? DiskShare
                            ?: throw ErroreNas("\"${p.condivisione}\" sul NAS non è una cartella condivisa di file")
                        return share.use { blocco(ArchivioSmb(it)) }
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
                is CertificatoNonFidato -> return ErroreNas(
                    "Il NAS usa un certificato proprio, non riconosciuto da Android. Impronta del certificato: ${causa.impronta}. " +
                        "Se è quella del tuo NAS puoi fidarti: confermala.", causa = e, improntaDaConfermare = causa.impronta
                )
                is java.security.cert.CertificateException -> return ErroreNas(causa.message ?: "Certificato del NAS non valido", causa = e)
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
                    if (p.webdav) "NAS non raggiungibile (${p.host}:${p.porta}). Controlla l'indirizzo, la porta (HTTPS 5003 / HTTP 5002) e, fuori studio, che la porta sia aperta nel router verso il NAS"
                    else "NAS non raggiungibile (${p.host}). Sei sul Wi-Fi dello studio, oppure con la VPN accesa? L'indirizzo è giusto?", causa = e
                )
            }
            causa = causa.cause
        }
        return ErroreNas("Collegamento al NAS non riuscito: ${e.message ?: e.javaClass.simpleName}", causa = e)
    }
}
