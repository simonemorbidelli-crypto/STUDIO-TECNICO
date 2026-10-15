package it.geometra.schedacliente

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.print.PrintAttributes
import android.print.PrintManager
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Mappa con i servizi WMS/WFS della Regione Toscana (Geoscopio). La pagina è in assets/mappa;
 * le richieste verso la Regione passano tutte da qui (solo https verso www502.regione.toscana.it).
 */
class MappaActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private var inStampa = false
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Mappa e WMS Regione Toscana"
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            webViewClient = WebViewClient()
            webChromeClient = object : WebChromeClient() {
                override fun onGeolocationPermissionsShowPrompt(
                    origin: String?, callback: GeolocationPermissions.Callback?
                ) {
                    callback?.invoke(origin, true, false)
                }
            }
            addJavascriptInterface(Ponte(), "Android")
        }
        setContentView(web)
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 7)
        }
        val giaUsata = getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(MAPPA_USATA, false)
        if (savedInstanceState != null || !giaUsata) {
            caricaMappa(false)
        } else {
            val dialog = android.app.AlertDialog.Builder(this, R.style.Theme_SchedaCliente_Dialog)
                .setTitle("Mappa")
                .setMessage("Vuoi ripartire dall'ultima visualizzazione (posizione, sfondo e strati accesi) oppure aprire una mappa nuova da zero?")
                .setPositiveButton("Ultima visualizzazione") { _, _ -> caricaMappa(false) }
                .setNegativeButton("Mappa nuova") { _, _ -> caricaMappa(true) }
                .setCancelable(false)
                .show()
            dialog.rendiPulsantiLeggibili(this)
        }
    }

    /** Con [daZero] la pagina dimentica posizione, sfondo e strati dell'ultima volta. */
    private fun caricaMappa(daZero: Boolean) {
        web.loadUrl("file:///android_asset/mappa/index.html" + if (daZero) "?nuova=1" else "")
    }

    override fun onPause() {
        super.onPause()
        getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(MAPPA_USATA, true).apply()
    }

    override fun onResume() {
        super.onResume()
        // Tornando dalla schermata di stampa di Android la pagina ripristina i comandi.
        if (inStampa) {
            inStampa = false
            js("window.fineStampa&&window.fineStampa()")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else NavigazioneIndietro.chiediDaSezione(this)
    }

    private fun js(codice: String) = runOnUiThread { web.evaluateJavascript(codice, null) }

    inner class Ponte {
        @JavascriptInterface
        fun chiaveGoogle(): String =
            getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(CHIAVE, "") ?: ""

        @JavascriptInterface
        fun svuotaCache() {
            runOnUiThread { web.clearCache(true) }
        }

        @JavascriptInterface
        fun richiedi(id: String, metodo: String, url: String, corpo: String) {
            thread {
                try {
                    val testo = scarica(client, metodo, url, corpo)
                    js("window.rispostaRete(${JSONObject.quote(id)},${JSONObject.quote(testo)})")
                } catch (e: Exception) {
                    js("window.erroreRete(${JSONObject.quote(id)},${JSONObject.quote(e.message ?: "Errore di rete")})")
                }
            }
        }

        /** Apre con il browser un file da scaricare (solo https verso i siti della Regione Toscana). */
        @JavascriptInterface
        fun apri(url: String) {
            if (!urlApribile(url)) return
            runOnUiThread {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (e: Exception) {
                    js("msg(${JSONObject.quote("Nessuna app può aprire il collegamento")})")
                }
            }
        }

        /** Stampa la mappa (o la salva in PDF) con il servizio di stampa di Android. */
        @JavascriptInterface
        fun stampa(titolo: String) {
            runOnUiThread {
                val nome = "Mappa " + titolo.take(40)
                val pm = getSystemService(Context.PRINT_SERVICE) as PrintManager
                var formato = PrintAttributes.MediaSize.ISO_A4
                if (web.width > web.height) formato = formato.asLandscape()
                inStampa = true
                pm.print(
                    nome,
                    web.createPrintDocumentAdapter(nome),
                    PrintAttributes.Builder().setMediaSize(formato).build()
                )
            }
        }

        @JavascriptInterface
        fun chiediChiaveGoogle() {
            runOnUiThread {
                val campo = EditText(this@MappaActivity).apply {
                    inputType = InputType.TYPE_CLASS_TEXT
                    hint = "Chiave Map Tiles API (vuoto = rimuovi)"
                    setText(chiaveGoogle())
                }
                AlertDialog.Builder(this@MappaActivity)
                    .setTitle("Chiave Google")
                    .setView(campo)
                    .setPositiveButton("Salva") { _, _ ->
                        getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                            .putString(CHIAVE, campo.text.toString().trim()).apply()
                        web.reload()
                    }
                    .setNegativeButton("Annulla", null)
                    .show()
            }
        }
    }

    companion object {
        private const val PREF = "mappa"
        private const val CHIAVE = "chiave_google"
        private const val MAPPA_USATA = "mappa_usata"
        private const val HOST = "www502.regione.toscana.it"
        private const val HOST_AUTORITA = "geodataserver.appenninosettentrionale.it"
        private const val LIMITE = 6 * 1024 * 1024

        /** Solo https verso il Geoscopio della Regione Toscana. */
        fun urlAmmesso(url: String): Boolean {
            val u = try { java.net.URI(url) } catch (e: Exception) { return false }
            if (u.scheme != "https" || u.userInfo != null) return false
            if (u.host == HOST) return true
            /* Autorità di bacino distrettuale dell'Appennino Settentrionale: solo il servizio ArcGIS pubblico (PAI/PGRA) */
            return u.host == HOST_AUTORITA && (u.rawPath ?: "").startsWith("/arcgis/rest/services/")
        }

        /** Collegamenti che la pagina può far aprire al browser (download dei dati, schede). */
        fun urlApribile(url: String): Boolean {
            val u = try { java.net.URI(url) } catch (e: Exception) { return false }
            val host = (u.host ?: return false).lowercase()
            return u.scheme == "https" && u.userInfo == null &&
                (host == HOST || host == "regione.toscana.it" || host.endsWith(".regione.toscana.it") ||
                    host in COLLEGAMENTI_AMMESSI)
        }

        /** Altri siti a cui rimandano le schede del Geoscopio (leggi, normativa, licenze, ARPAT): solo https. */
        private val COLLEGAMENTI_AMMESSI = setOf(
            "www.rete.toscana.it", "sira.arpat.toscana.it", "it.wikipedia.org", "www.gazzettaufficiale.it",
            "eur-lex.europa.eu", "creativecommons.org", "trasparenza.cultura.gov.it", "www.normattiva.it"
        )


        fun scarica(client: OkHttpClient, metodo: String, url: String, corpo: String): String {
            require(urlAmmesso(url)) { "Indirizzo non ammesso" }
            val rb = when (metodo.uppercase()) {
                "GET" -> Request.Builder().url(url).get()
                "POST" -> Request.Builder().url(url)
                    .post(corpo.toRequestBody("text/xml; charset=utf-8".toMediaType()))
                else -> throw IllegalArgumentException("Metodo non ammesso")
            }
            client.newCall(rb.build()).execute().use { r ->
                if (!r.isSuccessful) throw java.io.IOException("Errore HTTP ${r.code}")
                val src = r.body!!.byteStream()
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16384)
                while (true) {
                    val n = src.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > LIMITE) throw java.io.IOException("Risposta troppo grande")
                }
                return out.toString("UTF-8")
            }
        }
    }
}
