# Getting started

[Documentation](README.md)

## Requirements

- **JDK 25.** The project compiles with `maven.compiler.source/target = 25`.
- **Maven.** IntelliJ ships one, or install your own.
- **A GPU/driver that supports OpenGL 3.3 core** (Windows/Linux) or **Metal** (macOS). Vulkan 1.1 is an optional alternative on any of them, see [Rendering APIs](core/rendering-apis.md). The optional [3D layer](3d/README.md) needs OpenGL or Vulkan, so on macOS a 3D program asks for Vulkan.

## Dependencies

RenGL is a set of Maven modules. Start with `rengl-core` and add the others when you need them (audio, video, Vulkan,
3D, spell checking, ...), or depend on `me.piitex.engine:RenGL` for all of them. [Modules](modules.md) lists them.

```xml
<dependency>
    <groupId>me.piitex.engine</groupId>
    <artifactId>rengl-core</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

That is the Java side. The native libraries (LWJGL's and Skiko's) are separate jars per platform, so a project also
needs the natives of the platform it runs on. [Setting up a Maven project](#setting-up-a-maven-project) shows how.

## Setting up a Maven project

RenGL is not on Maven Central yet, so there are two steps: put the engine in your local Maven repository, then write a
`pom.xml` that picks the natives for each platform.

### 1. Install the engine

Once, in the folder you cloned RenGL into (`mvnw.cmd` on Windows):

```bash
./mvnw install -DskipTests
```

This installs every module into `~/.m2`, but not the natives jars. Those are built separately, one platform per
command. Each command repackages the published LWJGL and Skiko natives of one platform, so it needs the internet but no
other operating system. Run it for the platform you are on, and for any other platform you build or package for:

```bash
./mvnw -q -pl rengl-all -DskipTests -Dmaven.javadoc.skip=true -DskipAutoNatives -Dnatives.platform=linux-amd64 install
```

The platforms are `windows-amd64`, `windows-aarch64`, `linux-amd64`, `linux-aarch64`, `macos-x86_64` and
`macos-aarch64`. On Windows, `rengl-packager\install-natives.ps1` runs five of them for you (everything except
`windows-aarch64`). Repeat all of it when you update the engine.

`-pl rengl-all` makes the natives jar of the `RenGL` artifact, which holds the natives of every module. If you depend
on individual modules instead (see [Taking only some modules](#taking-only-some-modules)), leave `-pl rengl-all` out so
that each module that has natives gets its own jar.

### 2. Write the pom

A project depends on the engine twice: the engine jars, and the same artifact again with a `natives-<platform>`
classifier, which holds only the native libraries. The classifier comes from a property that one profile per platform
sets, so the same `pom.xml` builds on Windows, Linux and macOS:

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.example</groupId>
    <artifactId>myapp</artifactId>
    <version>1.0</version>

    <properties>
        <maven.compiler.source>25</maven.compiler.source>
        <maven.compiler.target>25</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <rengl.version>1.0-SNAPSHOT</rengl.version>
        <!-- JVM flags for running the app. The macOS profiles below add -XstartOnFirstThread. -->
        <rengl.jvmargs>--enable-native-access=ALL-UNNAMED</rengl.jvmargs>
    </properties>

    <dependencies>
        <dependency>
            <groupId>me.piitex.engine</groupId>
            <artifactId>RenGL</artifactId>
            <version>${rengl.version}</version>
        </dependency>
        <!-- The native libraries (LWJGL and Skiko) of the platform the profiles below pick. -->
        <dependency>
            <groupId>me.piitex.engine</groupId>
            <artifactId>RenGL</artifactId>
            <version>${rengl.version}</version>
            <classifier>${rengl.natives}</classifier>
        </dependency>
    </dependencies>

    <profiles>
        <profile>
            <id>rengl-windows-x64</id>
            <activation><os><family>windows</family><arch>amd64</arch></os></activation>
            <properties><rengl.natives>natives-windows</rengl.natives></properties>
        </profile>
        <profile>
            <id>rengl-windows-arm64</id>
            <activation><os><family>windows</family><arch>aarch64</arch></os></activation>
            <properties><rengl.natives>natives-windows-arm64</rengl.natives></properties>
        </profile>
        <profile>
            <id>rengl-linux-x64</id>
            <activation><os><family>unix</family><name>linux</name><arch>amd64</arch></os></activation>
            <properties><rengl.natives>natives-linux</rengl.natives></properties>
        </profile>
        <profile>
            <id>rengl-linux-arm64</id>
            <activation><os><family>unix</family><name>linux</name><arch>aarch64</arch></os></activation>
            <properties><rengl.natives>natives-linux-arm64</rengl.natives></properties>
        </profile>
        <profile>
            <id>rengl-macos-x64</id>
            <activation><os><family>mac</family><arch>x86_64</arch></os></activation>
            <properties>
                <rengl.natives>natives-macos</rengl.natives>
                <rengl.jvmargs>-XstartOnFirstThread --enable-native-access=ALL-UNNAMED</rengl.jvmargs>
            </properties>
        </profile>
        <profile>
            <id>rengl-macos-arm64</id>
            <activation><os><family>mac</family><arch>aarch64</arch></os></activation>
            <properties>
                <rengl.natives>natives-macos-arm64</rengl.natives>
                <rengl.jvmargs>-XstartOnFirstThread --enable-native-access=ALL-UNNAMED</rengl.jvmargs>
            </properties>
        </profile>
    </profiles>

    <build>
        <plugins>
            <!-- mvn compile exec:exec runs the app with the right flags for the platform. -->
            <plugin>
                <groupId>org.codehaus.mojo</groupId>
                <artifactId>exec-maven-plugin</artifactId>
                <version>3.5.0</version>
                <configuration>
                    <executable>java</executable>
                    <arguments>
                        <argument>${rengl.jvmargs}</argument>
                        <argument>-classpath</argument>
                        <classpath/>
                        <argument>com.example.Main</argument>
                    </arguments>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

Maven picks the profile that matches the computer it runs on:

| Computer | Profile | Natives classifier |
|---|---|---|
| Windows, x86-64 | `rengl-windows-x64` | `natives-windows` |
| Windows, arm64 | `rengl-windows-arm64` | `natives-windows-arm64` |
| Linux, x86-64 | `rengl-linux-x64` | `natives-linux` |
| Linux, arm64 | `rengl-linux-arm64` | `natives-linux-arm64` |
| macOS, Intel | `rengl-macos-x64` | `natives-macos` |
| macOS, Apple silicon | `rengl-macos-arm64` | `natives-macos-arm64` |

The names are LWJGL's, so `natives-macos` is the Intel Mac, and `natives-linux` and `natives-windows` are x86-64. The
Skiko runtime is inside the natives jar, so a program cannot start without it, even on its own platform.

To build for a platform other than the one you are on, name the classifier yourself instead of letting a profile pick
it: `mvn -Drengl.natives=natives-linux package`. That platform's jar has to be in your local repository (step 1).

Run `mvn help:active-profiles` to see which profile Maven picked. If the build says it cannot find
`RenGL:jar:natives-...`, that platform's natives jar was not installed.

### Taking only some modules

A project that depends on individual modules adds the classifier to each module that owns natives. Import the
[BOM](modules.md#using-them) so the versions agree:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>me.piitex.engine</groupId>
            <artifactId>rengl-bom</artifactId>
            <version>${rengl.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>me.piitex.engine</groupId>
        <artifactId>rengl-core</artifactId>
    </dependency>
    <dependency>
        <groupId>me.piitex.engine</groupId>
        <artifactId>rengl-core</artifactId>
        <classifier>${rengl.natives}</classifier>
    </dependency>
    <dependency>
        <groupId>me.piitex.engine</groupId>
        <artifactId>rengl-audio</artifactId>
    </dependency>
    <dependency>
        <groupId>me.piitex.engine</groupId>
        <artifactId>rengl-audio</artifactId>
        <classifier>${rengl.natives}</classifier>
    </dependency>
</dependencies>
```

