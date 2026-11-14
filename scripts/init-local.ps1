$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$configPath = Join-Path $projectRoot '.env'
if (-not (Test-Path -LiteralPath $configPath)) {
    $config = Get-Content -Raw -LiteralPath (Join-Path $projectRoot '.env.example')
    foreach ($placeholder in @('replace-local-admin-password','replace-local-app-password','replace-local-migration-password')) {
        $randomPassword = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
        $config = $config.Replace($placeholder,$randomPassword)
    }
    [IO.File]::WriteAllText($configPath,$config,[Text.UTF8Encoding]::new($false))
    Write-Output 'Created ignored .env with separate random local database passwords.'
}
Push-Location $projectRoot
try {
    & docker compose up -d --wait db
    if ($LASTEXITCODE -ne 0) { throw 'Database startup failed. Existing data has been preserved.' }
} finally { Pop-Location }
