<#
.SYNOPSIS
    Puts the natives artifact of every platform into the local Maven repository, for building app-images of all of them.

.DESCRIPTION
    rengl-packager\install-natives.ps1            Windows, Linux and macOS, x64 and arm64

    The engine's own build brings the natives of the computer it runs on. The packager needs the other platforms' natives
    to build their app-images, so this installs rengl-natives-windows, rengl-natives-linux, rengl-natives-macos and the
    arm64 ones: each holds the Skiko runtime of its platform, and its pom depends on LWJGL's natives for it, which Maven
    downloads. It needs the internet but no other operating system. Run it once, and again after the engine's version
    changes. Run it from anywhere; it works from the engine's folder.

    If PowerShell refuses to run scripts:
        powershell -ExecutionPolicy Bypass -File rengl-packager\install-natives.ps1
#>
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')

# Keeps Maven's own JVM from printing its native-access and Unsafe warnings over the build's output.
$env:MAVEN_OPTS = '--enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow'

Write-Host '==> rengl-natives (all six platforms)'
# The engine's parent pom has to be installed already (".\mvnw.cmd install -DskipTests"); this adds the natives modules.
& .\mvnw.cmd -q -f rengl-natives\pom.xml -DskipTests '-Dmaven.javadoc.skip=true' install
if ($LASTEXITCODE -ne 0) { Write-Error "The natives build failed (exit code $LASTEXITCODE)"; exit 1 }

Write-Host '==> Done'
$repository = Join-Path $env:USERPROFILE '.m2\repository\io\github\piitex'
Get-ChildItem $repository -Directory -Filter 'rengl-natives-*' | ForEach-Object {
    Get-ChildItem $_.FullName -Recurse -Filter '*.jar' | Where-Object { $_.Name -notmatch '-(sources|javadoc)\.jar$' }
} | Select-Object @{ n = 'Jar'; e = { $_.Name } }, @{ n = 'MB'; e = { [math]::Round($_.Length / 1MB, 1) } } | Format-Table -AutoSize
