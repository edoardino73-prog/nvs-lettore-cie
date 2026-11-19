# NVS - Postazione di timbratura: risveglio del computer alle 4 del mattino e impostazioni di alimentazione.
#   Installa : salva le impostazioni attuali, abilita i "timer di riattivazione", crea l'avvio automatico del salvaschermo
#              e due attivita' pianificate (GIORNO alle 4:00 e NOTTE all'ora di standby).
#   Giorno   : (alle 4:00, riattivando il PC) schermo e standby mai spenti, e riapre la finestra di timbratura a schermo intero.
#   Notte    : (all'ora impostata) il computer va in standby dopo 1 minuto senza uso, lo schermo si spegne dopo 1 minuto.
#   Rimuovi  : toglie attivita' e avvio automatico e rimette le impostazioni di alimentazione che c'erano prima.
# Parte dai file INSTALLA_SALVASCHERMO_E_RISVEGLIO.bat e RIMUOVI_SALVASCHERMO_E_RISVEGLIO.bat.
param(
  [Parameter(Mandatory = $true)][ValidateSet('Installa', 'Rimuovi', 'Giorno', 'Notte')][string]$Azione,
  [string]$Avvio = '',               # percorso di AVVIA_TIMBRATURA_SALVASCHERMO.bat
  [string]$Startup = '',             # cartella "Esecuzione automatica" dell'utente
  [string]$Utente = '',              # utente Windows della postazione (DOMINIO\nome)
  [string]$OraGiorno = '04:00',
  [string]$OraNotte = '23:00'        # vuoto = non crea l'attivita' della notte (lo standby resta quello di Windows)
)
$ErrorActionPreference = 'Stop'
$nomeG = 'NVS Timbratura - risveglio'; $nomeN = 'NVS Timbratura - standby notte'
$orig = Join-Path $env:ProgramData 'NVS_risveglio_impostazioni_originali.json'
$log = Join-Path $env:ProgramData 'NVS_risveglio_log.txt'
function Scrivi-Log($m) { try { Add-Content -Path $log -Value ((Get-Date -Format 'yyyy-MM-dd HH:mm:ss') + '  ' + $m) } catch {} }
$IMPOSTAZIONI = @(   # nome, sottogruppo, impostazione
  @('schermo', 'SUB_VIDEO', 'VIDEOIDLE'), @('standby', 'SUB_SLEEP', 'STANDBYIDLE'), @('ibernazione', 'SUB_SLEEP', 'HIBERNATEIDLE'), @('timer_riattivazione', 'SUB_SLEEP', 'RTCWAKE'))
function Leggi-Valore($sub, $imp) {      # valore (in corrente alternata) dell'impostazione del piano attivo; i secondi per i tempi
  $out = (& powercfg /query SCHEME_CURRENT $sub $imp) | Out-String
  $m = [regex]::Matches($out, '0x[0-9a-fA-F]{8}')
  if ($m.Count -lt 2) { throw "powercfg: non riesco a leggere $sub $imp" }
  return [Convert]::ToInt64($m[$m.Count - 2].Value.Substring(2), 16)   # penultimo = corrente alternata (l'ultimo e' a batteria)
}
function Imposta-Valore($sub, $imp, $v) { & powercfg /setacvalueindex SCHEME_CURRENT $sub $imp ([string]$v) | Out-Null; if ($LASTEXITCODE -ne 0) { throw "powercfg: impostazione $sub $imp non riuscita" } }
function Applica() { & powercfg /setactive SCHEME_CURRENT | Out-Null }

