# Family edition: install on a Google TV with profiles (Windows)

This puts the Apple TV launcher **in front of** Google TV instead of replacing it. Google TV's own home
(`com.google.android.apps.tv.launcherx`) holds the profiles, the profile switcher, per-profile
recommendations and Continue Watching, so it stays **enabled** and stays the **default Home**. The setup
wizard (`com.google.android.tungsten.setupwraith`) stays enabled too. Nothing is disabled or uninstalled.

How Home reaches this launcher:

- **HomeKeyService** (an accessibility service in the launcher) sees the remote's Home button and opens this
  launcher. **Press Home twice quickly** (or hold it) for Google TV's real home.
- When Google TV's home comes up on its own (after boot, after a profile switch, when an app or the Assistant
  goes Home), HomeKeyService brings this launcher back in front. Google's home stays put when you asked for it
  (double press, or "Who's watching?").
- A `BOOT_COMPLETED` receiver does the same at boot. Android only lets a background app open a screen when
  its accessibility service is bound, so both depend on HomeKeyService being on **for that profile**.

**Or make it the real Home (`-DefaultHome`, used on the family TV).** Covering Google's home means Google's
home shows for a moment first, at startup and on every Home press the accessibility service does not catch.
With `-DefaultHome` the installer makes this launcher the TV's default Home (`cmd package set-home-activity`):
Home and startup go straight to it, nothing flashes first, and TCL's startup block does not matter because
Android itself opens Home. Google TV's launcher stays installed and enabled: **double-press Home** opens it,
"Who's watching?" still opens its profile chooser, and after a pick this launcher comes back. If a Google TV
update ever takes Home back, HomeKeyService covers it as above until the installer is run again.
`undo-family.ps1` gives Home back to Google TV before uninstalling.

Everything else stays Google's: Play Store, your Google account, Chromecast, Google Assistant and voice, Google
Home controls, the Google TV phone remote, the Settings/Input/app buttons on the remote.

Written for: TCL Google TV "Smart TV Pro" (board G08, MediaTek tcl9618), Android 14, build
`tcl9618-user 14 UTT2.250416.001`, 2.4 GB RAM. Other Google TV 12–14 sets should work the same way.

All commands are **Windows PowerShell**, run from the folder you cloned the repo into
(`apple-tv-launcher`). Replace `192.168.1.50` and the ports with what your TV shows.

---

## 1. What you need

1. **Google platform-tools** (adb), no Android SDK needed:

   ```powershell
   Invoke-WebRequest https://dl.google.com/android/repository/platform-tools-latest-windows.zip -OutFile platform-tools.zip
   Expand-Archive platform-tools.zip -DestinationPath . -Force
   $env:Path = "$PWD\platform-tools;$env:Path"
   adb version
   ```

2. **The APK**, built by GitHub Actions on every push: on GitHub open **Actions → APK →** the newest run on
   `family-edition` (or `main` once merged) **→ Artifacts → apple-tv-launcher-family-apk**. Unzip it next to
   the repo. With the GitHub CLI:

   ```powershell
   gh run download --repo Blackphenixlord/apple-tv-launcher --name apple-tv-launcher-family-apk --dir apk
   $apk = (Get-ChildItem apk\*.apk | Select-Object -First 1).FullName
   ```

   Tagged versions (`v1.3-family`, ...) also attach the APK to a GitHub Release.

## 2. Connect over Wireless debugging (pair + connect)

On the TV: **Settings → System → About →** press OK on **Android TV OS build** 7 times. Back to **System →
Developer options →** turn on **Wireless debugging** (TCL keeps it under Developer options; "USB debugging"
alone is not enough). Keep the TV on the same Wi-Fi as the PC.

Open **Wireless debugging → Pair device with pairing code**. The TV shows an IP:port and a 6-digit code:

```powershell
adb pair 192.168.1.50:37215      # the PAIRING port; type the 6-digit code when asked
```

