$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$configPath = Join-Path $projectRoot '.env'
if (-not (Test-Path -LiteralPath $configPath)) { throw 'Run scripts/init-local.ps1 first, or create .env from .env.example.' }
foreach ($configLine in Get-Content -LiteralPath $configPath) {
    if ($configLine -match '^\s*#' -or [string]::IsNullOrWhiteSpace($configLine)) { continue }
    $equalsIndex = $configLine.IndexOf('=')
    if ($equalsIndex -lt 1) { throw 'Invalid .env entry. Expected NAME=value.' }
    $configName = $configLine.Substring(0,$equalsIndex).Trim()
    if ($configName -notmatch '^(POSTGRES_PASSWORD|DB_[A-Z_]+|SPRING_PROFILES_ACTIVE|LLM_[A-Z_]+|EMBEDDING_[A-Z_]+|KNOWLEDGE_STORAGE_PATH|SERVER_[A-Z_]+|UPLOAD_[A-Z_]+)$') {
        throw "Unsupported local config variable: $configName"
    }
    [Environment]::SetEnvironmentVariable($configName,$configLine.Substring($equalsIndex+1).Trim(),'Process')
}
if ($env:KNOWLEDGE_STORAGE_PATH -and -not [IO.Path]::IsPathRooted($env:KNOWLEDGE_STORAGE_PATH)) {
    $env:KNOWLEDGE_STORAGE_PATH = [IO.Path]::GetFullPath((Join-Path $projectRoot $env:KNOWLEDGE_STORAGE_PATH))
}
