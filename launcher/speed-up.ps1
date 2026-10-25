# Speed-ups for the family TV that keep casting, Wi-Fi, the Play Store and the Assistant working:
#   - turns off the telemetry, ad and demo packages in examples/tcl-g08-google-tv/debloat.txt (disable, not uninstall)
#   - Android's window and transition animations at 0.5x (the launcher's own animations are unaffected)
# Every package is checked first: never one that owns a saved Wi-Fi network, never one on the keep list.
#
# Usage:  .\launcher\speed-up.ps1 -Serial 192.168.1.50:41234          (apply)
#         .\launcher\speed-up.ps1 -Serial 192.168.1.50:41234 -Undo    (everything back on, animations 1x)
param(
    [string]$Serial = $env:ANDROID_SERIAL,
    [string]$List = "$PSScriptRoot\..\examples\tcl-g08-google-tv\debloat.txt",
    [switch]$Undo
)
$ErrorActionPreference = 'Stop'
$Keep = @('com.google.android.apps.mediashell', 'com.google.android.tungsten.setupwraith',
          'com.google.android.apps.tv.launcherx', 'com.google.android.tv.remote.service', 'com.google.android.katniss',
          'com.google.android.gms', 'com.google.android.gsf', 'com.android.vending', 'com.gathin.tvhome',
          'com.tcl.ttvs', 'com.tcl.tv', 'com.tcl.tvinput', 'com.tcl.systemserver', 'com.android.tv.settings')

$Adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $Adb) { $Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" }
function Tv([string]$Command) {
    $sel = @()
    if ($Serial) { $sel += @('-s', $Serial) }
    return ((& $Adb @sel shell $Command 2>&1) | Out-String).TrimEnd()
}

$pkgs = Get-Content $List | ForEach-Object { $_.Trim() } | Where-Object { $_ -and -not $_.StartsWith('#') }
$installed = Tv 'pm list packages'
$owners = Tv "dumpsys wifi | grep -o 'cname=[^ ]*' | sort -u"

foreach ($p in $pkgs) {
    if ($installed -notmatch [regex]::Escape("package:$p")) { Write-Host "  skip $p (not installed)"; continue }
    if ($Undo) {
        Write-Host "  $p : $(Tv "pm enable $p")"
        continue
    }
    if ($Keep -contains $p) { Write-Host "  KEEP $p (needed)"; continue }
    if ($owners -match [regex]::Escape($p)) { Write-Host "  KEEP $p (owns a saved Wi-Fi network)"; continue }
    # One command per call: chained pm commands can stop silently after the first.
    Write-Host "  $p : $(Tv "pm disable-user --user 0 $p")"
}
$scale = if ($Undo) { '1.0' } else { '0.5' }
foreach ($k in 'window_animation_scale', 'transition_animation_scale', 'animator_duration_scale') {
    Tv "settings put global $k $scale" | Out-Null
}
Write-Host "System animations: ${scale}x"
Write-Host "Disabled now:"
Write-Host (Tv 'pm list packages -d')
