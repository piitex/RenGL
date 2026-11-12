package me.piitex.engine.render3d.scene;

import me.piitex.engine.math.Vec2;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import me.piitex.engine.ui.overlays.TextFieldOverlay;
import me.piitex.engine.ui.scroll.ScrollContainer;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A container shown on a surface in the 3D world: it is drawn into a texture, and a pointer aimed at the texture
 * hovers and clicks it by the same rules as a window. No GPU is involved.
 */
class UiScreenTest {
    private static final Color RED = new Color(1f, 0f, 0f, 1f);
    private static final Color GREEN = new Color(0f, 1f, 0f, 1f);
    private static final Color BLUE = new Color(0f, 0f, 1f, 1f);

    /** A 100 x 60 panel: a 40 x 20 box near the top at (10, 5) and a 40 x 25 button near the bottom at (55, 30). */
    private static final class Panel {
        final Container root = new Container(100, 60);
        final Container box = new Container(10, 5, 40, 20);
        final ButtonOverlay button = new ButtonOverlay("Go", 40, 25);

        Panel() {
            root.getStyling().setBackgroundColor(RED);
            box.getStyling().setBackgroundColor(BLUE);
            button.setX(55);
            button.setY(30);
            root.addElement(box);
            root.addElement(button);
        }
    }

    /** The middle of the box, then of the button, as a spot on the 100 x 60 texture; neither is at the height a flip would leave alone. */
    private static final Vec2 ON_BOX = new Vec2(0.3f, 0.25f);
    private static final Vec2 ON_BUTTON = new Vec2(0.75f, 0.7f);
    private static final Vec2 ON_NOTHING = new Vec2(0.02f, 0.95f);

    private static int[] rgba(TextureData t, int x, int y) {
        int i = (y * t.width() + x) * 4;
        byte[] p = t.pixels();
        return new int[]{p[i] & 255, p[i + 1] & 255, p[i + 2] & 255, p[i + 3] & 255};
    }

    private static void assertColor(int[] actual, int r, int g, int b, String what) {
        assertTrue(Math.abs(actual[0] - r) <= 3 && Math.abs(actual[1] - g) <= 3 && Math.abs(actual[2] - b) <= 3 && actual[3] == 255,
                what + ": got " + actual[0] + ", " + actual[1] + ", " + actual[2] + ", " + actual[3] + ", wanted " + r + ", " + g + ", " + b + ", 255");
    }

    // ---- the picture ----------------------------------------------------------------------------------------------

    @Test
    void theTextureIsTheContainerSizeTimesTheScale() {
        try (UiScreen one = new UiScreen(new Panel().root); UiScreen two = new UiScreen(new Panel().root, 2f); UiScreen half = new UiScreen(new Panel().root, 0.5f)) {
            assertEquals(100, one.texture().width());
            assertEquals(60, one.texture().height());
            assertEquals(200, two.texture().width());
            assertEquals(120, two.texture().height());
            assertEquals(50, half.texture().width());
            assertEquals(30, half.texture().height());
        }
    }

    @Test
    void theTextureIsOpaqueBlackUntilTheFirstRefresh() {
        try (UiScreen screen = new UiScreen(new Panel().root)) {
            assertColor(rgba(screen.texture(), 50, 30), 0, 0, 0, "before refresh");
            assertEquals(0, screen.texture().version());
        }
    }

    @Test
    void refreshDrawsTheContainerWithTheTopRowFirst() {
        Panel panel = new Panel();
        try (UiScreen screen = new UiScreen(panel.root)) {
            screen.refresh();
            TextureData t = screen.texture();
            assertEquals(1, t.version());
            assertColor(rgba(t, 2, 2), 255, 0, 0, "the container's own background, top left");
            assertColor(rgba(t, 30, 15), 0, 0, 255, "the box");
            assertColor(rgba(t, 30, 45), 255, 0, 0, "below the box: the box is at the top, not flipped");
            assertColor(rgba(t, 90, 3), 255, 0, 0, "right of the box");
        }
    }

