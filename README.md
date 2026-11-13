# STUDIO-TECNICO: licenze e privacy delle app

Questo contenitore **non contiene il codice** delle app (quello sta in `studio-tecnico-codice`).
Contiene i file che le app e Google Play leggono su internet.

## I rami
| Ramo | A cosa serve |
|---|---|
| **`prova`** | **Quello vero.** Le app leggono questo ramo. |
| `main` | Vecchio, con un solo file di licenze di SchedaCliente meno aggiornato. Non lo legge Studio Tecnico. Non è verificato se lo leggono vecchie versioni di SchedaCliente: per questo non va cancellato. |

## I file (ramo `prova`)
| File | Chi lo legge |
|---|---|
| `licenze/licenze-studiotecnico.json` | App **Studio Tecnico**, a ogni apertura |
| `licenze/licenze.json` | App **SchedaCliente** |
| `privacy/informativa-privacy.html` | Pagina pubblica dell'informativa sulla privacy (anche per Google Play): https://simonemorbidelli-crypto.github.io/STUDIO-TECNICO/privacy/informativa-privacy.html |

## Prolungare la prova a un utente (Studio Tecnico)
In `licenze/licenze-studiotecnico.json` si aggiunge il codice del telefono con la data:
`"scadenze": { "CODICE": "2026-12-31" }`
Per un accesso senza scadenza si mette il codice nell'elenco `"sbloccati"`.

## Attenzione
**Non rinominare né cancellare** il ramo `prova`, questo contenitore o i file sopra: le app già installate li cercano a questo indirizzo e smetterebbero di controllare le licenze.
I campi che iniziano con `_` sono appunti: le app li ignorano.
