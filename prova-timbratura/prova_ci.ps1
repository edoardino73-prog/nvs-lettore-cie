# Prova automatica su Windows (GitHub): attivita' pianificate e alimentazione + comportamento del salvaschermo con una finestra vera del browser
$ErrorActionPreference = 'Stop'
$cartella = Split-Path $PSCommandPath
$ok = 0; $ko = 0
function Esito($c, $m) { if ($c) { $script:ok++; Write-Output "PASS $m" } else { $script:ko++; Write-Output "FAIL $m" } }
function Valore($sub, $imp) { $o = (& powercfg /query SCHEME_CURRENT $sub $imp) | Out-String; $m = [regex]::Matches($o, '0x[0-9a-fA-F]{8}'); return [Convert]::ToInt64($m[$m.Count - 2].Value.Substring(2), 16) }

Write-Output '== sintassi =='
foreach ($f in 'timbratura_salvaschermo.ps1', 'risveglio_timbratura.ps1') {
  $err = $null; [void][System.Management.Automation.Language.Parser]::ParseFile((Join-Path $cartella $f), [ref]$null, [ref]$err)
  Esito ($err.Count -eq 0) ("sintassi $f " + ($err | ForEach-Object { $_.Message }))
}
$p = & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $cartella 'timbratura_salvaschermo.ps1') -Prova | Out-String
Esito ($p -match 'PROVA: IdleMs=') ('funzioni di Windows compilate: ' + $p.Trim())

