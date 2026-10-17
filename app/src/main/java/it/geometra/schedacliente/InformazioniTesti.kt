package it.geometra.schedacliente

/** Testi (in HTML semplice) della sezione "Informazioni". */
object InformazioniTesti {

    val CHI_SONO: String = """
<h2>Chi sono</h2>
<p>Sono il <b>Geom. Simone Morbidelli</b>, geometra iscritto al Collegio dei Geometri della Provincia di Arezzo (albo n. 1335). Lavoro in provincia di Arezzo, soprattutto nel Valdarno superiore: rilievi e pratiche catastali, conformità urbanistica ed edilizia, certificazione energetica (APE), consulenza IMU, stime immobiliari e supporto tecnico alle compravendite.</p>

<h2>Come è nato il progetto</h2>
<p>Tutto è partito da una richiesta semplice, fatta a Claude in una chat: <i>«Fai un'app in cui compilo una scheda cliente per studio di geometra»</i>. Da lì <b>SchedaCliente</b> è cresciuta un passo alla volta, build dopo build, aggiungendo solo ciò che mi serviva davvero nel lavoro quotidiano. I dati restano sul telefono, in locale; l'app è pensata per l'uso nel mio studio e può essere adattata ad altri studi.</p>
<p>La versione <b>3.64</b> è stata un buon punto di partenza (l'ho fissata anche come tag nel repository): le migliorie più recenti sono elencate di seguito con il numero di build.</p>

<h2>Le migliorie, build per build</h2>
<p><b>v1.0</b> – Prima versione: scheda cliente con i dati dello studio.</p>
<p><b>v1.3 – v1.6</b> – Prime rifiniture della scheda e delle funzioni di base.</p>
<p><b>v2.0</b> – Prima grande revisione dell'app.</p>
<p><b>v2.3 – v2.6</b> – Arriva la <i>Ruota del caffè</i>, poi affinata nelle build successive (le build esatte sono approssimative).</p>
<p><b>v2.7 – v2.9</b> – Consolidamento delle funzioni e correzioni.</p>
<p><b>v3.0 – v3.5</b> – Nuova serie 3.x con ulteriori funzioni e miglioramenti di uso.</p>
<p><b>v3.19 – v3.22</b> – Altre migliorie incrementali.</p>
<p><b>Tra la v3.x e la v3.64</b> (build esatte non ricostruite con certezza) – foto da fotocamera, revisione completa dell'app, registro FDM con riepilogo su Drive, nuove icone (v3.52), miglioramenti alla ruota, <i>Bolletta energia</i> con lettura OCR/PDF, <i>Mega</i> con parere in Word, versione mostrata nel titolo, analizzatore dei vani validato su 7 planimetrie PDF.</p>
<p><b>v3.64</b> – Punto di partenza stabile per le migliorie successive.</p>
<p><b>v3.65</b> – Rinomina del locale in modifica; nuova sezione <i>Perizia unità</i> (superficie commerciale con coefficienti OMI, quotazioni OMI, valore di mercato).</p>
<p><b>v3.66</b> – Rilevamento dei locali per pianta, con misura dei contorni sui muri e passata finale di completezza.</p>
<p><b>v3.67</b> – Muri a doppia linea, portefinestre larghe, esclusione degli sguanci, aggancio ai muri dei poligoni proposti dall'AI.</p>
<p><b>v3.68</b> – Simboli e scritte a mano non tagliano più i vani; controllo d'area più stretto; aggancio più robusto.</p>
<p><b>v3.69</b> – Le soglie di porte e finestre chiudono i vani a filo muro.</p>
<p><b>v3.70</b> – Misura sui muri anche per i locali recuperati dall'intera tavola.</p>
<p><b>v3.71</b> – Non raddrizza i risalti veri dei muri (solo gli sguanci); prova su PDF reale.</p>
<p><b>v3.72</b> – Muri a doppia linea nei disegni misti; margine attorno alla pianta.</p>
<p><b>v3.73</b> – Conferma automatica del locale rilevato dopo pochi secondi; ritorno alla sezione con vista intera.</p>
<p><b>v3.74</b> – Regolarizzazione dei contorni; raggio porte iniziale 25 cm; pressione lunga in visualizzazione per correggere un locale.</p>
<p><b>v3.75</b> – Il rilevamento sceglie il metodo migliore per ogni vano; rimozione di scritte e carta millimetrata; banco di prova su 7 planimetrie catastali reali.</p>
<p><b>v3.76</b> – Correzioni dopo la verifica: «Interrompi» ferma davvero il rilevamento; «Salta locale» compare solo quando serve.</p>
<p><b>v3.77</b> – Grafica della planimetria più leggibile (nomi dei locali, pulsanti di modifica, avvisi in alto, scala).</p>
<p><b>v3.78</b> – Parere / relazione con l'AI dai documenti del NAS dello studio.</p>
<p><b>v3.79</b> – NAS letto una cartella alla volta; ricerca del cliente nelle cartelle contenitore.</p>
<p><b>v3.80</b> – Tutti i tipi di file selezionabili (Excel, PowerPoint, OpenDocument, RTF, HTML), scritta della scala sempre nello schermo.</p>
<p><b>v3.81</b> – Scala letta dalle tacche vere; pulsante «Elimina locale» nella modifica.</p>
<p><b>v3.82</b> – Perizia di stima completa in Word (richiesta dettata, allegati da telefono, NAS o Mega, AI).</p>
<p><b>v3.83</b> – Rilevamento locali più veloce, zoom fluido, scala trasparente.</p>
<p><b>v3.84</b> – Verifica di conformità (catasto, titolo edilizio, stato di fatto, atto/visura).</p>
<p><b>v3.85</b> – Collegamento al NAS anche via WebDAV/HTTPS, con conferma del certificato.</p>
<p><b>v3.86</b> – DXF con la vista corrente e i limiti nell'intestazione; finestra 16:10.</p>
<p><b>v3.87 – v3.90</b> – Sezione <i>Mappa e WMS Regione Toscana</i> sviluppata e rifinita in più passaggi.</p>
<p><b>v3.91</b> – Mappa: tutti i servizi WMS, legende e cartoteca del Geoscopio, selezione di un oggetto con scheda e download, ortofoto dal 1954, macchina del tempo con confronto a barra, stampa in PDF.</p>
<p><b>v3.92</b> – Mappa: <i>Istruttoria urbanistico-edilizia</i> (gruppi di vincoli accendibili con un tocco), <i>Vincoli nel punto che tocco</i>, pulsanti «Spegni tutto e comprimi», «Espandi tutto», «Comprimi tutto»; macchina del tempo con tutti i voli (anche 1965).</p>
<p><b>v3.93</b> – Mappa: PAI dissesti e PGRA dell'Autorità di bacino dell'Appennino Settentrionale nell'istruttoria e nei vincoli nel punto.</p>
<p><b>v3.94</b> – Tasto «indietro» con conferma (sezione → pagina iniziale, uscita dall'app); ripresa dell'ultimo progetto in «Aree locali da planimetria» e dell'ultima visualizzazione della mappa; sezione Informazioni.</p>
<p><b>v3.95</b> – Storia dell'applicazione ricostruita dalla chat originale.</p>
<p><b>v3.96</b> – Guide per utenti inesperti ed esperti riscritte per intero: scheda cliente, dettatura, Mega e NAS, planimetria, perizia, conformità, mappa (adattando il manuale del Geoscopio) e impostazioni.</p>
<p><b>v3.97</b> – Menu ⋮ riordinato (sottomenu «Scheda Cliente» e «Parere / Relazione con l'AI»); mappa: CTR 1:10.000 pulito, macchina del tempo che parte dall'ortofoto 2024/2025 1:5.000 a colori, menu a tocco prolungato (nota, vai a posizione, indicatore xy, quota, cerchio), «Seleziona oggetto» che interroga tutti gli strati accesi e solo quelli</p>
<p><b>v3.98</b> – «Seleziona oggetto»: la scheda mostra solo gli oggetti presenti nel punto toccato, senza l'elenco di tutti gli strati accesi</p>
<p><b>v3.99</b> – «Seleziona oggetto»: gli strati WMS (vincoli paesaggistici, ecc.) compaiono solo se hanno davvero un elemento nel punto toccato (prima comparivano tutti con titolo e link); limite portato a 60 strati per tocco</p>
<p><b>v4.00</b> – «Seleziona oggetto»: tornano i collegamenti alla scheda del vincolo, alla cartografia e alla disciplina (prima si vedeva solo il link generico alla home della Regione); nuovo pulsante «🧹 Spegni tutti i vincoli accesi» per togliere in un colpo i vincoli accesi con «Vincoli nel punto che tocco»</p>
<p><b>v4.01</b> – Confronto con il Geoscopio: strati a scelta esclusiva e caselle di categoria come nel Geoscopio, stili degli strati (catasto tematico), catasto dell'Agenzia delle Entrate funzionante (CRS EPSG:4258), schede dei risultati complete (tutti i campi e i collegamenti), collegamenti a leggi e normativa (Gazzetta Ufficiale, EUR-Lex, ARPAT, Wikipedia…) apribili, descrizioni delle categorie, strati non interrogabili segnalati</p>
<p><b>v4.02</b> – «Vincoli nel punto che tocco» mostra le schede complete con i collegamenti del Geoscopio (scheda analitico-descrittiva, cartografia, disciplina, PDF) e la scheda dei metadati del livello; nuovo pulsante a schermo 🧹 «spegni tutto e comprimi»; nuovo sottomenu ⋯ → ⭐ Preferiti per salvare e richiamare gruppi di livelli (sfondo invariato)</p>
<p><b>v4.11</b> – Correzione del metodo «Raffinato + chiude le piccole rientranze»: non crea più lati in diagonale (chiude solo le rientranze che proseguono una parete dritta, entro l'8% dell'area del vano)</p>
<p><b>v4.10</b> – Rifai i poligoni: quarto metodo «Raffinato + chiude le piccole rientranze»: pilastri, spalle di muro, cellette di finestra e nicchie piccole restano dentro il vano; gli spigoli veri e le forme a L/U non vengono toccati</p>
<p><b>v4.09</b> – Rilevamento automatico: le richieste all'AI (scala, piante, vani, vertici) usano ora temperatura 0, così la stessa planimetria con lo stesso metodo dà risultati molto più ripetibili (prima ogni prova poteva dare un risultato diverso)</p>
<p><b>v4.08</b> – Planimetria: il pulsante «Cancella tutti i vani e rifai i poligoni…» è spostato sotto l'elenco dei vani rilevati</p>
<p><b>v4.07</b> – Planimetria: il pulsante «Cancella tutti i vani e rifai i poligoni…» è sempre visibile nella pagina generale, sotto l'elenco dei vani rilevati (non serve più entrare in un locale); tre metodi: da spigolo a spigolo, raffinato, lato per lato (l'AI percorre ogni parete per intero e gli spigoli si ricavano dall'intersezione delle pareti)</p>
<p><b>v4.06</b> – Planimetria: sotto l'elenco dei vani il pulsante «Cancella tutti i vani e rifai…» cancella i vani rilevati senza reimportare la planimetria e chiede se rifarli da spigolo a spigolo (ignora i particolari) o in modo raffinato (tiene conto di ogni particolare)</p>
<p><b>v4.05</b> – Rilevamento automatico dei locali: la pausa tra un vertice e il successivo passa da 70 a 140 millisecondi</p>
<p><b>v4.04</b> – Strati dell'Autorità di bacino (PAI, PGRA, acque): in «Seleziona oggetto» e «Vincoli nel punto» compaiono i collegamenti ufficiali del SIT (scheda metadato, cruscotto del corpo idrico, delibera/decreto, download, struttura DB, disciplina di piano, relazione, WebGIS); nel pannello, per ogni gruppo e servizio, l'elenco dei documenti ufficiali</p>
<p><b>v4.03</b> – Nuova sezione «🌊 Autorità di bacino: PAI, alluvioni, acque» nel pannello livelli (circa 290 strati dei servizi dell'Autorità di bacino dell'Appennino Settentrionale, con ricerca); nei preferiti il tasto «ⓘ» mostra i nomi dei livelli salvati; tolti dai livelli principali «catasto solo particelle e numeri» e «catasto solo fabbricati»</p>
"""

