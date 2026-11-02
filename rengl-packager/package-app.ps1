<#
.SYNOPSIS
    Builds app-images (and optionally installers) for any RenGL Maven project, for Windows, Linux and macOS, from this computer.

.DESCRIPTION
    The settings live in a file in your project, so the only long command is the one-time setup:

        cd C:\Path\To\MyApp
        powershell -ExecutionPolicy Bypass -File C:\Path\To\RenGL\rengl-packager\package-app.ps1 -Init

    -Init writes rengl-package.properties (the app's name, main class, platforms, icons) with its best guesses, and
    package.cmd, a one-line launcher for this script. Check the properties file, then build with:

        package.cmd

    It builds the project with Maven, collects the jars and every platform's natives into target\input, and runs the RenGL
    packager. The result is in dist\app-images\ of the project: a folder and an archive per platform, or with
    installer=true (or -Installer true) a setup.exe (Windows) and a .deb (Linux) in place of the .zip and .tar.gz. macOS
    always gets a .tar.gz.

    Every setting is also a parameter of this script. A parameter wins over the file, so package.cmd -Platform linux-x64
    builds Linux once without editing anything. A project with no file at all works with -Name and -MainClass.

    Before the first run, in the RenGL folder (once, and again after updating the engine):
        .\mvnw.cmd install -DskipTests
        powershell -ExecutionPolicy Bypass -File rengl-packager\install-natives.ps1

    It needs JDK 25 and, the first time a platform is built, the internet.

.PARAMETER Init
    Write rengl-package.properties and package.cmd in the project, with settings guessed from its pom.xml and sources, and
    stop. Neither file is overwritten.

.PARAMETER Name
    The app's name, also its launcher's file name. File setting: name.

.PARAMETER MainClass
    The class with main(), e.g. com.example.Main. File setting: mainClass.

.PARAMETER Project
    The project folder. Default: the current folder.

.PARAMETER MainJar
    The app's jar in target\. Default: the jar Maven built there. File setting: mainJar.

.PARAMETER Platform
    Comma separated: windows-x64, linux-x64, linux-arm64, macos-x64, macos-arm64, or all. Default: all. File setting: platform.

.PARAMETER Installer
    false (the default): a .zip and .tar.gz per platform.
    true: a setup.exe (Windows) and a .deb (Linux) instead. The setup's window is a RenGL app (a folder, a progress bar,
    your own look to change; see me.piitex.engine.installer in rengl-packager). It needs the RenGL folder built (mvnw install, step 1 above),
    and falls back to the plain Windows window on a computer that cannot open the RenGL one.
    plain: the same, but the setup uses the small plain Windows window, which makes it about 50 MB smaller.
    File setting: installer.

.PARAMETER NoArchive
    Leave the folders; write no archives or installers. File setting: noArchive=true.

.PARAMETER Icon
    Icon files: an .ico for Windows, a .png for Linux, and icns=<square 1024 px png> for macOS. Repeat or comma separate.
    File setting: icon.

.PARAMETER Version
    The app's version. Default: 1.0.0. File setting: version.

.PARAMETER Vendor
    Shown in the setup and the .deb. File setting: vendor.

.PARAMETER Description
    One line about the app, shown in the .deb and the setup. File setting: description.

.PARAMETER Identifier
    The macOS bundle id, which also names the Windows uninstall entry. Default: me.rengl.<name>. File setting: identifier.

.PARAMETER AddModules
    Java modules the app loads by reflection, which the packager cannot see, e.g. java.logging,java.prefs. File setting: addModules.

.PARAMETER Content
    A folder of files to ship beside the jars (assets, default data). Repeat or comma separate. File setting: content.

.PARAMETER FileAssociation
    A file type the app opens, as ext[,mime[,description]]. Repeat for several. macOS lists it in Info.plist; the Windows
    setup adds the app to the type's Open with list. File setting: fileAssociation (one line per type).

.PARAMETER ShellVerb
    An Explorer right-click entry the Windows setup adds, as target|id|label|args, e.g.
    '.zip|OpenZip.Open|Open with OpenZip|"%1"'. Repeat for several. File setting: shellVerb (one line per entry).

