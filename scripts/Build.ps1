param([string[]]$Tasks = @('assembleDebug', 'testDebugUnitTest', 'lintDebug'))
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
Push-Location -LiteralPath $projectRoot
try {
    $gradleArgs = @('--console=plain')
    $proxy = Get-ItemProperty -LiteralPath 'HKCU:/Software/Microsoft/Windows/CurrentVersion/Internet Settings' -ErrorAction SilentlyContinue
    if ($proxy.ProxyEnable -eq 1 -and $proxy.ProxyServer -match '^([^:;=]+):(\d+)$') {
        $gradleArgs += "-Dhttps.proxyHost=$($Matches[1])"
        $gradleArgs += "-Dhttps.proxyPort=$($Matches[2])"
        $gradleArgs += "-Dhttp.proxyHost=$($Matches[1])"
        $gradleArgs += "-Dhttp.proxyPort=$($Matches[2])"
    }
    & '.\gradlew.bat' @gradleArgs @Tasks
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
} finally { Pop-Location }
