param(
    [string]$SdkPath = '',
    [string]$AvdName = 'skating_api36',
    [int]$Port = 5554
)
$ErrorActionPreference = 'Stop'
$workspace = Split-Path $PSScriptRoot -Parent
if (!$SdkPath) {
    $SdkPath = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $workspace 'android/.tools/sdk' }
}
$SdkPath = (Resolve-Path -LiteralPath $SdkPath).Path
$env:ANDROID_HOME = $SdkPath
$env:ANDROID_SDK_ROOT = $SdkPath
$env:ANDROID_AVD_HOME = Join-Path $workspace 'android/.tools/avd'
$emulator = Join-Path $SdkPath 'emulator/emulator.exe'
$adb = Join-Path $SdkPath 'platform-tools/adb.exe'
$image = Join-Path $SdkPath 'system-images/android-36/default/x86_64/system.img'
if (!(Test-Path -LiteralPath $emulator) -or !(Test-Path -LiteralPath $image)) {
    throw 'Install emulator and system-images;android-36;default;x86_64 with the Android SDK manager first. See docs/android-device-testing.md.'
}
& $emulator -accel-check
if ($LASTEXITCODE -ne 0) { throw 'Android Emulator hardware acceleration is unavailable.' }
$avdManager = Join-Path $SdkPath 'cmdline-tools/latest/bin/avdmanager.bat'
if (!(Test-Path -LiteralPath $avdManager)) { throw 'Install Android command-line tools inside SDK/cmdline-tools/latest (avdmanager derives its SDK root from its location).' }
New-Item -ItemType Directory -Force -Path $env:ANDROID_AVD_HOME | Out-Null
if (!(Test-Path -LiteralPath (Join-Path $env:ANDROID_AVD_HOME "$AvdName.ini"))) {
    'no' | & $avdManager create avd --name $AvdName --package 'system-images;android-36;default;x86_64' --device pixel_7 --path (Join-Path $env:ANDROID_AVD_HOME "$AvdName.avd")
    if ($LASTEXITCODE -ne 0) { throw 'AVD creation failed.' }
}
$artifacts = Join-Path $workspace 'tmp/mobile-testing'
New-Item -ItemType Directory -Force -Path $artifacts | Out-Null
$serial = "emulator-$Port"
$devices = & $adb devices
if ($devices | Where-Object { $_ -match "^$serial\s" }) {
    $existingName = (& $adb -s $serial emu avd name 2>$null | Select-Object -First 1)
    if ($existingName -ne $AvdName) { throw "Port $Port already belongs to a different or unavailable AVD. Choose a different port." }
}
if (!($devices | Where-Object { $_ -match "^$serial\s+device$" })) {
    $process = Start-Process -FilePath $emulator -ArgumentList @('-avd', $AvdName, '-port', $Port, '-no-window', '-no-audio', '-no-snapshot', '-gpu', 'software', '-memory', '2048', '-no-boot-anim') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $artifacts 'emulator.out.log') -RedirectStandardError (Join-Path $artifacts 'emulator.err.log')
    $process.Id | Set-Content -LiteralPath (Join-Path $artifacts 'emulator.pid')
}
$deadline = (Get-Date).AddMinutes(3)
do {
    $boot = & $adb -s $serial shell getprop sys.boot_completed 2>$null
    if ($boot -eq '1') { break }
    if ((Get-Date) -gt $deadline) { throw 'Emulator did not boot within three minutes. Inspect tmp/mobile-testing/emulator.*.log.' }
    Start-Sleep -Seconds 2
} while ($true)
& $adb -s $serial reverse tcp:8080 tcp:8080
if ($LASTEXITCODE -ne 0) { throw 'ADB reverse failed.' }
Write-Output "Ready: $serial (Android 16 / API 36); localhost:8080 forwarded to host."