Back on the **Wireless debugging** screen, note the IP address & port (a different port), then:

```powershell
adb connect 192.168.1.50:41234   # the CONNECT port from the main Wireless debugging screen
adb devices                      # should list 192.168.1.50:41234  device
$tv = '192.168.1.50:41234'
```

The connect port changes whenever Wireless debugging is turned off or the Wi-Fi drops; pairing is remembered.

## 3. Back up first

`install-family.ps1` makes a full backup on its own (step 4). To take one by hand:

```powershell
$bk = "family-backup-$(Get-Date -Format yyyyMMdd-HHmmss)"; New-Item -ItemType Directory $bk | Out-Null
adb -s $tv shell pm list users                         > $bk\users.txt
adb -s $tv shell pm list packages -f                   > $bk\packages.txt
adb -s $tv shell pm list packages -d                   > $bk\packages-disabled.txt
adb -s $tv shell getprop                               > $bk\getprop.txt
adb -s $tv shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME > $bk\home.txt
foreach ($u in (adb -s $tv shell pm list users | Select-String 'UserInfo\{(\d+):' | ForEach-Object { $_.Matches[0].Groups[1].Value })) {
    foreach ($ns in 'secure','global','system') { adb -s $tv shell settings --user $u list $ns > "$bk\settings-user$u-$ns.txt" }
}
```

## 4. Install for every profile and enable HomeKeyService (one command)

**On this TV (checked 2026-10-25), Google TV profiles are Google accounts, not Android users.** `pm list users`
shows `0:Owner` (every Google account on the TV) and `10:new_user`, an Android 12-era
`com.android.tv.profile` user with no accounts, unused for ~2 years. Installing in user 0 covers every
profile; the script also installs into user 10 but does not start it. Name the profiles as Google TV's
"Choose an account" screen shows them, with matching colours (example names):

```powershell
.\launcher\install-family.ps1 -Serial $tv -Apk $apk -DefaultHome -Profiles 'Riley|#BF360C','Family|#5C6BC0','Sam|#0288D1','Alex|#EC407A'
```

On a TV where each profile is its own Android user, leave `-Profiles` out: the names come from `pm list users`.

