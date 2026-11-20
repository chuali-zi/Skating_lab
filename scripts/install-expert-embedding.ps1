$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$target = Join-Path $root 'storage/models/bge-small-zh-v1.5'
$revision = '75c43b069aac4d136ba6bc1122f995fedcfd2781'
$remote = "https://huggingface.co/Xenova/bge-small-zh-v1.5/resolve/$revision"
$files = @(
    @('onnx/model_quantized.onnx','15b717c382bcb518ba457b93ea6850ede7f4f1cd8937454aa06972366cd19bcc'),
    @('tokenizer.json','48cea5d44424912a6fd1ea647bf4fe50b55ab8b1e5879c3275f80e339e8fae26')
)
New-Item -ItemType Directory -Path $target -Force | Out-Null
foreach ($entry in $files) {
    $name = Split-Path -Leaf $entry[0]
    $path = Join-Path $target $name
    if ((Test-Path -LiteralPath $path) -and (Get-FileHash -LiteralPath $path).Hash -eq $entry[1]) { continue }
    $download = Join-Path $target ([guid]::NewGuid().ToString() + '.part')
    try {
        Invoke-WebRequest -UseBasicParsing -Uri "$remote/$($entry[0])" -OutFile $download
        if ((Get-FileHash -LiteralPath $download).Hash -ne $entry[1]) { throw "Fingerprint mismatch for $name" }
        Move-Item -Force -LiteralPath $download -Destination $path
    } finally {
        if (Test-Path -LiteralPath $download) { Remove-Item -LiteralPath $download }
    }
}
Write-Output "Verified Chinese retrieval model at $target"