.PARAMETER DocumentTypes
    A file of Info.plist entries (the <array> of document types) that replaces the types made from FileAssociation on
    macOS, relative to the project. File setting: documentTypes.

.PARAMETER Output
    Where to write, relative to the project. Default: dist\app-images. Keep it short; Windows paths over about 260
    characters fail. File setting: output.
#>
param(
    [switch]$Init,
    [string]$Name,
    [string]$MainClass,
    [string]$Project = (Get-Location).Path,
    [string]$MainJar,
    [string]$Platform = 'all',
    [ValidateSet('true', 'false', 'plain')][string]$Installer = 'false',
    [switch]$NoArchive,
    [string[]]$Icon = @(),
    [string]$Version = '1.0.0',
    [string]$Vendor = '',
    [string]$Description = '',
    [string]$Identifier = '',
    [string]$AddModules = '',
    [string[]]$Content = @(),
    [string[]]$FileAssociation = @(),
    [string[]]$ShellVerb = @(),
    [string]$DocumentTypes = '',
    [string]$Output = 'dist\app-images'
)

$ErrorActionPreference = 'Stop'
$bound = $PSBoundParameters
$RenglRoot = Split-Path $PSScriptRoot -Parent
$Repository = Join-Path $env:USERPROFILE '.m2\repository\me\piitex\engine'
$ConfigName = 'rengl-package.properties'
# The engine's natives jar for each platform: LWJGL's and Skiko's native libraries.
$Natives = [ordered]@{
    'windows-x64' = 'natives-windows'
    'linux-x64'   = 'natives-linux'
    'linux-arm64' = 'natives-linux-arm64'
    'macos-x64'   = 'natives-macos'
    'macos-arm64' = 'natives-macos-arm64'
}
# The settings file's keys (lower case) and the parameters they stand for.
$Settings = [ordered]@{
    'name' = 'Name'; 'mainclass' = 'MainClass'; 'mainjar' = 'MainJar'; 'platform' = 'Platform'
    'installer' = 'Installer'; 'noarchive' = 'NoArchive'; 'icon' = 'Icon'; 'version' = 'Version'
    'vendor' = 'Vendor'; 'description' = 'Description'; 'identifier' = 'Identifier'; 'addmodules' = 'AddModules'
    'content' = 'Content'; 'output' = 'Output'
    'fileassociation' = 'FileAssociation'; 'shellverb' = 'ShellVerb'; 'documenttypes' = 'DocumentTypes'
}
# Settings that may be given on several lines, one value each (their values hold commas and bars, so they are not split).
$Repeating = @('FileAssociation', 'ShellVerb')

function Fail($message) {
    Write-Error $message
    exit 1
}

function Check($what) {
    if ($LASTEXITCODE -ne 0) { Fail "$what failed (exit code $LASTEXITCODE)" }
}

foreach ($tool in 'java', 'jdeps', 'jlink') {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) { Fail "$tool not found. Install JDK 25 and put its bin folder on PATH (or set JAVA_HOME)." }
}
if (-not (Test-Path (Join-Path $Project 'pom.xml'))) {
    Fail "There is no pom.xml in $Project. Run this from your project's folder, or pass -Project <folder>."
}
$Project = (Resolve-Path $Project).Path
Set-Location $Project

