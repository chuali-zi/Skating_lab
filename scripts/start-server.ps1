param([switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'load-local-env.ps1')
if (-not $env:JAVA_HOME) {
    $installedJdk = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.17.10-hotspot'
    if (Test-Path -LiteralPath (Join-Path $installedJdk 'bin/javac.exe')) { $env:JAVA_HOME = $installedJdk }
    else { throw 'Set JAVA_HOME to a full JDK 17 or later supported by the server.' }
}
Push-Location (Join-Path $projectRoot 'server')
try {
    if (-not $SkipBuild) {
        & .\mvnw.cmd -B --no-transfer-progress package
        if ($LASTEXITCODE -ne 0) { throw 'Build/tests failed; server was not started.' }
    }
    & (Join-Path $env:JAVA_HOME 'bin/java.exe') -jar target/skating-coach-server-0.1.0-SNAPSHOT.jar
    if ($LASTEXITCODE -ne 0) { throw 'Server exited with an error.' }
} finally { Pop-Location }