Write-Output '== risveglio e alimentazione =='
$risv = Join-Path $cartella 'risveglio_timbratura.ps1'
$tmp = Join-Path $env:TEMP 'nvsprova'; New-Item -ItemType Directory -Force $tmp | Out-Null
$avvio = Join-Path $tmp 'AVVIA_TIMBRATURA_SALVASCHERMO.bat'; Set-Content $avvio '@echo off'
$st = Join-Path $tmp 'Startup'; New-Item -ItemType Directory -Force $st | Out-Null
$prima = @{ s = Valore 'SUB_VIDEO' 'VIDEOIDLE'; b = Valore 'SUB_SLEEP' 'STANDBYIDLE'; r = Valore 'SUB_SLEEP' 'RTCWAKE' }
Write-Output ("prima: schermo=" + $prima.s + " standby=" + $prima.b + " rtcwake=" + $prima.r)
& powershell -NoProfile -ExecutionPolicy Bypass -File $risv -Azione Installa -Avvio $avvio -Startup $st -Utente ($env:USERDOMAIN + '\' + $env:USERNAME) -OraGiorno '04:00' -OraNotte '23:00'
Esito ($LASTEXITCODE -eq 0) 'Installa termina senza errori'
$tg = Get-ScheduledTask -TaskName 'NVS Timbratura - risveglio' -ErrorAction SilentlyContinue
$tn = Get-ScheduledTask -TaskName 'NVS Timbratura - standby notte' -ErrorAction SilentlyContinue
Esito ($tg -ne $null -and $tg.Settings.WakeToRun) "attivita' del giorno creata, con riattivazione del computer"
Esito ($tg -ne $null -and $tg.Triggers[0].StartBoundary -like '*T04:00:00*') ("attivita' del giorno alle 04:00 (" + $tg.Triggers[0].StartBoundary + ')')
Esito ($tn -ne $null -and $tn.Triggers[0].StartBoundary -like '*T23:00:00*') "attivita' della notte alle 23:00"
Esito (Test-Path (Join-Path $st 'NVS Timbratura salvaschermo.lnk')) 'collegamento di avvio automatico creato'
Esito ((Valore 'SUB_SLEEP' 'RTCWAKE') -eq 1) 'timer di riattivazione abilitati'
& powershell -NoProfile -ExecutionPolicy Bypass -File $risv -Azione Giorno -Avvio $avvio
Esito (((Valore 'SUB_VIDEO' 'VIDEOIDLE') -eq 0) -and ((Valore 'SUB_SLEEP' 'STANDBYIDLE') -eq 0)) 'Giorno: schermo e standby mai spenti'
& powershell -NoProfile -ExecutionPolicy Bypass -File $risv -Azione Notte
Esito (((Valore 'SUB_VIDEO' 'VIDEOIDLE') -eq 60) -and ((Valore 'SUB_SLEEP' 'STANDBYIDLE') -eq 60)) 'Notte: schermo e standby dopo 1 minuto'
& powershell -NoProfile -ExecutionPolicy Bypass -File $risv -Azione Rimuovi -Startup $st
$tg2 = Get-ScheduledTask -TaskName 'NVS Timbratura - risveglio' -ErrorAction SilentlyContinue
Esito ($tg2 -eq $null) "Rimuovi: attivita' tolte"
Esito (((Valore 'SUB_VIDEO' 'VIDEOIDLE') -eq $prima.s) -and ((Valore 'SUB_SLEEP' 'STANDBYIDLE') -eq $prima.b) -and ((Valore 'SUB_SLEEP' 'RTCWAKE') -eq $prima.r)) 'Rimuovi: impostazioni di prima rimesse'
Esito (-not (Test-Path (Join-Path $st 'NVS Timbratura salvaschermo.lnk'))) 'collegamento tolto'

Write-Output '== salvaschermo con finestra vera =='
Add-Type @"
using System; using System.Runtime.InteropServices;
[StructLayout(LayoutKind.Sequential)] public struct NvsTRect { public int L; public int T; public int R; public int B; }
public class NvsT {
  [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr h);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out NvsTRect r);
  [DllImport("user32.dll")] public static extern void mouse_event(uint f, int dx, int dy, uint d, UIntPtr e);
  [DllImport("user32.dll")] public static extern int GetSystemMetrics(int i);
}
"@
$pagina = Join-Path $tmp 'pagina.html'
Set-Content $pagina '<title>Timbratura NVS [salvaschermo]</title><body style="background:#000"><script>setTimeout(function(){document.title="Timbratura NVS [badge]";setTimeout(function(){document.title="Timbratura NVS [salvaschermo]"},8000)},52000)</script>'
$url = 'file:///' + ($pagina -replace '\\', '/')
$ps = Join-Path $cartella 'timbratura_salvaschermo.ps1'
$prof = Join-Path $env:LOCALAPPDATA 'NVS_timbratura_finestrella'
$sp = Start-Process powershell -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$ps`"", '-Url', "`"$url`"", '-Profilo', "`"$prof`"", '-MinutiFermo', '0.5') -PassThru -WindowStyle Hidden
function Finestra { Get-Process -ErrorAction SilentlyContinue | Where-Object { ($_.ProcessName -match 'chrome|msedge') -and $_.MainWindowTitle -like '*Timbratura NVS*' } | Select-Object -First 1 }
function Attendi($cond, $sec) { $t = Get-Date; while (((Get-Date) - $t).TotalSeconds -lt $sec) { if (& $cond) { return $true }; Start-Sleep -Milliseconds 500 }; return $false }
function Intera($w) { $r = New-Object NvsTRect; [void][NvsT]::GetWindowRect($w.MainWindowHandle, [ref]$r); return (($r.R - $r.L) -ge ([NvsT]::GetSystemMetrics(0) - 20)) -and (($r.B - $r.T) -ge ([NvsT]::GetSystemMetrics(1) - 20)) }
function Muovi { [NvsT]::mouse_event(1, 40, 25, 0, [UIntPtr]::Zero); Start-Sleep -Milliseconds 150; [NvsT]::mouse_event(1, -40, -25, 0, [UIntPtr]::Zero) }
$t0 = Get-Date
$comparsa = Attendi { Finestra } 40
Esito $comparsa 'la finestra del browser compare'
if ($comparsa) {
  Start-Sleep -Seconds 6; $w = Finestra
  Esito (-not [NvsT]::IsIconic($w.MainWindowHandle)) 'all avvio: finestra non ridotta a icona'
  Esito (Intera $w) 'all avvio: finestra a schermo intero'
  Muovi
  Esito (Attendi { $w = Finestra; $w -and [NvsT]::IsIconic($w.MainWindowHandle) } 8) 'movimento del mouse: finestra ridotta a icona'
  Esito (Attendi { $w = Finestra; $w -and -not [NvsT]::IsIconic($w.MainWindowHandle) } 50) 'computer fermo: la finestra torna'
  $w = Finestra; Esito (Intera $w) 'ritorno a schermo intero'
  Muovi
  Esito (Attendi { $w = Finestra; $w -and [NvsT]::IsIconic($w.MainWindowHandle) } 8) 'secondo movimento: di nuovo a icona'
  # passaggio di un badge (la pagina cambia il titolo in [badge] dopo ~52 s dall'apertura): con la finestra a icona deve comparire e poi ridursi
  $vistoBadge = Attendi { $w = Finestra; $w -and $w.MainWindowTitle -like '*[[]badge]*' } 60
  Write-Output ('titolo badge visto: ' + $vistoBadge)
  if ($vistoBadge) {
    Esito (Attendi { $w = Finestra; $w -and -not [NvsT]::IsIconic($w.MainWindowHandle) } 8) 'badge: la finestra compare'
    Esito (Attendi { $w = Finestra; $w -and [NvsT]::IsIconic($w.MainWindowHandle) } 16) 'finito il badge: di nuovo a icona'
  } else { Esito $false 'il titolo [badge] non e comparso' }
}
New-Item -ItemType File -Force (Join-Path $env:LOCALAPPDATA 'NVS_timbratura_STOP.flag') | Out-Null
Start-Sleep -Seconds 3
Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -like '*NVS_timbratura_finestrella*' -and $_.Name -match 'chrome|msedge' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
if (Test-Path (Join-Path $env:LOCALAPPDATA 'NVS_timbratura_log.txt')) { Write-Output '--- log ---'; Get-Content (Join-Path $env:LOCALAPPDATA 'NVS_timbratura_log.txt') | Select-Object -Last 15 }
Write-Output "RISULTATO: $ok ok, $ko falliti"
if ($ko -gt 0) { exit 1 }
