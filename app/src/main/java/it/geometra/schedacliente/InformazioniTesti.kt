package it.geometra.schedacliente

/** Testi (in HTML semplice) della sezione "Informazioni". */
object InformazioniTesti {

    val CHI_SONO: String = """
<h2>Chi sono</h2>
<p>Sono il <b>Geom. Simone Morbidelli</b>, geometra iscritto al Collegio dei Geometri della Provincia di Arezzo (albo n. 1335). Lavoro in provincia di Arezzo, soprattutto nel Valdarno superiore: rilievi e pratiche catastali, conformità urbanistica ed edilizia, certificazione energetica (APE), consulenza IMU, stime immobiliari e supporto tecnico alle compravendite.</p>

<h2>Come è nato il progetto</h2>
<p><b>SchedaCliente</b> è nata per avere sempre a portata di mano, sul telefono, le schede dei clienti dello studio. Da lì l'ho fatta crescere un passo alla volta, sviluppandola insieme a Claude (l'assistente di Anthropic), aggiungendo le funzioni che mi servivano davvero nel lavoro di ogni giorno: ricerca e ordinamento delle schede, backup e importazione, dettatura vocale delle pratiche con l'aiuto dell'intelligenza artificiale, i calcoli utili, il calcolo delle superfici dei locali da planimetria, i documenti di pratica da Mega e dal NAS dello studio, la verifica di conformità e la mappa con i dati della Regione Toscana.</p>
<p>La versione <b>3.64</b> è il punto da cui sono ripartito per le migliorie qui sotto: l'elenco dettagliato delle build comincia da lì.</p>

<h2>Le migliorie, build per build</h2>
<p><b>Fino alla v3.64</b> – schede clienti con tipi di pratica personalizzabili, ricerca e ordinamento, backup su Drive/cloud, dettatura con AI, calcoli utili, ripartizione bolletta elettrica, planimetria con calcolo delle aree. <i>(Non ho il dettaglio build per build di questo periodo.)</i></p>
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
"""

    val GUIDA_INESPERTI: String = """
<h2>Guida per chi comincia</h2>
<p>Questa guida ti accompagna passo per passo. Il menu con i tre puntini in alto a destra nella pagina iniziale è il punto da cui si raggiungono tutte le sezioni.</p>

<h3>1. Le schede dei clienti</h3>
<p>Nella pagina iniziale compili il modulo (nome, comune, tipo di pratica…) e tocchi <b>Salva</b>: la scheda compare nell'elenco. Per <b>cercare</b> usa il campo di ricerca; per cambiare l'ordine tocca <b>Ordina</b>. Per <b>modificare</b> una scheda tieni premuto il dito su di essa; per <b>eliminarla</b> falla scorrere di lato. Toccando una scheda si apre il suo dettaglio.</p>

<h3>2. Il backup</h3>
<p>Dal menu scegli <b>Salva backup su Drive o cloud</b> o <b>Condividi backup</b> per avere una copia delle schede; con <b>Importa backup</b> le rimetti sul telefono (anche su un telefono nuovo). Conviene farlo ogni tanto.</p>

<h3>3. Calcolare le superfici dei locali</h3>
<p>Menu → <b>Aree locali da planimetria</b>. Carica una planimetria (da file, PDF o con una foto), poi:</p>
<p>• <b>Imposta scala</b>: indica due punti di cui conosci la distanza (o lascia che l'app legga la scala dal foglio).<br>• <b>Nuovo locale</b>: tocca gli angoli della stanza uno dopo l'altro e chiudi il locale: l'app calcola la superficie.<br>• <b>Rilevamento automatico</b> (serve la chiave AI nelle Impostazioni): l'app propone i locali, tu li confermi o li correggi.<br>• Con <b>Salva progetto</b> (menu in alto a destra) conservi il lavoro; <b>Apri progetto salvato</b> lo riprende.<br>• Puoi esportare in <b>PDF</b> o <b>DXF</b> e passare alla <b>Perizia unità</b>.</p>
<p>Quando esci e poi rientri nella sezione, l'app ti chiede se vuoi <b>riprendere l'ultimo progetto</b> o cominciarne uno nuovo: il lavoro viene salvato da solo quando esci.</p>

<h3>4. La mappa</h3>
<p>Menu → <b>Mappa e WMS Regione Toscana</b>. Al primo ingresso si apre la mappa; dalle volte successive l'app ti chiede se ripartire dall'ultima visualizzazione o da una mappa nuova.</p>
<p>• Il pulsante <b>☰</b> apre il pannello degli strati (catasto, CTR, ortofoto, tutti i servizi della Regione).<br>• <b>🏛 Istruttoria urbanistico-edilizia</b>: spunta un gruppo (idrogeologici, PAI/PGRA, paesaggistici, archeologici…) e si accendono tutti i suoi strati.<br>• <b>📍 Vincoli nel punto che tocco</b> (menu ⋯): tocca un punto della mappa e restano accesi solo i vincoli che lo interessano, con l'elenco in basso.<br>• <b>⏹ Spegni tutto e comprimi</b> riporta la mappa pulita.<br>• <b>Macchina del tempo</b> (menu ⋯): sfoglia le ortofoto dal 1954 a oggi, o confronta due anni trascinando la barra.<br>• <b>Stampa</b> (menu ⋯) crea un PDF della vista.</p>
<p>I vincoli mostrati sono un aiuto alla consultazione: per l'istruttoria fa fede il dato ufficiale degli enti competenti.</p>

<h3>5. Documenti, pareri e perizie</h3>
<p>Dalla scheda cliente o dal menu puoi collegare una cartella <b>Mega</b> o la cartella del cliente sul <b>NAS dello studio</b>, scegliere i file da far leggere e farti preparare un <b>parere o una relazione</b> in Word. Per la <b>Perizia di stima completa</b> e la <b>Verifica di conformità</b> segui le schermate: ti chiedono i dati un passo alla volta.</p>

<h3>6. Le Impostazioni</h3>
<p>Qui inserisci la <b>chiave AI</b> (necessaria per dettatura e funzioni con l'AI), i dati del <b>NAS</b> e personalizzi i <b>tipi di pratica</b>.</p>

<h3>7. Il tasto «indietro» del telefono</h3>
<p>In ogni sezione ti chiede se tornare alla pagina iniziale, uscire solo dalla sezione o restare. Nella pagina iniziale ti chiede conferma prima di chiudere l'app.</p>
"""

