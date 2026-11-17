# NVS Tablet (app Android) — NON ANCORA COLLAUDATA SU UN TABLET REALE

App unica per il tablet di reparto: mostra il sito **https://tablet-nuovavillasilvana.pages.dev/** a tutto schermo, in **modalità chiosco**,
e include il **lettore della CIE via NFC**: in Anagrafica, «Importa da CIE (chip)» mostra il riquadro «Leggi la carta con questo tablet»
(scrivi il CAN, appoggia la carta, anteprima, applica). Nessun copia e incolla.

## Come funziona
- L'app è solo un contenitore: **gli aggiornamenti di NVS restano quelli di sempre** (`PUBBLICA_TUTTO.bat`, poi riaprire l'app). Non serve reinstallare l'APK
  a ogni novità, solo quando cambia l'app stessa (questa cartella).
- Il sito è aperto solo se è quello indicato in `Impostazioni.java`; qualunque altro indirizzo viene rifiutato. Il ponte con la lettura CIE vale solo per quel sito.
- Telecamera (fotografia ospite, lettore codici): il permesso si chiede alla prima apertura. Scelta file/foto: si può scattare o scegliere dalla galleria.
- Stampa: «Stampa» di NVS apre la stampa di Android (servizio di stampa del tablet o «Salva come PDF»).
- Senza rete compare «Nessuna connessione» e riprova da solo; le pagine già salvate dal tablet (copia offline di NVS) continuano a funzionare.

## Modalità chiosco
- All'apertura l'app chiede di **«fissare»** la schermata (blocco app): da quel momento i tasti Home e Indietro di sistema non escono dall'app.
- **Uscita (per il personale autorizzato):** premere **7 volte «volume giù»** entro 8 secondi, scrivere il **codice** (di partenza `4682`, in `Impostazioni.java`: **cambialo** e ricostruisci), «Esci».
- **Chiosco completo** (senza richiesta di conferma e senza uscite dai gesti di sistema): serve impostare il tablet come «dedicato» con un comando da PC (una volta sola).
  Il tablet non deve avere account Google configurati (o va ripristinato): collegarlo al PC con il debug USB attivo e dare
  `adb shell dpm set-device-owner it.nvs.tablet/.AdminReceiver`
  Per togliere la modalità: uscire dall'app col codice e dare `adb shell dpm remove-active-admin it.nvs.tablet/.AdminReceiver`.

## Installazione
1. Su GitHub (archivio «nvs-lettore-cie» → Releases) apri la bozza **«NVS Tablet - costruzione N»** (la più recente), scarica `NVS-Tablet.apk`.
2. Copialo sul tablet e aprilo (consenti «Installa app sconosciute»). Se c'è già una versione con firma diversa, **disinstallala prima** (i dati di NVS stanno sul server, non nell'app).
3. Apri «NVS Tablet», concedi la telecamera, conferma il blocco della schermata.

## Limiti noti (da verificare sul tablet)
- La **ricerca vocale** (`webkitSpeechRecognition`) non funziona nei contenitori web di Android: resta disponibile solo da Chrome.
- Nel chiosco completo, la finestra di scelta file/fotocamera di altre app potrebbe essere bloccata: va provata. Se succede, si può usare la modalità con «blocco schermata».
- L'APK viene firmato con una chiave di prova diversa a ogni costruzione, finché non si imposta una chiave fissa nei segreti dell'archivio GitHub
  (`NVS_KS_B64`, `NVS_KS_PASS`): in quel caso gli aggiornamenti dell'app si installano uno sopra l'altro senza disinstallare.
- Come per il lettore CIE: ogni lettura richiede il **CAN** (6 cifre) che non viene salvato; il PIN non serve.

## Costruire l'APK
Automatico con GitHub (flusso «Tablet APK», parte a ogni modifica di questa cartella o da «Run workflow»). Oppure Android Studio: apri questa cartella → Build → Build APK(s).
Licenze di terzi: JMRTD/SCUBA (LGPL 3.0), BouncyCastle (MIT), JP2 for Android (BSD a 2 clausole), AndroidX (Apache 2.0).
