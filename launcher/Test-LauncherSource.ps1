$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$launcher = Join-Path $root 'Launcher.ps1'
$profilesPath = Join-Path $root 'compatibility-profiles.json'

$tokens = $null
$parseErrors = $null
[System.Management.Automation.Language.Parser]::ParseFile($launcher, [ref]$tokens, [ref]$parseErrors) | Out-Null
if ($parseErrors.Count -gt 0) {
    $messages = $parseErrors | ForEach-Object { $_.Message }
    throw "Launcher.ps1 has PowerShell syntax errors: $($messages -join '; ')"
}

$profiles = Get-Content -LiteralPath $profilesPath -Raw | ConvertFrom-Json
$enabled = @($profiles.profiles | Where-Object { $_.launchAllowed } | ForEach-Object { $_.productVersion })
if ($enabled.Count -ne 1 -or $enabled[0] -ne '2.7.1.0') {
    throw 'Only the reviewed Elden Ring 2.7.1.0 native profile may be enabled.'
}
foreach ($profile in $profiles.profiles) {
    if ($profile.productVersion -ne '2.7.1.0' -and $profile.launchAllowed) {
        throw "Unreviewed Elden Ring profile is launch-enabled: $($profile.productVersion)"
    }
}

$source = Get-Content -LiteralPath $launcher -Raw
if ($source -match '(?im)^\s*\$Host\s*=') { throw 'Launcher assigns to PowerShell automatic variable $Host.' }
if ($source -match '(?i)Stop-Process\s+[^\r\n]*-Force') { throw 'Launcher must not force-stop processes.' }
if ($source -match '(?i)Remove-Item\s+[^\r\n]*session\.lock') { throw 'Launcher must not remove Minecraft world locks.' }
if ($source -notmatch 'function Close-OldMinecraftInstances') { throw 'Graceful Minecraft instance cleanup is missing.' }
if ($source -notmatch "'--online',\s*'false'") { throw 'ME3 must be invoked with online matchmaking disabled.' }

Write-Output 'Launcher source syntax and compatibility/safety policy checks passed.'
