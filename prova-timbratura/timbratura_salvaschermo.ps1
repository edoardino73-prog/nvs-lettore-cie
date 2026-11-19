# NVS - Timbratura come SALVASCHERMO.
# Apre la timbratura a schermo intero in una finestra del browser (profilo dedicato). Dopo N minuti senza che nessuno tocchi mouse o tastiera
# la finestra e' a schermo intero; al primo movimento del mouse (o tasto) si riduce a ICONA nella barra delle applicazioni.
# Al passaggio di un badge con la finestra a icona, compare a schermo intero per qualche secondo con l'esito e poi si riduce di nuovo.
# Parte dal file AVVIA_TIMBRATURA_SALVASCHERMO.bat. Si ferma solo con CHIUDI_TIMBRATURA.bat.
# Lo stato lo dice la pagina nel titolo della finestra: "[salvaschermo]" oppure "[badge]" (per qualche secondo dopo un passaggio).
param(
  [string]$Url = '',
  [string]$Profilo = '',
  [double]$MinutiFermo = 10,    # minuti senza tastiera/mouse dopo i quali la finestra torna a schermo intero
  [switch]$Prova             # solo controllo: compila le funzioni di Windows, scrive i valori e termina
)
if (-not $Profilo) { $Profilo = Join-Path $env:LOCALAPPDATA 'NVS_timbratura_finestrella' }
if ($MinutiFermo -lt 0.05) { $MinutiFermo = 0.05 }
Add-Type -AssemblyName System.Windows.Forms
Add-Type @"
using System;
using System.Runtime.InteropServices;
[StructLayout(LayoutKind.Sequential)] public struct NvsSavRect { public int L; public int T; public int R; public int B; }
[StructLayout(LayoutKind.Sequential)] public struct NvsSavLii { public uint cbSize; public uint dwTime; }
public class NvsSav {
  [DllImport("user32.dll")] public static extern bool GetLastInputInfo(ref NvsSavLii p);
  [DllImport("kernel32.dll")] public static extern uint GetTickCount();
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int c);
  [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr h);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr h, IntPtr after, int x, int y, int cx, int cy, uint flags);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out NvsSavRect r);
  [DllImport("user32.dll")] public static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);
  // istante (in tick) dell'ultimo movimento di mouse o pressione di tasto: cambia solo quando qualcuno usa il computer
  public static uint LastTick() { NvsSavLii l = new NvsSavLii(); l.cbSize = (uint)Marshal.SizeOf(typeof(NvsSavLii)); if (!GetLastInputInfo(ref l)) return 0; return l.dwTime; }
  public static uint IdleMs() { NvsSavLii l = new NvsSavLii(); l.cbSize = (uint)Marshal.SizeOf(typeof(NvsSavLii)); if (!GetLastInputInfo(ref l)) return 0; return unchecked(GetTickCount() - l.dwTime); }
}
"@
$schermo = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
$schW = $schermo.Width; $schH = $schermo.Height
if ($Prova) { Write-Output ("PROVA: IdleMs=" + [NvsSav]::IdleMs() + " LastTick=" + [NvsSav]::LastTick() + " Schermo=" + $schW + "x" + $schH); exit 0 }

$logFile = Join-Path $env:LOCALAPPDATA 'NVS_timbratura_log.txt'
function Scrivi-Log($m) { try { if ((Test-Path $logFile) -and (Get-Item $logFile).Length -gt 200KB) { Remove-Item $logFile -ErrorAction SilentlyContinue }; Add-Content -Path $logFile -Value ((Get-Date -Format 'yyyy-MM-dd HH:mm:ss') + '  [salvaschermo] ' + $m) -ErrorAction SilentlyContinue } catch {} }
Scrivi-Log ("avvio script - Url=" + ($Url -replace '#pin=\d+', '#pin=***') + " - Profilo=" + $Profilo + " - MinutiFermo=" + $MinutiFermo)
# una sola copia alla volta (una sola copia alla volta)
$mutex = New-Object System.Threading.Mutex($false, 'NVS_TIMBRATURA_SOPRA')
if (-not $mutex.WaitOne(0)) { Scrivi-Log "ESCO: c'e' gia' un'altra copia attiva (salvaschermo): usa CHIUDI_TIMBRATURA.bat e riprova"; exit }