    val GUIDA_INESPERTI: String get() = InformazioniGuida.NOVIZI

    val GUIDA_ESPERTI: String get() = InformazioniGuida.ESPERTI

    val COPYRIGHT: String = """
<h2>Copyright</h2>
<p>© 2026 <b>Geom. Simone Morbidelli</b>. Tutti i diritti riservati.</p>
<p><b>SchedaCliente</b> – versione %VERSIONE%.</p>
<p>L'applicazione è stata sviluppata con l'assistenza di Claude (Anthropic). Il nome, il codice e i contenuti originali di SchedaCliente sono di proprietà dell'autore: non possono essere copiati, modificati o distribuiti senza autorizzazione scritta.</p>

<h3>Dati e servizi di terzi</h3>
<p>• Mappe, catasto, CTR, ortofoto e vincoli: © Regione Toscana – Geoscopio, secondo le condizioni d'uso del Geoscopio.<br>• PAI dissesti e PGRA: Autorità di bacino distrettuale dell'Appennino Settentrionale.<br>• Quotazioni immobiliari: Osservatorio del Mercato Immobiliare (OMI), Agenzia delle Entrate.<br>• Sfondi cartografici: © OpenStreetMap contributors; Esri, Maxar, Earthstar Geographics; Google (con chiave personale).</p>

<h3>Componenti open source</h3>
<p>Leaflet e proj4js (mappa), OkHttp, smbj e Bouncy Castle (rete e NAS), librerie AndroidX e Material Components di Google, ML Kit (lettura del testo delle bollette): ciascuno è soggetto alla propria licenza.</p>

<h3>Avvertenze</h3>
<p>I risultati dei calcoli, delle verifiche sui vincoli e dei testi generati con l'AI sono un supporto al lavoro del professionista e vanno sempre controllati sulle fonti ufficiali prima dell'uso in atti o pratiche.</p>
"""
}