    @Test
    void aSharperScreenDrawsTheSameThingBigger() {
        Panel panel = new Panel();
        try (UiScreen screen = new UiScreen(panel.root, 2f)) {
            screen.refresh();
            TextureData t = screen.texture();
            assertColor(rgba(t, 60, 30), 0, 0, 255, "the box at twice the size");
            assertColor(rgba(t, 110, 30), 255, 0, 0, "where the box ends, at twice the size");
        }
    }

    @Test
    void aContainerWithNoBackgroundOfItsOwnIsADarkScreenNotASeeThroughOne() {
        Container bare = new Container(20, 20);
        try (UiScreen screen = new UiScreen(bare)) {
            screen.refresh();
            assertColor(rgba(screen.texture(), 10, 10), 0, 0, 0, "default backdrop");
            screen.setBackground(GREEN).refresh();
            assertColor(rgba(screen.texture(), 10, 10), 0, 255, 0, "chosen backdrop");
        }
    }

    @Test
    void aContainerAwayFromTheOriginStillFillsTheTexture() {
        Panel panel = new Panel();
        panel.root.setX(300);
        panel.root.setY(200);
        try (UiScreen screen = new UiScreen(panel.root)) {
            screen.refresh();
            assertColor(rgba(screen.texture(), 2, 2), 255, 0, 0, "corner");
            assertColor(rgba(screen.texture(), 30, 15), 0, 0, 255, "box");
            screen.pointAt(ON_BOX);
            assertSame(panel.box, screen.hovered(), "the pointer finds the box wherever the container sits");
        }
    }

    @Test
    void whatChangesInTheContainerShowsAfterTheNextRefresh() {
        Panel panel = new Panel();
        try (UiScreen screen = new UiScreen(panel.root)) {
            screen.refresh();
            assertColor(rgba(screen.texture(), 30, 15), 0, 0, 255, "before");
            panel.box.getStyling().setBackgroundColor(GREEN);
            assertColor(rgba(screen.texture(), 30, 15), 0, 0, 255, "not until refreshed");
            screen.refresh();
            assertColor(rgba(screen.texture(), 30, 15), 0, 255, 0, "after");
            assertEquals(2, screen.texture().version());
        }
    }

    @Test
    void refreshWithATimeStepAdvancesTheContainersAnimations() {
        List<Float> steps = new ArrayList<>();
        Container root = new Container(10, 10) {
            @Override
            public void update(float delta) {
                steps.add(delta);
                super.update(delta);
            }
        };
        try (UiScreen screen = new UiScreen(root)) {
            screen.refresh(0.25f);
            screen.refresh();
            assertEquals(List.of(0.25f), steps, "only the call with a step updates");
        }
    }

    @Test
    void hoverShowsOnTheScreen() {
        Panel panel = new Panel();
        panel.box.getStyling().setHoverFollowsBackground(false);
        panel.box.getStyling().setHoverColor(GREEN);
        try (UiScreen screen = new UiScreen(panel.root)) {
            screen.pointAt(ON_BOX);
            screen.refresh();
            assertColor(rgba(screen.texture(), 30, 15), 0, 255, 0, "hovered box");
            screen.pointAway();
            screen.refresh();
            assertColor(rgba(screen.texture(), 30, 15), 0, 0, 255, "box after the pointer left");
        }
    }

    // ---- the pointer ----------------------------------------------------------------------------------------------

    @Test
    void thePointerHoversWhatItIsOver() {
        Panel panel = new Panel();
        try (UiScreen screen = new UiScreen(panel.root)) {
            assertFalse(screen.isPointing());
            assertNull(screen.hovered());

            screen.pointAt(ON_BOX);
            assertTrue(screen.isPointing());
            assertSame(panel.box, screen.hovered());
            assertTrue(panel.box.isHovered());
            assertFalse(panel.button.isHovered());

            screen.pointAt(ON_BUTTON);
            assertSame(panel.button, screen.hovered());
            assertTrue(panel.button.isHovered());
            assertFalse(panel.box.isHovered(), "the box lost the pointer");

            screen.pointAt(ON_NOTHING);
            assertSame(panel.root, screen.hovered(), "bare background is the container itself");
            assertFalse(panel.button.isHovered());

            screen.pointAway();
            assertFalse(screen.isPointing());
            assertNull(screen.hovered());
            assertFalse(panel.root.isHovered());
        }
    }