if ($Init) {
    $configPath = Join-Path $Project $ConfigName
    $launcherPath = Join-Path $Project 'package.cmd'
    if (Test-Path $configPath) { Fail "$ConfigName already exists in $Project. Edit it, or delete it to start over." }

    $pom = Get-Content 'pom.xml' -Raw
    # The parent's id and version are not the project's own.
    $own = [regex]::Replace($pom, '(?s)<parent>.*?</parent>', '')
    $guessName = [regex]::Match($own, '<artifactId>\s*([^<\s]+)\s*</artifactId>').Groups[1].Value
    if (-not $guessName) { $guessName = Split-Path $Project -Leaf }
    $guessVersion = [regex]::Match($own, '(?s)</artifactId>\s*<version>\s*(\d+(?:\.\d+)*)').Groups[1].Value
    if (-not $guessVersion) { $guessVersion = '1.0.0' }

    $mains = @()
    $pomMain = [regex]::Match($pom, '<mainClass>\s*([^<\s]+)\s*</mainClass>').Groups[1].Value
    if ($pomMain -and $pomMain -notmatch '\$\{') { $mains += $pomMain }
    $src = Join-Path $Project 'src\main\java'
    if (Test-Path $src) {
        $mains += Get-ChildItem $src -Recurse -Filter '*.java' |
            Where-Object { Select-String -Path $_.FullName -Pattern 'static\s+void\s+main\s*\(' -Quiet } |
            ForEach-Object { ($_.FullName.Substring($src.Length + 1) -replace '\.java$', '') -replace '[\\/]', '.' }
    }
    $mains = @($mains | Select-Object -Unique)
    $guessMain = if ($mains.Count) { $mains[0] } else { '' }

    $lines = @(
        '# RenGL packaging settings for this project. Build with package.cmd, in this folder.',
        '# Lines starting with # are comments, and a setting with nothing after the = is ignored.',
        '# A parameter on the command line wins over this file: package.cmd -Platform linux-x64',
        '',
        '# The app''s name, which is also the name of its launcher (MyApp.exe).',
        "name=$guessName",
        '',
        '# The class with the main method, package included.'
    )
    if ($mains.Count -gt 1) { $lines += "# Classes with a main method: $($mains -join ', ')" }
    if (-not $mains.Count) { $lines += '# No main method was found in src\main\java; fill this in.' }
    $lines += @(
        "mainClass=$guessMain",
        "version=$guessVersion",
        '',
        '# What to build: windows-x64, linux-x64, linux-arm64, macos-x64, macos-arm64, or all (comma separated).',
        '# Each platform other than this one downloads its Java modules the first time (about 85 MB each).',
        'platform=windows-x64',
        '',
        '# false: a .zip and .tar.gz per platform.',
        '# true: a setup.exe for Windows (with a RenGL window) and a .deb for Linux, instead.',
        '# plain: the same, with the small plain Windows window in the setup (about 50 MB smaller).',
        'installer=false',
        '',
        '# More settings, if you want them. Remove the # to use one.',
        '# The Windows, Linux and macOS icons. The macOS one is made from a square 1024 px png.',
        '# icon=MyApp.ico,MyApp.png,icns=MyApp-1024.png',
        '# vendor=Your Name',
        '# description=One line about the app',
        '# A folder of files to ship beside the jars (assets, default data).',
        '# content=assets',
        '# Java modules the app loads by reflection, which the packager cannot see.',
        '# addModules=java.logging,java.prefs'
    )
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($configPath, (($lines -join "`r`n") + "`r`n"), $utf8)
    Write-Host "Wrote ${ConfigName}:"
    Write-Host "  name=$guessName  mainClass=$guessMain  version=$guessVersion"

    if (Test-Path $launcherPath) {
        Write-Host 'package.cmd already exists; left as it is.'
    } else {
        $cmd = @(
            '@echo off',
            'rem Builds this project''s app with the RenGL packager. The settings are in rengl-package.properties.',
            'rem Parameters pass through: package.cmd -Installer true   or   package.cmd -Platform linux-x64',
            "powershell -NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`" %*",
            'exit /b %errorlevel%'
        )
        [System.IO.File]::WriteAllText($launcherPath, (($cmd -join "`r`n") + "`r`n"), (New-Object System.Text.UTF8Encoding($false)))
        Write-Host 'Wrote package.cmd'
    }
    if (-not $guessMain) { Write-Warning "No class with a main method was found. Set mainClass in $ConfigName." }
    Write-Host ''
    Write-Host "Check $ConfigName, then build with: package.cmd"
    exit 0
}

