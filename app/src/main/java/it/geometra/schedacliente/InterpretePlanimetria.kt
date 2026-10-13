package it.geometra.schedacliente

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import android.content.Context
import android.util.Base64

/** Rettangolo (frazioni 0-1 dell'immagine intera) che racchiude una singola pianta/piano, quando l'immagine ne contiene più di una. */
data class RettangoloPianta(val nome: String, val x0: Float, val y0: Float, val x1: Float, val y1: Float)

/**
 * Invia la foto/scansione di una planimetria al servizio AI (Anthropic/Claude,
 * con la stessa chiave API personale usata per la dettatura) e gli chiede di
 * leggere le quote scritte su ogni locale per calcolarne l'area.
 *
 * Non viene fatta nessuna misurazione "a occhio" sui pixel dell'immagine:
 * senza una scala dichiarata sarebbe un numero inventato. Il calcolo si basa
 * solo sulle quote (in metri) effettivamente scritte e leggibili sul disegno,
 * come già avviene normalmente sulle planimetrie catastali/di progetto.
 */
object InterpretePlanimetria {

    private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
    private const val MODELLO = "claude-haiku-5-5"
    private const val LATO_MASSIMO_PX = 1568
    /** Lato minimo (px) a cui viene ingrandito un ritaglio piccolo prima di inviarlo all'AI: un ritaglio
     *  di un solo locale è spesso molto più piccolo di [LATO_MASSIMO_PX]; inviarlo ingrandito (anche se
     *  non aggiunge dettaglio reale) fa sì che le linee dei muri occupino molti più pixel/porzioni
     *  dell'immagine analizzata, migliorando la precisione con cui il modello individua l'esatto
     *  punto di incontro delle pareti invece di una stima più grossolana su un'immagine minuscola. */
    private const val LATO_MINIMO_INGRANDIMENTO_PX = 1300

    interface Callback {
        fun onSuccesso(risultato: JSONObject)
        fun onErrore(messaggio: String)
    }

    interface CallbackVertici {
        fun onSuccesso(vertici: List<PointF>)
        fun onErrore(messaggio: String)
    }

    interface CallbackVerticeSingolo {
        fun onSuccesso(trovato: Boolean, x: Float, y: Float)
        fun onErrore(messaggio: String)
    }

    interface CallbackPiante {
        fun onSuccesso(piante: List<RettangoloPianta>)
        fun onErrore(messaggio: String)
    }

    /**
     * Individua quante piante/piani distinti sono disegnati nell'immagine (es. "PIANTA PIANO
     * SEMINTERRATO" + "PIANTA PIANO TERRA" nella stessa pagina catastale) e il rettangolo che
     * racchiude ciascuna. Va chiamato PRIMA del rilevamento dei locali: analizzare i locali di
     * più piante assieme in una sola immagine porta l'AI a confondere vertici/locali di piante
     * diverse (il principale motivo di contorni palesemente sbagliati, che si estendono da una
     * pianta all'altra). Elaborando una pianta alla volta, ritagliata separatamente, si evita
     * del tutto l'ambiguità.
     */
    fun rilevaPiante(chiaveApi: String, bitmap: Bitmap, callback: CallbackPiante) {
        val handler = Handler(Looper.getMainLooper())
        Thread {
            try {
                val base64Immagine = codificaBitmap(bitmap)
                val risultato = eseguiChiamataPiante(chiaveApi, base64Immagine)
                val pianteJson = risultato.optJSONArray("piante")
                val piante = mutableListOf<RettangoloPianta>()
                if (pianteJson != null) {
                    for (i in 0 until pianteJson.length()) {
                        val p = pianteJson.optJSONObject(i) ?: continue
                        val nome = p.optString("nome").ifBlank { "Pianta ${i + 1}" }
                        val x0 = p.optDouble("x0", Double.NaN)
                        val y0 = p.optDouble("y0", Double.NaN)
                        val x1 = p.optDouble("x1", Double.NaN)
                        val y1 = p.optDouble("y1", Double.NaN)
                        if (x0.isNaN() || y0.isNaN() || x1.isNaN() || y1.isNaN()) continue
                        if (x1 <= x0 || y1 <= y0) continue
                        piante.add(RettangoloPianta(nome, x0.toFloat(), y0.toFloat(), x1.toFloat(), y1.toFloat()))
                    }
                }
                handler.post { callback.onSuccesso(piante) }
            } catch (e: Exception) {
                handler.post { callback.onErrore(e.message ?: "Errore sconosciuto") }
            }
        }.start()
    }