It prints the profiles it found, writes `family-backup-<time>\` (package lists, disabled list, getprop, every
setting of every profile, current Home, `restore.json`), then for **each Android user**:

- installs the launcher (`pm install-existing --user N com.gathin.tvhome`),
- appends `com.gathin.tvhome/.HomeKeyService` to that profile's accessibility services (others are kept),
- writes `family.json` with everyone's names for "Who's watching?".

It finishes by printing the default Home (this launcher with `-DefaultHome`, else still Google TV's) and
checking that no package was disabled. Leave out `-DefaultHome` to keep Google TV as the default Home and have
the launcher cover it instead.

The same by hand, if you prefer (`10` is an example user id from `pm list users`):

```powershell
adb -s $tv push $apk /data/local/tmp/tvhome.apk
adb -s $tv shell pm install -r /data/local/tmp/tvhome.apk
adb -s $tv shell pm list users
# for EVERY user id N listed (0, 10, 11, ...):
adb -s $tv shell pm install-existing --user 10 com.gathin.tvhome
$cur = adb -s $tv shell settings --user 10 get secure enabled_accessibility_services
$new = if ($cur -eq 'null' -or -not $cur) { 'com.gathin.tvhome/.HomeKeyService' } else { "${cur}:com.gathin.tvhome/.HomeKeyService" }
adb -s $tv shell settings --user 10 put secure enabled_accessibility_services "'$new'"
adb -s $tv shell settings --user 10 put secure accessibility_enabled 1
```

**TCL only, and required:** TCL's firmware (`TclAppBoot`) will not start an app's services in the background
unless the app has TCL's *auto-start* permission. Without it HomeKeyService shows as enabled but never binds
(`dumpsys accessibility` → `Bound services:{}`, and logcat says `forbid bind service ... OP_AUTO_START`). The
script allows it for this launcher only; by hand:

```powershell
adb -s $tv shell appops set --user 0 com.gathin.tvhome AUTO_START allow
adb -s $tv shell appops write-settings
```

The second line matters: Android writes app permissions like this to disk only every 30 minutes or at a clean
shutdown. Unplugging the TV before that loses it, and at the next boot TCL blocks the launcher again (logcat:
`TclAppBoot ... forbid bind service ... HomeKeyService` and the same for `BOOT_COMPLETED`). Checked on this TV:
with it saved, a restart comes up on this launcher.

Android 13+ blocks turning on a sideloaded app's accessibility service from the TV's Settings ("Restricted
setting"); the adb `settings` commands above are not affected.

The build-and-install scripts have the same mode if you build the APK yourself:
`.\launcher\build-and-install.ps1 -FamilyMode -Serial $tv` or `FAMILY_MODE=1 TV_HOST=... launcher/build-and-install.sh`.

## 5. Verify Home and each profile

```powershell
# Google TV's launcher must still be the default Home and enabled:
adb -s $tv shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
adb -s $tv shell pm list packages -d | Select-String 'launcherx|setupwraith'      # must print nothing
# HomeKeyService enabled and BOUND for the profile on screen now:
adb -s $tv shell dumpsys accessibility | Select-String 'HomeKeyService|Bound services'
# ...and enabled for every profile:
adb -s $tv shell pm list users
adb -s $tv shell settings --user 10 get secure enabled_accessibility_services
# A screenshot (cmd /c keeps the PNG bytes intact; PowerShell 5.1's > would corrupt them):
cmd /c "adb -s $tv exec-out screencap -p > home.png"
```

Then with the **real remote** (an injected `input keyevent` is not proof that the accessibility service
catches the physical button):

1. Press **Home**: this launcher opens.
2. Press **Home twice quickly**: Google TV's home opens. Press Home once more: back here.
3. Reboot (`adb -s $tv reboot`): after the Google TV home appears, this launcher comes up over it.
4. **Top right → avatar → "Who's watching?"**, pick another person: Google TV's profile screen opens; pick the
   profile there. After the switch, this launcher comes up for that profile, with that person's initial in
   the top bar. Repeat for every profile.
5. Assistant (mic button), Chromecast from a phone, the Google TV phone remote, Play Store: all still work.

## 6. Find Google TV's profile switcher

The picker hands over to Google TV's own switcher; the launcher never switches profiles itself.

**Found on this TV:** `com.google.android.apps.tv.launcherx/.profile.chooser.ProfileChooserActivity` ("Choose an
account": one avatar per profile, Add account, Manage accounts), `exported=true`, no permission. It is
the built-in default (`profile_switcher` in config.xml), so nothing needs setting. Picking someone there switches
Google TV; HomeKeyService notes who was picked (the top bar's avatar) and brings this launcher back.

If a launcherx update renames it, the launcher looks for another launcherx activity whose name says it switches
profiles, and failing that opens Google TV's home with directions. To find the screen on another build:

```powershell
adb -s $tv shell dumpsys package com.google.android.apps.tv.launcherx | Select-String -Pattern 'profile' -CaseSensitive:$false
# Open Google TV's profile screen with the remote, then ask which activity is in front:
adb -s $tv shell dumpsys activity activities | Select-String 'topResumedActivity'
# Try one directly (it must open without an error):
adb -s $tv shell am start -n com.google.android.apps.tv.launcherx/<the.activity.Name>
```

If one works, pass it to the installer so every profile uses it:

```powershell
.\launcher\install-family.ps1 -Serial $tv -Apk $apk -ProfileSwitcher 'com.google.android.apps.tv.launcherx/<the.activity.Name>' -SkipBackup
```

## 7. Motion and frame times

**Settings (top right) → Motion**: Full (default), Reduced, Off. Full: spring focus with a slight overshoot,
parallax lean, a slowly drifting shine, the launch zoom, the dock fading back while you're in the app rows,
the blurred "Who's watching?" with avatars popping in, aerial crossfades. Reduced: short, stiff, no blur.

Check frame times on the TV (60 fps = 16.7 ms a frame):

```powershell
.\tools\frametimes.ps1 -Serial $tv            # Android's gfxinfo: 50/90/95/99th percentile, janky frames
```

The 90th percentile should be at most 16 ms and janky frames under about 5%. For a full timeline:

```powershell
adb -s $tv shell perfetto -o /data/misc/perfetto-traces/home.pftrace -t 10s gfx view input am wm sched freq
#   ...move around Home with the remote during those 10 s...
adb -s $tv pull /data/misc/perfetto-traces/home.pftrace
```

and open `home.pftrace` at <https://ui.perfetto.dev> (look at the `com.gathin.tvhome` RenderThread rows).

## 8. Updating

Download the newer artifact and run step 4 again (it upgrades in place and keeps settings). Updates install
over the old APK only when both are signed with the same key, see Signing.

### Signing

Without signing secrets the workflow signs with a throwaway key, so each build is a different signer and
`pm install -r` refuses it over the previous one (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`): run the undo below
first. To sign every build with your own key once:

