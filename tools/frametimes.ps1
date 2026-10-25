# Frame times of the launcher on the TV while the remote moves focus: Android's own gfxinfo counters, reset
# first, then a run of D-pad presses across the dock and the app rows, then the summary.
#
# Target: 60 fps means a frame every 16.7 ms. The 90th percentile should stay at or under 16 ms and janky
# frames under about 5%. Compare Settings > Motion = Full against Reduced on the same TV.
#
# Usage:  .\tools\frametimes.ps1 -Serial 192.168.1.50:41234 [-Presses 30]
# For a full timeline (what each frame spent its time on), take a Perfetto trace instead; see INSTALL-FAMILY.md,
# "Check the frame times".
param(
    [string]$Serial = $env:ANDROID_SERIAL,
    [int]$Presses = 30
)
$ErrorActionPreference = 'Stop'
$Pkg = 'com.gathin.tvhome'
$Adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $Adb) { $Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" }
if (-not (Test-Path $Adb)) { throw 'adb not found: install Google platform-tools and put it on PATH' }

function Tv([string]$Command) {
    $sel = @()
    if ($Serial) { $sel += @('-s', $Serial) }
    return ((& $Adb @sel shell $Command 2>&1) | Out-String).TrimEnd()
}

# Bring the launcher up, as the Home button does, and let it settle.
Tv "am start -W -a android.intent.action.MAIN -c android.intent.category.HOME -n $Pkg/.MainActivity" | Out-Null
Start-Sleep -Seconds 2
Tv "dumpsys gfxinfo $Pkg reset" | Out-Null

# Right along the dock, down into the rows and back: focus springs, the shine, the dock fade and the scroll.
$keys = @()
for ($i = 0; $i -lt $Presses; $i++) {
    switch ($i % 10) {
        { $_ -lt 4 } { $keys += 22; break }      # right
        4 { $keys += 20; break }                 # down into the rows
        { $_ -lt 8 } { $keys += 21; break }      # left
        default { $keys += 19 }                  # up, back to the dock
    }
}
Write-Host "Sending $($keys.Count) D-pad presses..."
Tv ("for k in " + ($keys -join ' ') + "; do input keyevent `$k; sleep 0.25; done") | Out-Null
Start-Sleep -Seconds 1

$stats = Tv "dumpsys gfxinfo $Pkg"
$want = 'Total frames rendered|Janky frames|50th percentile|90th percentile|95th percentile|99th percentile|Number Missed Vsync|Number High input latency|Number Slow UI thread|Number Slow bitmap uploads|Number Slow issue draw commands|Number Frame deadline missed'
$lines = $stats -split "`n" | Where-Object { $_ -match $want } | Select-Object -Unique
$lines | ForEach-Object { Write-Host $_.Trim() }

$p90 = [regex]::Match($stats, '90th percentile: (\d+)ms')
$jank = [regex]::Match($stats, 'Janky frames: \d+ \(([\d.]+)%\)')
if ($p90.Success -and $jank.Success) {
    $ok = ([int]$p90.Groups[1].Value -le 16) -and ([double]$jank.Groups[1].Value -le 5)
    if ($ok) { Write-Host 'Result: smooth (90th percentile within one 60 Hz frame).' -ForegroundColor Green }
    else { Write-Host 'Result: frames are being missed. Try Settings > Motion > Reduced and compare.' -ForegroundColor Yellow }
}
