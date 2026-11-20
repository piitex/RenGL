#!/bin/sh
# Puts the natives artifact of every platform into the local Maven repository, for building app-images of all of them.
# The macOS and Linux twin of install-natives.ps1, for any POSIX shell.
#
#     sh rengl-packager/install-natives.sh        Windows, Linux and macOS, x64 and arm64
#
# The engine's own build brings the natives of the computer it runs on. The packager needs the other platforms' natives
# to build their app-images, so this installs rengl-natives-windows, rengl-natives-linux, rengl-natives-macos and the
# arm64 ones: each holds the Skiko runtime of its platform, and its pom depends on LWJGL's natives for it, which Maven
# downloads. It needs the internet but no other operating system. Run it once, and again after the engine's version
# changes. Run it from anywhere; it works from the engine's folder.
set -e
cd "$(dirname "$0")/.."

# Keeps Maven's own JVM from printing its native-access and Unsafe warnings over the build's output.
MAVEN_OPTS='--enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow'
export MAVEN_OPTS

echo '==> rengl-natives (all six platforms)'
# The engine's parent pom has to be installed already ("./mvnw install -DskipTests"); this adds the natives modules.
# Through sh, so a wrapper that lost its executable bit (a checkout made on Windows, a zip) still runs.
sh ./mvnw -q -f rengl-natives/pom.xml -DskipTests -Dmaven.javadoc.skip=true install || {
    echo "error: the natives build failed" >&2
    exit 1
}

echo '==> Done'
find "$HOME/.m2/repository/io/github/piitex" -path '*rengl-natives-*' -name '*.jar' \
    ! -name '*-sources.jar' ! -name '*-javadoc.jar' -exec ls -lh {} \; | awk '{ print $5 "\t" $NF }'