    @Test
    void aNullSpotTakesThePointerAway() {
        Panel panel = new Panel();
        try (UiScreen screen = new UiScreen(panel.root)) {
            screen.pointAt(ON_BOX);
            screen.pointAt((Vec2) null);
            assertFalse(screen.isPointing());
            assertFalse(panel.box.isHovered());
        }
    }

    @Test
    void aPlainElementClicksOnPressAtThePointersSpotInContainerPoints() {
        Panel panel = new Panel();
        List<double[]> clicks = new ArrayList<>();
        panel.box.onMouseClick(e -> clicks.add(new double[]{e.getMouseX(), e.getMouseY(), e.getButton(), e.getAction()}));
        try (UiScreen screen = new UiScreen(panel.root)) {
            screen.pointAt(ON_BOX);
            assertTrue(screen.press(), "the press was used");
            assertEquals(1, clicks.size());
            assertEquals(30, clicks.get(0)[0], 0.01);
            assertEquals(15, clicks.get(0)[1], 0.01);
            assertEquals(0, clicks.get(0)[2], "the left button");
            assertEquals(1, clicks.get(0)[3], "a press");
            screen.release();
            assertEquals(1, clicks.size(), "release adds nothing for a click on press");
        }
    }

    @Test
    void aButtonClicksWhenTheButtonComesUpOverIt() {
        Panel panel = new Panel();
        AtomicInteger clicks = new AtomicInteger();
        panel.button.onMouseClick(e -> clicks.incrementAndGet());
        try (UiScreen screen = new UiScreen(panel.root)) {
            screen.pointAt(ON_BUTTON);
            assertTrue(screen.press());
            assertEquals(0, clicks.get(), "not on press");
            screen.release();
            assertEquals(1, clicks.get(), "on release");
            screen.release();
            assertEquals(1, clicks.get(), "a second release has nothing waiting");
        }
    }

    @Test
    void releasingElsewhereOrAfterThePointerLeftDoesNotClick() {
        Panel panel = new Panel();
        AtomicInteger clicks = new AtomicInteger();
        panel.button.onMouseClick(e -> clicks.incrementAndGet());
        try (UiScreen screen = new UiScreen(panel.root)) {
            screen.pointAt(ON_BUTTON);
            screen.press();
            screen.pointAt(ON_BOX);
            screen.release();
            assertEquals(0, clicks.get(), "let go over something else");

            screen.pointAt(ON_BUTTON);
            screen.press();
            screen.pointAway();
            screen.release();
            assertEquals(0, clicks.get(), "let go with the pointer off the screen");

            screen.pointAt(ON_BUTTON);
            screen.press();
            screen.pointAt(new Vec2(0.78f, 0.8f)); // moved, still on the button
            screen.release();
            assertEquals(1, clicks.get(), "moved but still on it");
        }
    }

    @Test
    void aPressOnNothingIsLeftToTheGame() {
        Panel panel = new Panel();
        try (UiScreen screen = new UiScreen(panel.root)) {
            assertFalse(screen.press(), "pointer not on the screen");
            screen.pointAt(ON_NOTHING);
            assertFalse(screen.press(), "bare background listens for nothing");
        }
    }

    @Test
    void aDisabledElementTakesThePressAndDoesNotClick() {
        Panel panel = new Panel();
        AtomicInteger clicks = new AtomicInteger(), refused = new AtomicInteger();
        panel.button.onMouseClick(e -> clicks.incrementAndGet());
        panel.button.onDisabledClick(refused::incrementAndGet);
        panel.button.setDisabled(true);
        try (UiScreen screen = new UiScreen(panel.root)) {
            screen.pointAt(ON_BUTTON);
            assertTrue(screen.press(), "a disabled button still takes the press");
            screen.release();
            assertEquals(0, clicks.get());
            assertEquals(1, refused.get(), "its disabled click runs on release");
        }
    }

