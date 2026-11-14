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
    # Windows locks a running JAR. Keep Maven's target artifact free for rebuilds
    # by running an immutable, content-addressed copy in the ignored tmp folder.
    $builtJar = Join-Path $projectRoot 'server/target/skating-coach-server-0.1.0-SNAPSHOT.jar'
    if (-not (Test-Path -LiteralPath $builtJar)) { throw 'Packaged server JAR not found; run without -SkipBuild first.' }
    $runtimeDirectory = Join-Path $projectRoot 'tmp/runtime'
    New-Item -ItemType Directory -Force -Path $runtimeDirectory | Out-Null
    $jarHash = (Get-FileHash -LiteralPath $builtJar -Algorithm SHA256).Hash.ToLowerInvariant()
    $runtimeJar = Join-Path $runtimeDirectory "skating-coach-$jarHash.jar"
    if (-not (Test-Path -LiteralPath $runtimeJar)) { Copy-Item -LiteralPath $builtJar -Destination $runtimeJar }
    & (Join-Path $env:JAVA_HOME 'bin/java.exe') -jar $runtimeJar
    if ($LASTEXITCODE -ne 0) { throw 'Server exited with an error.' }
} finally { Pop-Location }
