package it.geometra.schedacliente

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import android.util.Base64

/**
 * Individua automaticamente, su una planimetria catastale, le posizioni pixel di DUE segni
 * del reticolo di riferimento (il bordo con piccole crocette/tacche che circonda il disegno),
 * scelti il più lontano possibile tra loro lungo UN SOLO verso (orizzontale o verticale, quello
 * più leggibile), insieme al numero di intervalli (segmenti da 10 m ciascuno) che li separano.
 * Usare due segni lontani, anziché due semplicemente consecutivi, allarga la base della misura:
 * lo stesso piccolo errore di stima "a occhio" sulla posizione di un segno incide molto meno,
 * in proporzione, sulla distanza totale (e quindi sulla scala calcolata), rispetto a quando la
 * base è di un solo intervallo. Dalla distanza in pixel tra i due segni e dal numero di
 * intervalli (x10 m) l'app calcola la scala (metri reali / pixel) senza bisogno di leggere un
 * testo "Scala 1:N".
 *
 * Qui, a differenza della lettura delle quote, è ammessa una stima visiva sui pixel: è l'unico
 * modo per individuare automaticamente il reticolo, e il valore risultante resta comunque
 * verificabile e ricalibrabile a mano dall'utente in qualsiasi momento con "Imposta scala".
 */
object InterpretaScalaParametri {

    private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
    private const val MODELLO = "claude-haiku-5-5"
    private const val LATO_MASSIMO_PX = 1568

    interface Callback {
        fun onSuccesso(trovato: Boolean, x1: Double?, y1: Double?, x2: Double?, y2: Double?, numeroIntervalli: Int, nota: String?)
        fun onErrore(messaggio: String)
    }

    fun analizza(chiaveApi: String, bitmap: Bitmap, callback: Callback) {
        val handler = Handler(Looper.getMainLooper())
        Thread {
            try {
                val base64Immagine = codificaBitmap(bitmap)
                val risultato = eseguiChiamata(chiaveApi, base64Immagine)
                val trovato = risultato.optBoolean("trovato", false)
                val numeroIntervalli = risultato.optInt("numero_intervalli", 1).coerceAtLeast(1)
                handler.post {
                    callback.onSuccesso(
                        trovato,
                        if (risultato.isNull("x1")) null else risultato.optDouble("x1"),
                        if (risultato.isNull("y1")) null else risultato.optDouble("y1"),
                        if (risultato.isNull("x2")) null else risultato.optDouble("x2"),
                        if (risultato.isNull("y2")) null else risultato.optDouble("y2"),
                        numeroIntervalli,
                        risultato.optString("nota")
                    )
                }
            } catch (e: Exception) {
                handler.post { callback.onErrore(e.message ?: "Errore sconosciuto") }
            }
        }.start()
    }