    @Test
    void theRightButtonFiresNothingOnReleaseAndKeepsLeftClicksSeparate() {
        Panel panel = new Panel();
        AtomicInteger clicks = new AtomicInteger();
        panel.button.onMouseClick(e -> clicks.incrementAndGet());
        try (UiScreen screen = new UiScreen(panel.root)) {
            screen.pointAt(ON_BUTTON);
            screen.press(1); // right
            screen.release(1);
            assertEquals(0, clicks.get(), "a click-on-release button waits for the left button");
        }
    }

    @Test
    void aHitOnTheScreensNodeAimsThePointerAndAnyOtherHitTakesItAway() {
        Panel panel = new Panel();
        AtomicInteger clicks = new AtomicInteger();
        panel.box.onMouseClick(e -> clicks.incrementAndGet());
        Material white = new Material(Color.WHITE);
        Node3D monitor = new Node3D("monitor", Mesh.plane(1f, 1f), white);
        Node3D wall = new Node3D("wall", Mesh.plane(1f, 1f), white);
        Hit onMonitor = new Hit(monitor, 1f, new Vec3(0, 0, 0), new Vec3(0, 0, 1), 0, -1, ON_BOX);
        Hit onWall = new Hit(wall, 1f, new Vec3(0, 0, 0), new Vec3(0, 0, 1), 0, -1, ON_BOX);
        try (UiScreen screen = new UiScreen(panel.root)) {
            screen.pointAt(onMonitor, monitor);
            assertSame(panel.box, screen.hovered());

            screen.pointAt(onWall, monitor);
            assertFalse(screen.isPointing(), "a hit on another node");
            screen.pointAt(onMonitor, monitor);
            screen.pointAt(null, monitor);
            assertFalse(screen.isPointing(), "no hit at all");

            assertFalse(screen.click(onWall, monitor), "clicking the wall does not click the screen");
            assertEquals(0, clicks.get());
            assertTrue(screen.click(onMonitor, monitor));
            assertEquals(1, clicks.get());
        }
    }

    @Test
    void aMeshWithoutAreaGivesNoSpotAndSoNoPointer() {
        Panel panel = new Panel();
        Node3D monitor = new Node3D("monitor", Mesh.plane(1f, 1f), new Material(Color.WHITE));
        Hit noUv = new Hit(monitor, 1f, new Vec3(0, 0, 0), new Vec3(0, 0, 1), 0);
        try (UiScreen screen = new UiScreen(panel.root)) {
            screen.pointAt(noUv, monitor);
            assertFalse(screen.isPointing());
        }
    }

    @Test
    void theWheelScrollsWhatIsUnderThePointer() {
        ScrollContainer list = new ScrollContainer(0, 0, 100, 50);
        list.addElement(new Container(0, 0, 100, 200));
        Container root = new Container(100, 50);
        root.addElement(list);
        try (UiScreen screen = new UiScreen(root)) {
            assertFalse(screen.scroll(0, -1), "pointer not on the screen");
            screen.pointAt(new Vec2(0.5f, 0.5f));
            assertEquals(0, list.getScrollOffsetY());
            assertFalse(screen.scroll(0, 1), "wheel up at the top has nothing to do");
            assertTrue(screen.scroll(0, -1), "wheel down");
            double down = list.getScrollOffsetY();
            assertTrue(down > 0, "the list moved");
            assertTrue(screen.scroll(0, 1), "wheel back up");
            assertTrue(list.getScrollOffsetY() < down, "the list came back");
        }
    }

