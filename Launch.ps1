$ErrorActionPreference = 'Stop'
$ProjectRoot = $PSScriptRoot
$Install = Get-Content -LiteralPath "$ProjectRoot\installation.json" -Raw | ConvertFrom-Json
$GameExe = Join-Path $Install.game_dir 'eldenring.exe'
if (!(Test-Path -LiteralPath $GameExe)) { throw "Elden Ring was not found: $GameExe" }
$env:ERMC_DIR = Join-Path $ProjectRoot 'runtime'
$env:ERBRIDGE = '1'
$env:JAVA_HOME = (Get-ChildItem "$ProjectRoot\.tools\java" -Directory | Select-Object -First 1).FullName
$env:GRADLE_USER_HOME = Join-Path $ProjectRoot '.tools\gradle-home'
New-Item -ItemType Directory -Path $env:ERMC_DIR -Force | Out-Null
$SharedFile = Join-Path $env:ERMC_DIR 'bridge.shm'
function Read-BridgeHeader {
    if (!(Test-Path -LiteralPath $SharedFile)) { return $null }
    try {
        $Header = New-Object byte[] 128
        $Stream = [IO.File]::Open($SharedFile, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite)
        try { $Read = $Stream.Read($Header, 0, $Header.Length) } finally { $Stream.Dispose() }
        if ($Read -ne $Header.Length -or [BitConverter]::ToUInt32($Header, 0) -ne 0x434D484D) { return $null }
        return [PSCustomObject]@{
            HostProcessId = [BitConverter]::ToUInt32($Header, 0x20)
            MinecraftProcessId = [BitConverter]::ToUInt32($Header, 0x24)
            MinecraftStartMs = [BitConverter]::ToUInt64($Header, 0x30)
            CoreStatus = [BitConverter]::ToInt32($Header, 0x44)
        }
    } catch { return $null }
}
$HostProcess = Get-Process eldenring -ErrorAction SilentlyContinue | Where-Object Path -eq $GameExe | Select-Object -First 1
if (!$HostProcess) {
    Write-Output 'Starting Elden Ring...'
    $HostProcess = Start-Process -FilePath $GameExe -WorkingDirectory $Install.game_dir -PassThru
}
Write-Output 'Waiting for Elden Ring to load the Minecraft bridge...'
$Watch = [Diagnostics.Stopwatch]::StartNew()
$Ready = $false
while ($Watch.Elapsed.TotalSeconds -lt 90) {
    $State = Read-BridgeHeader
    if ($State -and $State.HostProcessId -eq $HostProcess.Id) {
        if ($State.CoreStatus -eq 1) { $Ready = $true; break }
        if ($State.CoreStatus -lt 0) { throw "Elden Ring bridge failed to load (status $($State.CoreStatus)). See runtime\er-bridge.log." }
    }
    if (!(Get-Process -Id $HostProcess.Id -ErrorAction SilentlyContinue)) {
        throw 'Elden Ring closed during startup. Minecraft was not launched. See runtime\er-bridge.log.'
    }
    Start-Sleep -Milliseconds 500
}
if (!$Ready) { throw 'Elden Ring did not load the bridge. Close Elden Ring and start it through start.bat. See runtime\er-bridge.log.' }
Write-Output "Elden Ring bridge ready (PID $($HostProcess.Id)). Choose Continue in the game."
$State = Read-BridgeHeader
if ($State -and $State.MinecraftProcessId -gt 0) {
    $MinecraftProcess = Get-Process -Id $State.MinecraftProcessId -ErrorAction SilentlyContinue
    if ($MinecraftProcess -and $MinecraftProcess.Path -eq (Join-Path $env:JAVA_HOME 'bin\java.exe')) {
        $StartedMs = [DateTimeOffset]::new($MinecraftProcess.StartTime).ToUnixTimeMilliseconds()
        if ([Math]::Abs([double]$StartedMs - [double]$State.MinecraftStartMs) -lt 120000) {
            Write-Output 'Minecraft bridge is already running. Use F8 to switch control.'
            return
        }
    }
}
Write-Output 'Starting Minecraft... The runClient task stays active while you play.'
$Gradle = (Get-ChildItem "$ProjectRoot\.tools\gradle" -Directory | Select-Object -First 1).FullName + '\bin\gradle.bat'
& $Gradle '-p' "$ProjectRoot\bridge-base\elden-ring\mc-bridge" '--no-daemon' '--no-configuration-cache' 'remapJar' 'runClient'
if ($LASTEXITCODE -ne 0) { throw 'Minecraft launch failed; see the console and run/logs/latest.log.' }
