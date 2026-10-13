package it.geometra.schedacliente

import android.graphics.PointF
import java.util.Locale

/**
 * Genera i locali tracciati come disegno DXF (formato AutoCAD R12, il più compatibile con
 * qualunque software CAD: AutoCAD, LibreCAD, QCAD, BricsCAD...): una polilinea chiusa per
 * ciascun locale, con due righe di testo al centro (nome del locale e superficie in mq).
 *
 * Le coordinate dei vertici, espresse in pixel dell'immagine originale, vengono convertite in
 * metri reali con la stessa scala (metri/pixel) già usata per calcolare le aree. L'asse Y viene
 * invertito nel passaggio (in un'immagine cresce verso il basso, in CAD verso l'alto): così il
 * disegno risulta orientato correttamente e non specchiato rispetto alla planimetria originale.
 *
 * CORREZIONE (ottobre 2026): il testo (nomi dei locali, superfici) va scritto nel file con la
 * codifica ANSI/Windows-1252, non UTF-8: un DXF "R12 ASCII" come questo non ha un modo standard
 * di dichiarare UTF-8, e i lettori CAD (AutoCAD, LibreCAD...) per un file di questo tipo si
 * aspettano Windows-1252 di default. Scrivendo il testo come UTF-8 (fatto fino alla versione
 * precedente), una lettera accentata o un trattino "–" venivano salvati come PIÙ byte UTF-8 che
 * il lettore CAD interpretava uno per uno come caratteri Windows-1252 diversi, producendo i
 * simboli illeggibili tipo "â€?" segnalati dall'utente al posto degli accenti/trattini. Chi
 * scrive il file su disco (in PlanimetriaActivity.scriviDxfSuUri) deve quindi codificare questa
 * stringa con Charsets.forName("Windows-1252"), MAI con UTF-8.
 *
 * CORREZIONE (ottobre 2026): all'apertura in un CAD il disegno poteva comparire "vuoto" o
 * zoomato su un punto qualunque, perche' un DXF senza indicazioni esplicite sull'inquadratura
 * viene aperto da molti lettori con una vista predefinita (es. centrata sull'origine) invece che
 * con uno zoom automatico su tutto il disegno. Per far comparire subito tutta la planimetria,
 * senza dover zoomare a mano, si scrivono ora sia i limiti del disegno ($EXTMIN/$EXTMAX in
 * HEADER) sia una viewport attiva ("*ACTIVE" in TABLES/VPORT) gia' inquadrata su quei limiti con
 * un margine: e' la stessa informazione che AutoCAD e simili usano per uno "Zoom Extents", cosi'
 * il software la applica da solo all'apertura invece di aspettare un comando manuale.
 */
object EsportatoreDxf {

