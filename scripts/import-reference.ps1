param(
    [Parameter(Mandatory=$true)][string]$SourceId,
    [string]$ApiBase='http://127.0.0.1:8080/api/v1',
    [string]$CompetitionKey
)
$ErrorActionPreference = 'Stop'
if (-not $env:SKATING_API_TOKEN) { throw 'Set SKATING_API_TOKEN to an authorized session token. It will not be printed.' }
$headers = @{Authorization="Bearer $env:SKATING_API_TOKEN"}
$catalog = Invoke-RestMethod -Uri "$ApiBase/knowledge/catalog" -Headers $headers
$source = $catalog.items | Where-Object { $_.id -eq $SourceId } | Select-Object -First 1
if (-not $source) { throw 'Unknown source id. Only reviewed catalog URLs can be imported by this script.' }
$sourceUri = [Uri]$source.url
$approvedHosts = @('isu-d8g8b4b7ece7aphs.a03.azurefd.net','www.cfsa.com.cn')
if ($sourceUri.Scheme -ne 'https' -or $sourceUri.Host -notin $approvedHosts) {
    throw 'This catalog entry is a discovery or member resource. Obtain permitted material and upload it separately.'
}
$tempPath = Join-Path ([IO.Path]::GetTempPath()) (([Guid]::NewGuid().ToString())+'.pdf')
try {
    Invoke-WebRequest -Uri $sourceUri -OutFile $tempPath -MaximumRedirection 0
    $form = @{file=Get-Item -LiteralPath $tempPath;title=$source.title;requestId="catalog:$SourceId";sourceUrl=$source.url}
    if ($CompetitionKey) { $form.competitionKey=$CompetitionKey }
    $result = Invoke-RestMethod -Method Post -Uri "$ApiBase/knowledge/upload" -Headers $headers -Form $form
    [pscustomobject]@{id=$result.id;title=$result.title;status=$result.status;source=$source.url;sha256=(Get-FileHash -LiteralPath $tempPath -Algorithm SHA256).Hash}
} finally {
    if (Test-Path -LiteralPath $tempPath) { Remove-Item -LiteralPath $tempPath }
}