# The settings file, then the parameters over it.
$configPath = Join-Path $Project $ConfigName
if (Test-Path $configPath) {
    Write-Host "==> Settings from $ConfigName"
    $number = 0
    foreach ($line in Get-Content $configPath) {
        $number++
        $text = $line.Trim()
        if (-not $text -or $text.StartsWith('#')) { continue }
        $eq = $text.IndexOf('=')
        if ($eq -lt 1) { Fail "${ConfigName}, line ${number}: expected setting=value, found '$text'." }
        $key = $text.Substring(0, $eq).Trim().ToLower()
        $value = $text.Substring($eq + 1).Trim()
        if ($key -eq 'installerui') {
            Fail "${ConfigName}, line ${number}: installerUi is gone. installer=true now gives the setup with the RenGL window; installer=plain gives the small plain Windows window. Delete this line."
        }
        if (-not $Settings.Contains($key)) {
            Fail "${ConfigName}, line ${number}: '$($text.Substring(0, $eq).Trim())' is not a setting. The settings are: name, mainClass, mainJar, platform, installer, noArchive, icon, version, vendor, description, identifier, addModules, content, fileAssociation, shellVerb, documentTypes, output."
        }
        if (-not $value) { continue }
        $parameter = $Settings[$key]
        if ($bound.ContainsKey($parameter)) { continue }
        if ($parameter -eq 'Installer') {
            if ($value -notmatch '^(true|false|plain)$') { Fail "${ConfigName}, line ${number}: installer must be true, false or plain, not '$value'." }
            $Installer = $value
        } elseif ($parameter -eq 'NoArchive') {
            if ($value -notmatch '^(true|false)$') { Fail "${ConfigName}, line ${number}: noArchive must be true or false, not '$value'." }
            $NoArchive = [switch]($value -eq 'true')
        } elseif ($Repeating -contains $parameter) {
            $current = (Get-Variable -Name $parameter).Value
            Set-Variable -Name $parameter -Value (@($current) + $value)
        } else {
            Set-Variable -Name $parameter -Value $value
        }
    }
}
if (-not $Name) { Fail "The app has no name. Set name= in $ConfigName (package-app.ps1 -Init writes it), or pass -Name." }
if (-not $MainClass) { Fail "The app has no main class. Set mainClass= in $ConfigName (package-app.ps1 -Init writes it), or pass -MainClass." }

$platforms = if ($Platform -eq 'all') { @($Natives.Keys) } else { $Platform -split ',' | ForEach-Object { $_.Trim() } }
foreach ($p in $platforms) {
    if (-not $Natives.Contains($p)) { Fail "Unknown platform '$p'. Use: $($Natives.Keys -join ', '), or all." }
}
$Icon = $Icon | ForEach-Object { $_ -split ',' } | Where-Object { $_ }
$Content = $Content | ForEach-Object { $_ -split ',' } | Where-Object { $_ }

# The project's own Maven wrapper, or RenGL's.
$mvn = if (Test-Path '.\mvnw.cmd') { '.\mvnw.cmd' } elseif (Test-Path (Join-Path $RenglRoot 'mvnw.cmd')) { Join-Path $RenglRoot 'mvnw.cmd' } else { 'mvn' }

Write-Host "==> Building $Project"
# Keeps Maven's own JVM from printing its native-access and Unsafe warnings over the build's output.
$env:MAVEN_OPTS = '--enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow'
# junit comes in through RenGL's dependencies and has no business in an app.
& $mvn -q -DskipTests clean package dependency:copy-dependencies '-DincludeScope=runtime' '-DexcludeArtifactIds=junit' '-DoutputDirectory=target/input'
Check 'The Maven build'

if (-not $MainJar) {
    $jars = Get-ChildItem target -Filter '*.jar' | Where-Object { $_.Name -notmatch '-(sources|javadoc|tests)\.jar$|^original-' } | Sort-Object LastWriteTime -Descending
    if (-not $jars) { Fail "Maven built no jar in $Project\target. Does the project's pom make one?" }
    $MainJar = $jars[0].Name
}
if (-not (Test-Path "target\$MainJar")) { Fail "target\$MainJar does not exist." }
Copy-Item "target\$MainJar" target\input\ -Force

