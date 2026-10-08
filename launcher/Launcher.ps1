param(
    [switch]$SelfTest
)

$ErrorActionPreference = 'Stop'
$script:Root = $PSScriptRoot
$script:PackageRoot = Join-Path $script:Root 'package'
$script:MainRoot = Join-Path $script:PackageRoot 'main'
$script:MinecraftRoot = Join-Path $script:PackageRoot 'minecraft'
$script:RuntimeRoot = Join-Path $script:Root 'runtime'
$script:ProfilesPath = Join-Path $script:Root 'compatibility-profiles.json'
$script:Profiles = Get-Content -LiteralPath $script:ProfilesPath -Raw | ConvertFrom-Json
$script:Me3Path = Join-Path $script:MainRoot 'me3\bin\me3.exe'
$script:Me3ProfilePath = Join-Path $script:MainRoot 'minecraft-ring.me3'
$script:NativeLoaderPath = Join-Path $script:MainRoot 'bridge\erbridge_loader.dll'
$script:PrismPath = Join-Path $script:MinecraftRoot 'Prism\prismlauncher.exe'
$script:PrismMods = Join-Path $script:MinecraftRoot 'Prism\instances\MinecraftRing\.minecraft\mods'
$script:BridgeLog = Join-Path $script:RuntimeRoot 'er-bridge.log'
$script:LauncherLog = Join-Path $script:Root 'launcher.log'

function Write-LauncherLog([string]$Message) {
    $line = '[{0}] {1}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Message
    Add-Content -LiteralPath $script:LauncherLog -Value $line -Encoding UTF8
}

