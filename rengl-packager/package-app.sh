#!/bin/sh
# Builds app-images (and optionally installers) for any RenGL Maven project, for Windows, Linux and macOS, from this
# computer. The macOS and Linux twin of package-app.ps1, for any POSIX shell (sh, bash, zsh, dash).
#
# Once per project, in its folder:
#     sh /path/to/RenGL/rengl-packager/package-app.sh -Init
# writes rengl-package.properties (the app's name, main class, platforms, icons) with its best guesses, and package.sh,
# a one-line launcher for this script. Check the properties file, then build with:
#     ./package.sh
# A setting on the command line wins over the file: ./package.sh -Platform linux-x64   or   ./package.sh --installer true
# All the settings: sh package-app.sh --help
#
# Before the first run, in the RenGL folder (once, and again after updating the engine):
#     ./mvnw install -DskipTests
#     sh rengl-packager/install-natives.sh
#
# The work is done by the packager jar (java -jar rengl-packager-<version>.jar project); this script only finds the jar
# and the java to run it with. It needs JDK 25 and, the first time a platform is built, the internet.
set -e

here=$(cd "$(dirname "$0")" && pwd)
root=$(dirname "$here")

# The jar this RenGL folder built, else the newest one in the local Maven repository.
jar=${RENGL_PACKAGER:-}
if [ -z "$jar" ]; then
    for candidate in "$here"/target/rengl-packager-*.jar; do
        case $candidate in
            *-ui.jar | *-sources.jar | *-javadoc.jar | *-tests.jar) ;;
            *) [ -f "$candidate" ] && jar=$candidate ;;
        esac
    done
fi
if [ -z "$jar" ]; then
    repository=$HOME/.m2/repository/io/github/piitex/rengl-packager
    if [ -d "$repository" ]; then
        for version in $(ls -t "$repository"); do
            if [ -f "$repository/$version/rengl-packager-$version.jar" ]; then
                jar=$repository/$version/rengl-packager-$version.jar
                break
            fi
        done
    fi
fi
if [ -z "$jar" ] || [ ! -f "$jar" ]; then
    echo "error: the RenGL packager is not built. In the RenGL folder ($root) run: ./mvnw install -DskipTests" >&2
    exit 1
fi

if command -v java >/dev/null 2>&1; then
    java=java
elif [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    java=$JAVA_HOME/bin/java
else
    echo "error: java not found. Install JDK 25 and put its bin folder on PATH (or set JAVA_HOME)." >&2
    exit 1
fi

exec "$java" --enable-native-access=ALL-UNNAMED -jar "$jar" project -RenglHome "$root" "$@"
