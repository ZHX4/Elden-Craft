param(
    [Parameter(Mandatory = $true)]
    [string]$GameDir
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = $PSScriptRoot
$GameDir = [IO.Path]::GetFullPath($GameDir).TrimEnd('\')
if (Get-Process eldenring -ErrorAction SilentlyContinue) { throw 'Close Elden Ring before installation.' }
if (!(Test-Path -LiteralPath "$GameDir\eldenring.exe")) { throw 'Elden Ring was not found.' }
$BackupRoot = Join-Path $ProjectRoot ('backups\' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $BackupRoot -Force | Out-Null
$SaveRoot = Join-Path $env:APPDATA 'EldenRing'
if (Test-Path -LiteralPath $SaveRoot) { Copy-Item -LiteralPath $SaveRoot -Destination "$BackupRoot\saves" -Recurse }
$ModNames = @('dinput8.dll','dxgi.dll','d3d11.dll','winmm.dll','version.dll','modengine2.dll','modengine2','mods','mod','SeamlessCoop','ersc_launcher.exe','ersc.dll','ersc_settings.ini','mod_loader_config.ini','ReShade.ini','ReShadePreset.ini','reshade-shaders','erbridge')
$Moved = @()
foreach ($Name in $ModNames) {
    $Candidate = [IO.Path]::GetFullPath((Join-Path $GameDir $Name))
    if (!$Candidate.StartsWith($GameDir + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Invalid backup path.' }
    if (Test-Path -LiteralPath $Candidate) {
        Move-Item -LiteralPath $Candidate -Destination (Join-Path $BackupRoot $Name)
        $Moved += $Name
    }
}
if (Test-Path -LiteralPath "$GameDir\steam_appid.txt") { Copy-Item -LiteralPath "$GameDir\steam_appid.txt" -Destination "$BackupRoot\steam_appid.txt" }
New-Item -ItemType Directory -Path "$GameDir\erbridge" -Force | Out-Null
Copy-Item -LiteralPath "$ProjectRoot\dist\dinput8.dll" -Destination "$GameDir\dinput8.dll"
Copy-Item -LiteralPath "$ProjectRoot\dist\erbridge_core.dll" -Destination "$GameDir\erbridge\erbridge_core.dll"
'1245620' | Set-Content -LiteralPath "$GameDir\steam_appid.txt" -Encoding ASCII
[ordered]@{ game_dir=$GameDir; backup=$BackupRoot; moved_mods=$Moved; installed_at=(Get-Date).ToString('o'); native_sha256=(Get-FileHash "$GameDir\erbridge\erbridge_core.dll").Hash } | ConvertTo-Json | Set-Content "$ProjectRoot\installation.json" -Encoding UTF8
Write-Output "Installed. Backups: $BackupRoot. Previous mods moved: $($Moved.Count)."
