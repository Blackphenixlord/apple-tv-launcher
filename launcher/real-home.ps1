# Make this launcher the TV's real Home, replacing Google TV's launcher, while keeping Chromecast casting.
#
# Why it takes three steps on Google TV 14 (checked on a TCL G08):
#   - Google's launcher (launcherx) declares Home at priority 2; while it is enabled Android always opens it,
#     whatever the default Home is set to.
#   - With launcherx off, the setup wizard's RecoveryActivity (priority 1) takes Home the same way, and on some
#     TVs crash-loops the system.
#   - Turning off the whole setup wizard frees Home but breaks casting: the Cast receiver's device certificate
#     (CastInfoProvider) lives inside the wizard.
# So this turns off only the wizard's RecoveryActivity screen (the wizard and its Cast provider stay on), then
# makes this launcher the default Home, then turns off launcherx, and checks where Home ends up. If any step is
# refused or Home is not this launcher at the end, everything it changed is put back.
#
# -WholeWizard turns off the whole setup wizard instead of its one screen, for TVs that refuse the screen alone
# (the TCL G08 does). Casting from phones' Cast buttons then stops (Chromecast's certificate is in the wizard);
# YouTube's own casting and receivers such as Kodi (DLNA, AirPlay) still work. Wi-Fi is checked first.
#
# Usage:  .\launcher\real-home.ps1 -Serial 192.168.1.50:41234                (make it the real Home)
#         .\launcher\real-home.ps1 -Serial 192.168.1.50:41234 -WholeWizard   (when the screen alone is refused)
#         .\launcher\real-home.ps1 -Serial 192.168.1.50:41234 -Undo          (Google TV's launcher back as Home)
# Run install-family.ps1 first. Back up first (install-family.ps1 does). Profiles inside Google's launcher
# (its home screen, Continue Watching, its profile switcher) are unavailable while it is off.
param(
    [string]$Serial = $env:ANDROID_SERIAL,
    [switch]$WholeWizard,
    [switch]$Undo
)
$ErrorActionPreference = 'Stop'
$Pkg = 'com.gathin.tvhome'
$Ours = "$Pkg/.MainActivity"
$Google = 'com.google.android.apps.tv.launcherx'
$Wizard = 'com.google.android.tungsten.setupwraith'
$Recovery = "$Wizard/.RecoveryActivity"

$Adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $Adb) { $Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" }
if (-not (Test-Path $Adb)) { throw 'adb not found: install Google platform-tools (INSTALL-FAMILY.md, step 1)' }

function Tv([string]$Command) {
    $sel = @()
    if ($Serial) { $sel += @('-s', $Serial) }
    return ((& $Adb @sel shell $Command 2>&1) | Out-String).TrimEnd()
}

$homeQuery = 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -n 1'
$googleHome = "$Google/.home.HomeActivity"

function Restore-Google {
    Tv "pm enable $Wizard" | Out-Null
    Tv "pm enable $Google" | Out-Null
    Tv "pm enable $Recovery" | Out-Null
    Tv "cmd package set-home-activity --user 0 $googleHome" | Out-Null
    Tv 'am start -a android.intent.action.MAIN -c android.intent.category.HOME' | Out-Null
}

if ($Undo) {
    Restore-Google
    Write-Host "Home: $(Tv $homeQuery)"
    Write-Host "Google TV's launcher is Home again; the setup wizard (and casting) is back on."
    return
}

if ((Tv "pm list packages $Pkg") -notmatch [regex]::Escape("package:$Pkg")) { throw 'Install the launcher first: install-family.ps1' }
# Android deletes the Wi-Fi networks an app saved when that app is turned off: never touch an owner of one.
$owners = Tv "dumpsys wifi | grep -o 'cname=[^ ]*' | sort -u"
if ($owners -match [regex]::Escape($Google)) { throw "Google's launcher owns a saved Wi-Fi network; forget and re-join it in Settings first." }
if ($WholeWizard -and $owners -match [regex]::Escape($Wizard)) { throw "The setup wizard owns a saved Wi-Fi network; turning it off would delete it. Forget and re-join the network in Settings first." }

# This launcher up first, so the TV is never without a working Home while the others go away.
Tv "am start -W -n $Ours" | Out-Null
if ($WholeWizard) {
    Write-Host "1/3 Turning off the setup wizard (Wi-Fi checked: it owns none)..."
    $r = Tv "pm disable-user --user 0 $Wizard"
} else {
    Write-Host "1/3 Turning off only the setup wizard's Recovery screen (the wizard and Chromecast stay on)..."
    $r = Tv "pm disable-user --user 0 $Recovery"
}
Write-Host "    $r"
if ($r -notmatch 'new state') {
    throw "Android refused ($r). Nothing was changed. If it refused the screen alone, -WholeWizard turns off the whole wizard (casting from Cast buttons stops)."
}

Write-Host '2/3 Making this launcher the default Home...'
Write-Host "    $(Tv "cmd package set-home-activity --user 0 $Ours")"

Write-Host "3/3 Turning off Google TV's launcher..."
Write-Host "    $(Tv "pm disable-user --user 0 $Google")"
Tv "cmd package set-home-activity --user 0 $Ours" | Out-Null   # Android drops the preference when the Home set changes

$homeNow = Tv $homeQuery
if ($homeNow -notlike "$Pkg/*") {
    Restore-Google
    throw "Home resolved to '$homeNow', not this launcher, so everything was put back as it was."
}
Tv 'am start -a android.intent.action.MAIN -c android.intent.category.HOME' | Out-Null
if ($WholeWizard) { Write-Host "Done: Home is $homeNow. Cast buttons in Netflix and similar apps stop; YouTube casting and Kodi still work." }
else { Write-Host "Done: Home is $homeNow. Casting should still work (the setup wizard is on); try casting from a phone." }
Write-Host "Undo any time:  .\launcher\real-home.ps1 -Serial $Serial -Undo"