    private fun eseguiChiamataPiante(chiaveApi: String, base64Immagine: String): JSONObject {
        val promptSistema = """
            Questa immagine è una planimetria catastale o di progetto che può contenere UNA o
            PIÙ piante (piani) di uno stesso edificio, disposte una sopra l'altra o una accanto
            all'altra, ciascuna di solito con una propria etichetta (es. "PIANTA PIANO TERRA",
            "PIANTA PIANO SEMINTERRATO", "PIANTA PIANO PRIMO"...) e un proprio disegno completo
            e autonomo di muri e locali, separato dalle altre piante.

            Individua quante piante distinte sono presenti nell'immagine e, per ciascuna, un
            rettangolo che la racchiude per intero (tutti i suoi locali, muri ed eventuali quote
            o etichette), SENZA includere parti del disegno di un'altra pianta. Se è presente
            una sola pianta, restituisci comunque un solo elemento il cui rettangolo copre
            l'area del disegno.

            Esprimi ogni rettangolo con le coordinate del vertice in alto a sinistra (x0,y0) e
            del vertice in basso a destra (x1,y1), ciascuna come frazione (da 0 a 1)
            rispettivamente della larghezza e dell'altezza DELL'INTERA IMMAGINE.

            Restituisci SOLO un oggetto JSON valido, senza markdown, senza testo prima o dopo,
            con questa struttura esatta:
            {
              "piante": [
                {"nome": "Piano terra", "x0": 0.0, "y0": 0.42, "x1": 1.0, "y1": 0.95}
              ]
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
                put("text", "Individua le piante presenti in questa immagine e i rispettivi rettangoli, seguendo esattamente le regole date.")
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

        return eseguiRichiestaHttp(chiaveApi, corpo)
    }

    fun analizza(context: Context, chiaveApi: String, immagineUri: Uri, callback: Callback) {
        val handler = Handler(Looper.getMainLooper())

        Thread {
            try {
                val base64Immagine = preparaImmagineBase64(context, immagineUri)
                val risultato = eseguiChiamata(chiaveApi, base64Immagine)
                handler.post { callback.onSuccesso(risultato) }
            } catch (e: Exception) {
                handler.post { callback.onErrore(e.message ?: "Errore sconosciuto") }
            }
        }.start()
    }

    /** Come [analizza], ma a partire da un bitmap già decodificato (es. una pagina di PDF già renderizzata). */
    fun analizzaBitmap(chiaveApi: String, bitmap: Bitmap, callback: Callback) {
        val handler = Handler(Looper.getMainLooper())

        Thread {
            try {
                val base64Immagine = codificaBitmap(bitmap)
                val risultato = eseguiChiamata(chiaveApi, base64Immagine)
                handler.post { callback.onSuccesso(risultato) }
            } catch (e: Exception) {
                handler.post { callback.onErrore(e.message ?: "Errore sconosciuto") }
            }
        }.start()
    }

    /**
     * Versione "leggera" usata dal rilevamento automatico (scala + vertici): a differenza di
     * [analizza]/[analizzaBitmap] non chiede quote, aree o note per ciascun locale (dati inutili
     * in quel flusso, dove l'area si calcola sempre dalle coordinate), ma solo nome, destinazione
     * e vertici. Risposta molto più corta per ogni locale: su planimetrie con molti locali o più
     * piante nella stessa immagine evita il troncamento della risposta dell'AI (causa dell'errore
     * "Non sono riuscito a interpretare la risposta dell'AI").
     */
    fun analizzaVerticiBitmap(chiaveApi: String, bitmap: Bitmap, callback: Callback) {
        val handler = Handler(Looper.getMainLooper())

        Thread {
            try {
                val base64Immagine = codificaBitmap(bitmap)
                val risultato = eseguiChiamataVertici(chiaveApi, base64Immagine)
                handler.post { callback.onSuccesso(risultato) }
            } catch (e: Exception) {
                handler.post { callback.onErrore(e.message ?: "Errore sconosciuto") }
            }
        }.start()
    }

    /** Come [analizzaVerticiBitmap], ma a partire da un Uri (es. foto scattata o immagine scelta senza PDF). */
    fun analizzaVertici(context: Context, chiaveApi: String, immagineUri: Uri, callback: Callback) {
        val handler = Handler(Looper.getMainLooper())

        Thread {
            try {
                val base64Immagine = preparaImmagineBase64(context, immagineUri)
                val risultato = eseguiChiamataVertici(chiaveApi, base64Immagine)
                handler.post { callback.onSuccesso(risultato) }
            } catch (e: Exception) {
                handler.post { callback.onErrore(e.message ?: "Errore sconosciuto") }
            }
        }.start()
    }

    /**
     * Raffina i vertici di UN SOLO locale, già individuato approssimativamente in precedenza,
     * lavorando su un ritaglio dell'immagine originale centrato su quel locale (zoomato, non
     * sull'intera planimetria/doppia pianta). Chiedere la posizione dei vertici su un'immagine
     * molto più piccola e già centrata sul locale è molto più preciso che chiederla sull'intera
     * planimetria: lo stesso piccolo errore di stima "a occhio" del modello, espresso come
     * frazione (0-1) di un'immagine piccola, corrisponde a molti meno pixel di scostamento reale
     * una volta riportato sull'immagine intera, rispetto a quando la frazione è calcolata
     * sull'intera planimetria (che può contenere più locali e più piante).
     */
    fun raffinaVerticiLocale(
        chiaveApi: String,
        ritaglio: Bitmap,
        nomeLocale: String,
        destinazione: String,
        callback: CallbackVertici
    ) {
        val handler = Handler(Looper.getMainLooper())
        Thread {
            try {
                val base64Immagine = codificaBitmap(ritaglio, ingrandisciSePiccola = true)
                val risultato = eseguiChiamataRaffinamento(chiaveApi, base64Immagine, nomeLocale, destinazione)
                val verticiJson = risultato.optJSONArray("vertici")
                val vertici = mutableListOf<PointF>()
                if (verticiJson != null) {
                    for (i in 0 until verticiJson.length()) {
                        val punto = verticiJson.optJSONObject(i) ?: continue
                        val fx = punto.optDouble("x", Double.NaN)
                        val fy = punto.optDouble("y", Double.NaN)
                        if (!fx.isNaN() && !fy.isNaN()) {
                            vertici.add(PointF(fx.toFloat(), fy.toFloat()))
                        }
                    }
                }
                handler.post { callback.onSuccesso(vertici) }
            } catch (e: Exception) {
                handler.post { callback.onErrore(e.message ?: "Errore sconosciuto") }
            }
        }.start()
    }

    /**
     * Ultimo livello di precisione: individua UN SOLO vertice, in un ritaglio piccolissimo e
     * molto ingrandito centrato approssimativamente su quel solo angolo (vedi
     * [raffinaVerticiLocale] per il livello "intero locale" precedente). Con un solo angolo
     * nell'inquadratura, senza altre pareti o locali a confondere, l'AI può dedicare tutta
     * la sua stima a quell'unico punto.
     */
    fun raffinaVerticeSingolo(chiaveApi: String, ritaglio: Bitmap, callback: CallbackVerticeSingolo) {
        val handler = Handler(Looper.getMainLooper())
        Thread {
            try {
                val base64Immagine = codificaBitmap(ritaglio, ingrandisciSePiccola = true)
                val risultato = eseguiChiamataVerticeSingolo(chiaveApi, base64Immagine)
                val trovato = risultato.optBoolean("trovato", false)
                val x = risultato.optDouble("x", Double.NaN)
                val y = risultato.optDouble("y", Double.NaN)
                handler.post {
                    if (trovato && !x.isNaN() && !y.isNaN()) {
                        callback.onSuccesso(true, x.toFloat(), y.toFloat())
                    } else {
                        callback.onSuccesso(false, 0f, 0f)
                    }
                }
            } catch (e: Exception) {
                handler.post { callback.onErrore(e.message ?: "Errore sconosciuto") }
            }
        }.start()
    }

    private fun eseguiChiamataVerticeSingolo(chiaveApi: String, base64Immagine: String): JSONObject {
        val promptSistema = """
            Questa immagine è un RITAGLIO MOLTO RAVVICINATO E INGRANDITO di una planimetria di
            edificio, centrato approssimativamente su UN SOLO vertice/angolo di un locale (il
            punto in cui si incontrano due pareti). La posizione esatta di quel vertice può
            essere leggermente spostata dal centro geometrico dell'immagine: il tuo unico
            compito è trovarla con la massima precisione possibile.

            Individua il punto ESATTO in cui le linee delle due pareti si incontrano
            (l'intersezione reale dei tratti del muro, non il centro dello spessore del muro
            né un punto approssimativo nelle vicinanze). Restituisci le coordinate di quel
            punto come frazione (da 0 a 1) rispettivamente della larghezza e dell'altezza di
            QUESTO RITAGLIO.

            Se in questo ritaglio non è individuabile con sufficiente sicurezza un angolo di
            parete (es. il ritaglio non contiene davvero un vertice, è troppo confuso o
            tagliato male), restituisci trovato=false invece di indovinare.

            Restituisci SOLO un oggetto JSON valido, senza markdown, senza testo prima o dopo,
            con questa struttura esatta:
            {"trovato": true, "x": 0.52, "y": 0.47}
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
                put("text", "Individua con la massima precisione il vertice al centro di questo ritaglio, seguendo esattamente le regole date.")
            }
        )

        val corpo = JSONObject().apply {
            put("model", MODELLO)
            put("max_tokens", 256)
            put("system", promptSistema)
            put("messages", org.json.JSONArray().put(
                JSONObject().apply {
                    put("role", "user")
                    put("content", contenutoUtente)
                }
            ))
        }

        return eseguiRichiestaHttp(chiaveApi, corpo)
    }

    private fun eseguiChiamataRaffinamento(
        chiaveApi: String,
        base64Immagine: String,
        nomeLocale: String,
        destinazione: String
    ): JSONObject {
        val descrizioneLocale = if (destinazione.isNotBlank() && destinazione != nomeLocale) {
            "\"$nomeLocale\" ($destinazione)"
        } else {
            "\"$nomeLocale\""
        }
        val promptSistema = """
            Questa immagine è un RITAGLIO ravvicinato e ingrandito di una planimetria di
            edificio, incentrato su UN SOLO locale: $descrizioneLocale. Ai bordi del ritaglio
            possono comparire piccole porzioni di locali adiacenti o del reticolo/cornice
            esterna: IGNORALE, interessa solo il contorno del locale indicato.

            Individua con la MASSIMA precisione possibile i vertici del perimetro di questo
            locale, seguendo esattamente le linee delle pareti che lo delimitano. Ogni vertice
            deve coincidere con il punto esatto in cui si incontrano le linee di due pareti
            (l'intersezione reale dei tratti del muro), non un punto approssimativo nelle
            vicinanze né il centro dello spessore del muro. Esamina ogni angolo singolarmente,
            uno alla volta, invece di stimare l'intera forma a colpo d'occhio.

            FORME IRREGOLARI: segui il perimetro reale, NON ridurlo a un rettangolo. Se il
            locale è a L, a U, a T, ha rientranze, nicchie, sporgenze, angoli smussati, pareti
            oblique o è formato da più porzioni comunicanti (es. ingresso + corridoio, soggiorno
            con angolo cottura), metti un vertice in OGNI cambio di direzione delle pareti,
            compresi gli angoli rientranti: i vertici sono tanti quanti servono (6, 8, 10 o più),
            non per forza 4. Le pareti oblique restano oblique (non raddrizzarle), e i lati non
            vanno forzati a essere paralleli o perpendicolari se nel disegno non lo sono.

            NON leggere né usare quote scritte, aree scritte o la scala grafica: qui serve solo
            il contorno visivo.

            Restituisci i vertici come elenco di punti {"x":.., "y":..} con x e y espressi come
            frazione (da 0 a 1) rispettivamente della larghezza e dell'altezza DI QUESTO
            RITAGLIO (NON dell'eventuale planimetria intera originale), in ordine lungo il
            perimetro del locale (senza ripetere il primo punto alla fine). Se il locale
            indicato non è individuabile con sufficiente sicurezza in questo ritaglio (es. è
            stato tagliato male e non si vede per intero), restituisci un array vuoto.

            Restituisci SOLO un oggetto JSON valido, senza markdown, senza testo prima o dopo,
            con questa struttura esatta:
            {
              "vertici": [{"x": 0.12, "y": 0.34}, {"x": 0.30, "y": 0.34}, {"x": 0.30, "y": 0.42}, {"x": 0.22, "y": 0.42}, {"x": 0.22, "y": 0.50}, {"x": 0.12, "y": 0.50}]
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
                put("text", "Individua con precisione i vertici del locale $descrizioneLocale in questo ritaglio, seguendo esattamente le regole date.")
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

        return eseguiRichiestaHttp(chiaveApi, corpo)
    }

    private fun eseguiChiamataVertici(chiaveApi: String, base64Immagine: String): JSONObject {
        val promptSistema = """
            Sei un assistente tecnico che individua visivamente i locali disegnati in una
            planimetria di edificio (disegno catastale o di progetto), anche quando
            l'immagine contiene più piante (es. piano interrato e piano terra).

            REGOLE:
            - NON leggere né usare quote scritte, aree scritte, la scala grafica o il
              reticolo di riferimento (parametri) sul bordo del disegno: qui servono solo i
              contorni visivi dei locali, l'area verrà calcolata altrove da coordinate già
              note.
            - Per ogni locale che riesci a distinguere (le pareti che lo delimitano sono
              visibili), individua il nome/sigla del locale se scritto, la destinazione
              d'uso se scritta (es. "Cucina", "Camera 1", "Bagno", "Garage"; stringa vuota
              se non scritta o non leggibile, senza indovinare dalla forma) e una stima
              visiva dei vertici del suo perimetro.
            - I vertici vanno dati come elenco di punti {"x":.., "y":..} con x e y espressi
              come frazione (da 0 a 1) rispettivamente della larghezza e dell'altezza
              DELL'INTERA IMMAGINE, in ordine lungo il perimetro del locale (senza ripetere
              il primo punto alla fine). Dai sempre una stima anche se non perfetta: verrà
              mostrata all'utente, che la correggerà a mano. Se non riesci a distinguere il
              contorno di un locale, restituisci un array vuoto per quel locale.
            - Massima attenzione alla precisione pixel di ogni vertice: deve coincidere
              esattamente con il punto in cui si incontrano le linee delle due pareti che
              formano l'angolo (l'intersezione effettiva dei tratti del muro), non un punto
              generico vicino all'angolo né il centro dello spessore del muro. Guarda il
              dettaglio di ogni singolo angolo separatamente prima di dare la coordinata,
              invece di stimare tutti i vertici del locale a colpo d'occhio dalla forma
              generale: una stima "a occhio" tende a introdurre uno scostamento sistematico
              (es. tutti i punti leggermente spostati nella stessa direzione), che va evitato.
            - FORME IRREGOLARI: segui il perimetro reale di ogni locale, NON ridurlo a un
              rettangolo. Locali a L, a U, a T, con rientranze, nicchie, sporgenze, angoli
              smussati, pareti oblique o formati da più porzioni comunicanti (es. ingresso +
              corridoio, soggiorno con angolo cottura) hanno un vertice in OGNI cambio di
              direzione delle pareti, compresi gli angoli rientranti: tanti vertici quanti
              servono (6, 8, 10 o più), non per forza 4. Le pareti oblique restano oblique.
            - Se l'immagine contiene più piante/piani, individua i locali di TUTTE le piante
              presenti, non solo della prima.
            - Sii SINTETICO: non aggiungere spiegazioni, commenti o campi diversi da quelli
              richiesti. La risposta deve restare breve anche con molti locali, per evitare
              di essere troncata.

            Restituisci SOLO un oggetto JSON valido, senza markdown, senza testo prima o
            dopo, con questa struttura esatta:
            {
              "locali": [
                {
                  "nome": "nome o sigla del locale come scritto sul disegno (stringa vuota se assente)",
                  "destinazione": "uso del locale come scritto sul disegno (stringa vuota se non scritto/leggibile)",
                  "vertici": [{"x": 0.12, "y": 0.34}, {"x": 0.30, "y": 0.34}, {"x": 0.30, "y": 0.42}, {"x": 0.22, "y": 0.42}, {"x": 0.22, "y": 0.50}, {"x": 0.12, "y": 0.50}]
                }
              ]
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
                put("text", "Individua i locali presenti in questa planimetria (tutte le piante se più d'una) e stimane i vertici, seguendo esattamente le regole date.")
            }
        )

        val corpo = JSONObject().apply {
            put("model", MODELLO)
            // Una pianta con molti locali (e locali irregolari con molti vertici) può superare
            // 4096 token: la risposta troncata non era leggibile e la pianta restava vuota.
            put("max_tokens", 12000)
            put("system", promptSistema)
            put("messages", org.json.JSONArray().put(
                JSONObject().apply {
                    put("role", "user")
                    put("content", contenutoUtente)
                }
            ))
        }

        return eseguiRichiestaHttp(chiaveApi, corpo)
    }

    private fun eseguiChiamata(chiaveApi: String, base64Immagine: String): JSONObject {
        val promptSistema = """
            Sei un assistente tecnico che legge planimetrie di edifici (disegni
            catastali o di progetto) per calcolare l'area dei singoli locali.

            REGOLE FONDAMENTALI (da rispettare sempre):
            - Calcola l'area SOLO usando quote numeriche (misure in metri)
              effettivamente scritte e leggibili nell'immagine, vicino a ciascun
              locale. Non stimare o dedurre misure dalla dimensione dei pixel o
              dal disegno, e non misurare né leggere la scala grafica o il
              reticolo di riferimento (parametri) eventualmente presente sul
              bordo del disegno: se non c'è una quota scritta e leggibile, NON
              inventarla.
            - Se un locale è chiaramente rettangolare e riporta due quote
              (base e altezza, es. "4,20 x 3,50"), calcola area = base × altezza.
            - L'area da calcolare è sempre la superficie netta del locale:
              NON aggiungere l'area di sguanci di porte o finestre (il piccolo
              rientro/spessore dell'apertura nel muro) né di nicchie interne,
              anche se riportano una quota propria separata. Se le quote
              base × altezza che leggi corrispondono già alle dimensioni
              nette della stanza (il caso normale nelle planimetrie tecniche),
              usa direttamente quel prodotto senza altre aggiunte.
            - Se un locale ha una forma non rettangolare (a L, con rientranze
              importanti, pareti oblique...) e il semplice prodotto delle due
              quote principali non rappresenterebbe l'area reale, NON calcolare
              comunque un numero: segnala il locale con area null e una nota
              che spiega il motivo.
            - Se le quote di un locale non sono leggibili o assenti, segnalalo
              con area null e una nota ("quote non leggibili" o simile).
            - Se nell'immagine è presente un'area già scritta esplicitamente
              (es. "mq 14,70" dentro al locale), puoi riportarla direttamente
              come area, specificando nella nota che è il valore scritto sul
              disegno e non un calcolo.
            - Se vicino al locale è scritta una destinazione d'uso (es.
              "Cucina", "Camera 1", "Soggiorno", "Bagno", "Garage", "Ripostiglio"),
              riportala nel campo "destinazione" esattamente come scritta sul
              disegno. Se non è scritta o non è leggibile, lascia il campo
              vuoto: non indovinare la destinazione dalla forma o dimensione
              del locale.
            - Oltre alle quote, per ogni locale di cui riesci a individuare
              visivamente il contorno (le pareti che lo delimitano), fornisci
              anche una stima approssimativa dei suoi vertici nel campo
              "vertici": un elenco di punti {"x":.., "y":..} con x e y
              espressi come frazione (da 0 a 1) rispettivamente della
              larghezza e dell'altezza DELL'INTERA IMMAGINE, elencati in
              ordine lungo il perimetro del locale (senza ripetere il primo
              punto alla fine). A differenza delle quote e delle aree, qui PUOI
              e DEVI dare una stima visiva anche se non hai misure numeriche
              esatte: questi vertici verranno sempre mostrati all'utente, che
              li correggerà uno per uno a mano prima che vengano usati, quindi
              sono utili anche se non perfetti. Se non riesci a distinguere il
              contorno del locale (pareti poco chiare, locale tagliato ai
              bordi dell'immagine, ecc.), restituisci un array vuoto per quel
              locale.

            Restituisci SOLO un oggetto JSON valido, senza markdown, senza testo
            prima o dopo, con questa struttura esatta:
            {
              "locali": [
                {
                  "nome": "nome o sigla del locale come scritto sul disegno",
                  "destinazione": "uso del locale come scritto sul disegno (stringa vuota se non scritto/leggibile)",
                  "quote": "quote lette, es. 4,20 x 3,50 (stringa vuota se nessuna)",
                  "area_mq": numero oppure null,
                  "nota": "stringa vuota se tutto ok, altrimenti spiegazione",
                  "vertici": [{"x": 0.12, "y": 0.34}, {"x": 0.30, "y": 0.34}, {"x": 0.30, "y": 0.42}, {"x": 0.22, "y": 0.42}, {"x": 0.22, "y": 0.50}, {"x": 0.12, "y": 0.50}]
                }
              ],
              "superficie_totale_mq": numero oppure null (somma delle sole aree calcolate, null se nessuna),
              "avviso_generale": "eventuale avviso complessivo, es. qualità immagine scarsa, stringa vuota se nessuno"
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
                put("text", "Leggi le quote e le destinazioni d'uso di questa planimetria, calcola le aree nette dei locali e stima i vertici di ciascun locale, seguendo esattamente le regole date.")
            }
        )

        val corpo = JSONObject().apply {
            put("model", MODELLO)
            put("max_tokens", 4096)
            put("system", promptSistema)
            put("messages", org.json.JSONArray().put(
                JSONObject().apply {
                    put("role", "user")
                    put("content", contenutoUtente)
                }
            ))
        }

        return eseguiRichiestaHttp(chiaveApi, corpo)
    }

    /** Invia la richiesta all'API Anthropic e restituisce il JSON estratto dalla risposta testuale. */
    private fun eseguiRichiestaHttp(chiaveApi: String, corpo: JSONObject): JSONObject {
        val url = URL(ENDPOINT)
        val connessione = url.openConnection() as HttpURLConnection
        connessione.requestMethod = "POST"
        connessione.setRequestProperty("content-type", "application/json")
        connessione.setRequestProperty("x-api-key", chiaveApi)
        connessione.setRequestProperty("anthropic-version", "2023-06-01")
        connessione.doOutput = true
        connessione.connectTimeout = 20000
        connessione.readTimeout = 60000

        OutputStreamWriter(connessione.outputStream, Charsets.UTF_8).use { scrittore ->
            scrittore.write(corpo.toString())
        }

        val codice = connessione.responseCode
        val stream = if (codice in 200..299) connessione.inputStream else connessione.errorStream
        val testoRisposta = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }

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

        val motivoArresto = rispostaJson.optString("stop_reason")
        try {
            return JSONObject(estraiJson(testoModello.toString()))
        } catch (e: Exception) {
            if (motivoArresto == "max_tokens") {
                throw IllegalStateException(
                    "La risposta dell'AI è stata troncata: probabilmente ci sono troppi locali o piante nella stessa immagine. Prova con una sola pianta per immagine."
                )
            }
            throw e
        }
    }

    /** Ridimensiona e comprime l'immagine per restare entro limiti ragionevoli, poi la codifica in base64. */
    private fun preparaImmagineBase64(context: Context, uri: Uri): String {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Impossibile leggere l'immagine scelta")
        val originale = input.use { BitmapFactory.decodeStream(it) }
            ?: throw IllegalStateException("Il file scelto non è un'immagine valida")
        return codificaBitmap(originale)
    }

    /**
     * @param ingrandisciSePiccola se true e l'immagine è più piccola di [LATO_MINIMO_INGRANDIMENTO_PX],
     *   la ingrandisce fino a quella soglia prima di codificarla (vedi nota su [LATO_MINIMO_INGRANDIMENTO_PX]).
     *   Usato solo per i ritagli di un singolo locale (raffinamento), dove la precisione del vertice
     *   conta più del peso della richiesta; non per l'immagine intera o per pianta, già grandi di loro.
     */
    private fun codificaBitmap(originale: Bitmap, ingrandisciSePiccola: Boolean = false): String {
        val latoMaggiore = maxOf(originale.width, originale.height)
        val bitmapRidimensionato = when {
            latoMaggiore > LATO_MASSIMO_PX -> {
                val scala = LATO_MASSIMO_PX.toFloat() / latoMaggiore
                Bitmap.createScaledBitmap(
                    originale,
                    (originale.width * scala).toInt(),
                    (originale.height * scala).toInt(),
                    true
                )
            }
            ingrandisciSePiccola && latoMaggiore in 1 until LATO_MINIMO_INGRANDIMENTO_PX -> {
                val scala = LATO_MINIMO_INGRANDIMENTO_PX.toFloat() / latoMaggiore
                Bitmap.createScaledBitmap(
                    originale,
                    (originale.width * scala).toInt(),
                    (originale.height * scala).toInt(),
                    true
                )
            }
            else -> originale
        }

        val output = ByteArrayOutputStream()
        val qualita = if (ingrandisciSePiccola) 95 else 85
        bitmapRidimensionato.compress(Bitmap.CompressFormat.JPEG, qualita, output)
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
