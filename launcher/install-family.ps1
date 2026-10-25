# Family edition install for Google TV with profiles (see INSTALL-FAMILY.md). Plain adb, no Android SDK:
# platform-tools is enough, and the APK can come straight from the GitHub Actions artifact.
#
# What it does, for EVERY Android user on the TV (on some builds each Google TV profile is one; on the TCL
# G08 the profiles are Google accounts inside user 0, and user 10 is an unused leftover):
#   - backs up the package lists, settings and current Home first (family-backup-<time>\)
#   - installs the APK (pm install-existing --user N for each user)
#   - enables HomeKeyService only, appended to that user's accessibility services
#   - writes family.json (the profile names for "Who's watching?") for each running user
# What it never does: disable, hide or uninstall any package, change the default Home, or start a user.
# launcherx and the setup wizard stay enabled and launcherx stays the default Home, so Google TV profiles
# keep working.
#
# Usage:  .\launcher\install-family.ps1 -Serial 192.168.1.50:41234 [-Apk app-release.apk]
#         [-Profiles 'Riley|#BF360C','Family|#5C6BC0','Sam|#0288D1','Alex|#EC407A']
#         [-ProfileSwitcher com.google.android.apps.tv.launcherx/.SomeActivity]  (after INSTALL-FAMILY.md step 6)
#         [-SkipBackup]
param(
    [string]$Serial = $env:ANDROID_SERIAL,
    [string]$Apk = '',
    [string[]]$Profiles = @(),
    [string]$ProfileSwitcher = '',
    [switch]$SkipBackup
)
$ErrorActionPreference = 'Stop'
$Pkg = 'com.gathin.tvhome'
$Service = "$Pkg/.HomeKeyService"
$GoogleHome = 'com.google.android.apps.tv.launcherx'
$Palette = @('#FF5E3A', '#34C759', '#0A84FF', '#FFB000', '#BF5AF2', '#FF375F')

function Find-Adb {
    $onPath = Get-Command adb -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }
    foreach ($c in @("$PSScriptRoot\..\platform-tools\adb.exe", "$PSScriptRoot\platform-tools\adb.exe",
                     "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe", "$env:ANDROID_HOME\platform-tools\adb.exe")) {
        if ($c -and (Test-Path $c)) { return (Resolve-Path $c).Path }
    }
    throw 'adb not found: install Google platform-tools (INSTALL-FAMILY.md, step 1) and put it on PATH'
}
$Adb = Find-Adb

function Tv([string]$Command) {
    # One shell command on the TV; returns its output as one string. Single quotes only inside $Command:
    # Windows PowerShell 5.1 drops embedded double quotes from native arguments.
    $sel = @()
    if ($Serial) { $sel += @('-s', $Serial) }
    $out = & $Adb @sel shell $Command 2>&1
    return ($out | Out-String).TrimEnd()
}

function Get-Users {
    # "UserInfo{10:Mom:410} running" -> id 10, name Mom
    $list = Tv 'pm list users'
    $users = @()
    foreach ($m in [regex]::Matches($list, 'UserInfo\{(\d+):([^:}]*):([0-9a-fA-F]+)\}')) {
        $users += [pscustomobject]@{ Id = [int]$m.Groups[1].Value; Name = $m.Groups[2].Value.Trim(); Flags = $m.Groups[3].Value }
    }
    if ($users.Count -eq 0) { throw "pm list users returned no users:`n$list" }
    return $users
}

# ---- connected? ----------------------------------------------------------------------------------------
$state = if ($Serial) { & $Adb -s $Serial get-state 2>&1 } else { & $Adb get-state 2>&1 }
if ("$state" -notmatch 'device') { throw "No TV on adb ($state). Pair and connect first (INSTALL-FAMILY.md, step 2), then pass -Serial <ip:port>." }
Write-Host "TV: $(Tv 'getprop ro.product.model') / Android $(Tv 'getprop ro.build.version.release')"

if (-not $Apk) {
    foreach ($c in @("$PSScriptRoot\app\build\outputs\apk\release\app-release.apk", "$PSScriptRoot\app-release.apk", '.\app-release.apk')) {
        if (Test-Path $c) { $Apk = $c; break }
    }
}
if (-not $Apk -or -not (Test-Path $Apk)) { throw 'APK not found: pass -Apk <path to app-release.apk> (from the GitHub Actions artifact)' }
$Apk = (Resolve-Path $Apk).Path

$users = Get-Users
Write-Host ("Profiles (Android users): " + (($users | ForEach-Object { "$($_.Id)=$($_.Name)" }) -join ', '))

# ---- back up first -----------------------------------------------------------------------------------
$disabledBefore = Tv 'pm list packages -d'
if (-not $SkipBackup) {
    $dir = Join-Path (Get-Location) ("family-backup-" + (Get-Date -Format 'yyyyMMdd-HHmmss'))
    New-Item -ItemType Directory -Force $dir | Out-Null
    Tv 'pm list packages -f' | Out-File -Encoding utf8 "$dir\packages.txt"
    $disabledBefore | Out-File -Encoding utf8 "$dir\packages-disabled.txt"
    Tv 'pm list packages -u' | Out-File -Encoding utf8 "$dir\packages-all-incl-uninstalled.txt"
    Tv 'getprop' | Out-File -Encoding utf8 "$dir\getprop.txt"
    Tv 'pm list users' | Out-File -Encoding utf8 "$dir\users.txt"
    Tv "dumpsys package $GoogleHome" | Out-File -Encoding utf8 "$dir\launcherx-dumpsys.txt"
    $restore = @()
    foreach ($u in $users) {
        foreach ($ns in @('secure', 'global', 'system')) {
            Tv "settings --user $($u.Id) list $ns" | Out-File -Encoding utf8 "$dir\settings-user$($u.Id)-$ns.txt"
        }
        $restore += [pscustomobject]@{
            user = $u.Id
            name = $u.Name
            enabled_accessibility_services = Tv "settings --user $($u.Id) get secure enabled_accessibility_services"
            accessibility_enabled = Tv "settings --user $($u.Id) get secure accessibility_enabled"
            home = Tv "cmd package resolve-activity --user $($u.Id) --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -n 1"
        }
    }
    $restore | ConvertTo-Json | Out-File -Encoding utf8 "$dir\restore.json"
    Write-Host "Backup: $dir  (undo-family.ps1 -Backup '$dir' puts the settings back exactly)"
}