    private fun eseguiChiamata(chiaveApi: String, base64Immagine: String): JSONObject {
        val promptSistema = """
            Sei un assistente tecnico che analizza planimetrie catastali italiane, sia
            scansionate/esportate in PDF sia fotografate o salvate come immagine (foto
            scattata con il telefono, screenshot, JPG/PNG scaricato dal sito dell'Agenzia
            delle Entrate, ecc.). La qualità dell'immagine può quindi essere più bassa di
            un PDF nativo: leggera inclinazione/prospettiva, sfocatura, ombre, compressione
            JPEG, bordi tagliati. Guarda con attenzione comunque: i segni che cerchi sono
            piccoli, quindi ingrandisci mentalmente i bordi del disegno prima di escludere
            che siano presenti.

            Sul bordo del disegno (cornice esterna della planimetria) è spesso presente un
            reticolo di riferimento, chiamato tecnicamente "parametri": una serie di piccoli
            segni disposti a intervalli regolari lungo uno o più lati della cornice. Questi
            segni possono comparire in forme diverse secondo il disegno:
            - crocette "+" o piccole croci;
            - semplici trattini/tacche perpendicolari al bordo;
            - coppie di trattini (doppia tacca) a ogni intervallo;
            - numeri progressivi multipli di 10 (es. "10", "20", "30"...) accanto alle tacche.
            In tutti questi casi, ogni intervallo tra due segni CONSECUTIVI (adiacenti, senza
            altri segni tra loro) rappresenta ESATTAMENTE 10 metri nella realtà.

            Il tuo compito, in due fasi:
            1. Scegli UN SOLO verso del reticolo, orizzontale oppure verticale (quello in cui i
               segni sono più numerosi, nitidi e allineati con sicurezza lungo lo stesso lato o
               la stessa riga/colonna del disegno; non mescolare mai segni di lati diversi).
            2. Su quel verso, individua i DUE segni PIÙ LONTANI possibile tra loro: il primo
               e l'ULTIMO segno della fila, cioè quelli agli estremi opposti della linea retta
               più lunga della cornice. Non fermarti ai primi segni che incontri: percorri tutta
               la linea retta fino in fondo, a entrambe le estremità, e controlla che oltre i
               due segni scelti non ce ne siano altri allineati. Se il disegno ha più lati con
               segni, scegli il lato con la linea più lunga (il maggior numero di intervalli),
               purché i segni siano leggibili con sicurezza. Una base di misura più lunga rende
               il calcolo della scala molto più preciso. Conta con attenzione, uno per uno, ESATTAMENTE quanti
               intervalli (spazi tra un segno e il successivo) separano questi due segni: se ad
               esempio sono il 1° e il 5° segno della fila, gli intervalli sono 4, non 5.

            Restituisci le coordinate pixel di entrambi i segni scelti come frazione (da 0 a 1)
            rispettivamente della larghezza e dell'altezza DELL'INTERA IMMAGINE, e il numero di
            intervalli contati tra loro.

            Qui, a differenza della lettura di quote o aree, puoi e devi dare una stima
            visiva sui pixel: è l'unico scopo di questa analisi.

            Se non riesci a individuare con sufficiente sicurezza un reticolo di questo tipo
            (nessuna cornice con segni a intervalli regolari, immagine troppo tagliata o
            poco chiara anche dopo un'attenta ricerca lungo tutti i lati), restituisci
            trovato=false e spiega brevemente il motivo in "nota" (es. "cornice non visibile
            nell'immagine", "bordo tagliato", "segni presenti ma troppo sfocati per essere
            letti con sicurezza"). Se riesci a leggere solo due segni consecutivi (un solo
            intervallo) e nessun altro lungo lo stesso verso, va bene comunque: restituisci
            quelli con numero_intervalli=1.

            Restituisci SOLO un oggetto JSON valido, senza markdown, senza
            testo prima o dopo, con questa struttura esatta:
            {
              "trovato": true oppure false,
              "x1": numero (0-1) oppure null,
              "y1": numero (0-1) oppure null,
              "x2": numero (0-1) oppure null,
              "y2": numero (0-1) oppure null,
              "numero_intervalli": numero intero (almeno 1) oppure null,
              "nota": "breve spiegazione, stringa vuota se tutto chiaro"
            }
        """.trimIndent()

        val contenutoUtente = org.json.JSONArray()
        contenutoUtente.put(
            JSONObject().apply {
                put("type", "image")
                put("source", JSONObject().apply {
                    put("type", "base64")
                    put("media_type", "image/jpeg")
                    put("data", base64Immagine)
                })
            }
        )
        contenutoUtente.put(
            JSONObject().apply {
                put("type", "text")
                put("text", "Esamina con attenzione tutti i lati della cornice di questa planimetria catastale e individua, lungo un solo verso (orizzontale o verticale), i due segni del reticolo dei parametri più lontani tra loro e il numero di intervalli che li separano, seguendo esattamente le regole date.")
            }
        )

        val corpo = JSONObject().apply {
            put("model", MODELLO)
            put("max_tokens", 1024)
            put("system", promptSistema)
            put("messages", org.json.JSONArray().put(
                JSONObject().apply {
                    put("role", "user")
                    put("content", contenutoUtente)
                }
            ))
        }

        val (codice, testoRisposta) = AiDeterministica.invia(ENDPOINT, chiaveApi, corpo)

        if (codice !in 200..299) {
            val messaggioErrore = try {
                JSONObject(testoRisposta).optJSONObject("error")?.optString("message")
            } catch (e: Exception) {
                null
            } ?: "Il servizio AI ha risposto con un errore (codice $codice)"
            throw IllegalStateException(messaggioErrore)
        }

        val rispostaJson = JSONObject(testoRisposta)
        val contenuto = rispostaJson.optJSONArray("content")
            ?: throw IllegalStateException("Risposta AI senza contenuto")
        val testoModello = StringBuilder()
        for (i in 0 until contenuto.length()) {
            val blocco = contenuto.getJSONObject(i)
            if (blocco.optString("type") == "text") {
                testoModello.append(blocco.optString("text"))
            }
        }

        return JSONObject(estraiJson(testoModello.toString()))
    }

    private fun codificaBitmap(originale: Bitmap): String {
        val latoMaggiore = maxOf(originale.width, originale.height)
        val bitmapRidimensionato = if (latoMaggiore > LATO_MASSIMO_PX) {
            val scala = LATO_MASSIMO_PX.toFloat() / latoMaggiore
            Bitmap.createScaledBitmap(
                originale,
                (originale.width * scala).toInt(),
                (originale.height * scala).toInt(),
                true
            )
        } else {
            originale
        }
        val output = ByteArrayOutputStream()
        bitmapRidimensionato.compress(Bitmap.CompressFormat.JPEG, 85, output)
        return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
    }

    private fun estraiJson(testo: String): String {
        val inizio = testo.indexOf('{')
        val fine = testo.lastIndexOf('}')
        if (inizio == -1 || fine == -1 || fine < inizio) {
            throw IllegalStateException("Non sono riuscito a interpretare la risposta dell'AI")
        }
        return testo.substring(inizio, fine + 1)
    }
}
