# NVS Lettore CIE (app Android) — NON ANCORA COLLAUDATA

Piccola app per tablet/telefono Android con NFC. Legge il chip della Carta d'Identità Elettronica (CIE 3.0), mostra i dati e li copia
negli appunti nel formato `nvs-cie/1`. In NVS, nella scheda di anagrafica, il pulsante **«📇 Importa da CIE (chip)»** li riceve,
mostra un'anteprima con i controlli e, solo dopo la conferma, riempie i campi.

## Stato onesto
- Il codice **non è stato compilato né provato su una carta vera** (l'ambiente in cui è stato scritto non può raggiungere gli
  archivi di Android e non ha una CIE). È scritto sullo schema standard dei lettori di documenti elettronici (libreria JMRTD, protocollo PACE con il CAN).
- La parte NVS (importazione, anteprima, controlli) invece è provata.
- Alla prima compilazione può servire una piccola correzione (nome di una funzione della libreria o versione di una libreria): un
  qualsiasi sviluppatore Android la sistema in pochi minuti. Se Gradle dice che una versione non esiste, usare l'ultima disponibile su mvnrepository.com.
- **Da verificare con una carta vera:** (1) dove si trova il CAN sulla tua CIE; (2) quali campi il chip dà con il CAN (cognome, nome, data di
  nascita, sesso, numero e scadenza del documento sì; il codice fiscale solo se la CIE lo mette nei campi facoltativi della riga a lettura ottica, altrimenti
  si prende dalla tessera sanitaria come adesso); (3) che non serva altro per la tua versione di carta.

## Che cosa legge e che cosa no
- Legge: cognome, nome, data di nascita, sesso, cittadinanza, numero e scadenza del documento, codice fiscale (se presente).
- **Non usa il PIN** della carta e non lo chiede mai. Il **CAN** (6 cifre) si scrive a ogni lettura e non viene salvato.
- Non legge la fotografia né le impronte. Residenza, stanza, referenti e MMG non sono nel chip: si compilano in NVS a mano.
- I dati restano solo sullo schermo e negli appunti. La schermata non finisce nelle «app recenti» (protezione FLAG_SECURE).

## Come ottenere l'APK senza installare nulla (consigliato): GitHub
1. Crea un account GitHub e un nuovo archivio **privato** (es. «nvs-lettore-cie»).
2. Carica tutto il contenuto di questa cartella, **compresa la cartella nascosta `.github`** (trascinala nella pagina «Add file → Upload files»).
3. Apri la scheda **Actions** → «Costruisci APK» → **Run workflow**. Dopo 3-5 minuti compare l'allegato **NVS-Lettore-CIE-apk**.
4. Scaricalo, decomprimilo, copia `app-debug.apk` sul tablet e aprilo (consenti «Installa app sconosciute» per quell'app).

## Oppure con Android Studio
Apri questa cartella con Android Studio → attendi la sincronizzazione → **Build → Build Bundle(s)/APK(s) → Build APK(s)**.

## Uso
1. Sul tablet apri «NVS Lettore CIE», scrivi il CAN della carta.
2. Appoggia la CIE sul retro del tablet (dove c'è l'antenna NFC) e tienila ferma finché compare il testo.
3. Apri NVS → Anagrafica → **Importa da CIE (chip)** → «Leggi dagli appunti» → controlla l'anteprima → «Applica» → **Salva**.

## Privacy
I dati della CIE sono dati personali: aggiungi una riga nell'informativa/consenso dell'ospite o del rappresentante legale
(lettura del documento di identità elettronico per l'anagrafica). Non fotografare né conservare il CAN insieme alla carta.

## Formato di scambio `nvs-cie/1` (utilizzabile anche da altri gestionali)
```json
{
  "formato": "nvs-cie/1",
  "letto_il": "2026-11-17T09:40:00",
  "cognome": "BIANCHI", "nome": "GIUSEPPE",
  "codice_fiscale": "BNCGPP38C12L188A",
  "data_nascita": "1938-03-12", "sesso": "M", "cittadinanza": "ITA",
  "documento": { "tipo": "IDENT", "numero": "CA12345AB", "scadenza": "2034-03-12", "stato_emittente": "ITA" },
  "mrz": { "personal_number": "", "optional2": "" }
}
```
Licenze: JMRTD e SCUBA sono LGPL, BouncyCastle è MIT.