    fun genera(locali: List<LocaleTracciato>, scalaX: Double, scalaY: Double): String {
        val sb = StringBuilder()

        fun riga(codice: Int, valore: String) {
            sb.append(codice).append('\n').append(valore).append('\n')
        }
        fun riga(codice: Int, valore: Double) {
            riga(codice, String.format(Locale.US, "%.4f", valore))
        }
        fun riga(codice: Int, valore: Int) {
            riga(codice, valore.toString())
        }

        // Converte un punto dallo spazio pixel dell'immagine a coordinate reali (metri) in CAD.
        fun reale(p: PointF): PointF = PointF((p.x * scalaX).toFloat(), (-(p.y * scalaY)).toFloat())

        // Limiti (bounding box) di tutto il disegno in coordinate reali, con un margine attorno:
        // servono sia per $EXTMIN/$EXTMAX in HEADER sia per la viewport attiva in TABLES, scritte
        // entrambe prima della sezione ENTITIES, cosi' il CAD apre il file gia' inquadrato su
        // tutta la planimetria (vedi nota in testa al file).
        var minX = 0.0; var minY = 0.0; var maxX = 0.0; var maxY = 0.0
        var primoPunto = true
        for (locale in locali) {
            for (v in locale.verticiPx) {
                val r = reale(v)
                if (primoPunto) {
                    minX = r.x.toDouble(); maxX = r.x.toDouble()
                    minY = r.y.toDouble(); maxY = r.y.toDouble()
                    primoPunto = false
                } else {
                    if (r.x < minX) minX = r.x.toDouble()
                    if (r.x > maxX) maxX = r.x.toDouble()
                    if (r.y < minY) minY = r.y.toDouble()
                    if (r.y > maxY) maxY = r.y.toDouble()
                }
            }
        }
        if (primoPunto) {
            // Caso limite (nessun locale): non dovrebbe capitare, l'esportazione DXF è disabilitata
            // finché non c'è almeno un locale tracciato, ma si evita comunque una divisione a vuoto.
            minX = -1.0; minY = -1.0; maxX = 1.0; maxY = 1.0
        }
        val margineX = ((maxX - minX) * 0.08).coerceAtLeast(0.5)
        val margineY = ((maxY - minY) * 0.08).coerceAtLeast(0.5)
        val estMinX = minX - margineX; val estMaxX = maxX + margineX
        val estMinY = minY - margineY; val estMaxY = maxY + margineY
        val centroVistaX = (estMinX + estMaxX) / 2.0
        val centroVistaY = (estMinY + estMaxY) / 2.0
        val altezzaVista = (estMaxY - estMinY).coerceAtLeast(1.0)
        val larghezzaVista = (estMaxX - estMinX).coerceAtLeast(1.0)

        fun testo(valore: String, xCentro: Double, yCentro: Double, altezza: Double) {
            // Nessuna giustificazione DXF (gruppi 72/11 etc.): per restare compatibili con il
            // maggior numero di lettori possibile si usa testo semplice allineato a sinistra,
            // spostato di metà larghezza stimata per ottenere comunque un effetto centrato.
            val larghezzaStimata = valore.length * altezza * 0.6
            riga(0, "TEXT")
            riga(8, "TESTO")
            riga(10, xCentro - larghezzaStimata / 2.0)
            riga(20, yCentro)
            riga(30, 0.0)
            riga(40, altezza)
            riga(1, valore)
        }

        // HEADER
        riga(0, "SECTION")
        riga(2, "HEADER")
        riga(9, "\$ACADVER")
        riga(1, "AC1009")
        // Dichiara esplicitamente la codifica testo usata nel file (vedi nota sopra): senza
        // questa riga molti lettori CAD assumono comunque ANSI_1252 di default per un DXF R12,
        // ma dichiararla evita ambiguità con lettori meno comuni.
        riga(9, "\$DWGCODEPAGE")
        riga(3, "ANSI_1252")
        // Limiti del disegno (vedi nota in testa al file): insieme alla viewport attiva in TABLES
        // fanno sì che il CAD apra il file già inquadrato su tutta la planimetria.
        riga(9, "\$EXTMIN")
        riga(10, estMinX); riga(20, estMinY); riga(30, 0.0)
        riga(9, "\$EXTMAX")
        riga(10, estMaxX); riga(20, estMaxY); riga(30, 0.0)
        riga(0, "ENDSEC")

        // TABLES: definizione dei layer usati (LOCALI per le polilinee, TESTO per le etichette)
        riga(0, "SECTION")
        riga(2, "TABLES")
        riga(0, "TABLE")
        riga(2, "LAYER")
        riga(70, 3)
        riga(0, "LAYER"); riga(2, "0"); riga(70, 0); riga(62, 7); riga(6, "CONTINUOUS")
        riga(0, "LAYER"); riga(2, "LOCALI"); riga(70, 0); riga(62, 3); riga(6, "CONTINUOUS")
        riga(0, "LAYER"); riga(2, "TESTO"); riga(70, 0); riga(62, 1); riga(6, "CONTINUOUS")
        riga(0, "ENDTAB")

        // Viewport attiva "*ACTIVE": è questa, insieme a $EXTMIN/$EXTMAX in HEADER, a far aprire
        // il disegno già inquadrato su tutti i locali invece che su una vista predefinita (vedi
        // nota in testa al file). Centro e altezza di vista sono i limiti calcolati sopra.
        riga(0, "TABLE")
        riga(2, "VPORT")
        riga(70, 1)
        riga(0, "VPORT")
        riga(2, "*ACTIVE")
        riga(70, 0)
        riga(10, 0.0); riga(20, 0.0)
        riga(11, 1.0); riga(21, 1.0)
        riga(12, centroVistaX); riga(22, centroVistaY)
        riga(13, 0.0); riga(23, 0.0)
        riga(14, 1.0); riga(24, 1.0)
        riga(15, 1.0); riga(25, 1.0)
        riga(16, 0.0); riga(26, 0.0); riga(36, 1.0)
        riga(17, centroVistaX); riga(27, centroVistaY); riga(37, 0.0)
        riga(40, altezzaVista)
        riga(41, larghezzaVista / altezzaVista)
        riga(42, 50.0)
        riga(43, 0.0)
        riga(44, 0.0)
        riga(50, 0.0)
        riga(51, 0.0)
        riga(71, 0)
        riga(72, 100)
        riga(73, 1)
        riga(74, 3)
        riga(75, 0)
        riga(76, 0)
        riga(77, 0)
        riga(78, 0)
        riga(0, "ENDTAB")
        riga(0, "ENDSEC")

        // ENTITIES
        riga(0, "SECTION")
        riga(2, "ENTITIES")

        for (locale in locali) {
            val vertici = locale.verticiPx
            if (vertici.size < 3) continue

            // Poligono chiuso del locale: POLYLINE/VERTEX/SEQEND "classica" (non LWPOLYLINE),
            // la forma di gran lunga più compatibile con qualunque versione di CAD.
            riga(0, "POLYLINE")
            riga(8, "LOCALI")
            riga(66, 1)
            riga(70, 1)
            for (v in vertici) {
                val r = reale(v)
                riga(0, "VERTEX")
                riga(8, "LOCALI")
                riga(10, r.x.toDouble())
                riga(20, r.y.toDouble())
                riga(30, 0.0)
            }
            riga(0, "SEQEND")

            // Due righe di testo al centro del locale: nome e superficie.
            val centroPx = GeometriaUtils.centroide(vertici)
            val centro = reale(centroPx)
            val largBboxM = (vertici.maxOf { it.x } - vertici.minOf { it.x }) * scalaX
            val altBboxM = (vertici.maxOf { it.y } - vertici.minOf { it.y }) * scalaY
            val altezzaTesto = (minOf(largBboxM, altBboxM) * 0.12).coerceIn(0.08, 0.30)

            testo(locale.nome, centro.x.toDouble(), centro.y.toDouble() + altezzaTesto * 0.7, altezzaTesto)
            testo(
                String.format(Locale.US, "%.2f mq", locale.areaM2),
                centro.x.toDouble(),
                centro.y.toDouble() - altezzaTesto * 0.9,
                altezzaTesto
            )
        }

        riga(0, "ENDSEC")
        riga(0, "EOF")

        return sb.toString()
    }
}