$TOPMOST = [IntPtr](-1); $NOTOPMOST = [IntPtr](-2)
$profiloMask = '*NVS_timbratura_finestrella*'
$stop = Join-Path $env:LOCALAPPDATA 'NVS_timbratura_STOP.flag'
Remove-Item $stop -ErrorAction SilentlyContinue

function Trova-Browser {
  $c = @("$env:ProgramFiles\Google\Chrome\Application\chrome.exe", "${env:ProgramFiles(x86)}\Google\Chrome\Application\chrome.exe",
         "${env:ProgramFiles(x86)}\Microsoft\Edge\Application\msedge.exe", "$env:ProgramFiles\Microsoft\Edge\Application\msedge.exe",
         "$env:LOCALAPPDATA\Google\Chrome\Application\chrome.exe")
  foreach ($p in $c) { if ($p -and (Test-Path $p)) { return $p } }
  return $null
}
function Avvia-Finestra {
  if (-not $Url) { return }
  $exe = Trova-Browser
  if (-not $exe) { Scrivi-Log "ERRORE: non trovo ne' Google Chrome ne' Microsoft Edge nelle cartelle di installazione"; return }
  Scrivi-Log ("apro la finestra a schermo intero con " + $exe)
  $a = @("--user-data-dir=`"$Profilo`"", '--kiosk', "--app=$Url", '--no-first-run', '--disable-session-crashed-bubble', '--noerrdialogs', '--autoplay-policy=no-user-gesture-required')
  try { Start-Process -FilePath $exe -ArgumentList $a } catch { Scrivi-Log ("ERRORE nell'apertura del browser: " + $_.Exception.Message) }
}
function Cerca-Browser {   # il processo principale del browser del profilo dedicato (o $null)
  try {
    $procs = Get-CimInstance Win32_Process -Filter "Name='chrome.exe' OR Name='msedge.exe'" -ErrorAction Stop |
             Where-Object { $_.CommandLine -like $profiloMask -and $_.CommandLine -notlike '*--type=*' }
    foreach ($p in $procs) { $gp = Get-Process -Id $p.ProcessId -ErrorAction SilentlyContinue; if ($gp) { return $gp } }
    return $null
  } catch {
    return (Get-Process -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowHandle -ne 0 -and ($_.MainWindowTitle -like '*Timbratura NVS*') } | Select-Object -First 1)
  }
}
function Rettangolo($h) { $r = New-Object NvsSavRect; [void][NvsSav]::GetWindowRect($h, [ref]$r); return $r }
function Mostra($h) {      # finestra a schermo intero, davanti a tutto (il tocco sul tasto Alt serve a Windows per consentire il passaggio in primo piano)
  try {
    [void][NvsSav]::ShowWindow($h, 9)                                                  # SW_RESTORE: toglie l'icona
    [void][NvsSav]::SetWindowPos($h, $TOPMOST, 0, 0, 0, 0, 0x0003)                     # sopra le altre, senza muoverla
    [NvsSav]::keybd_event(0x12, 0, 0, [UIntPtr]::Zero); [NvsSav]::keybd_event(0x12, 0, 2, [UIntPtr]::Zero)
    [void][NvsSav]::SetForegroundWindow($h)
    Start-Sleep -Milliseconds 400
    $r = Rettangolo $h                                                                 # se dopo il ripristino non copre tutto lo schermo, la si porta a tutto schermo
    if (($r.R - $r.L) -lt ($schW - 20) -or ($r.B - $r.T) -lt ($schH - 20)) { [void][NvsSav]::SetWindowPos($h, $TOPMOST, 0, 0, $schW, $schH, 0x0040) }
  } catch { Scrivi-Log ("Mostra: " + $_.Exception.Message) }
  $script:tickBase = [NvsSav]::LastTick()                                              # il tocco di Alt non conta come movimento dell'utente
}
function Riduci($h) {      # finestra ridotta a icona nella barra delle applicazioni
  try {
    [void][NvsSav]::SetWindowPos($h, $NOTOPMOST, 0, 0, 0, 0, 0x0013)
    [void][NvsSav]::ShowWindow($h, 6)                                                  # SW_MINIMIZE
  } catch { Scrivi-Log ("Riduci: " + $_.Exception.Message) }
  $script:tickBase = [NvsSav]::LastTick()
}

$riaperture = @()          # orari delle riaperture: se sono troppe (il browser non parte) ci si ferma
$gp = Cerca-Browser
if (-not $gp) { Avvia-Finestra }
$visto = $false; $assenze = 0; $senzaFin = 0; $inizio = Get-Date
$conta = 0; $hCorr = [IntPtr]::Zero
$stato = 'saver'           # saver = schermo intero | icona = ridotta a icona | badge = mostrata per il passaggio di un badge
$tickBase = [NvsSav]::LastTick()
while ($true) {
  if (Test-Path $stop) { break }                                 # chiusura voluta (CHIUDI_TIMBRATURA.bat)
  $conta++
  if ($gp) { $gp = Get-Process -Id $gp.Id -ErrorAction SilentlyContinue }
  if (-not $gp -or ($conta % 12) -eq 0) { $gp = Cerca-Browser }
  $trovata = [bool]$gp; $h = [IntPtr]::Zero; $visibile = $false
  if ($gp -and $gp.MainWindowHandle -ne 0 -and [NvsSav]::IsWindowVisible($gp.MainWindowHandle)) { $h = $gp.MainWindowHandle; $visibile = $true }
  if ($visibile) {
    $visto = $true; $senzaFin = 0
    if ($h -ne $hCorr) { $hCorr = $h; $stato = 'saver'; $tickBase = [NvsSav]::LastTick() }   # finestra nuova (aperta o riaperta): parte a schermo intero
    $badge = ([string]$gp.MainWindowTitle).Contains('[badge]')
    $iconica = [NvsSav]::IsIconic($h)
    $tick = [NvsSav]::LastTick(); $mossa = ($tick -ne $tickBase); if ($mossa) { $tickBase = $tick }
    if ($stato -eq 'saver') {
      if ($iconica) { $stato = 'icona' }                                              # ridotta a mano
      elseif ($mossa) { Riduci $h; $stato = 'icona' }                                 # mouse o tasto: a icona
    } elseif ($stato -eq 'badge') {
      if ($iconica) { $stato = 'icona' }
      elseif ($mossa -or -not $badge) { Riduci $h; $stato = 'icona' }                 # finito il tempo del badge (o l'utente si muove): di nuovo a icona
    } else {   # icona
      if (-not $iconica) { $stato = 'saver'; $tickBase = [NvsSav]::LastTick() }       # riaperta a mano dalla barra: e' a schermo intero
      elseif ($badge) { Mostra $h; $stato = 'badge' }                                 # passaggio di un badge: si vede l'esito
      elseif ([NvsSav]::IdleMs() -ge ($MinutiFermo * 60000)) { Mostra $h; $stato = 'saver' }   # computer fermo: salvaschermo
    }
  } else { $senzaFin++ }
  if ($trovata) { $assenze = 0 } else { $assenze++ }
  # la finestra non c'e' piu': il browser e' terminato, oppure resta attivo in sottofondo ma senza finestra visibile
  if ($assenze -ge 5 -or ($visto -and $senzaFin -ge 8)) {
    Start-Sleep -Seconds 2
    if (Test-Path $stop) { break }
    $ora = Get-Date; $riaperture = @($riaperture | Where-Object { ($ora - $_).TotalMinutes -lt 2 })
    if ($riaperture.Count -ge 6) { Scrivi-Log 'mi fermo: troppe riaperture ravvicinate'; break }
    $riaperture += $ora; Avvia-Finestra; $assenze = 0; $senzaFin = 0; $visto = $false; $inizio = Get-Date; $gp = $null
  }
  if (-not $visto -and -not $trovata -and ((Get-Date) - $inizio).TotalMinutes -gt 10) { Scrivi-Log "mi fermo: in 10 minuti non e' comparsa nessuna finestra"; break }
  Start-Sleep -Milliseconds 400
}
Remove-Item $stop -ErrorAction SilentlyContinue
$mutex.ReleaseMutex()
