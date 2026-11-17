# NVS Lettore CIE per PC — NON ANCORA COLLAUDATO SU UNA CARTA VERA

Programma per Windows che legge la carta d'identità elettronica con un **lettore NFC/contactless USB** (standard PC/SC, per esempio quello che Windows
mostra come «Generic Contactless Card Reader») e copia i dati negli appunti nel formato `nvs-cie/1`. In NVS (desktop o tablet): Anagrafica →
**«📇 Importa da CIE (chip)»** → «Leggi dagli appunti» → anteprima → «Applica».

## Uso
1. Scarica `NVS-Lettore-CIE-PC.zip` da GitHub (Releases → «NVS Lettore CIE PC - costruzione N»), estrailo e apri `NVS Lettore CIE PC\NVS Lettore CIE PC.exe`. Java è già incluso, non serve installare nulla.
2. Scrivi il **CAN** (6 cifre sul fronte della carta), premi «Leggi la carta» e appoggia la CIE **ferma e piatta** al centro del lettore.
3. Compaiono i dati e vengono copiati negli appunti. Il CAN non viene salvato; il PIN della carta non serve.

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