    @Test
    void aScreenNeedsAContainerWithASize() {
        assertThrows(IllegalArgumentException.class, () -> new UiScreen(null));
        assertThrows(IllegalArgumentException.class, () -> new UiScreen(new Container(0, 10)));
        assertThrows(IllegalArgumentException.class, () -> new UiScreen(new Container(10, 10), 0f));
        assertThrows(IllegalArgumentException.class, () -> new UiScreen(new Container(10, 10), -1f));
    }

    // ---- typing ---------------------------------------------------------------------------------------------------

    /** A 100 x 60 form with two text fields, one at the top (5, 5, 90 x 15) and one below it (5, 30, 90 x 15). */
    private static final class Form {
        final Container root = new Container(100, 60);
        final TextFieldOverlay first = new TextFieldOverlay(5, 5, 90, 15);
        final TextFieldOverlay second = new TextFieldOverlay(5, 30, 90, 15);

        Form() {
            root.getStyling().setBackgroundColor(RED);
            root.addElement(first);
            root.addElement(second);
        }
    }

    private static final Vec2 ON_FIRST = new Vec2(0.5f, 12.5f / 60f);
    private static final Vec2 ON_SECOND = new Vec2(0.5f, 37.5f / 60f);
    private static final Vec2 FIRST_LEFT = new Vec2(0.06f, 12.5f / 60f);
    private static final Vec2 FIRST_RIGHT = new Vec2(0.94f, 12.5f / 60f);

    private static void type(UiScreen screen, String text) {
        text.codePoints().forEach(screen::typeChar);
    }

    private static boolean press(UiScreen screen, Vec2 at) {
        screen.pointAt(at);
        boolean taken = screen.press();
        screen.release();
        return taken;
    }

    @Test
    void pressingAFieldGivesItTheKeyboard() {
        Form form = new Form();
        try (UiScreen screen = new UiScreen(form.root)) {
            assertFalse(screen.hasFocus());
            assertTrue(press(screen, ON_FIRST), "a field takes the press");
            assertTrue(screen.hasFocus());
            assertSame(form.first, screen.focused());
            assertTrue(form.first.isFocused(), "the field was told");
        }
    }

    @Test
    void pressingAnotherFieldMovesTheKeyboardToIt() {
        Form form = new Form();
        try (UiScreen screen = new UiScreen(form.root)) {
            press(screen, ON_FIRST);
            press(screen, ON_SECOND);
            assertSame(form.second, screen.focused());
            assertFalse(form.first.isFocused(), "the first was told it lost it");
            assertTrue(form.second.isFocused());
        }
    }

    @Test
    void pressingBareBackgroundOrAwayFromTheScreenLetsGoOfTheKeyboard() {
        Form form = new Form();
        try (UiScreen screen = new UiScreen(form.root)) {
            press(screen, ON_FIRST);
            assertFalse(press(screen, ON_NOTHING), "bare background is not taken");
            assertFalse(screen.hasFocus());
            assertFalse(form.first.isFocused());

            press(screen, ON_FIRST);
            screen.pointAway();
            assertFalse(screen.press(), "a press away from the screen is not the screen's");
            assertFalse(screen.hasFocus(), "but it is a click elsewhere, so the typing stops");
        }
    }

    @Test
    void typedCharactersGoToTheFocusedFieldOnly() {
        Form form = new Form();
        try (UiScreen screen = new UiScreen(form.root)) {
            assertFalse(screen.typeChar('x'), "nothing has the keyboard");
            press(screen, ON_SECOND);
            assertTrue(screen.typeChar('h'));
            assertTrue(screen.typeChar('i'));
            assertEquals("hi", form.second.getText());
            assertEquals("", form.first.getText());
        }
    }

