# Packaging

[Documentation](../README.md) › [Reference](README.md)

Your players do not have Java, so an app has to carry its own. Packaging turns your jars into something a player can run
or install:

| You get | How |
|---|---|
| **An app-image**: a folder (or `.app`) with a native launcher, a private Java runtime and your jars. Zip it, and it runs anywhere with no install. | The RenGL packager |
| **An installer**: a `setup.exe` (Windows) or `.deb` (Linux) that adds shortcuts and an uninstaller. | The RenGL packager with `--installer` |
| **A `.msi`, `.dmg`, `.pkg` or `.rpm`**, or file-type registration on Windows and Linux. | `jpackage`, [below](#with-jpackage) |

The RenGL packager builds **all five platforms from one computer**, Windows included. `jpackage` can only build for the
platform it runs on. [`AppEnvironment`](app-environment.md) behaves the same under both.

## Quick start

This turns any RenGL project into an app you can hand to someone. It assumes only this:

- **JDK 25** is installed and on your `PATH`. `java -version` should say 25.
- **The RenGL source folder** (this repository) is on your computer. Below, `C:\Path\To\RenGL` stands for wherever you
  cloned it.
- **Your project is a Maven project** (it has a `pom.xml`) that depends on `me.piitex.engine:RenGL` and has a class
  with a `main` method. It needs no packaging files of its own.

You need an internet connection the first time.

**1. Once: install the engine and its packager.** In the RenGL folder:

```powershell
cd C:\Path\To\RenGL
.\mvnw.cmd install -DskipTests
powershell -ExecutionPolicy Bypass -File rengl-packager\install-natives.ps1
```

The first command builds the engine and the packager into your local Maven repository (`~/.m2`) and takes a while. The
second adds the Linux, macOS and Windows natives (LWJGL's and Skiko's native libraries) that your other platforms'
images need. Repeat both when you update the engine.

**2. Once per project: create its settings.** In your project's folder (the one with `pom.xml`), run the RenGL folder's
`package-app.ps1` with `-Init`:

```powershell
cd C:\Path\To\MyApp
powershell -ExecutionPolicy Bypass -File C:\Path\To\RenGL\rengl-packager\package-app.ps1 -Init
```

This is the last long command. It looks at your `pom.xml` and sources, and writes two files into the project:

- **`rengl-package.properties`** holds the settings: the app's name, its main class, the version, which platforms to
  build. The values are guesses, so open it and check them.
- **`package.cmd`** is a one-line launcher for the script, with the path to the RenGL folder already filled in. That path
  belongs to this computer, so do not copy `package.cmd` to another one; run `-Init` there instead.

(`-ExecutionPolicy Bypass` is there because PowerShell refuses to run unsigned scripts by default. `package.cmd` has it
built in.)

The settings file looks like this, with comments that explain each line:

```properties
name=MyApp
mainClass=com.example.Main
version=1.0.0
platform=windows-x64
installer=false
```

| Setting | Default | What it does |
|---|---|---|
| `name` | none, required | The app's name, also its launcher's file name (`MyApp.exe`) |
| `mainClass` | none, required | The class with `main`, package included |
| `version` | `1.0.0` | The app's version |
| `platform` | `all` | What to build, comma separated: `windows-x64`, `linux-x64`, `linux-arm64`, `macos-x64`, `macos-arm64`. `-Init` writes `windows-x64`, which is the quick one |
| `installer` | `false` | `true` makes a `setup.exe` for Windows and a `.deb` for Linux instead of the `.zip` and `.tar.gz`. See [Installers](#installers) |
| `icon` | none | The Windows, Linux and macOS icons: `MyApp.ico,MyApp.png,icns=MyApp-1024.png`. The last is made from a square 1024 px PNG |
| `vendor`, `description` | none | Shown in the setup and the `.deb` |
| `identifier` | `me.rengl.<name>` | The macOS bundle id, which also names the Windows uninstall entry |
| `addModules` | none | Java modules the app loads by reflection, e.g. `java.logging,java.prefs` |
| `content` | none | A folder of files to ship beside the jars, e.g. `assets` |
| `output` | `dist\app-images` | Where the results go. Keep it short |
| `mainJar` | the jar Maven builds | Only for a project that builds more than one jar |

Paths in the file (`icon`, `content`, `output`) are relative to the project. A line starting with `#` is a comment, and
a setting with nothing after the `=` is ignored. A misspelled setting is an error, not silently skipped.

**3. Build.** In the project's folder:

```powershell
.\package.cmd
```

It builds your project with Maven (replacing its `target` folder), collects the jars and natives, and runs the
packager. Anything on the command line wins over the file for that run, so you rarely edit the file to try something:

```powershell
.\package.cmd -Installer
.\package.cmd -Platform all
.\package.cmd -Platform linux-x64,macos-arm64
```

Every setting is a parameter of the script with the same name, and `Get-Help C:\Path\To\RenGL\rengl-packager\package-app.ps1 -Detailed`
lists them. You can also skip the file: `package-app.ps1 -Name MyApp -MainClass com.example.Main` works on its own.

**4. Take the result** from the `dist\app-images` folder of your project:

```
dist\app-images\
    MyApp-1.0.0-windows-x64.zip          unzip and run MyApp.exe
    MyApp-1.0.0-linux-x64.tar.gz         extract and run bin/MyApp
    MyApp-1.0.0-macos-arm64.tar.gz       extract and open MyApp.app
    windows-x64\MyApp\                   the same, as folders
    ...
```

With `installer=true` or `-Installer`, the Windows `.zip` becomes `MyApp-1.0.0-windows-x64-setup.exe` and each Linux
`.tar.gz` becomes a `.deb`. macOS keeps its `.tar.gz`. The first build of another platform downloads that platform's Java
modules once into `~/.rengl/jdks` (about 340 MB for the four platforms besides your own). Keep the project's path short:
Windows fails on paths over about 260 characters.

CCA, RenGL Editor, Phorg and SketchGL have their own `packaging\app-images.ps1` with their settings filled in, so
there it is just `powershell -ExecutionPolicy Bypass -File packaging\app-images.ps1`, with the same `-Platform` and
`-Installer`. Your project does not have one, and does not need one. To run the packager without the scripts, see
[Running it](#running-it).

## The RenGL packager

`rengl-packager` is a command-line tool in this repository. Its images have the same layout `jpackage --type app-image`
makes, so [`AppEnvironment`](app-environment.md) cannot tell them apart:

```
Windows   MyApp\MyApp.exe, app\ (jars and MyApp.cfg), runtime\
Linux     MyApp/bin/MyApp, lib/app/, lib/runtime/
macOS     MyApp.app/Contents/MacOS/MyApp, Contents/app/, Contents/runtime/, Contents/Info.plist
```

**The launcher.** On Windows, `MyApp.exe` is a small native program (`rengl-packager/native/windows/launcher.c`, built
once and checked in, so you need no compiler). It loads the bundled `jvm.dll` and starts your `main` in the same
process, as `jpackage`'s does. It accepts `main(String[])` and the Java 25 `main()` forms. It reads `app\MyApp.cfg`, where you can
edit the JVM options after the build. On Linux and macOS the launcher is a shell script that `exec`s the bundled `java`,
so the process is the app and the Dock, Apple events and signals reach it.

**The runtime.** The packager runs `jlink` with the JDK that runs it, so the image holds only the Java modules your jars
use (found with `jdeps`). For another platform, `jlink` needs that platform's modules. The packager downloads them from
Amazon Corretto once, of the same release as the JDK running it, and keeps them in `~/.rengl/jdks`. To use a JDK you
already have, pass `--jdk linux-x64=/path/to/jdk`.

### The input folder

The packager, and `jpackage`, take **one folder holding your app jar and every dependency jar**. For a Maven project:

```powershell
.\mvnw.cmd clean package dependency:copy-dependencies "-DoutputDirectory=target/input"
Copy-Item target\myapp.jar target\input\
```

Use your project's own Maven (`mvn` if you have it installed; the RenGL folder only ships `mvnw`). Add
`-DincludeScope=runtime` if test libraries end up in the folder.

The folder also needs **the natives jar of every platform you build for**. These hold LWJGL's and Skiko's native
libraries, and a platform's image starts and then fails to find them if its jar is missing. The packager picks them by
file name (`natives-windows`, `natives-linux-arm64`, `natives-macos-arm64`, ...) and gives each image only its own.
Your build brings only the jar of the computer it runs on. Step 1 of the quick start put all of them in your Maven
repository, so copy them in:

```powershell
Copy-Item "$env:USERPROFILE\.m2\repository\me\piitex\engine\RenGL\1.0-SNAPSHOT\RenGL-1.0-SNAPSHOT-natives-*.jar" target\input\
```

`1.0-SNAPSHOT` is the engine's version in its `pom.xml`. With the RenGL [modules](../modules.md) rather than the one
`RenGL` jar, copy the `natives-<platform>` jar of each module that has them.

### Running it

The packager is the jar that step 1 installed, with a version in its name. Run it with Java 25:

```powershell
$packager = "$env:USERPROFILE\.m2\repository\me\piitex\engine\rengl-packager\1.0-SNAPSHOT\rengl-packager-1.0-SNAPSHOT.jar"

java --enable-native-access=ALL-UNNAMED -jar $packager `
  --name MyApp --app-version 1.0.0 `
  --input target\input --main-jar myapp.jar --main-class com.example.Main `
  --icon MyApp.ico --icon MyApp.png --icon icns=MyApp-1024.png `
  --platform windows-x64,linux-x64,macos-arm64 --output dist
```

On macOS or Linux it is the same command, with `~/.m2/...` for the path and `\` ending the lines. Without
`--enable-native-access=ALL-UNNAMED` it still works, but Java prints a warning about setting the icon. It writes:

```
dist/windows-x64/MyApp/                 dist/MyApp-1.0.0-windows-x64.zip
dist/linux-x64/MyApp/                   dist/MyApp-1.0.0-linux-x64.tar.gz
dist/macos-arm64/MyApp.app/             dist/MyApp-1.0.0-macos-arm64.tar.gz
```

The `.tar.gz` files keep their executable bits even when built on Windows. `--platform all` builds all five, and with
no `--platform` it builds this computer's. Keep `--output` short: Windows paths over about 260 characters fail (see
[Troubleshooting](#troubleshooting)). `--help` lists every option.

| Option | What it does |
|---|---|
| `--name`, `--input`, `--main-jar`, `--main-class` | Required: the app's name (also the launcher's file name), the jar folder, your jar in it, and the class with `main` |
| `--app-version`, `--vendor`, `--description` | The app's identity. The version defaults to `1.0.0` |
| `--icon` | Repeat for each platform: `.ico` is set on `MyApp.exe` (only when building on Windows), `.icns` goes in the app bundle, `.png` is the Linux icon. `--icon icns=MyApp-1024.png` makes the macOS icon from a square 128, 256, 512 or 1024 px PNG, so you do not need a Mac to draw one |
| `--java-option` | A JVM option for every platform. `-XstartOnFirstThread` (macOS) and `--enable-native-access=ALL-UNNAMED` are added for you |
| `--modules`, `--add-modules` | Replace or extend the Java modules in the runtime, when the app uses one `jdeps` cannot see (loaded by reflection or `ServiceLoader`). The `java.logging,java.prefs` pair is a common one |
| `--content <dir>` | A folder of files to ship beside the jars (assets, default data). Its contents are copied into every platform's app folder |
| `--file-association ext,mime,description` | Registers the type in the macOS `Info.plist`. Windows and Linux images cannot register types without `jpackage`, but a file passed on the command line arrives like any launch |
| `--identifier` | The macOS bundle id; defaults to `me.rengl.<name>`. On Windows it also names the Apps & features entry |
| `--installer` | A `setup.exe` for Windows and a `.deb` for Linux instead of the `.zip` and `.tar.gz`. See [Installers](#installers) |
| `--no-archive` | Leave the folders only; write no archives or installers |
| `--jdk platform=path` | Use this JDK for a platform instead of downloading Corretto's modules |

### Installers

`--installer` (`-Installer` in a project's `app-images.ps1`) makes an installer from the image, still without
`jpackage`, WiX or NSIS.

**Windows: `MyApp-1.0.0-windows-x64-setup.exe`.** One file: a small native setup program
(`rengl-packager/native/windows/setup.c`, built once and checked in) with the image appended to it as a zip. Run it and
it asks for the install folder and offers **a desktop shortcut** and **a Start menu shortcut** (both ticked), then
offers to launch the app. It installs for the current user, by default into `%LOCALAPPDATA%\Programs\MyApp`, so it needs
no administrator rights, and it adds an Apps & features entry. It also writes `uninstall.exe` into the install folder.
The uninstaller removes exactly the files the installer wrote, plus the shortcuts and the registry entry, so a folder
chosen by mistake only loses what the installer put there. Files the app made itself in its install folder stay, and so
does the data it keeps in its data folder.

For scripts and package managers it also runs without a window:

```powershell
.\MyApp-1.0.0-windows-x64-setup.exe /S /DIR=C:\Tools\MyApp /DESKTOP=0 /STARTMENU=1 /LAUNCH
C:\Tools\MyApp\uninstall.exe /S
```

| Switch | Meaning |
|---|---|
| `/S` | Silent: no window |
| `/DIR=path` | Install folder |
| `/DESKTOP=0` or `1` | Desktop shortcut (default 1) |
| `/STARTMENU=0` or `1` | Start menu shortcut (default 1) |
| `/LAUNCH` | Start the app when done |

Installing over an older version replaces its files. Close the app first: files in use cannot be written or removed,
and the setup and uninstaller say so. The uninstaller keeps its file list when that happens, so running it again after
closing the app finishes the job. `--icon` with a `.ico` sets the setup's own icon too.

**Linux: `MyApp-1.0.0-linux-x64.deb`.** The image goes in `/opt/MyApp`, the launcher is linked as `/usr/bin/myapp`, and
a `.desktop` file and the PNG icon give it an entry in the desktop's application menu. Install it with
`sudo apt install ./MyApp-1.0.0-linux-x64.deb`. A `.deb` has no install-time choices, so the menu entry is always made.
It is built without `dpkg`, from any OS. There is no `.rpm`.

**macOS** has no installer here. Players drag `MyApp.app` out of the archive into Applications.

### Troubleshooting

| What you see | Why, and what to do |
|---|---|
| `The argument 'packaging\app-images.ps1' to the -File parameter does not exist` | Your project has no such file: only CCA, RenGL Editor, Phorg and SketchGL do. Use the RenGL folder's `package-app.ps1` with its full path, as in the [quick start](#quick-start) |
| `There is no pom.xml in ...` | `package-app.ps1` was started in the wrong folder. Go to your project's folder first, or pass `-Project C:\Path\To\MyApp` |
| `The app has no name` or `The app has no main class` | `name` or `mainClass` is empty in `rengl-package.properties`, or the file is missing. Run `package-app.ps1 -Init` once, or fill them in |
| `'platfrom' is not a setting` (or another word) | A setting in `rengl-package.properties` is misspelled. The message lists the settings that exist |
| `rengl-package.properties already exists` | `-Init` never overwrites. Edit the file, or delete it and run `-Init` again |
| `package.cmd` is not recognized | In PowerShell a file in the current folder needs `.\`: run `.\package.cmd`. Also check you are in the project's folder |
| `package.cmd` cannot find `package-app.ps1` | The RenGL folder has moved since `-Init` wrote it. Delete `package.cmd` and run `-Init` again |
| `mvn` is not recognized | The RenGL folder ships the Maven wrapper instead: use `.\mvnw.cmd` (`./mvnw` on macOS and Linux) |
| `Unable to access jarfile rengl-packager.jar` | There is no jar by that name. Use the full path of the installed one, as in [Running it](#running-it) |
| `The RenGL packager is not at ...` | Step 1 of the quick start has not been run, or not for this engine version |
| `The windows-x64 natives are missing` (or another platform) | Run `install-natives.ps1` from step 1 |
| `running scripts is disabled on this system` | Run `package.cmd`, which starts the script with the policy bypassed, or start the script as `powershell -ExecutionPolicy Bypass -File ...`, as in the quick start |
| `Could not set the icon: Could not open ...\MyApp.exe` | The output path is too long for Windows (over about 260 characters). Build into a short folder such as `C:\dist` |
| The app starts and then cannot find its native libraries | That platform's natives jar was not in the input folder |
| The app crashes at start for a missing Java class | `jdeps` did not find a module the app loads by reflection. Add it with `--add-modules` |
| Warnings about "restricted method" while packaging | Start Java with `--enable-native-access=ALL-UNNAMED`, as the commands above do |
| The setup or uninstaller reports files in use | The app is running. Close it and run the setup or uninstaller again |
| macOS says the app "cannot be opened because the developer cannot be verified" | The image is not signed. Right-click the app and choose Open, or remove the quarantine flag with `xattr -dr com.apple.quarantine MyApp.app`. Signing is covered [below](#signing-and-notarizing-on-macos) |

### What was checked, and what was not

On Windows 11: the Windows images of the RenGL examples and of Phorg were built and started and showed their window;
the icon was read back from the `.exe`; the Phorg setup was run both with its wizard and with `/S`, its files were
compared with the image, and it was uninstalled, also while the app was running (the retry finished the job). The
other projects' scripts were run and built their images and installers, but those apps were not launched. The macOS
arm64 image was run on a Mac and works. Not checked:

- **The `.deb` was not installed.** Its structure was inspected (control file, modes, link and `.desktop` file); no
  Debian system was available to run `dpkg -i`.
- **The Linux images and the macOS x64 image were not launched.** If one fails, the first thing to read is the launcher
  script: `bin/MyApp` (Linux) or `Contents/MacOS/MyApp` (macOS).
- **Nothing is signed.** The Windows setup and `MyApp.exe` trigger SmartScreen when downloaded, and `MyApp.exe` has no
  version resource (Task Manager shows the file name). Sign with `signtool`. macOS signing is [below](#signing-and-notarizing-on-macos),
  done to `MyApp.app` with `codesign --deep` rather than `--mac-sign` of `jpackage`.
- **Windows on ARM** has no launcher yet, and Corretto no build to download. `windows-x64` apps run there under
  emulation.
- **The Windows setup installs per user only.** There is no "all users" choice and no file associations. Use `jpackage`
  for those, or give the Windows folder to Inno Setup or NSIS.
- A path to the install folder with characters outside the Windows ANSI code page works, but then `user.dir` is the
  install folder rather than where the app was started.

### Rebuilding the Windows launcher and setup

You only need this to change `launcher.c` or `setup.c`. It needs Visual Studio's C++ build tools. Then rebuild the
packager with `.\mvnw.cmd -pl rengl-packager install -DskipTests` so it picks up the new `.exe`.

```powershell
rengl-packager\native\windows\build-launcher.cmd "C:\path\to\jdk-25"
rengl-packager\native\windows\build-setup.cmd
```

## With jpackage

`jpackage`, which ships with the JDK, builds a native app with its own Java runtime: an `.app` or `.dmg` on macOS, an
`.exe` or `.msi` on Windows, a `.deb`, `.rpm` or app folder on Linux. It has to be run on the platform it builds for.
Give it the [input folder](#the-input-folder), with the natives jar of **that** platform.

What was checked while writing this: a RenGL app built as a macOS `app-image` with a `.zip` file association, launched
with `open -a`, received the file through [`Engine.onOpenFiles`](../core/engine.md#files-the-os-asks-the-app-to-open),
both on a cold start and while running. The Windows and Linux commands, and everything about signing and
notarization, are the standard `jpackage` and Apple tools and were **not** run here.

The flags that matter, then the commands:

| Flag | Why |
|---|---|
| `--name`, `--app-version`, `--vendor`, `--description` | The app's identity. On macOS the version must be `x.y.z` and start with 1 or more |
| `--icon` | `.icns` on macOS, `.ico` on Windows, `.png` on Linux |
| `--main-jar`, `--main-class` | What to start |
| `--java-options "-XstartOnFirstThread"` | **macOS only.** GLFW has to run on the first thread. Do not pass it on Windows or Linux, where the JVM does not know the option |
| `--java-options "--enable-native-access=ALL-UNNAMED"` | All platforms: LWJGL and Skiko load native code |
| `--file-associations types.properties` | Registers file types; see [below](#opening-files-from-the-os) |
| `--runtime-image <JDK>` | Optional: use a whole JDK as the runtime. Without it, `jpackage` builds a smaller one from the modules it finds your jar needs, which may leave out a module a library loads by reflection, so run the installed app before shipping it |

```bash
# macOS
jpackage --type dmg --name MyApp --app-version 1.0.0 --icon MyApp.icns \
  --input target/input --main-jar myapp.jar --main-class com.example.Main \
  --java-options "-XstartOnFirstThread" --java-options "--enable-native-access=ALL-UNNAMED"

# Windows (an .msi needs the WiX Toolset installed; use --type exe or app-image to skip it)
jpackage --type msi --name MyApp --app-version 1.0.0 --icon MyApp.ico --win-menu --win-shortcut \
  --input target/input --main-jar myapp.jar --main-class com.example.Main \
  --java-options "--enable-native-access=ALL-UNNAMED"

# Linux
jpackage --type deb --name myapp --app-version 1.0.0 --icon myapp.png \
  --input target/input --main-jar myapp.jar --main-class com.example.Main \
  --java-options "--enable-native-access=ALL-UNNAMED"
```

`jpackage` does not make AppImages. For one, build with `--type app-image` and give the folder to `appimagetool`.

## Opening files from the OS

Describe the types in a properties file and pass it to `jpackage` with `--file-associations`. Repeat the flag for each
type.

```properties
extension=zip
mime-type=application/zip
description=Zip archive
# icon=zip.icns
```

Registering the type only makes the OS offer your app. How the file reaches it depends on the platform:

| Platform | How the path arrives |
|---|---|
| Windows, Linux | As `main(String[] args)` |
| macOS | As an Apple event, with **empty** `args` |

[`Engine.onOpenFiles`](../core/engine.md#files-the-os-asks-the-app-to-open) takes both, so set it up in `main`:

```java nocompile
public static void main(String[] args) {
    Engine.onOpenFiles(paths -> archive.open(paths.getFirst()));
    Engine.openFilesFromArguments(args);
    new Engine().start(window);
}
```

While testing, a freshly built app may not be offered for the file type until the OS has noticed it. Launching it once,
or moving it to `/Applications`, does that.

## Signing and notarizing on macOS

Without both, Gatekeeper warns that the app cannot be checked and the user has to override it. You need a paid Apple
Developer account and a **Developer ID Application** certificate in your keychain.

1. Build with signing on. A Java app needs entitlements for the hardened runtime, because the JVM compiles code at
   run time and loads native libraries it extracted itself:

   ```xml
   <!-- entitlements.plist -->
   <plist version="1.0"><dict>
     <key>com.apple.security.cs.allow-jit</key><true/>
     <key>com.apple.security.cs.allow-unsigned-executable-memory</key><true/>
     <key>com.apple.security.cs.disable-library-validation</key><true/>
   </dict></plist>
   ```

   ```bash
   jpackage ... --type dmg --mac-sign \
     --mac-signing-key-user-name "Your Name (TEAMID)" \
     --mac-entitlements entitlements.plist
   ```

2. Upload for notarization and wait. Store the credentials once with `xcrun notarytool store-credentials`:

   ```bash
   xcrun notarytool submit MyApp-1.0.0.dmg --keychain-profile "notary" --wait
   ```

3. Staple the ticket to the disk image, so it checks out offline:

   ```bash
   xcrun stapler staple MyApp-1.0.0.dmg
   ```

If notarization fails, `xcrun notarytool log <id> --keychain-profile notary` says which file was refused. The usual
cause is an unsigned library inside the app.

## Things to know

- Build each `jpackage` installer on its own platform, with that platform's natives in the input folder. The RenGL
  packager has no such limit.
- Use [`AppEnvironment`](app-environment.md) so an installed app finds its data in the right place without launch flags.
- The `-XstartOnFirstThread` flag is also what Vulkan needs on macOS.
