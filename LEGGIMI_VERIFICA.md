# SchedaCliente v3.75 — pacchetto per la verifica

App Android nativa (Kotlin) per lo studio del Geom. Simone Morbidelli: schede cliente,
planimetrie con calcolo superfici, perizia unità (OMI), documenti Mega con parere AI,
ripartizione bollette, "ruota del caffè" e altro.

## Contenuto del pacchetto

| Percorso | Cosa contiene |
|---|---|
| `SchedaCliente-v3.75-debug.apk` | APK di debug già compilato (installabile) |
| `sorgenti/` | Progetto Gradle completo (senza cartelle `build/` e senza `local.properties`) |
| `sorgenti/storia-git.bundle` | Storia git completa (commit dalla v3.64 "buon punto di partenza" alla v3.75) |
| `anteprime/` | Immagini prodotte dai test (risultati del riconoscimento vani sulle planimetrie catastali) |

## Come compilare e far girare i test

Requisiti: JDK 17, Android SDK (compileSdk 34), Gradle 8.x (il progetto non ha `gradlew`).

```bash
cd sorgenti
echo "sdk.dir=/percorso/android-sdk" > local.properties
gradle testDebugUnitTest      # test (Robolectric 4.13, rendering NATIVE)
gradle assembleDebug          # APK in app/build/outputs/apk/debug/SchedaCliente-v3.75-debug.apk
```

Per recuperare la storia git: `git clone storia-git.bundle schedacliente-git`.

Note:
- `app/schedacliente-debug.keystore` è una keystore di **debug** fissa (password nel `build.gradle`):
  serve perché l'accesso a Google Drive è registrato sull'impronta SHA-1 di questa firma. Non è
  una chiave di produzione.
- Nessuna chiave API è contenuta nei sorgenti: la chiave AI (Anthropic) si inserisce
  nell'app, nelle Impostazioni, ed è salvata cifrata (EncryptedSharedPreferences).
- Modelli AI usati: `claude-haiku-5-5` (piante, scala, affinamenti), `claude-sonnet-5-5`
  (elenco vani della planimetria, parere sui documenti Mega).

## Test presenti (tutti superati alla consegna)

| Classe | Test | Cosa verifica |
|---|---|---|
| AnteprimaGraficheTest | 4 | grafiche ruota/riepilogo FDM |
| CorrezioniRevisioneTest | 6 | ID univoci, guardie doppio salvataggio, centroide |
| ParereMegaTest | 9 | lettura cartelle Mega pubbliche, preparazione documenti, DOCX |
| PeriziaTest | 7 | coefficienti superficie commerciale, parsing OMI |
| RilevatoreScalaBluTest | 4 | scala grafica blu sul bordo delle planimetrie |
| SuperficieViewTest | 3 | vista planimetria, zoom, annulla |
| SegmentazioneTest | 12 | contorni dei vani (sintetici + screenshot/PDF reali) |
| BancoProvaCatastoTest | 2 | 11 piante da 7 planimetrie catastali reali + prova su carta millimetrata |

## Parte più delicata da verificare: riconoscimento automatico dei vani

File principali: `PlanimetriaActivity.kt` (flusso) e `SegmentazionePlanimetria.kt` (analisi immagine).

Flusso ("l'AI dice cosa c'è, il telefono misura dov'è"):
1. AI → riquadri delle piante nel foglio (`InterpretePlanimetria.rilevaPiante`).
2. Ogni riquadro viene allargato sulle zone di disegno (`RilevatoreZoneDisegno`, solo allargamento)
   e ritagliato ad alta risoluzione.
3. AI (Sonnet) → elenco completo dei vani con nome, `punto_interno` e vertici stimati.
4. `AnalizzatoreVani` misura il contorno di ogni vano sul disegno con due modelli dei muri
   e sceglie il migliore vano per vano:
   - **PIENI**: muri = tratti spessi (scritte, croci porte, gradini, reticolo ignorati; finestre e
     soglie dentro lo spessore del muro rimesse come muro; coppie di linee parallele = muro);
   - **LINEE**: muri = tutte le linee, con rimozione delle scritte riconosciute dalla forma.
   Riempimento dal punto interno con chiusura delle porte a raggio crescente (25 cm → 1 m con
   scala nota), controllo d'area rispetto alla stima AI (0,6–1,45), regolarizzazione del contorno
   (denti dei segni convenzionali, spigoli, ortogonalità, sguanci).
5. Se il vano non si chiude (scale, logge aperte, linee tratteggiate): forma AI con i lati
   portati sulle facce dei muri (`agganciaAiMuri`).
6. Passata di completezza: spazi chiusi non indicati → "Locale da verificare N" (solo dentro
   l'ingombro dell'unità e non più grandi del vano maggiore).
7. Carta millimetrata / reticolo regolare nero: rimosso a monte (`togliReticolo`).

Risultato sul banco di prova (con e senza scala): 70 vani su 79 contornati sui muri; i restanti
sono vani scala (gradini), un disimpegno chiuso da linea tratteggiata e un ripostiglio minuscolo
in una scansione rovinata, che passano alla forma AI agganciata ai muri.

Limiti noti: canne fumarie e rientranze entro ~45 cm vengono raddrizzate (incluse nel vano);
spazi chiusi di altre proprietà dentro la pianta (es. "altri sub.") vengono proposti come
"Locale da verificare" e vanno scartati a mano; la parte AI non è coperta dai test automatici
(serve una chiave API e un telefono).

## Altre funzioni recenti (v3.65–v3.75)

- Perizia unità: superficie commerciale per destinazione (DPR 138/98 all. C / Manuale OMI),
  quotazioni OMI dalla consultazione pubblica Agenzia Entrate, rettifica percentuale, valore.
- Modifica planimetria: rinomina locale, annulla ultima modifica vertici, zoom fino a 25×,
  visualizzazione a schermo intero con pressione lunga (2 s) su un locale per correggerlo,
  conferma automatica del locale rilevato dopo 3 s, ritorno alla vista intera.
- Documenti Mega: link pubblico per cliente o dal menu, navigazione cartelle, parere AI in DOCX
  (lingua di correzione it-IT).
