param(
    [switch]$SkipBuild,
    [switch]$GlmCodingPlan,
    [switch]$VideoObservations,
    [string]$GlmModel = 'glm-5.3',
    [ValidateRange(0,65535)][int]$Port = 0
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'load-local-env.ps1')
if (-not $env:JAVA_HOME) {
    $installedJdk = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.17.10-hotspot'
    if (Test-Path -LiteralPath (Join-Path $installedJdk 'bin/javac.exe')) { $env:JAVA_HOME = $installedJdk }
    else { throw 'Set JAVA_HOME to a full JDK 17 or later supported by the server.' }
}
Push-Location (Join-Path $projectRoot 'server')
$previousModelEnvironment = @{}
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
    if ($Port -ne 0) {
        $previousModelEnvironment['SERVER_PORT'] = [Environment]::GetEnvironmentVariable('SERVER_PORT', 'Process')
        $env:SERVER_PORT = [string]$Port
    }
    $installedEncoder = Join-Path $projectRoot 'storage/models/bge-small-zh-v1.5'
    if (-not $env:SKATING_BGE_DIR -and (Test-Path -LiteralPath (Join-Path $installedEncoder 'model_quantized.onnx'))) {
        $previousModelEnvironment['SKATING_BGE_DIR'] = $null
        $env:SKATING_BGE_DIR = $installedEncoder
    }
    if ($VideoObservations) {
        $previousModelEnvironment['OPENAI_API_KEY'] = $env:OPENAI_API_KEY
        $previousModelEnvironment['SKATING_RUN_LIVE_LUNA'] = $env:SKATING_RUN_LIVE_LUNA
        foreach ($scope in @('Process','User','Machine')) {
            $videoKey = [Environment]::GetEnvironmentVariable('OPENAI_API_KEY',$scope)
            if ($videoKey) { break }
        }
        if (-not $videoKey) { throw 'Video observations require OPENAI_API_KEY in the runtime environment.' }
        $env:OPENAI_API_KEY = $videoKey
        $env:SKATING_RUN_LIVE_LUNA = 'true'
        $videoKey = $null
    }
    if ($GlmCodingPlan) {
        # Read the Windows store too: an already-running terminal may not have
        # inherited a variable added through System Properties. Never print or
        # persist the key, and only expose it after Maven/tests have finished.
        $glmKey = $null
        foreach ($scope in @('Process', 'User', 'Machine')) {
            $glmKey = [Environment]::GetEnvironmentVariable('GLM_API_KEY', $scope)
            if (-not [string]::IsNullOrWhiteSpace($glmKey)) { break }
        }
        if ([string]::IsNullOrWhiteSpace($glmKey)) { throw 'GLM_API_KEY was not found in Process, User or Machine environment.' }
        if ([string]::IsNullOrWhiteSpace($GlmModel)) { throw 'Specify a GLM model ID.' }
        $modelEnvironment = @{
            LLM_ENDPOINT = 'https://open.bigmodel.cn/api/coding/paas/v4/chat/completions'
            LLM_API_KEY = $glmKey
            LLM_MODEL = $GlmModel
            LLM_TIMEOUT_SECONDS = '180'
            LLM_JSON_MODE = 'true'
            LLM_MAX_OUTPUT_TOKENS = '32768'
        }
        foreach ($name in $modelEnvironment.Keys) {
            $previousModelEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
            [Environment]::SetEnvironmentVariable($name, $modelEnvironment[$name], 'Process')
        }
        $glmKey = $null
        $modelEnvironment.Clear()
        Write-Output "GLM Coding Plan development connection configured: $GlmModel (key not displayed or saved)."
    }
    & (Join-Path $env:JAVA_HOME 'bin/java.exe') -jar $runtimeJar
    if ($LASTEXITCODE -ne 0) { throw 'Server exited with an error.' }
} finally {
    foreach ($name in $previousModelEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $previousModelEnvironment[$name], 'Process')
    }
    Pop-Location
}
