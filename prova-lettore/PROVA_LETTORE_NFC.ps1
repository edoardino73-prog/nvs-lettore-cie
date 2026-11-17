# Prova del lettore NFC per la CIE. Non scrive nulla, non cambia impostazioni, non invia dati.
# Mostra a schermo un rapporto, lo copia negli appunti e lo salva in "risultato_prova_lettore.txt" (stessa cartella).
$ErrorActionPreference = 'Stop'
$cartella = Split-Path -Parent $MyInvocation.MyCommand.Path
Add-Type -TypeDefinition (Get-Content -Raw -Encoding UTF8 (Join-Path $cartella 'prova_lettore.cs')) -Language CSharp
[ProvaLettore]::Esegui()
$rapporto = [ProvaLettore]::Rapporto
$rapporto += "`r`n6) Dispositivi USB di tipo lettore/tastiera presenti:`r`n"
try {
  $d = Get-PnpDevice -PresentOnly -ErrorAction Stop | Where-Object { $_.Class -in 'SmartCardReader','HIDClass','Keyboard' -and $_.InstanceId -like 'USB*' }
  foreach ($x in $d) { $rapporto += "   - [" + $x.Class + "] " + $x.FriendlyName + "  " + $x.InstanceId + "`r`n" }
} catch { $rapporto += "   (elenco non disponibile)`r`n" }
Write-Host ""
Write-Host $rapporto.Substring($rapporto.IndexOf("6) Dispositivi"))
$rapporto | Out-File -Encoding UTF8 (Join-Path $cartella 'risultato_prova_lettore.txt')
try { Set-Clipboard -Value $rapporto; Write-Host "Rapporto copiato negli appunti: incollalo nella chat." } catch { }
