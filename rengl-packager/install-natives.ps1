<#
.SYNOPSIS
    Puts the natives jar of every platform into the local Maven repository, for building app-images of all of them.

.DESCRIPTION
    rengl-packager\install-natives.ps1            Linux, macOS (x64 and arm64), and Windows x64

    The engine's own build only makes the natives jar of the computer it runs on (RenGL-<version>-natives-windows.jar on
    Windows). The packager needs the other platforms' jars to build their app-images, so this makes them: it repackages
    LWJGL's and Skiko's published natives, so it needs the internet but no other operating system. Run it once, and again
    after the engine's version or its natives change. Run it from anywhere; it works from the engine's folder.

    If PowerShell refuses to run scripts:
        powershell -ExecutionPolicy Bypass -File rengl-packager\install-natives.ps1
#>
param(
    [string[]]$Platforms = @('windows-amd64', 'linux-amd64', 'linux-aarch64', 'macos-x86_64', 'macos-aarch64')
)

$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')
$Platforms = $Platforms | ForEach-Object { $_ -split ',' }  # "-File ... -Platforms a,b" arrives as one string

# Keeps Maven's own JVM from printing its native-access and Unsafe warnings over the build's output.
$env:MAVEN_OPTS = '--enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow'

foreach ($platform in $Platforms) {
    Write-Host "==> natives-$platform"
    # The modules themselves have to be installed already (".\mvnw.cmd install -DskipTests"); this adds a classifier to rengl-all.
    & .\mvnw.cmd -q -pl rengl-all -DskipTests '-Dmaven.javadoc.skip=true' -DskipAutoNatives "-Dnatives.platform=$platform" install
    if ($LASTEXITCODE -ne 0) { Write-Error "The $platform build failed (exit code $LASTEXITCODE)"; exit 1 }
}

Write-Host '==> Done'
$repository = Join-Path $env:USERPROFILE '.m2\repository\io\github\piitex\RenGL'
Get-ChildItem $repository -Recurse -Filter '*-natives-*.jar' |
    Select-Object @{ n = 'Jar'; e = { $_.Name } }, @{ n = 'MB'; e = { [math]::Round($_.Length / 1MB, 1) } } | Format-Table -AutoSize
