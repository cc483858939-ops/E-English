param(
    [string[]]$Tasks = @('testDebugUnitTest', 'assembleDebug'),
    [string]$Log = 'artifacts/build.log',
    [string]$Device = ''
)
$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
Push-Location $project
try {
    $localSdk = Join-Path $project '.local/android-sdk'
    if (Test-Path $localSdk) { $env:ANDROID_HOME = $localSdk }
    $localGradle = Join-Path $project '.local/gradle'
    if (Test-Path $localGradle) { $env:GRADLE_USER_HOME = $localGradle }
    foreach ($dir in @('temp', 'android-user', 'avd')) {
        New-Item -ItemType Directory -Force (Join-Path $project ".local/$dir") | Out-Null
    }
    $env:TEMP = Join-Path $project '.local/temp'
    $env:TMP = $env:TEMP
    $env:ANDROID_USER_HOME = Join-Path $project '.local/android-user'
    $env:ANDROID_AVD_HOME = Join-Path $project '.local/avd'
    $arguments = @('--no-daemon', '--console=plain', '--max-workers=1',
        '-Pkotlin.compiler.execution.strategy=in-process', '-Dorg.gradle.jvmargs=-Xmx1536m')
    if ($Device) {
        $env:ANDROID_SERIAL = $Device
        $arguments += "-Pandroid.injected.device.serial=$Device"
    }
    New-Item -ItemType Directory -Force (Split-Path $Log -Parent) | Out-Null
    & "$project/gradlew.bat" @Tasks @arguments 2>&1 | Tee-Object $Log
    exit $LASTEXITCODE
} finally { Pop-Location }