    @Test
    void keysEditTheFocusedField() {
        Form form = new Form();
        try (UiScreen screen = new UiScreen(form.root)) {
            assertFalse(screen.key(GLFW.GLFW_KEY_BACKSPACE, GLFW.GLFW_PRESS, 0), "nothing has the keyboard, so the key is the game's");
            press(screen, ON_FIRST);
            type(screen, "abc");
            assertTrue(screen.key(GLFW.GLFW_KEY_BACKSPACE, GLFW.GLFW_PRESS, 0));
            assertEquals("ab", form.first.getText());
            assertTrue(screen.key(GLFW.GLFW_KEY_LEFT, GLFW.GLFW_PRESS, 0));
            type(screen, "X");
            assertEquals("aXb", form.first.getText(), "the arrow moved the caret");
            assertTrue(screen.key(GLFW.GLFW_KEY_BACKSPACE, GLFW.GLFW_REPEAT, 0), "a held key repeats");
            assertEquals("ab", form.first.getText());
        }
    }

    @Test
    void shortcutsWorkOnTheFocusedField() {
        Form form = new Form();
        try (UiScreen screen = new UiScreen(form.root)) {
            press(screen, ON_FIRST);
            type(screen, "abc");
            screen.key(GLFW.GLFW_KEY_A, GLFW.GLFW_PRESS, GLFW.GLFW_MOD_CONTROL);
            assertEquals("abc", form.first.getSelectedText(), "select all");
            type(screen, "Z");
            assertEquals("Z", form.first.getText(), "typing replaces the selection");
            screen.key(GLFW.GLFW_KEY_Z, GLFW.GLFW_PRESS, GLFW.GLFW_MOD_CONTROL);
            assertEquals("abc", form.first.getText(), "undo");
        }
    }

    @Test
    void aKeyLetGoOfReachesTheFieldButIsNeverTaken() {
        Form form = new Form();
        try (UiScreen screen = new UiScreen(form.root)) {
            press(screen, ON_FIRST);
            type(screen, "ab");
            assertFalse(screen.key(GLFW.GLFW_KEY_BACKSPACE, GLFW.GLFW_RELEASE, 0), "the game still sees a key come up");
            assertEquals("ab", form.first.getText(), "and a release edits nothing");
        }
    }

    @Test
    void enterSubmitsTheField() {
        Form form = new Form();
        List<String> submitted = new ArrayList<>();
        form.first.onSubmit(submitted::add);
        try (UiScreen screen = new UiScreen(form.root)) {
            press(screen, ON_FIRST);
            type(screen, "open");
            assertTrue(screen.key(GLFW.GLFW_KEY_ENTER, GLFW.GLFW_PRESS, 0));
            assertEquals(List.of("open"), submitted);
        }
    }

    @Test
    void tabMovesBetweenFieldsAndWrapsAround() {
        Form form = new Form();
        try (UiScreen screen = new UiScreen(form.root)) {
            assertFalse(screen.key(GLFW.GLFW_KEY_TAB, GLFW.GLFW_PRESS, 0), "Tab with nothing focused is the game's");
            assertFalse(screen.hasFocus());

            press(screen, ON_FIRST);
            assertTrue(screen.key(GLFW.GLFW_KEY_TAB, GLFW.GLFW_PRESS, 0));
            assertSame(form.second, screen.focused());
            screen.key(GLFW.GLFW_KEY_TAB, GLFW.GLFW_PRESS, 0);
            assertSame(form.first, screen.focused(), "wraps from the last field to the first");
            screen.key(GLFW.GLFW_KEY_TAB, GLFW.GLFW_PRESS, GLFW.GLFW_MOD_SHIFT);
            assertSame(form.second, screen.focused(), "Shift+Tab goes back, wrapping the other way");
            assertFalse(form.first.isFocused());
        }
    }

    @Test
    void tabFollowsTheTabIndexAndSkipsDisabledFields() {
        Form form = new Form();
        form.second.setTabIndex(0);
        try (UiScreen screen = new UiScreen(form.root)) {
            screen.focusNext();
            assertSame(form.second, screen.focused(), "a field with a tab index comes first");
            screen.focusNext();
            assertSame(form.first, screen.focused());

            form.second.setDisabled(true);
            screen.focusNext();
            assertSame(form.first, screen.focused(), "a disabled field is not a stop");
            screen.focusPrevious();
            assertSame(form.first, screen.focused());
        }
    }

