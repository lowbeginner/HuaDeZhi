# Generate once. Keep .signing and keystore.properties for every future update.
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$propertiesPath = Join-Path $projectRoot 'keystore.properties'
$keyPath = Join-Path $projectRoot '.signing/personal-release.jks'
if ((Test-Path -LiteralPath $propertiesPath) -or (Test-Path -LiteralPath $keyPath)) {
    throw 'Signing files already exist. Reuse the existing key; do not replace it.'
}
$keytool = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/keytool.exe' } else { (Get-Command keytool.exe).Source }
if (!(Test-Path -LiteralPath $keytool)) { throw 'Install JDK 17 or newer and set JAVA_HOME first.' }
New-Item -ItemType Directory -Force -Path (Join-Path $projectRoot '.signing') | Out-Null
$randomBytes = New-Object byte[] 32
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
try { $rng.GetBytes($randomBytes) } finally { $rng.Dispose() }
$password = [Convert]::ToBase64String($randomBytes)
& $keytool -genkeypair -noprompt -keystore $keyPath -storetype JKS -alias personal-release -keyalg RSA -keysize 3072 -validity 36500 -storepass $password -keypass $password -dname 'CN=Personal Expense Tracker, OU=Personal, O=Personal, C=CN'
if ($LASTEXITCODE -ne 0) { throw 'Signing key generation failed.' }
$propertiesText = "storeFile=.signing/personal-release.jks`nstorePassword=$password`nkeyAlias=personal-release`nkeyPassword=$password`n"
[IO.File]::WriteAllText($propertiesPath, $propertiesText, [Text.UTF8Encoding]::new($false))
Write-Output 'Release key created. Back up .signing/personal-release.jks AND keystore.properties privately. Both are excluded from Git.'
