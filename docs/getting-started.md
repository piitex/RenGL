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

## Building

```bash
mvn compile
```

The native libraries (LWJGL and Skiko) are picked automatically by OS and architecture through Maven
profiles (`lwjgl-natives-<os>-<arch>`). Supported: Linux, macOS and Windows, each on x86-64 and arm64.

To publish jars that other apps can depend on, the `natives.platform` property builds one small "natives" jar per
platform for every module that has natives. Run one platform per invocation:

```bash
mvn -DskipAutoNatives -Dnatives.platform=macos-aarch64 package
```

## Running

Run the class that calls `Engine.start`. The bundled demo lists these JVM flags:

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