switch ($Azione) {
  'Installa' {
    if (-not ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { throw 'Servono i diritti di amministratore (conferma la richiesta di Windows).' }
    if (-not $Avvio -or -not (Test-Path $Avvio)) { throw "Non trovo AVVIA_TIMBRATURA_SALVASCHERMO.bat ($Avvio)." }
    if (-not $Utente) { $Utente = $env:USERDOMAIN + '\' + $env:USERNAME }
    if (-not (Test-Path $orig)) {          # le impostazioni attuali si salvano UNA volta sola
      $o = @{}; foreach ($i in $IMPOSTAZIONI) { $o[$i[0]] = Leggi-Valore $i[1] $i[2] }
      ($o | ConvertTo-Json) | Set-Content -Path $orig -Encoding UTF8
      Write-Output ('Impostazioni attuali salvate in ' + $orig)
    }
    Imposta-Valore 'SUB_SLEEP' 'RTCWAKE' 1; Applica     # timer di riattivazione consentiti
    # avvio automatico del salvaschermo all'accesso a Windows 
    if ($Startup) {
      $vecchio = Join-Path $Startup 'NVS Timbratura.lnk'; if (Test-Path $vecchio) { Remove-Item $vecchio -Force }
      $l = Join-Path $Startup 'NVS Timbratura salvaschermo.lnk'
      $s = (New-Object -ComObject WScript.Shell).CreateShortcut($l); $s.TargetPath = $Avvio; $s.WorkingDirectory = (Split-Path $Avvio); $s.WindowStyle = 7; $s.Description = 'NVS timbratura salvaschermo'; $s.Save()
      Write-Output ('Avvio automatico creato: ' + $l)
    }
    $ps1 = $PSCommandPath
    $imp = New-ScheduledTaskSettingsSet -WakeToRun -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable -ExecutionTimeLimit (New-TimeSpan -Minutes 10)
    $pr = New-ScheduledTaskPrincipal -UserId $Utente -LogonType Interactive -RunLevel Limited
    $argG = '-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File "' + $ps1 + '" -Azione Giorno -Avvio "' + $Avvio + '"'
    $azG = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument $argG
    Register-ScheduledTask -TaskName $nomeG -Action $azG -Trigger (New-ScheduledTaskTrigger -Daily -At ([datetime]$OraGiorno)) -Settings $imp -Principal $pr -Force | Out-Null
    Write-Output ("Attivita' creata: $nomeG (ogni giorno alle $OraGiorno, riattiva il computer)")
    if ($OraNotte) {
      $argN = '-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File "' + $ps1 + '" -Azione Notte'
      $azN = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument $argN
      Register-ScheduledTask -TaskName $nomeN -Action $azN -Trigger (New-ScheduledTaskTrigger -Daily -At ([datetime]$OraNotte)) -Settings (New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable) -Principal $pr -Force | Out-Null
      Write-Output ("Attivita' creata: $nomeN (ogni giorno alle $OraNotte, standby dopo 1 minuto di inattivita')")
    }
    Scrivi-Log ("installato: giorno $OraGiorno, notte '$OraNotte', utente $Utente")
  }
  'Giorno' {
    Imposta-Valore 'SUB_VIDEO' 'VIDEOIDLE' 0; Imposta-Valore 'SUB_SLEEP' 'STANDBYIDLE' 0; Imposta-Valore 'SUB_SLEEP' 'HIBERNATEIDLE' 0; Applica
    Scrivi-Log 'giorno: schermo e standby mai spenti'
    # la finestra di timbratura viene riaperta pulita: se il programma e' attivo basta chiudere il browser del suo profilo (lo riapre da solo a schermo intero), altrimenti si avvia
    $m = $null; $attivo = [System.Threading.Mutex]::TryOpenExisting('NVS_TIMBRATURA_SOPRA', [ref]$m)
    if ($attivo) {
      try { $m.Dispose() } catch {}
      Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -like '*NVS_timbratura_finestrella*' -and $_.Name -match 'chrome|msedge' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
      Scrivi-Log 'giorno: finestra di timbratura riaperta'
    } elseif ($Avvio -and (Test-Path $Avvio)) {
      Start-Process -FilePath $Avvio -WindowStyle Minimized
      Scrivi-Log 'giorno: avviato il salvaschermo'
    }
  }
  'Notte' {
    Imposta-Valore 'SUB_VIDEO' 'VIDEOIDLE' 60; Imposta-Valore 'SUB_SLEEP' 'STANDBYIDLE' 60; Applica
    Scrivi-Log 'notte: standby dopo 1 minuto di inattivita'
  }
  'Rimuovi' {
    foreach ($n in @($nomeG, $nomeN)) { if (Get-ScheduledTask -TaskName $n -ErrorAction SilentlyContinue) { Unregister-ScheduledTask -TaskName $n -Confirm:$false; Write-Output ("Attivita' tolta: $n") } }
    if ($Startup) { foreach ($f in @('NVS Timbratura salvaschermo.lnk')) { $l = Join-Path $Startup $f; if (Test-Path $l) { Remove-Item $l -Force; Write-Output ('Avvio automatico tolto: ' + $l) } } }
    if (Test-Path $orig) {
      $o = Get-Content $orig -Raw | ConvertFrom-Json
      foreach ($i in $IMPOSTAZIONI) { Imposta-Valore $i[1] $i[2] ([int64]$o.($i[0])) }
      Applica; Remove-Item $orig -Force
      Write-Output 'Impostazioni di alimentazione rimesse come prima.'
    }
    Scrivi-Log 'rimosso'
  }
}
