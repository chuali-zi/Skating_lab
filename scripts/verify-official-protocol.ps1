param([Parameter(Mandatory)][uri]$BaseUrl,[Parameter(Mandatory)][string]$ProtocolFile,[string]$PdfToText='pdftotext')
$ErrorActionPreference='Stop'
if(-not $BaseUrl.IsLoopback){throw 'Use the isolated local development API.'}
if((Get-FileHash -LiteralPath $ProtocolFile).Hash -ne '0d475f51aaff8001213cb44bac462db41d936f513bac1cc11aa8fc89e44345a1'){
    throw 'Expected the published 2026 Skate Canada women free skating judges protocol.'
}
$lines=& $PdfToText -layout -enc UTF-8 $ProtocolFile -
if($LASTEXITCODE -ne 0){throw 'The official PDF could not be read.'}
$api=$BaseUrl.AbsoluteUri.TrimEnd('/')+'/api/v1'
$headers=@{Authorization='Bearer '+(Invoke-RestMethod -Method Post -Uri "$api/dev/session").token}
$culture=[Globalization.CultureInfo]::InvariantCulture
$cache=@{}
$rows=0
$bonusRows=0
$correct=0
foreach($line in $lines){
    # Select every standalone jump row. Separators, spins, steps and +REP require different rules.
    if($line -notmatch '^\s*\d+\s+(?<code>[1-5](?:Lz|Lo|[AFST])[qe!<*]*)\s+(?:F\s+)?(?:[qe!<*,]+\s+)?(?<bv>\d+\.\d\d)\s+(?<x>x\s+)?-?\d+\.\d\d\s'){continue}
    $code=$Matches.code; $expected=[decimal]::Parse($Matches.bv,$culture); $factor=1d
    if($Matches.x){$factor=1.10d;$bonusRows++}
    if(-not $cache.ContainsKey($code)){
        $question=[uri]::EscapeDataString("$code 的基础分是多少？")
        $answer=Invoke-RestMethod -Headers $headers -Uri "$api/knowledge/expert?competitionKey=isu-2026-27-senior-women-fs&q=$question"
        if($answer.status -ne 'matched' -or $answer.answers.Count -ne 1 -or $answer.evidence[0].sourceUrl -notmatch '#page=\d+' -or
                $answer.answers[0].text -notmatch 'BASE[）)]\s*为\s*([0-9]+\.[0-9]+)'){throw "No cited BASE reply for $code"}
        $cache[$code]=[decimal]::Parse($Matches[1],$culture)
    }
    $rows++
    if([decimal]::Round($cache[$code]*$factor,2) -eq $expected){$correct++}else{throw "Official protocol mismatch for $code."}
}
if($rows -ne 48){throw "Unexpected number of eligible rows: $rows"}
[pscustomobject]@{protocol='2026 Skate Canada, Women FS';rows=$rows;uniqueCodes=$cache.Count;givenBonusRows=$bonusRows;matched=$correct}
