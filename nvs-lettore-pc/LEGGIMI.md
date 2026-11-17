# NVS Lettore CIE per PC — NON ANCORA COLLAUDATO SU UNA CARTA VERA

Programma per Windows che legge la carta d'identità elettronica con un **lettore NFC/contactless USB** (standard PC/SC, per esempio quello che Windows
mostra come «Generic Contactless Card Reader») e copia i dati negli appunti nel formato `nvs-cie/1`. In NVS (desktop o tablet): Anagrafica →
**«📇 Importa da CIE (chip)»** → «Leggi dagli appunti» → anteprima → «Applica».

## Uso
1. Scarica `NVS-Lettore-CIE-PC.zip` da GitHub (Releases → «NVS Lettore CIE PC - costruzione N»), estrailo e apri `NVS Lettore CIE PC\NVS Lettore CIE PC.exe`. Java è già incluso, non serve installare nulla.
2. Scrivi il **CAN** (6 cifre sul fronte della carta), premi «Leggi la carta» e appoggia la CIE **ferma e piatta** al centro del lettore.
3. Compaiono i dati e vengono copiati negli appunti. Il CAN non viene salvato; il PIN della carta non serve.

## Collegamento diretto con NVS (senza appunti)
Con il programma **aperto** sul PC, in NVS (desktop o tablet usato sul PC) la finestra «Importa da CIE (chip)» mostra il riquadro viola
**«Leggi la carta con il lettore USB di questo computer»**: si scrive il CAN dentro NVS, si preme «Leggi la carta», si appoggia la CIE e compare l'anteprima.
- Il programma ascolta **solo su questo computer** (127.0.0.1, porta 8765) e risponde solo ai siti autorizzati. Il sito del tablet è già nell'elenco; per ogni altro sito
  (per esempio il sito del desktop) alla prima volta compare una finestra «richiesta di accesso» con «Consenti sempre / Solo questa volta / Rifiuta». Consenti solo l'indirizzo di NVS.
  I siti consentiti «sempre» sono nel file `%APPDATA%\NVS Lettore CIE\siti_autorizzati.txt` (si può togliere una riga per revocare).
- Chrome/Edge possono chiedere «Consentire a questo sito di collegarsi a dispositivi della rete locale?»: rispondere **Consenti**.
- Se il riquadro non compare, il programma non è aperto (o è aperto due volte): resta il metodo con gli appunti.

## Cosa legge
Come l'app Android: cognome, nome, codice fiscale, sesso, data di nascita, cittadinanza, numero e scadenza del documento, luogo di nascita, residenza,
data di rilascio, fotografia (JPEG2000 convertita in JPEG). Residenza, comune di rilascio e simili si controllano nell'anteprima di NVS.

## Se non funziona
- «Nessun lettore trovato»: collega il lettore; il servizio «Smart card» di Windows deve essere attivo. Per controllare il lettore c'è `_strumenti\prova_lettore_nfc`.
- Se compare un errore lungo, copialo (è il testo con «Scambi con la carta») e mandalo a chi segue il programma.
- Windows può avvisare che il programma non è firmato («Windows ha protetto il PC»): «Maggiori informazioni» → «Esegui comunque».

## Costruzione
Automatica con GitHub (flusso «Lettore PC», solo su Windows). Sorgenti Java 17 + Gradle: cartella `src`, `build.gradle`.
Licenze di terzi: JMRTD/SCUBA (LGPL 3.0), BouncyCastle (MIT), JAI ImageIO (decodifica JPEG2000), commons-io (Apache 2.0), org.json (JSON License), OpenJDK (GPLv2 + Classpath Exception).
