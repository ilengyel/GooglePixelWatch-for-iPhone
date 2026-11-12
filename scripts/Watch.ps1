param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('Build', 'Pair', 'Connect', 'Devices', 'Install', 'Logcat')]
    [string]$Action,
    [string]$Target
)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent

foreach ($name in @('JAVA_HOME', 'ANDROID_HOME')) {
    $value = [Environment]::GetEnvironmentVariable($name, 'User')
    if ($value) {
        [Environment]::SetEnvironmentVariable($name, $value, 'Process')
    }
}

if (-not $env:ANDROID_HOME) {
    throw 'Set ANDROID_HOME to your Android SDK directory.'
}

function Invoke-Checked {
    param([string]$Executable, [string[]]$Arguments)
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Executable failed with exit code $LASTEXITCODE."
    }
}

if ($Action -in @('Pair', 'Connect', 'Install', 'Logcat') -and
    [string]::IsNullOrWhiteSpace($Target)) {
    throw 'Specify -Target with the watch IP:port (pairing port for Pair, connection port otherwise).'
}

Push-Location $root
try {
    if ($Action -in @('Build', 'Install')) {
        if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME\bin\javac.exe")) {
            throw 'Set JAVA_HOME to a full JDK 21 installation, not a JRE.'
        }
        Invoke-Checked "$root\gradlew.bat" @(':app:assembleDebug', '--console=plain')
    }

    if ($Action -eq 'Build') {
        return
    }

    $adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
    if (-not (Test-Path $adb)) {
        throw "ADB is missing at $adb. Install Android SDK platform-tools."
    }

    switch ($Action) {
        'Pair' {
            Invoke-Checked $adb @('pair', $Target)
        }
        'Connect' {
            Invoke-Checked $adb @('connect', $Target)
            Invoke-Checked $adb @('-s', $Target, 'get-state')
        }
        'Devices' {
            Invoke-Checked $adb @('devices', '-l')
        }
        'Install' {
            Invoke-Checked $adb @('-s', $Target, 'get-state')
            Invoke-Checked $adb @('-s', $Target, 'install', '-r',
                "$root\app\build\outputs\apk\debug\app-debug.apk")
            Invoke-Checked $adb @('-s', $Target, 'shell', 'am', 'start', '-n',
                'dev.toastmod.ancs/.presentation.MainActivity')
        }
        'Logcat' {
            Invoke-Checked $adb @('-s', $Target, 'logcat')
        }
    }
}
finally {
    Pop-Location
}
