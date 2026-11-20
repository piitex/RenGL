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
    (the second adds the rengl-natives-<platform> artifacts of all six platforms)

    It needs JDK 25 and, the first time a platform is built, the internet.

    The work is done by the packager jar (java -jar rengl-packager-<version>.jar project); this script only finds the jar
    and passes the parameters on. On macOS and Linux, package-app.sh beside it does the same from any shell, with the
    same parameters, and -Init there writes package.sh instead of package.cmd. This script also runs in PowerShell 7 on
    macOS and Linux.

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

# Everything else happens in the packager jar (java -jar rengl-packager.jar project), which package-app.sh starts the same
# way on macOS and Linux. This script finds the jar and passes the parameters on.
$ErrorActionPreference = 'Stop'
$RenglRoot = Split-Path $PSScriptRoot -Parent

$Jar = $env:RENGL_PACKAGER
if (-not $Jar) {
    # The jar this RenGL folder built, else the newest one in the local Maven repository.
    $Jar = Get-ChildItem (Join-Path $PSScriptRoot 'target') -Filter 'rengl-packager-*.jar' -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch '-(ui|sources|javadoc|tests)\.jar$' } | Select-Object -First 1 -ExpandProperty FullName
}
if (-not $Jar) {
    $repository = Join-Path ([Environment]::GetFolderPath('UserProfile')) '.m2/repository/io/github/piitex/rengl-packager'
    $Jar = Get-ChildItem $repository -Directory -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending |
        ForEach-Object { Join-Path $_.FullName "rengl-packager-$($_.Name).jar" } | Where-Object { Test-Path $_ } | Select-Object -First 1
}
if (-not $Jar -or -not (Test-Path $Jar)) {
    Write-Error "The RenGL packager is not built. In the RenGL folder ($RenglRoot) run: ./mvnw install -DskipTests  (.\mvnw.cmd on Windows)"
    exit 1
}

$Java = Get-Command java -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty Source
if (-not $Java -and $env:JAVA_HOME) {
    $Java = Get-ChildItem (Join-Path $env:JAVA_HOME 'bin') -Filter 'java*' -ErrorAction SilentlyContinue |
        Where-Object { $_.BaseName -eq 'java' } | Select-Object -First 1 -ExpandProperty FullName
}
if (-not $Java) {
    Write-Error 'java not found. Install JDK 25 and put its bin folder on PATH (or set JAVA_HOME).'
    exit 1
}

# Windows PowerShell (and PowerShell 7 before 7.3) passes a quote inside an argument to a program without escaping it,
# so a shell verb's "%1" would lose its quotes. PowerShell 7.3 and later escape it themselves.
$passing = Get-Variable PSNativeCommandArgumentPassing -ValueOnly -ErrorAction SilentlyContinue
$escapeQuotes = (-not $passing) -or $passing -eq 'Legacy'

$forward = @('project', '-RenglHome', $RenglRoot, '-Project', (Resolve-Path $Project).Path)
foreach ($key in $PSBoundParameters.Keys) {
    if ($key -eq 'Project') { continue }
    $value = $PSBoundParameters[$key]
    if ($value -is [switch]) {
        if ($value.IsPresent) { $forward += "-$key" }
        continue
    }
    foreach ($item in @($value)) {
        $text = [string]$item
        # Windows PowerShell drops an empty argument altogether, which would take the next one as the value.
        if ($text -eq '') { continue }
        if ($escapeQuotes) { $text = $text -replace '"', '\"' }
        $forward += "-$key", $text
    }
}

& $Java --enable-native-access=ALL-UNNAMED -jar $Jar @forward
exit $LASTEXITCODE
