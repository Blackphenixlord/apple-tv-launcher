# One command back to stock Google TV after install-family.ps1 (see INSTALL-FAMILY.md, "Undo").
#
# For every Android user (Google TV profile): takes this launcher's services out of the accessibility
# settings (or puts that profile's backed-up values back exactly, with -Backup), then uninstalls this
# launcher, which is an ordinary app, not a system package. No system package is touched: install-family.ps1
# never disabled or uninstalled one, so there is nothing to re-enable. Google TV's home stayed the default
# the whole time; the script only checks that it still is and opens it.
#
# Usage:  .\launcher\undo-family.ps1 -Serial 192.168.1.50:41234 [-Backup .\family-backup-20261025-101500]
param(
    [string]$Serial = $env:ANDROID_SERIAL,
    [string]$Backup = ''
)
$ErrorActionPreference = 'Stop'
$Pkg = 'com.gathin.tvhome'
$GoogleHome = 'com.google.android.apps.tv.launcherx'

$Adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $Adb) {
    foreach ($c in @("$PSScriptRoot\..\platform-tools\adb.exe", "$PSScriptRoot\platform-tools\adb.exe",
                     "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe")) {
        if (Test-Path $c) { $Adb = (Resolve-Path $c).Path; break }
    }
}
if (-not $Adb) { throw 'adb not found: install Google platform-tools (INSTALL-FAMILY.md, step 1)' }

function Tv([string]$Command) {
    $sel = @()
    if ($Serial) { $sel += @('-s', $Serial) }
    return ((& $Adb @sel shell $Command 2>&1) | Out-String).TrimEnd()
}

$state = if ($Serial) { & $Adb -s $Serial get-state 2>&1 } else { & $Adb get-state 2>&1 }
if ("$state" -notmatch 'device') { throw "No TV on adb ($state). Connect first (INSTALL-FAMILY.md, step 2)." }

$saved = @{}
if ($Backup) {
    $file = Join-Path $Backup 'restore.json'
    if (-not (Test-Path $file)) { throw "$file not found" }
    foreach ($r in (Get-Content -Raw $file | ConvertFrom-Json)) { $saved[[int]$r.user] = $r }
}

$list = Tv 'pm list users'
$ids = @([regex]::Matches($list, 'UserInfo\{(\d+):') | ForEach-Object { [int]$_.Groups[1].Value })
if ($ids.Count -eq 0) { throw "pm list users returned no users:`n$list" }

foreach ($id in $ids) {
    Write-Host "--- profile $id"
    $current = Tv "settings --user $id get secure enabled_accessibility_services"
    if ($saved.ContainsKey($id)) {
        $want = "$($saved[$id].enabled_accessibility_services)".Trim()
        $a11y = "$($saved[$id].accessibility_enabled)".Trim()
    } else {
        # No backup: keep every other service, drop only this launcher's.
        $keep = @("$current" -split ':' | Where-Object { $_ -and $_ -ne 'null' -and $_ -notlike "$Pkg/*" })
        $want = if ($keep.Count) { $keep -join ':' } else { 'null' }
        $a11y = if ($keep.Count) { '1' } else { '0' }
    }
    if ($want -eq 'null' -or -not $want) {
        Tv "settings --user $id delete secure enabled_accessibility_services" | Out-Null
    } else {
        Tv "settings --user $id put secure enabled_accessibility_services '$want'" | Out-Null
    }
    if ($a11y -eq 'null' -or -not $a11y) { Tv "settings --user $id delete secure accessibility_enabled" | Out-Null }
    else { Tv "settings --user $id put secure accessibility_enabled $a11y" | Out-Null }
    Write-Host "  accessibility services: $(Tv "settings --user $id get secure enabled_accessibility_services")"
}

# Home back to Google TV first (install-family.ps1 -DefaultHome or real-home.ps1 made this launcher the default),
# so the TV is never left with no Home while this launcher goes away. real-home.ps1 turned Google's launcher and the
# setup wizard's Recovery screen off; both come back on (a no-op when they already are).
Tv "pm enable $GoogleHome" | Out-Null
Tv 'pm enable com.google.android.tungsten.setupwraith/.RecoveryActivity' | Out-Null
$google =Tv "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME $GoogleHome | tail -n 1"
foreach ($id in $ids) {
    if ((Tv "am get-started-user-state $id") -notmatch 'RUNNING') { continue }
    Write-Host (Tv "cmd package set-home-activity --user $id $google")
}

# Uninstall for every user at once (this launcher only; it is not a system app).
$out = Tv "pm uninstall $Pkg"
Write-Host "Uninstall $Pkg : $out"

$homeNow = Tv 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -n 1'
Write-Host "Default Home: $homeNow"
if ($homeNow -notlike "$GoogleHome/*") {
    Write-Warning "Home resolves to '$homeNow', not Google TV's launcher. Pick Google TV on the TV if it asks, or run: adb shell cmd package set-home-activity $google"
}
Tv 'input keyevent KEYCODE_HOME' | Out-Null
Write-Host 'Back to stock Google TV. Switch off Wireless debugging in Developer options when you are done.'
