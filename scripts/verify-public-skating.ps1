param(
    [Parameter(Mandatory)][uri]$BaseUrl,
    [string]$OutputDir=(Join-Path (Split-Path -Parent $PSScriptRoot) 'tmp/public-skating-validation')
)
$ErrorActionPreference='Stop'
if(-not $BaseUrl.IsLoopback){ throw 'This acceptance test requires an isolated local development server.' }
$api=$BaseUrl.AbsoluteUri.TrimEnd('/')+'/api/v1'
$clips=@(
    @{id='amber'; file='Amber_Glenn_2025_Worlds_Free_Skate_3A_02.webm'; path='3/3c'; sha='ee99522b0b9e6bec18ce93ed0570c658ccf53bbb5e2aaa958b97e23fb4c55887'},
    @{id='deniss'; file='Deniss_Vasiljevs_2025_Worlds_Free_Skate_sit_spin.webm'; path='d/dc'; sha='bdfdf3f225b393ffeb4a37785f4564fb61bf4c2d43eb2f7515d9330dc276ec5d'},
    @{id='starr'; file='2025_Skate_America_Starr_Andrews_3T.webm'; path='b/b9'; sha='a400ab62462d17b3b58ae38cc39ba8a27ca76f64b7ef5704f0784ab21beeaefe'}
)
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
$session=Invoke-RestMethod -Method Post -Uri "$api/dev/session"
$headers=@{Authorization="Bearer $($session.token)"}
$uploadHeaders=$headers.Clone()
$uploadHeaders['X-Skating-Frame-Consent']='1'
$index=Invoke-RestMethod -Headers $headers -Uri "$api/assets/video-jobs"
if(-not $index.submission_enabled){throw 'Start the local server with -VideoObservations and install FFmpeg.'}
$results=@()
foreach($clip in $clips){
    $id=$clip.id
    $webm=Join-Path $OutputDir "$id.webm"
    $mp4=Join-Path $OutputDir "$id.mp4"
    $file=$clip.file
    $media="https://upload.wikimedia.org/wikipedia/commons/transcoded/$($clip.path)/$file/$file.480p.vp9.webm"
    if(-not (Test-Path -LiteralPath $webm)){
        Invoke-WebRequest -UseBasicParsing -Uri $media -UserAgent 'SkatingLab/1.0 (https://github.com/chuali-zi/Skating_lab)' -OutFile $webm
    }
    if((Get-FileHash -LiteralPath $webm).Hash -ne $clip.sha){throw "Public source fingerprint changed for $id; verify the new source before sending."}
    & ffmpeg -hide_banner -nostdin -loglevel error -y -i $webm -map 0:v:0 -an -sn -dn -c:v libx264 -crf 21 -threads 2 -pix_fmt yuv420p -movflags +faststart $mp4
    if($LASTEXITCODE -ne 0){throw "Could not prepare $id"}
    $job=Invoke-RestMethod -Method Post -Headers $uploadHeaders -Uri "$api/assets/video-jobs" -ContentType 'video/mp4' -InFile $mp4
    $location="$api/assets/video-jobs/$($job.id)"
    $deadline=[DateTime]::UtcNow.AddMinutes(2)
    do {
        Start-Sleep -Seconds 1
        $detail=Invoke-RestMethod -Headers $headers -Uri $location
        if([DateTime]::UtcNow -gt $deadline){throw "Observation timed out for $id"}
    } while($detail.status -in @('queued','running'))
    if($detail.status -ne 'completed'){throw "Observation failed for $id. $($detail.error)"}
    $r=$detail.result
    $dir=New-Item -ItemType Directory -Force -Path (Join-Path $OutputDir "$id-$($job.id)")
    for($i=0;$i -lt $r.frame_timestamps_seconds.Count;$i++){
        $framePath=Join-Path $dir.FullName ('{0:d2}.jpg' -f $i)
        $frame=Invoke-WebRequest -UseBasicParsing -Headers $headers -Uri "$location/frames/$i" -OutFile $framePath -PassThru
        if($frame.Headers['Cache-Control'] -notcontains 'no-store' -and [string]$frame.Headers['Cache-Control'] -notmatch 'no-store'){throw 'Frame cache policy changed.'}
    }
    $rows=[int][Math]::Ceiling($r.frame_timestamps_seconds.Count/4.0)
    & ffmpeg -hide_banner -nostdin -loglevel error -y -start_number 0 -i (Join-Path $dir.FullName '%02d.jpg') -vf "scale=240:-1,tile=4x$rows" -frames:v 1 -threads 1 (Join-Path $dir.FullName 'sheet.jpg')
    if($LASTEXITCODE -ne 0){throw "Could not decode evidence frames for $id"}
    $results+=[ordered]@{id=$id;source="https://commons.wikimedia.org/wiki/File:$file";author='FloweringDagwood';
        license='https://creativecommons.org/licenses/by/4.0/';derivativeSha256=$clip.sha;preparation='Full 480p public derivative, H.264 MP4, audio removed';
        durationSeconds=$r.duration_seconds;model=$r.model;times=$r.frame_timestamps_seconds;maxGap=$r.max_sampling_gap_seconds;
        observations=$r.model_output.observations;pending=$r.model_output.pending_review;assessment=$r.technical_assessment}
    ConvertTo-Json -InputObject $results -Depth 8 | Set-Content -Encoding utf8 -LiteralPath (Join-Path $OutputDir 'public-skating-report.json')
    Write-Output "$id · $($r.frame_timestamps_seconds.Count) frames · $($r.model_output.observations.Count) observations · $($r.model_output.pending_review.Count) pending"
}