    @Test
    void escapeLetsGoOfTheKeyboard() {
        Form form = new Form();
        try (UiScreen screen = new UiScreen(form.root)) {
            press(screen, ON_FIRST);
            assertTrue(screen.key(GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_PRESS, 0));
            assertFalse(screen.hasFocus());
            assertFalse(form.first.isFocused());
            assertFalse(screen.key(GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_PRESS, 0), "with nothing focused it is the game's");
        }
    }

    @Test
    void aDisabledFieldTakesThePressButNotTheKeyboard() {
        Form form = new Form();
        try (UiScreen screen = new UiScreen(form.root)) {
            press(screen, ON_FIRST);
            form.second.setDisabled(true);
            assertTrue(press(screen, ON_SECOND), "a disabled field still takes the press");
            assertSame(form.first, screen.focused(), "and leaves the keyboard where it was");

            form.first.setDisabled(true);
            assertFalse(screen.hasFocus(), "a field disabled while it has the keyboard loses it");
            assertFalse(screen.typeChar('x'));
        }
    }

    @Test
    void theKeyboardCanBeGivenAndTakenInCode() {
        Form form = new Form();
        try (UiScreen screen = new UiScreen(form.root)) {
            screen.focus(form.second);
            assertSame(form.second, screen.focused());
            assertTrue(form.second.isFocused());
            screen.focus(new me.piitex.engine.ui.overlays.TextOverlay("Go", 14f, Color.BLACK));
            assertFalse(screen.hasFocus(), "something that cannot take keys clears the focus");
            assertFalse(form.second.isFocused());
            screen.focus(form.first);
            screen.clearFocus();
            assertFalse(form.first.isFocused());
        }
    }

    @Test
    void aScreenWithNoFieldHasNowhereToTab() {
        Container plain = new Container(100, 60);
        plain.addElement(new me.piitex.engine.ui.overlays.TextOverlay("Hello", 14f, Color.BLACK));
        try (UiScreen screen = new UiScreen(plain)) {
            screen.focusNext();
            screen.focusPrevious();
            assertFalse(screen.hasFocus());
        }
    }

    @Test
    void aButtonIsATabStopThatLeavesOtherKeysToTheGame() {
        Panel panel = new Panel();
        AtomicInteger pressed = new AtomicInteger();
        panel.button.onAction(pressed::incrementAndGet);
        try (UiScreen screen = new UiScreen(panel.root)) {
            screen.focusNext();
            assertSame(panel.button, screen.focused());
            assertFalse(screen.key(GLFW.GLFW_KEY_W, GLFW.GLFW_PRESS, 0), "W is the game's while a button has focus");
            assertFalse(screen.typeChar('w'));
            assertTrue(screen.key(GLFW.GLFW_KEY_ENTER, GLFW.GLFW_PRESS, 0));
            assertEquals(1, pressed.get());
        }
    }

    @Test
    void pressingAFieldPutsTheCaretWhereItWasPressed() {
        Form form = new Form();
        try (UiScreen screen = new UiScreen(form.root)) {
            press(screen, ON_FIRST);
            type(screen, "abcdef");
            press(screen, FIRST_LEFT);
            type(screen, "X");
            assertEquals("Xabcdef", form.first.getText(), "pressed at the left end");
            press(screen, FIRST_RIGHT);
            type(screen, "Y");
            assertEquals("XabcdefY", form.first.getText(), "pressed at the right end");
        }
    }

    @Test
    void movingThePointerWhileHoldingSelectsTextUntilTheRelease() {
        Form form = new Form();
        try (UiScreen screen = new UiScreen(form.root)) {
            press(screen, ON_FIRST);
            type(screen, "abc");

            screen.pointAt(FIRST_LEFT);
            screen.press();
            screen.pointAt(FIRST_RIGHT);
            assertEquals("abc", form.first.getSelectedText(), "dragged across the whole text");
            screen.release();

            screen.pointAt(FIRST_LEFT);
            assertEquals("abc", form.first.getSelectedText(), "after the release the pointer no longer extends it");
        }
    }