    val GUIDA_ESPERTI: String = """
<h2>Guida per utenti esperti</h2>
<p>Riepilogo operativo, senza passaggi ovvi.</p>

<h3>Navigazione e stato</h3>
<p>• Back: sezione → dialogo (pagina iniziale / esci dalla sezione / resta); home → conferma di uscita (<code>finishAffinity</code>). La freccia della barra torna subito indietro. «Pagina iniziale» usa <code>CLEAR_TOP|SINGLE_TOP</code> su MainActivity.<br>• Planimetria: salvataggio automatico dell'ultimo lavoro in <code>onStop</code> (immagine riscritta solo se cambiata); all'ingresso propone «Riprendi l'ultimo / Nuovo progetto». I progetti nominati restano in «Salva / Apri progetto salvato».<br>• Mappa: stato (centro, zoom, sfondo, strati, opacità, macchina del tempo) in <code>localStorage</code>; all'ingresso «Ultima visualizzazione / Mappa nuova» (la seconda cancella lo stato).</p>

<h3>Schede e backup</h3>
<p>Dettatura con AI (chiave Anthropic personale, cifrata sul telefono). Backup JSON su file/Drive/cloud o condivisione; importazione da qualsiasi provider di documenti. Tipi di pratica e comuni personalizzabili.</p>

<h3>Planimetria</h3>
<p>Scala per calibrazione a due punti, da etichetta o dalle tacche del foglio. Rilevamento automatico: l'AI individua piante e vani, il telefono misura i contorni sui muri (modello «pieni» o «linee», scelto per vano) e propone «Locale da verificare» per gli spazi chiusi non indicati. Modifica vertici con aggiungi/rimuovi/annulla, rinomina, elimina; pressione lunga di 2 s in visualizzazione per correggere un locale. Export PDF e DXF (vista corrente e limiti in intestazione). Limiti noti: canne fumarie e rientranze entro ~45 cm vengono raddrizzate; spazi di altre proprietà vanno scartati a mano.</p>

<h3>Perizia, conformità, documenti</h3>
<p>Perizia unità: superficie commerciale per destinazione (DPR 138/98 all. C / Manuale OMI), quotazioni OMI, rettifica percentuale. Perizia completa in Word con allegati da telefono, NAS o Mega. Verifica di conformità: catasto, titolo edilizio, stato di fatto, atto/visura. Parere AI da Mega (link pubblico di cartella) o NAS (SMB2/3 o WebDAV/HTTPS, sola lettura; fuori studio serve VPN).</p>

<h3>Mappa</h3>
<p>• Sfondi: OpenStreetMap, satellite Esri, Google (con chiave).<br>• Strati: catasto, CTR, ortofoto 1954 → oggi, tutti i WMS del Geoscopio (legende, cartoteca, selezione con scheda e download).<br>• <b>Istruttoria</b>: gruppi idrogeologici, PAI/PGRA, paesaggistici (art. 136, art. 142 lett. a–i, DM 426/2020, UNESCO), archeologici, beni architettonici, aree protette/Natura 2000, strumenti urbanistici vigenti; solo dati correnti. Spunta di gruppo o per singolo strato (stato indeterminato se parziale).<br>• <b>Vincoli nel punto</b>: GetFeatureInfo (±60 m) sui servizi della Regione e <code>identify</code> sul server ArcGIS dell'Autorità di bacino; accende solo i vincoli presenti. Gli usi civici contano solo se il campo PRES_UC è compilato. Il DM 426/2020 non è interrogabile.<br>• <b>PAI/PGRA</b>: PAI dissesti (adottato 28/03/2024, in salvaguardia) e PGRA (aggiornamento 01/12/2025) per il bacino dell'Arno. Altri bacini non inclusi.<br>• <b>Macchina del tempo</b>: tutti i voli (esclusi i falsi colori) in elenco e cursore; confronto a due anni con barra trascinabile.<br>• <b>Spegni tutto e comprimi</b>, <b>Espandi/Comprimi tutto</b> nel pannello ☰. Stampa in PDF in A4.</p>
<p>Le verifiche sui vincoli sono indicative: per l'istruttoria fa fede il dato ufficiale.</p>
"""

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
