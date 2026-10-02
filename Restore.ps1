$ErrorActionPreference = 'Stop'
$Install = Get-Content "$PSScriptRoot\installation.json" -Raw | ConvertFrom-Json
if (Get-Process eldenring -ErrorAction SilentlyContinue) { throw 'Close Elden Ring first.' }
$GameDir = $Install.game_dir
$Archive = Join-Path $PSScriptRoot ('backups\bridge-disabled-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $Archive | Out-Null
foreach ($Name in @('dinput8.dll','erbridge','steam_appid.txt')) {
    $Candidate = [IO.Path]::GetFullPath((Join-Path $GameDir $Name))
    if (!$Candidate.StartsWith($GameDir + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Invalid restore path.' }
    if (Test-Path -LiteralPath $Candidate) { Move-Item -LiteralPath $Candidate -Destination (Join-Path $Archive $Name) }
}
foreach ($Name in $Install.moved_mods) {
    Copy-Item -LiteralPath (Join-Path $Install.backup $Name) -Destination (Join-Path $GameDir $Name) -Recurse
}
if (Test-Path -LiteralPath (Join-Path $Install.backup 'steam_appid.txt')) { Copy-Item -LiteralPath (Join-Path $Install.backup 'steam_appid.txt') -Destination (Join-Path $GameDir 'steam_appid.txt') }
Write-Output 'The bridge is disabled. Previous mod files restored. Save backups have not been applied.'