```powershell
keytool -genkeypair -v -keystore family-release.jks -alias family -keyalg RSA -keysize 4096 -validity 36500
gh secret set RELEASE_KEYSTORE_BASE64 --repo Blackphenixlord/apple-tv-launcher --body ([Convert]::ToBase64String([IO.File]::ReadAllBytes("$PWD\family-release.jks")))
gh secret set RELEASE_KEYSTORE_PASSWORD --repo Blackphenixlord/apple-tv-launcher
gh secret set RELEASE_KEY_ALIAS --repo Blackphenixlord/apple-tv-launcher --body family
gh secret set RELEASE_KEY_PASSWORD --repo Blackphenixlord/apple-tv-launcher
```

Keep `family-release.jks` and its password somewhere safe, outside the repo.

## 9. Undo: back to stock Google TV (one command)

```powershell
.\launcher\undo-family.ps1 -Serial $tv -Backup .\family-backup-20261025-101500
```

For every profile it puts the accessibility settings back exactly as they were in the backup (without
`-Backup` it removes only this launcher's services and keeps any others), then uninstalls this launcher, which
is an ordinary app. No system package needs re-enabling, because none was ever disabled. Google TV's home was
the default Home the whole time; the script checks that it still is and opens it.

Afterwards turn off **Developer options → Wireless debugging** on the TV.

## Troubleshooting

- **Home still opens Google TV.** `dumpsys accessibility` must list HomeKeyService under *Bound services* for
  the profile on screen. On TCL, check `adb shell appops get com.gathin.tvhome AUTO_START` says `allow`. A
  force-stop of the launcher unbinds it until reboot or until the service is re-enabled:
  `adb shell settings put secure enabled_accessibility_services null`, then step 4 again.
- **Testing over adb:** `input keyevent KEYCODE_HOME` is injected after the accessibility key filter, so Android
  handles it and opens Google's home; the launcher then covers it (the same path as boot). Two quick injected
  Home presses still leave Google's home up (the second one comes back within 700 ms of the cover). Only the
  real remote shows whether its Home button reaches HomeKeyService directly.
- **"Who's watching?" only opens Google TV home.** No switcher activity was found or allowed; that is the
  documented fallback. See step 6.
- **A profile shows "You" instead of its name.** `family.json` could not be written for it (the profile was not
  running). Switch to it once and re-run step 4 with `-SkipBackup`.
- **Profiles are not Android users on your build** (`pm list users` shows only user 0): everything still works;
  profiles then switch inside Google TV without a user switch, and the picker hands over the same way.