`rengl-core`, `rengl-audio`, `rengl-3d` and `rengl-tilemap` have a natives jar on every platform. `rengl-vulkan` has one
for macOS only (MoltenVK), so on Windows and Linux there is no `natives-windows` jar to ask for. The other modules have
no natives. [Modules](modules.md#native-libraries) has the list. The natives jar of `RenGL` holds the natives of all
of them, which is why the pom above needs only one.

## Building the engine from source

To work on RenGL itself rather than use it:

```bash
mvn compile
```

The native libraries (LWJGL and Skiko) are picked automatically by OS and architecture through Maven
profiles (`lwjgl-natives-<os>-<arch>`). Supported: Linux, macOS and Windows, each on x86-64 and arm64.

To publish jars that other apps can depend on, the `natives.platform` property builds one small "natives" jar per
platform for every module that has natives. `-Dnatives.platform=all` (or the `natives-bundle-all` profile) builds all six in
one run; a single platform takes one invocation:

```bash
mvn -DskipAutoNatives -Dnatives.platform=macos-aarch64 package
```

## Running

Run the class that calls `Engine.start`. With the pom above, `mvn compile exec:exec` runs `com.example.Main` with the
right flags for your platform. To run it any other way, pass these JVM flags yourself (the demos use them):

```text
-XstartOnFirstThread --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=deny
```

- `-XstartOnFirstThread` matters on macOS, where windowing calls have to happen on the process's first thread. It is needed for Vulkan too.
- The other two flags reduce JVM warnings about native access.

The window uses the platform's usual graphics API (Metal on macOS, OpenGL elsewhere). To use Vulkan, or to see which one
you got, see [Rendering APIs](core/rendering-apis.md).

## Your first window

Every program follows the same four steps: create a [`Window`](core/window.md), put a [`Container`](containers/container.md) in it, put elements in the
container, then hand the window to an [`Engine`](core/engine.md).

```java
import me.piitex.engine.Engine;
import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.overlays.TextOverlay;

public class Hello {
    public static void main(String[] args) {
        // 1. The window (title + size).
        Window window = new Window(new WindowOptions("Hello RenGL").setDimensions(1280, 720));

        // 2. A container that fills the window. Elements start transparent, so the
        // window's own background shows through it.
        Container root = new Container(1280, 720);
        window.addContainer(root);

        // 3. Elements. x/y is the top-left corner.
        TextOverlay title = new TextOverlay("Hello, RenGL!", 48f, Color.BLACK);
        title.setPosition(50, 50);
        root.addElement(title);

        // 4. Start the engine. This call blocks until the window is closed.
        new Engine().start(window);
    }
}
```

`Engine.start` does not return until the window closes, so build the whole UI *before* calling it.
Anything you need to do afterward, such as reacting to input, happens inside callbacks.

## A slightly bigger example

Widgets go inside a layout so you do not position each one by hand. This puts a themed panel in the middle
of the window with a text field and a button.

```java
import me.piitex.engine.*;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.layout.Layout;
import me.piitex.engine.ui.layout.VerticalLayout;
import me.piitex.engine.ui.overlays.*;
import me.piitex.engine.ui.theme.DarkTheme;
import me.piitex.engine.ui.theme.ThemeManager;

public class Form {
    public static void main(String[] args) {
        ThemeManager.setCurrent(new DarkTheme()); // before building, so everything starts dark
        Window window = new Window(new WindowOptions("Form").setDimensions(800, 600));

        Container root = new Container(800, 600);
        window.addContainer(root);

        VerticalLayout panel = new VerticalLayout(250, 150, 300, 300);
        panel.setSpacing(16);
        panel.setPadding(20);
        panel.setAlignment(Layout.Alignment.TOP_CENTER);
        panel.useTheme(); // opt this panel into theme colors
        root.addElement(panel);

        TextFieldOverlay name = new TextFieldOverlay(200, 28);
        name.setPlaceholder("Your name...");
        panel.addElement(name);

        ButtonOverlay submit = new ButtonOverlay("Submit", 200, 36);
        submit.onAction(() -> System.out.println("Hello, " + name.getText()));
        panel.addElement(submit);

        new Engine().start(window);
    }
}
```

## Where to go next

- How the pieces fit together: [Architecture](architecture.md).
- Positioning things automatically: [Layouts](layouts/README.md).
- A tour of every widget: [Overlay catalog](overlays/README.md).
- Colors, borders, and dark mode: [Styling](styling/styling.md) and [Theming](styling/theming.md).
- 3D inside your UI, which is optional: [3D](3d/README.md).