# ---- install for every profile -------------------------------------------------------------------------
Write-Host 'Uploading the APK...'
$pushArgs = @(); if ($Serial) { $pushArgs += @('-s', $Serial) }
& $Adb @pushArgs push $Apk /data/local/tmp/tvhome.apk | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'adb push failed' }
# pm over the shell, not adb install: on Android 14 adb install can wait for an on-screen confirmation.
$out = Tv 'pm install -r /data/local/tmp/tvhome.apk'
Write-Host $out
if ($out -notmatch 'Success') { throw 'pm install failed; nothing else was changed' }
Tv 'rm -f /data/local/tmp/tvhome.apk' | Out-Null

# family.json: the profile names "Who's watching?" shows, the same file for every profile.
$entries = @()
$i = 0
foreach ($u in $users) {
    $name = if ($u.Name) { $u.Name } else { "Profile $($u.Id)" }
    $entries += "$($u.Id)=$($name -replace '[|]', '/')|$($Palette[$i % $Palette.Count])"
    $i++
}
# Google TV profiles that are accounts inside one Android user (the TCL G08: every account in user 0) are not
# in pm list users at all: -Profiles names them, as Google TV's chooser shows them.
if ($Profiles.Count -gt 0) { $entries = $Profiles }
elseif ($users.Count -eq 1) { Write-Warning 'Only one Android user: if Google TV has several profiles here, pass -Profiles with their names (INSTALL-FAMILY.md, step 4).' }
$family = @{ family_profiles = @($entries) }
if ($ProfileSwitcher) { $family.profile_switcher = $ProfileSwitcher }
$json = Join-Path $env:TEMP 'tvhome-family.json'
[IO.File]::WriteAllText($json, ($family | ConvertTo-Json), (New-Object System.Text.UTF8Encoding $false))
& $Adb @pushArgs push $json /data/local/tmp/tvhome-family.json | Out-Null

foreach ($u in $users) {
    $id = $u.Id
    Write-Host "--- profile $id ($($u.Name))"
    # Installed for this user (pm install covers all users on most builds; this makes sure).
    Write-Host (Tv "pm install-existing --user $id $Pkg")
    # HomeKeyService, appended without dropping anything already enabled for this user.
    $current = Tv "settings --user $id get secure enabled_accessibility_services"
    if ($current -match [regex]::Escape($Service)) {
        Write-Host '  HomeKeyService already enabled'
    } else {
        $new = if (-not $current -or $current -eq 'null') { $Service } else { "${current}:$Service" }
        Tv "settings --user $id put secure enabled_accessibility_services '$new'" | Out-Null
        Write-Host '  HomeKeyService enabled'
    }
    Tv "settings --user $id put secure accessibility_enabled 1" | Out-Null
    # The Wi-Fi pill's network name needs location on Android 10+; harmless if refused.
    Tv "pm grant --user $id $Pkg android.permission.ACCESS_FINE_LOCATION" | Out-Null
    # TCL firmware (TclAppBoot) refuses to start an app's services in the background, the accessibility
    # service included, unless the app has TCL's own AUTO_START op: Settings > Apps > Auto-start, per app.
    # Without it HomeKeyService stays "enabled" but never binds. Other TVs have no such op; the error is ignored.
    $auto = Tv "appops set --user $id $Pkg AUTO_START allow"
    if ($auto -notmatch 'Unknown|Error') { Write-Host '  auto-start allowed (TCL)' }
    # family.json through the launcher's ThemeProvider (only the adb shell may write it). A user that is not
    # running cannot take it, and is not started for this; re-run once it has been used.
    if ((Tv "am get-started-user-state $id") -notmatch 'RUNNING') {
        Write-Host "  not running: family.json skipped (re-run with -SkipBackup after using this profile)"
        continue
    }
    $res = Tv "content write --user $id --uri content://$Pkg.theme/family.json < /data/local/tmp/tvhome-family.json"
    if ($res -match 'Exception|Error') { Write-Warning "  family.json not written for user $id ($res); its picker shows Android's name only" }
    else { Write-Host '  family.json written' }
}
Tv 'rm -f /data/local/tmp/tvhome-family.json' | Out-Null
Tv "cmd package compile -m speed -f $Pkg" | Out-Null

# ---- verify ------------------------------------------------------------------------------------------
$homeNow = Tv 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -n 1'
Write-Host "Default Home (should still be Google TV's): $homeNow"
$disabledAfter = Tv 'pm list packages -d'
if ($disabledAfter -ne $disabledBefore) { Write-Warning 'The disabled-package list changed during the install. This script never disables anything; check what else ran.' }
else { Write-Host 'No package was disabled.' }
$bound = Tv 'dumpsys accessibility | grep -c HomeKeyService'
Write-Host "HomeKeyService entries in dumpsys accessibility (current profile): $bound"
Write-Host 'Done. Press Home on the remote: this launcher should open. Press it twice quickly for Google TV home.'