    @Test
    void shiftTabGoesBackwardsThroughThreeFields() {
        Container root = new Container(100, 60);
        TextFieldOverlay a = new TextFieldOverlay(5, 5, 90, 10), b = new TextFieldOverlay(5, 20, 90, 10), c = new TextFieldOverlay(5, 35, 90, 10);
        root.addElement(a);
        root.addElement(b);
        root.addElement(c);
        try (UiScreen screen = new UiScreen(root)) {
            screen.focus(b);
            screen.key(GLFW.GLFW_KEY_TAB, GLFW.GLFW_PRESS, 0);
            assertSame(c, screen.focused(), "Tab goes forward");
            screen.key(GLFW.GLFW_KEY_TAB, GLFW.GLFW_PRESS, GLFW.GLFW_MOD_SHIFT);
            screen.key(GLFW.GLFW_KEY_TAB, GLFW.GLFW_PRESS, GLFW.GLFW_MOD_SHIFT);
            assertSame(a, screen.focused(), "Shift+Tab goes back, twice");
            screen.focusPrevious();
            assertSame(c, screen.focused(), "and wraps to the last");
            screen.clearFocus();
            screen.focusPrevious();
            assertSame(c, screen.focused(), "with nothing focused, backwards starts at the last");
        }
    }

    @Test
    void aFieldThatIsDisabledCannotBeGivenTheKeyboardInCode() {
        Form form = new Form();
        form.second.setDisabled(true);
        try (UiScreen screen = new UiScreen(form.root)) {
            screen.focus(form.second);
            assertFalse(screen.hasFocus());
            assertFalse(form.second.isFocused());
        }
    }

    @Test
    void everyKeyActionReachesTheFocusedElement() {
        List<Integer> actions = new ArrayList<>();
        TextFieldOverlay recorder = new TextFieldOverlay(5, 5, 90, 15) {
            @Override
            public void onKeyPressed(int key, int action, int mods) {
                actions.add(action);
                super.onKeyPressed(key, action, mods);
            }
        };
        Container root = new Container(100, 60);
        root.addElement(recorder);
        try (UiScreen screen = new UiScreen(root)) {
            screen.focus(recorder);
            screen.key(GLFW.GLFW_KEY_LEFT, GLFW.GLFW_PRESS, 0);
            screen.key(GLFW.GLFW_KEY_LEFT, GLFW.GLFW_REPEAT, 0);
            screen.key(GLFW.GLFW_KEY_LEFT, GLFW.GLFW_RELEASE, 0);
            assertEquals(List.of(GLFW.GLFW_PRESS, GLFW.GLFW_REPEAT, GLFW.GLFW_RELEASE), actions);
        }
    }

    @Test
    void typedTextShowsOnTheScreenAfterARefresh() {
        Form form = new Form();
        try (UiScreen screen = new UiScreen(form.root)) {
            screen.refresh();
            byte[] before = screen.texture().pixels().clone();
            press(screen, ON_FIRST);
            type(screen, "HELLO");
            assertArrayEquals(before, screen.texture().pixels(), "nothing changes until the refresh");
            screen.refresh();
            int changed = 0;
            byte[] after = screen.texture().pixels();
            for (int y = 5; y < 20; y++) {
                for (int x = 5; x < 95; x++) {
                    int i = (y * screen.texture().width() + x) * 4;
                    if (before[i] != after[i] || before[i + 1] != after[i + 1] || before[i + 2] != after[i + 2]) changed++;
                }
            }
            assertTrue(changed > 30, "the first field's pixels changed with the text: " + changed);
            for (int y = 30; y < 45; y++) {
                for (int x = 5; x < 95; x++) {
                    int i = (y * screen.texture().width() + x) * 4;
                    assertTrue(before[i] == after[i] && before[i + 1] == after[i + 1] && before[i + 2] == after[i + 2],
                            "the second field did not change at " + x + ", " + y);
                }
            }
        }
    }
}
