# Packaging

[Documentation](../README.md) › [Reference](README.md)

A free utility has to install on machines that have no JDK. `jpackage`, which ships with the JDK, builds a native app
with its own Java runtime: an `.app` or `.dmg` on macOS, an `.exe` or `.msi` on Windows, a `.deb`, `.rpm` or app
folder on Linux. It has to be run on the platform it builds for.

What was checked while writing this: a RenGL app built as a macOS `app-image` with a `.zip` file association, launched
with `open -a`, received the file through [`Engine.onOpenFiles`](../core/engine.md#files-the-os-asks-the-app-to-open),
both on a cold start and while running. The Windows and Linux commands, and everything about signing and
notarization, are the standard `jpackage` and Apple tools and were **not** run here.

## What goes in the input folder

`jpackage` takes a folder of jars. Put your app jar and every dependency jar in it, including the LWJGL and Skiko
`natives-*` jars for the platform you are building on.

```bash
mvn package dependency:copy-dependencies -DoutputDirectory=target/input
cp target/myapp.jar target/input/
```

## One command per platform

The flags that matter, then the commands.

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

Describe the types in a properties file and pass it with `--file-associations`. Repeat the flag for each type.

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

- Build each platform's installer on that platform, with that platform's natives in the input folder.
- Use [`AppEnvironment`](app-environment.md) so an installed app finds its data in the right place without launch flags.
- The `-XstartOnFirstThread` flag is also what Vulkan needs on macOS.