# The engine's version: from the RenGL jar the build brought, else the newest one in the local Maven repository.
$engine = Get-ChildItem target\input -Filter 'RenGL-*.jar' | Where-Object { $_.Name -match '^RenGL-(.+?)(-natives-.+)?\.jar$' } | Select-Object -First 1
if ($engine) {
    $RenglVersion = [regex]::Match($engine.Name, '^RenGL-(.+?)(-natives-.+)?\.jar$').Groups[1].Value
} else {
    $newest = Get-ChildItem "$Repository\RenGL" -Directory -ErrorAction SilentlyContinue | Where-Object { $_.Name -notmatch '\$' } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $newest) { Fail "The RenGL engine is not in your Maven repository. In the RenGL folder run: .\mvnw.cmd install -DskipTests" }
    $RenglVersion = $newest.Name
    Write-Warning "$MainJar's dependencies hold no RenGL jar; using the natives of RenGL $RenglVersion."
}

# The build brings the natives of the computer it runs on. The packager picks natives by file name and gives each image
# only its own platform's, so the other platforms' jars just have to be in the folder.
foreach ($p in $platforms) {
    $classifier = $Natives[$p]
    if (Get-ChildItem target\input -Filter "RenGL-*-$classifier.jar") { continue }
    $source = "$Repository\RenGL\$RenglVersion\RenGL-$RenglVersion-$classifier.jar"
    if (-not (Test-Path $source)) { Fail "The $p natives are missing ($source). In the RenGL folder run: powershell -ExecutionPolicy Bypass -File rengl-packager\install-natives.ps1" }
    Copy-Item $source target\input\
}

$Packager = if ($env:RENGL_PACKAGER) { $env:RENGL_PACKAGER } else { "$Repository\rengl-packager\$RenglVersion\rengl-packager-$RenglVersion.jar" }
if (-not (Test-Path $Packager)) {
    Fail "The RenGL packager is not at $Packager. In the RenGL folder run: .\mvnw.cmd install -DskipTests"
}

$packagerArgs = @(
    '--name', $Name, '--app-version', $Version,
    '--input', 'target\input', '--main-jar', $MainJar, '--main-class', $MainClass,
    '--platform', ($platforms -join ','), '--output', $Output
)
if ($Vendor) { $packagerArgs += '--vendor', $Vendor }
if ($Description) { $packagerArgs += '--description', $Description }
if ($Identifier) { $packagerArgs += '--identifier', $Identifier }
if ($AddModules) { $packagerArgs += '--add-modules', $AddModules }
foreach ($i in $Icon) { $packagerArgs += '--icon', $i }
foreach ($c in $Content) { $packagerArgs += '--content', $c }
foreach ($a in $FileAssociation) { $packagerArgs += '--file-association', $a }
# Windows PowerShell passes a quote inside an argument to a program without escaping it, and "%1" would lose its quotes.
foreach ($v in $ShellVerb) { $packagerArgs += '--shell-verb', ($v -replace '"', '\"') }
if ($DocumentTypes) { $packagerArgs += '--document-types', (Join-Path $Project $DocumentTypes) }
# -XstartOnFirstThread is added for macOS by the packager, where it belongs.
$packagerArgs += '--java-option', '--enable-native-access=ALL-UNNAMED'
if ($Installer -eq 'true') {
    $uiInput = Join-Path $RenglRoot 'rengl-packager\target\ui-input'
    if (-not (Test-Path $uiInput)) { Fail "The setup's RenGL window is not built ($uiInput). In the RenGL folder run: .\mvnw.cmd install -DskipTests   (or use installer=plain for the small plain Windows window)" }
    $packagerArgs += '--installer-ui', $uiInput
} elseif ($Installer -eq 'plain') {
    $packagerArgs += '--installer'
}
if ($NoArchive) { $packagerArgs += '--no-archive' }

Write-Host "==> RenGL packager ($($platforms -join ', '))"
& java --enable-native-access=ALL-UNNAMED -jar $Packager @packagerArgs
Check 'The RenGL packager'