function Expand-SafeZip([string]$ArchivePath, [string]$Destination) {
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $destinationFull = [IO.Path]::GetFullPath($Destination).TrimEnd('\') + '\'
    New-Item -ItemType Directory -Path $Destination -Force | Out-Null
    $archive = [IO.Compression.ZipFile]::OpenRead($ArchivePath)
    try {
        foreach ($entry in $archive.Entries) {
            $relative = $entry.FullName.Replace('/', '\')
            if ([IO.Path]::IsPathRooted($relative) -or $relative -match '(^|\\)\.\.(\\|$)') {
                throw "Unsafe archive entry rejected: $($entry.FullName)"
            }
            $target = [IO.Path]::GetFullPath((Join-Path $Destination $relative))
            if (!$target.StartsWith($destinationFull, [StringComparison]::OrdinalIgnoreCase)) {
                throw "Archive entry escaped destination: $($entry.FullName)"
            }
            if ($entry.FullName.EndsWith('/')) {
                New-Item -ItemType Directory -Path $target -Force | Out-Null
                continue
            }
            New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force | Out-Null
            $inputStream = $entry.Open()
            try {
                $outputStream = [IO.File]::Open($target, [IO.FileMode]::Create, [IO.FileAccess]::Write, [IO.FileShare]::None)
                try { $inputStream.CopyTo($outputStream) } finally { $outputStream.Dispose() }
            } finally { $inputStream.Dispose() }
        }
    } finally { $archive.Dispose() }
}

function Initialize-Package {
    $required = @($script:Me3Path, $script:Me3ProfilePath, $script:NativeLoaderPath, $script:PrismPath,
        (Join-Path $script:PrismMods 'er-bridge-0.3.0.jar'),
        (Join-Path $script:PrismMods 'fabric-api-0.116.17+1.21.1.jar'))
    if (($required | Where-Object { !(Test-Path -LiteralPath $_ -PathType Leaf) }).Count -eq 0) { return }

    $gameArchive = Join-Path $script:Root 'MinecraftRing-0.3.0.zip'
    $minecraftArchive = Join-Path $script:Root 'MinecraftRing-Minecraft-0.3.0.zip'
    foreach ($archive in @($gameArchive, $minecraftArchive)) {
        if (!(Test-Path -LiteralPath $archive -PathType Leaf)) { throw "Required package archive is missing: $archive" }
    }
    if (Test-Path -LiteralPath $script:PackageRoot) {
        throw "The package folder exists but is incomplete. Preserve it, then extract this launcher into a fresh folder: $script:PackageRoot"
    }
    New-Item -ItemType Directory -Path $script:PackageRoot | Out-Null
    Expand-SafeZip $gameArchive $script:MainRoot
    Expand-SafeZip $minecraftArchive $script:MinecraftRoot
    $missing = @($required | Where-Object { !(Test-Path -LiteralPath $_ -PathType Leaf) })
    if ($missing.Count -gt 0) { throw "Package extraction is incomplete: $($missing -join ', ')" }
    Write-LauncherLog 'Original Minecraft Ring 0.3.0 package archives extracted and verified.'
}

function Get-GameVersion([string]$ExePath) {
    if (!(Test-Path -LiteralPath $ExePath -PathType Leaf)) { return $null }
    $product = [Diagnostics.FileVersionInfo]::GetVersionInfo($ExePath).ProductVersion
    if ($product -match '(\d+\.\d+\.\d+\.\d+)') { return $Matches[1] }
    if ($product -match '(\d+\.\d+\.\d+)') { return $Matches[1] }
    return $product
}

function Find-Profile([string]$Version) {
    return $script:Profiles.profiles | Where-Object { $_.productVersion -eq $Version } | Select-Object -First 1
}

function Quote-ProcessArgument([string]$Value) {
    return '"' + $Value.Replace('"', '\"') + '"'
}

function Get-Me3Arguments([string]$GameExe, [string]$ProfilePath, [bool]$SkipSteamInit) {
    $me3Args = @('launch', '--game', 'eldenring', '--exe', (Quote-ProcessArgument $GameExe), '--profile',
        (Quote-ProcessArgument $ProfilePath), '--online', 'false')
    if ($SkipSteamInit) { $me3Args += @('--skip-steam-init', 'true') }
    return ,$me3Args
}

function Get-TLauncherProfileProblem([string]$GameDir) {
    if ([string]::IsNullOrWhiteSpace($GameDir) -or !(Test-Path -LiteralPath $GameDir -PathType Container)) {
        return 'Choose the TLauncher game directory first.'
    }
    # Fabric's generated version metadata may omit inheritsFrom when consumed by
    # TLauncher. Validate the profile identity, client entry point, and loader library.
    $profileId = 'fabric-loader-0.19.5-1.21.1'
    $profileDir = Join-Path $GameDir "versions\$profileId"
    $profileJson = Join-Path $profileDir "$profileId.json"
    $profileJar = Join-Path $profileDir "$profileId.jar"
    if (!(Test-Path -LiteralPath $profileJson -PathType Leaf) -or !(Test-Path -LiteralPath $profileJar -PathType Leaf)) {
        return 'Install Fabric Loader 0.19.5 for Minecraft 1.21.1 into this TLauncher game directory first.'
    }
    try { $metadata = Get-Content -LiteralPath $profileJson -Raw | ConvertFrom-Json } catch {
        return 'The TLauncher Fabric profile metadata is invalid. Reinstall Fabric Loader 0.19.5 for Minecraft 1.21.1.'
    }
    if ($metadata.id -ne $profileId -or $metadata.mainClass -ne 'net.fabricmc.loader.impl.launch.knot.KnotClient') {
        return 'The TLauncher Fabric profile is not the expected Fabric 0.19.5 client profile. Reinstall Fabric Loader 0.19.5 for Minecraft 1.21.1.'
    }
    $loaderLibrary = @($metadata.libraries | Where-Object { $_.name -ceq 'net.fabricmc:fabric-loader:0.19.5' })
    if ($loaderLibrary.Count -eq 0) {
        return 'The selected TLauncher profile does not contain Fabric Loader 0.19.5. Do not use the generic Fabric 1.21.1 profile if it resolves to Loader 0.17.2.'
    }
    return $null
}

function Test-TLauncherSetup([string]$GameDir) {
    $profileProblem = Get-TLauncherProfileProblem $GameDir
    if ($profileProblem) { return $profileProblem }
    foreach ($name in @('er-bridge-0.3.0.jar', 'fabric-api-0.116.17+1.21.1.jar')) {
        $source = Join-Path $script:PrismMods $name
        $target = Join-Path $GameDir "mods\$name"
        if (!(Test-Path -LiteralPath $target -PathType Leaf)) {
            return "Required mod is missing from TLauncher mods: $name. Use Prepare TLauncher mods."
        }
        if (!(Test-Path -LiteralPath $source -PathType Leaf)) { return "Bundled mod is missing from the private launcher: $name." }
        $sourceHash = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash
        $targetHash = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash
        if ($sourceHash -ne $targetHash) { return "TLauncher has a different $name. Back it up or remove it yourself, then use Prepare TLauncher mods; no files were changed." }
    }
    return $null
}

function Install-TLauncherMods([string]$GameDir) {
    $profileProblem = Get-TLauncherProfileProblem $GameDir
    if ($profileProblem) {
        throw 'TLauncher needs the Fabric Loader 0.19.5 / Minecraft 1.21.1 profile before mod files can be prepared.'
    }
    $sourceFiles = @('er-bridge-0.3.0.jar', 'fabric-api-0.116.17+1.21.1.jar') | ForEach-Object { Join-Path $script:PrismMods $_ }
    foreach ($source in $sourceFiles) { if (!(Test-Path -LiteralPath $source -PathType Leaf)) { throw "Bundled mod is missing: $source" } }
    $modDir = Join-Path $GameDir 'mods'
    New-Item -ItemType Directory -Path $modDir -Force | Out-Null
    foreach ($source in $sourceFiles) {
        $target = Join-Path $modDir (Split-Path -Leaf $source)
        if (Test-Path -LiteralPath $target -PathType Leaf) {
            $sourceHash = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash
            $targetHash = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash
            if ($sourceHash -ne $targetHash) {
                throw "A different file already exists at $target. Nothing was copied or overwritten; move or back it up yourself, then retry."
            }
        }
    }
    $copied = 0
    foreach ($source in $sourceFiles) {
        $target = Join-Path $modDir (Split-Path -Leaf $source)
        if (Test-Path -LiteralPath $target -PathType Leaf) {
            $sourceHash = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash
            $targetHash = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash
            if ($sourceHash -eq $targetHash) { continue }
            throw "A different file already exists at $target. Nothing was overwritten; move or back it up yourself, then retry."
        }
        Copy-Item -LiteralPath $source -Destination $target
        $copied++
    }
    Write-LauncherLog "Prepared TLauncher mods directory; copied $copied file(s)."
    $setupProblem = Test-TLauncherSetup $GameDir
    if ($setupProblem) { throw "TLauncher setup is incomplete after copying mod files: $setupProblem" }
    return "TLauncher mods are ready at $modDir."
}

function Read-BridgeHeader {
    $sharedFile = Join-Path $script:RuntimeRoot 'bridge.shm'
    if (!(Test-Path -LiteralPath $sharedFile -PathType Leaf)) { return $null }
    try {
        $header = New-Object byte[] 128
        $stream = [IO.File]::Open($sharedFile, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite)
        try { $read = $stream.Read($header, 0, $header.Length) } finally { $stream.Dispose() }
        if ($read -ne 128 -or [BitConverter]::ToUInt32($header, 0) -ne 0x434D484D) { return $null }
        return [PSCustomObject]@{
            HostProcessId = [BitConverter]::ToUInt32($header, 0x20)
            CoreStatus = [BitConverter]::ToInt32($header, 0x44)
        }
    } catch { return $null }
}

function Get-BridgeOwnerState([object]$Bridge, [object]$Process, [string]$ExpectedExe, [datetime]$LaunchStartedAt) {
    if (!$Bridge -or !$Process -or !$Process.Path -or $Process.Id -ne $Bridge.HostProcessId) { return 'stale' }
    try {
        # Ignore the shared-memory header left by an earlier run, including a reused PID.
        if ($Process.StartTime -lt $LaunchStartedAt.AddSeconds(-2)) { return 'stale' }
        $actualExe = [IO.Path]::GetFullPath($Process.Path)
        $expectedPath = [IO.Path]::GetFullPath($ExpectedExe)
        if (!$actualExe.Equals($expectedPath, [StringComparison]::OrdinalIgnoreCase)) { return 'wrong-executable' }
        return 'current'
    } catch { return 'stale' }
}

function Test-IsMinecraftWindowTitle([string]$Title) {
    return ![string]::IsNullOrWhiteSpace($Title) -and $Title -match '(?i)Minecraft'
}

function Test-IsMinecraftJavaCommandLine([string]$CommandLine) {
    if ([string]::IsNullOrWhiteSpace($CommandLine)) { return $false }
    return $CommandLine -match '(?i)(net\.minecraft\.client\.main\.Main|net\.fabricmc\.loader\.impl\.launch\.knot\.KnotClient|cpw\.mods\.bootstraplauncher\.BootstrapLauncher)'
}

function Get-RunningMinecraftClientProcesses {
    # Task Manager labels Java processes by runtime vendor, not by game. Inspect the
    # client entry point in memory only; never display or log the full command line.
    try {
        $javaProcesses = @(Get-CimInstance -ClassName Win32_Process -Filter "Name='javaw.exe' OR Name='java.exe'" -ErrorAction Stop)
    } catch {
        throw "Could not safely check for existing Minecraft Java processes: $($_.Exception.Message). No game was started."
    }
    $gameIds = @{}
    foreach ($javaProcess in $javaProcesses) {
        if ((Test-IsMinecraftWindowTitle $javaProcess.Name) -or
            (Test-IsMinecraftJavaCommandLine ([string]$javaProcess.CommandLine))) {
            $gameIds[[int]$javaProcess.ProcessId] = $true
        }
    }
    return @(Get-Process -Name javaw,java -ErrorAction SilentlyContinue | Where-Object {
        $gameIds.ContainsKey([int]$_.Id) -or (Test-IsMinecraftWindowTitle $_.MainWindowTitle)
    })
}

function Close-OldMinecraftInstances([int]$TimeoutSeconds = 25) {
    $processes = @(Get-RunningMinecraftClientProcesses)
    foreach ($process in $processes) {
        try {
            $process.Refresh()
            if ($process.HasExited) { continue }
            if ($process.MainWindowHandle -eq 0 -or !$process.CloseMainWindow()) {
                throw "Minecraft PID $($process.Id) has no closable game window. Close that Minecraft process manually; no process was force-stopped."
            }
            if (!$process.WaitForExit($TimeoutSeconds * 1000)) {
                throw "Minecraft PID $($process.Id) did not close within $TimeoutSeconds seconds. It may still be saving. Close it manually; no process was force-stopped and no world files were changed."
            }
            Write-LauncherLog "Requested graceful close and waited for Minecraft game PID $($process.Id) to exit."
        } catch {
            if ($_.Exception.Message -match '^Minecraft PID ') { throw }
            throw "Could not safely close Minecraft PID $($process.Id): $($_.Exception.Message). Close it manually; no process was force-stopped."
        }
    }
    $remaining = @(Get-RunningMinecraftClientProcesses)
    if ($remaining.Count -gt 0) {
        $pids = ($remaining | ForEach-Object { $_.Id }) -join ', '
        throw "Minecraft game window(s) are still running (PID $pids). Close them manually; no process was force-stopped."
    }
    if ($processes.Count -gt 0) { Add-UiLog 'Closed previous Minecraft game window(s) normally before starting a fresh bridge session.' }
}

function Test-ExecutablePath([string]$Path) {
    if ([string]::IsNullOrWhiteSpace($Path) -or !(Test-Path -LiteralPath $Path -PathType Leaf)) { return $false }
    return [IO.Path]::GetFileName($Path).Equals('eldenring.exe', [StringComparison]::OrdinalIgnoreCase)
}

function Invoke-SelfTest {
    Initialize-Package
    $reservedAssignment = Select-String -LiteralPath $PSCommandPath -Pattern '^\s*\$(Host|Error|Matches|Args|Input|This|Null|True|False|foreach|switch)\s*=' -CaseSensitive:$false
    if ($reservedAssignment) { throw "PowerShell reserved automatic variable assignment found at line $($reservedAssignment[0].LineNumber)." }
    if (!(Test-IsMinecraftWindowTitle 'Minecraft* 1.21.1 - Singleplayer')) { throw 'Minecraft window-title detection self-test failed.' }
    if (Test-IsMinecraftWindowTitle 'Java(TM) Platform SE binary') { throw 'Generic Java process was incorrectly classified as Minecraft.' }
    if (!(Test-IsMinecraftJavaCommandLine 'javaw.exe net.fabricmc.loader.impl.launch.knot.KnotClient --gameDir C:\Users\Example\.minecraft')) { throw 'Fabric Minecraft process detection self-test failed.' }
    if (Test-IsMinecraftJavaCommandLine 'javaw.exe com.example.DesktopApplication --argument harmless') { throw 'Unrelated Java process was incorrectly classified as Minecraft.' }
    $expected = @{
        '2.7.1.0' = $true
        '2.7.1.1' = $false
        '2.6.0.0' = $false
        '9.9.9.9' = $false
    }
    foreach ($version in $expected.Keys) {
        $profile = Find-Profile $version
        $allowed = [bool]($profile -and $profile.launchAllowed)
        if ($allowed -ne $expected[$version]) { throw "Version-gate self-test failed for $version." }
    }
    $sampleArgs = Get-Me3Arguments 'D:\Owned Games\ELDEN RING\Game\eldenring.exe' 'C:\Private Folder\minecraft-ring.me3' $true
    if (($sampleArgs -notcontains '--online') -or ($sampleArgs -notcontains 'false') -or ($sampleArgs -notcontains '--skip-steam-init')) {
        throw 'ME3 argument self-test failed.'
    }
    if (($sampleArgs -notcontains '"D:\Owned Games\ELDEN RING\Game\eldenring.exe"') -or
        ($sampleArgs -notcontains '"C:\Private Folder\minecraft-ring.me3"')) { throw 'ME3 path quoting self-test failed.' }
    $testStarted = Get-Date
    $testBridge = [PSCustomObject]@{ HostProcessId = 4242 }
    $testHost = [PSCustomObject]@{ Id = 4242; Path = 'C:\Games\ELDEN RING\Game\eldenring.exe'; StartTime = $testStarted }
    if ((Get-BridgeOwnerState $testBridge $testHost $testHost.Path $testStarted) -ne 'current') { throw 'Fresh bridge owner self-test failed.' }
    $staleHost = [PSCustomObject]@{ Id = 4242; Path = $testHost.Path; StartTime = $testStarted.AddMinutes(-1) }
    if ((Get-BridgeOwnerState $testBridge $staleHost $testHost.Path $testStarted) -ne 'stale') { throw 'Stale bridge header self-test failed.' }
    $wrongHost = [PSCustomObject]@{ Id = 4242; Path = 'C:\Games\Other\eldenring.exe'; StartTime = $testStarted }
    if ((Get-BridgeOwnerState $testBridge $wrongHost $testHost.Path $testStarted) -ne 'wrong-executable') { throw 'Bridge executable identity self-test failed.' }
    $path = 'C:\Program Files (x86)\Steam\steamapps\common\ELDEN RING\Game\eldenring.exe'
    if (Test-Path -LiteralPath $path -PathType Leaf) {
        $localVersion = Get-GameVersion $path
        $localProfile = Find-Profile $localVersion
        if (!$localProfile) { Write-Output "Local Elden Ring ${localVersion}: no profile; launch remains blocked." }
        elseif ($localProfile.launchAllowed) { Write-Output "Local Elden Ring ${localVersion}: exact profile is allowed; this self-test does not claim runtime validation." }
        else { Write-Output "Local Elden Ring ${localVersion}: profile is present but launch remains blocked." }
    }
    $tlauncherGameDir = Join-Path $env:APPDATA '.minecraft'
    if (Test-Path -LiteralPath (Join-Path $tlauncherGameDir 'versions\fabric-loader-0.19.5-1.21.1')) {
        $tlauncherProblem = Test-TLauncherSetup $tlauncherGameDir
        if ($tlauncherProblem) { throw "Installed TLauncher profile self-test failed: $tlauncherProblem" }
        $genericProfile = Join-Path $tlauncherGameDir 'versions\Fabric 1.21.1\Fabric 1.21.1.json'
        if (Test-Path -LiteralPath $genericProfile) {
            $genericMetadata = Get-Content -LiteralPath $genericProfile -Raw | ConvertFrom-Json
            if (@($genericMetadata.libraries | Where-Object { $_.name -ceq 'net.fabricmc:fabric-loader:0.19.5' }).Count -gt 0) {
                throw 'TLauncher self-test expected the generic Fabric 1.21.1 profile not to pass unless it carries Loader 0.19.5.'
            }
        }
        Write-Output 'TLauncher profile metadata and required mod SHA-256 checks passed.'
    }
    Write-Output 'Package contents and version gate passed. Self-test did not launch either game or modify game files.'
}

if ($SelfTest) {
    Invoke-SelfTest
    exit 0
}

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
[Windows.Forms.Application]::EnableVisualStyles()
Initialize-Package
New-Item -ItemType Directory -Path $script:RuntimeRoot -Force | Out-Null

$form = New-Object Windows.Forms.Form
$form.Text = 'Minecraft Ring - Windows Launcher'
$form.StartPosition = 'CenterScreen'
$form.Size = New-Object Drawing.Size(850, 630)
$form.MinimumSize = New-Object Drawing.Size(780, 560)
$form.Font = New-Object Drawing.Font('Segoe UI', 9)

$heading = New-Object Windows.Forms.Label
$heading.Text = 'Minecraft Ring'
$heading.Font = New-Object Drawing.Font('Segoe UI Semibold', 18)
$heading.Location = New-Object Drawing.Point(22, 16)
$heading.Size = New-Object Drawing.Size(400, 34)
$form.Controls.Add($heading)

$subheading = New-Object Windows.Forms.Label
$subheading.Text = 'Experimental offline bridge - Minecraft 1.21.1 - TLauncher optional'
$subheading.Location = New-Object Drawing.Point(24, 52)
$subheading.Size = New-Object Drawing.Size(650, 22)
$form.Controls.Add($subheading)

$lblGame = New-Object Windows.Forms.Label
$lblGame.Text = 'Elden Ring executable'
$lblGame.Location = New-Object Drawing.Point(24, 91)
$lblGame.Size = New-Object Drawing.Size(240, 22)
$form.Controls.Add($lblGame)

$txtGame = New-Object Windows.Forms.TextBox
$txtGame.Location = New-Object Drawing.Point(24, 116)
$txtGame.Size = New-Object Drawing.Size(660, 25)
$localExe = 'C:\Program Files (x86)\Steam\steamapps\common\ELDEN RING\Game\eldenring.exe'
if (Test-Path -LiteralPath $localExe -PathType Leaf) { $txtGame.Text = $localExe }
$form.Controls.Add($txtGame)

$btnGame = New-Object Windows.Forms.Button
$btnGame.Text = 'Browse...'
$btnGame.Location = New-Object Drawing.Point(698, 114)
$btnGame.Size = New-Object Drawing.Size(110, 28)
$form.Controls.Add($btnGame)

$lblVersion = New-Object Windows.Forms.Label
$lblVersion.Location = New-Object Drawing.Point(24, 149)
$lblVersion.Size = New-Object Drawing.Size(780, 36)
$form.Controls.Add($lblVersion)

$grpMinecraft = New-Object Windows.Forms.GroupBox
$grpMinecraft.Text = 'Minecraft launcher'
$grpMinecraft.Location = New-Object Drawing.Point(24, 192)
$grpMinecraft.Size = New-Object Drawing.Size(784, 178)
$form.Controls.Add($grpMinecraft)

$radioPrism = New-Object Windows.Forms.RadioButton
$radioPrism.Text = 'Bundled portable Prism (recommended)'
$radioPrism.Checked = $true
$radioPrism.Location = New-Object Drawing.Point(16, 27)
$radioPrism.Size = New-Object Drawing.Size(300, 24)
$grpMinecraft.Controls.Add($radioPrism)

$radioTL = New-Object Windows.Forms.RadioButton
$radioTL.Text = 'Existing TLauncher (sign-in and game start remain manual)'
$radioTL.Location = New-Object Drawing.Point(16, 57)
$radioTL.Size = New-Object Drawing.Size(430, 24)
$grpMinecraft.Controls.Add($radioTL)

$lblTLexe = New-Object Windows.Forms.Label
$lblTLexe.Text = 'TLauncher.exe'
$lblTLexe.Location = New-Object Drawing.Point(16, 88)
$lblTLexe.Size = New-Object Drawing.Size(96, 22)
$grpMinecraft.Controls.Add($lblTLexe)

$txtTLexe = New-Object Windows.Forms.TextBox
$txtTLexe.Enabled = $false
$txtTLexe.Location = New-Object Drawing.Point(114, 86)
$txtTLexe.Size = New-Object Drawing.Size(540, 24)
$grpMinecraft.Controls.Add($txtTLexe)

$btnTLexe = New-Object Windows.Forms.Button
$btnTLexe.Text = 'Browse...'
$btnTLexe.Enabled = $false
$btnTLexe.Location = New-Object Drawing.Point(664, 84)
$btnTLexe.Size = New-Object Drawing.Size(100, 27)
$grpMinecraft.Controls.Add($btnTLexe)

$lblTLdir = New-Object Windows.Forms.Label
$lblTLdir.Text = 'Game folder'
$lblTLdir.Location = New-Object Drawing.Point(16, 120)
$lblTLdir.Size = New-Object Drawing.Size(96, 22)
$grpMinecraft.Controls.Add($lblTLdir)

$txtTLdir = New-Object Windows.Forms.TextBox
$txtTLdir.Text = Join-Path $env:APPDATA '.minecraft'
$txtTLdir.Enabled = $false
$txtTLdir.Location = New-Object Drawing.Point(114, 118)
$txtTLdir.Size = New-Object Drawing.Size(540, 24)
$grpMinecraft.Controls.Add($txtTLdir)

$btnTLdir = New-Object Windows.Forms.Button
$btnTLdir.Text = 'Browse...'
$btnTLdir.Enabled = $false
$btnTLdir.Location = New-Object Drawing.Point(664, 116)
$btnTLdir.Size = New-Object Drawing.Size(100, 27)
$grpMinecraft.Controls.Add($btnTLdir)

$btnPrepare = New-Object Windows.Forms.Button
$btnPrepare.Text = 'Prepare TLauncher mods'
$btnPrepare.Enabled = $false
$btnPrepare.Location = New-Object Drawing.Point(16, 145)
$btnPrepare.Size = New-Object Drawing.Size(190, 25)
$grpMinecraft.Controls.Add($btnPrepare)

$chkSkipSteam = New-Object Windows.Forms.CheckBox
$chkSkipSteam.Text = 'Skip Steam initialization (only for a legally owned non-Steam installation)'
$chkSkipSteam.Location = New-Object Drawing.Point(24, 382)
$chkSkipSteam.Size = New-Object Drawing.Size(600, 24)
$form.Controls.Add($chkSkipSteam)

$status = New-Object Windows.Forms.Label
$status.Text = 'Choose a supported Elden Ring build. Unknown builds are blocked.'
$status.Location = New-Object Drawing.Point(24, 414)
$status.Size = New-Object Drawing.Size(780, 42)
$form.Controls.Add($status)

$logBox = New-Object Windows.Forms.TextBox
$logBox.Multiline = $true
$logBox.ReadOnly = $true
$logBox.ScrollBars = 'Vertical'
$logBox.Location = New-Object Drawing.Point(24, 458)
$logBox.Size = New-Object Drawing.Size(784, 86)
$form.Controls.Add($logBox)

$btnStart = New-Object Windows.Forms.Button
$btnStart.Text = 'Launch offline'
$btnStart.Location = New-Object Drawing.Point(24, 554)
$btnStart.Size = New-Object Drawing.Size(160, 32)
$btnStart.Enabled = $false
$form.Controls.Add($btnStart)

$btnClose = New-Object Windows.Forms.Button
$btnClose.Text = 'Close'
$btnClose.Location = New-Object Drawing.Point(698, 554)
$btnClose.Size = New-Object Drawing.Size(110, 32)
$form.Controls.Add($btnClose)

$timer = New-Object Windows.Forms.Timer
$timer.Interval = 500
$script:WaitCount = 0
$script:Me3Process = $null
$script:LaunchStarted = $false

function Add-UiLog([string]$Message) {
    $logBox.AppendText($Message + [Environment]::NewLine)
    Write-LauncherLog $Message
}

function Refresh-GameStatus {
    $exe = $txtGame.Text.Trim()
    if (!(Test-ExecutablePath $exe)) {
        $lblVersion.Text = 'Select an eldenring.exe to check its build.'
        $status.Text = 'No Elden Ring executable selected.'
        $btnStart.Enabled = $false
        return
    }
    $version = Get-GameVersion $exe
    $profile = Find-Profile $version
    if (!$profile) {
        $lblVersion.Text = "Detected build: $version (no profile)"
        $status.Text = 'Unsupported build: no compatibility profile is available. Launch is blocked.'
        $btnStart.Enabled = $false
        return
    }
    $lblVersion.Text = "Detected build: $version - $($profile.verification)"
    if (!$profile.launchAllowed) {
        $status.Text = "Blocked: $($profile.note)"
        $btnStart.Enabled = $false
        return
    }
    $status.Text = "Ready to launch offline. $($profile.note)"
    $btnStart.Enabled = $true
}

$txtGame.add_TextChanged({ Refresh-GameStatus })
$btnGame.add_Click({
    $dialog = New-Object Windows.Forms.OpenFileDialog
    $dialog.Title = 'Select Elden Ring executable'
    $dialog.Filter = 'Elden Ring executable (eldenring.exe)|eldenring.exe|Executable files (*.exe)|*.exe'
    if ($dialog.ShowDialog() -eq [Windows.Forms.DialogResult]::OK) { $txtGame.Text = $dialog.FileName }
})

$btnTLexe.add_Click({
    $dialog = New-Object Windows.Forms.OpenFileDialog
    $dialog.Title = 'Select your existing TLauncher executable'
    $dialog.Filter = 'TLauncher executable|TLauncher.exe|Executable files (*.exe)|*.exe'
    if ($dialog.ShowDialog() -eq [Windows.Forms.DialogResult]::OK) { $txtTLexe.Text = $dialog.FileName }
})
$btnTLdir.add_Click({
    $dialog = New-Object Windows.Forms.FolderBrowserDialog
    $dialog.Description = 'Select the configured TLauncher Minecraft game directory (usually %APPDATA%\.minecraft).'
    if ($dialog.ShowDialog() -eq [Windows.Forms.DialogResult]::OK) { $txtTLdir.Text = $dialog.SelectedPath }
})
$btnPrepare.add_Click({
    try {
        $result = Install-TLauncherMods $txtTLdir.Text.Trim()
        [Windows.Forms.MessageBox]::Show($result, 'TLauncher setup', 'OK', 'Information') | Out-Null
        Add-UiLog $result
    } catch {
        [Windows.Forms.MessageBox]::Show($_.Exception.Message, 'TLauncher setup', 'OK', 'Warning') | Out-Null
    }
})
$radioTL.add_CheckedChanged({
    $enabled = $radioTL.Checked
    $txtTLexe.Enabled = $enabled
    $txtTLdir.Enabled = $enabled
    $btnTLexe.Enabled = $enabled
    $btnTLdir.Enabled = $enabled
    $btnPrepare.Enabled = $enabled
})
$btnClose.add_Click({ $form.Close() })

$btnStart.add_Click({
    try {
        Refresh-GameStatus
        if (!$btnStart.Enabled) { return }
        $gameExe = [IO.Path]::GetFullPath($txtGame.Text.Trim())
        if ($radioTL.Checked) {
            if (!(Test-Path -LiteralPath $txtTLexe.Text.Trim() -PathType Leaf)) { throw 'Select your existing TLauncher.exe.' }
            $problem = Test-TLauncherSetup $txtTLdir.Text.Trim()
            if ($problem) { throw $problem }
        }
        if ($script:LaunchStarted) { throw 'A launch is already in progress.' }
        $runningGame = @(Get-Process -Name eldenring -ErrorAction SilentlyContinue)
        if ($runningGame.Count -gt 0) { throw 'Close every Elden Ring session first. Start it only once through this launcher, offline.' }
        Close-OldMinecraftInstances
        $script:LaunchStarted = $true
        $btnStart.Enabled = $false
        New-Item -ItemType Directory -Path $script:RuntimeRoot -Force | Out-Null
        $env:ERMC_DIR = $script:RuntimeRoot
        $env:ERBRIDGE = '1'
        if ($radioTL.Checked) {
            $tlProcessName = [IO.Path]::GetFileNameWithoutExtension($txtTLexe.Text.Trim())
            $tlExePath = [IO.Path]::GetFullPath($txtTLexe.Text.Trim())
            $runningTLauncher = Get-Process -Name $tlProcessName -ErrorAction SilentlyContinue | Where-Object {
                $_.Path -and [IO.Path]::GetFullPath($_.Path).Equals($tlExePath, [StringComparison]::OrdinalIgnoreCase)
            } | Select-Object -First 1
            if ($runningTLauncher) { throw 'Close this TLauncher window and any Minecraft game it started first. This launcher must start a fresh TLauncher process so the bridge path is inherited; this does not sign you out.' }
        } else {
            $runningPrism = Get-Process -Name prismlauncher -ErrorAction SilentlyContinue | Select-Object -First 1
            if ($runningPrism) { throw 'Close Prism first; this launcher must start it so the bridge runtime path is inherited.' }
        }
        $me3Args = Get-Me3Arguments $gameExe $script:Me3ProfilePath $chkSkipSteam.Checked
        $script:HostLaunchStartedAt = Get-Date
        $script:LastIgnoredBridgePid = 0
        $stdout = Join-Path $script:RuntimeRoot 'me3.stdout.log'
        $stderr = Join-Path $script:RuntimeRoot 'me3.stderr.log'
        $script:Me3Process = Start-Process -FilePath $script:Me3Path -ArgumentList $me3Args -WorkingDirectory $script:MainRoot -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
        $script:WaitCount = 0
        $timer.Start()
        $status.Text = 'Starting Elden Ring offline. Waiting for this run''s bridge; keep this window open and do not launch TLauncher manually.'
        Add-UiLog "Started ME3 (PID $($script:Me3Process.Id)) for $gameExe. Online matchmaking is explicitly disabled."
    } catch {
        $script:LaunchStarted = $false
        $btnStart.Enabled = $true
        $status.Text = $_.Exception.Message
        Add-UiLog "Launch refused/failed: $($_.Exception.Message)"
        [Windows.Forms.MessageBox]::Show($_.Exception.Message, 'Launch failed', 'OK', 'Warning') | Out-Null
    }
})

$timer.add_Tick({
    $script:WaitCount++
    $bridge = Read-BridgeHeader
    if ($bridge -and $bridge.HostProcessId -gt 0) {
        $hostProcess = Get-Process -Id $bridge.HostProcessId -ErrorAction SilentlyContinue
        $ownerState = Get-BridgeOwnerState $bridge $hostProcess $txtGame.Text.Trim() $script:HostLaunchStartedAt
        if ($ownerState -eq 'stale') {
            if ($script:LastIgnoredBridgePid -ne $bridge.HostProcessId) {
                $script:LastIgnoredBridgePid = $bridge.HostProcessId
                Add-UiLog "Ignoring stale bridge header from PID $($bridge.HostProcessId); waiting for the newly launched Elden Ring process."
            }
        } elseif ($ownerState -eq 'wrong-executable') {
            $timer.Stop()
            $script:LaunchStarted = $false
            $btnStart.Enabled = $true
            $status.Text = 'The new bridge process belongs to a different Elden Ring executable; Minecraft was not launched.'
            Add-UiLog $status.Text
            return
        } elseif ($ownerState -eq 'current') {
            if ($bridge.CoreStatus -lt 0) {
                $timer.Stop()
                $script:LaunchStarted = $false
                $btnStart.Enabled = $true
                $status.Text = "Bridge rejected this build (status $($bridge.CoreStatus)). See runtime\er-bridge.log."
                Add-UiLog $status.Text
                return
            }
            if ($bridge.CoreStatus -eq 1) {
                $timer.Stop()
                try {
                    if ($radioPrism.Checked) {
                        $prismDir = Split-Path -Parent $script:PrismPath
                        $minecraft = Start-Process -FilePath $script:PrismPath -ArgumentList @('--launch', 'MinecraftRing') -WorkingDirectory $prismDir -PassThru
                        $status.Text = "Bridge ready. Prism started (PID $($minecraft.Id)); finish sign-in/setup there if prompted."
                        Add-UiLog $status.Text
                    } else {
                        $tlExe = [IO.Path]::GetFullPath($txtTLexe.Text.Trim())
                        $tlDir = Split-Path -Parent $tlExe
                        $launcher = Start-Process -FilePath $tlExe -WorkingDirectory $tlDir -PassThru
                        $status.Text = "Bridge ready. TLauncher opened (PID $($launcher.Id)). Sign in yourself if needed, select Fabric Loader 0.19.5 for Minecraft 1.21.1, then press Enter the game. Do not open another Minecraft instance."
                        Add-UiLog $status.Text
                    }
                } catch {
                    $script:LaunchStarted = $false
                    $btnStart.Enabled = $true
                    $status.Text = "Bridge ready, but Minecraft launcher failed: $($_.Exception.Message)"
                    Add-UiLog $status.Text
                }
                return
            }
        }
    }
    if (($script:Me3Process -and $script:Me3Process.HasExited) -or $script:WaitCount -ge 180) {
        $timer.Stop()
        $script:LaunchStarted = $false
        $btnStart.Enabled = $true
        $status.Text = 'Elden Ring bridge did not become ready; Minecraft was not launched. Check runtime logs.'
        Add-UiLog $status.Text
    }
})

$form.add_Shown({ Refresh-GameStatus; Add-UiLog 'Launcher ready. Elden Ring starts offline through bundled ME3; online matchmaking is disabled.' })
[void]$form.ShowDialog()
