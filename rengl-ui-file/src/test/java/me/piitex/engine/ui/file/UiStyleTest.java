package me.piitex.engine.ui.file;

import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.background.Background;
import me.piitex.engine.ui.background.ConstellationBackground;
import me.piitex.engine.ui.background.DotGridBackground;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.LinearGradient;
import me.piitex.engine.ui.color.Paint;
import me.piitex.engine.ui.color.RainbowColor;
import me.piitex.engine.ui.color.ScrollingGradient;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.overlays.TextOverlay;
import me.piitex.engine.ui.theme.BaseTheme;
import me.piitex.engine.ui.theme.DarkTheme;
import me.piitex.engine.ui.theme.LightTheme;
import me.piitex.engine.ui.theme.ThemeManager;
import me.piitex.engine.utils.flags.GeneratedClass;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a layout file can say about how an element looks beyond a flat color: gradients, a background effect, a themed panel.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
class UiStyleTest {
    @AfterEach
    void putTheThemeBack() {
        ThemeManager.setCurrent(null);
    }

    private static Container root() {
        Container root = (Container) UiKinds.get("container").create("Root");
        root.setWidth(400);
        root.setHeight(300);
        return root;
    }

    private static Element add(Container parent, String kind, String name) {
        Element element = UiKinds.get(kind).create(name);
        parent.addElement(element);
        return element;
    }

    /** Sets a property as an editor or a file does, which is what marks it as set on purpose. */
    private static void set(Element element, String property, Object value) {
        UiKinds.of(element).getProperty(property).set(element, value);
    }

    private static Container again(Container root) {
        return UiFile.parse(UiFile.toJson(root), Path.of(""));
    }

    private static Element named(Container root, String name) {
        return UiFile.find(root, name);
    }

    @Test
    void aGradientBackgroundAndBorderAreSavedAndLoaded() {
        Container root = root();
        Element panel = add(root, "container", "panel");
        set(panel, "background", new ScrollingGradient(List.of(Color.RED, Color.BLUE), 30f, 55f));
        set(panel, "border", new LinearGradient(Color.GREEN, Color.BLACK, 90f));

        Element back = named(again(root), "panel");
        ScrollingGradient fill = assertInstanceOf(ScrollingGradient.class, back.getStyling().getBackgroundColor());
        assertEquals(55f, fill.getSpeed());
        assertEquals(30f, fill.getAngle());
        LinearGradient border = assertInstanceOf(LinearGradient.class, back.getStyling().getBorderColor());
        assertEquals(90f, border.getAngle());
        assertFalse(border instanceof ScrollingGradient);
        assertTrue(UiKinds.get("container").getProperty("background").isSet(back));
    }

    @Test
    void aRainbowBackgroundAndTextAreSavedAndLoaded() {
        Container root = root();
        set(add(root, "container", "bar"), "background", new RainbowColor(9f, 0.5f, 1f, 1f));
        set(add(root, "text", "title"), "textColor", new LinearGradient(Color.RED, Color.YELLOW, 0f));

        Container back = again(root);
        assertEquals(9f, assertInstanceOf(RainbowColor.class, named(back, "bar").getStyling().getBackgroundColor()).getCycleDuration());
        assertInstanceOf(LinearGradient.class, ((TextOverlay) named(back, "title")).getTextColor());
    }

    @Test
    void aFileFromBeforeGradientsStillReadsItsColors() {
        String json = """
                { "format": 1, "root": { "kind": "container", "name": "r", "width": 100, "height": 100, "children": [
                  { "kind": "container", "name": "old", "background": [1, 0, 0, 1], "border": "#0000FF" } ] } }""";
        Element old = named(UiFile.parse(json, Path.of("")), "old");
        assertEquals(1f, ((Color) old.getStyling().getBackgroundColor()).getR());
        assertEquals(1f, ((Color) old.getStyling().getBorderColor()).getB());
    }

    @Test
    void aThemedBackgroundThatWasNeverSetIsNotWrittenAndOneThatWasSetIs() {
        Container root = root();
        add(root, "button", "plain");
        set(add(root, "button", "styled"), "background", new LinearGradient(Color.RED, Color.BLUE));
        String json = UiFile.toJson(root);
        Container back = UiFile.parse(json, Path.of(""));
        assertFalse(UiKinds.get("button").getProperty("background").isSet(named(back, "plain")));
        assertTrue(UiKinds.get("button").getProperty("background").isSet(named(back, "styled")));
        assertEquals(1, json.split("\"background\"", -1).length - 1, "only the one that was set is written");
    }

    @Test
    void aGradientComesBackFromTheEditorsGetter() {
        UiProperty background = UiKinds.get("container").getProperty("background");
        Element panel = UiKinds.get("container").create("p");
        LinearGradient gradient = new LinearGradient(Color.RED, Color.BLUE, 10f);
        background.set(panel, gradient);
        assertSame(gradient, background.get(panel));
    }

    @Test
    void aButtonAndAContainerHaveAHoverBackgroundAndATextDoesNot() {
        assertTrue(UiKinds.get("button").getProperty("hoverBackground").appliesTo(UiKinds.get("button").create("b")));
        assertTrue(UiKinds.get("vertical").getProperty("hoverBackground").appliesTo(UiKinds.get("vertical").create("v")));
        assertTrue(UiKinds.get("textfield").getProperty("hoverBackground").appliesTo(UiKinds.get("textfield").create("t")));
        assertFalse(UiKinds.get("text").getProperty("hoverBackground").appliesTo(UiKinds.get("text").create("x")));
        assertFalse(UiKinds.get("image").getProperty("hoverBackground").appliesTo(UiKinds.get("image").create("i")));
    }

    @Test
    void aHoverBackgroundIsSavedAndALayoutsOwnHoverFollowsItsBackground() {
        Container root = root();
        set(add(root, "button", "go"), "hoverBackground", new LinearGradient(Color.GREEN, Color.BLACK, 0f));
        set(add(root, "container", "card"), "background", Color.RED);

        Container back = again(root);
        assertInstanceOf(LinearGradient.class, named(back, "go").getStyling().getHoverColor());
        assertSame(named(back, "card").getStyling().getBackgroundColor(), named(back, "card").getStyling().getHoverColor(), "no hover look of its own until it is given one");
        assertFalse(UiKinds.get("container").getProperty("hoverBackground").isSet(named(back, "card")));
    }

    @Test
    void anEffectIsSavedAndLoadedWithItsSettings() {
        Container root = root();
        Element stars = add(root, "container", "stars");
        ConstellationBackground field = new ConstellationBackground();
        field.setParticleCount(33);
        field.setLinkDistance(70f);
        set(stars, "effect", field);

        Element back = named(again(root), "stars");
        ConstellationBackground read = assertInstanceOf(ConstellationBackground.class, back.getBackground());
        assertEquals(33, read.getParticleCount());
        assertEquals(70f, read.getLinkDistance());
        assertTrue(UiKinds.get("container").getProperty("effect").isSet(back));
    }

    @Test
    void anEffectIsLeftOutWhenThereIsNoneAndWhenItIsNotOneAFileCanHold() {
        Container root = root();
        add(root, "container", "bare");
        Element mine = add(root, "container", "mine");
        mine.setBackground((renderer, x, y, width, height) -> {
        });
        assertFalse(UiFile.toJson(root).contains("\"effect\""), "set in code, not through the property, so it isn't the file's");
        set(mine, "effect", new DotGridBackground());
        set(mine, "effect", null);
        assertNull(mine.getBackground());
        assertTrue(UiFile.toJson(root).contains("\"effect\"") == false, "set to none, so nothing to write");
    }

    @Test
    void theEffectPropertyHandsBackACopyAndChangesTheOneThatIsThere() {
        UiProperty effect = UiKinds.get("container").getProperty("effect");
        Element panel = UiKinds.get("container").create("p");
        ConstellationBackground showing = new ConstellationBackground();
        panel.setBackground(showing);
        effect.read(panel, Map.of("type", "constellation", "particleCount", 9.0), Path.of(""));
        assertSame(showing, panel.getBackground(), "same sort: changed in place, so it doesn't start over");
        assertEquals(9, showing.getParticleCount());

        Background before = (Background) effect.get(panel);
        assertNotSame(showing, before, "a copy, so what undo holds doesn't change with the effect");
        showing.setParticleCount(77);
        assertEquals(9, ((ConstellationBackground) before).getParticleCount());

        effect.set(panel, before);
        assertEquals(9, ((ConstellationBackground) panel.getBackground()).getParticleCount(), "put back by setting the copy");
        effect.read(panel, Map.of("type", "dotgrid"), Path.of(""));
        assertInstanceOf(DotGridBackground.class, panel.getBackground());
        effect.set(panel, null);
        assertNull(panel.getBackground());
    }

    @Test
    void handingTheEffectBackToTheThemeTakesAwayWhatWasSet() {
        UiProperty effect = UiKinds.get("container").getProperty("effect");
        Element panel = UiKinds.get("container").create("p");
        effect.read(panel, Map.of("type", "dotgrid"), Path.of(""));
        assertTrue(effect.isSet(panel));
        effect.reset(panel);
        assertFalse(effect.isSet(panel));
        assertNull(panel.getBackground());
    }

    @Test
    void aPanelFollowsTheThemeUntilItIsToldNotTo() {
        Container root = root();
        Container panel = (Container) add(root, "container", "panel");
        add(root, "container", "plain");
        UiProperty themed = UiKinds.get("container").getProperty("panel");
        themed.set(panel, true);
        assertTrue(panel.isUsingTheme());

        BaseTheme dark = new DarkTheme();
        ThemeManager.setCurrent(dark);
        assertSame(dark.getContainerBackground(), panel.getStyling().getBackgroundColor());
        assertEquals(dark.getCornerRadius(), panel.getStyling().getCornerRadius());

        Container back = again(root);
        assertTrue(((Container) named(back, "panel")).isUsingTheme());
        assertFalse(((Container) named(back, "plain")).isUsingTheme());
        assertEquals(1, UiFile.toJson(root).split("\"panel\": true", -1).length - 1, "only a panel says so");

        ThemeManager.setCurrent(new LightTheme());
        assertSame(ThemeManager.getCurrent().getContainerBackground(), ((Container) named(back, "panel")).getStyling().getBackgroundColor());

        themed.set(panel, false);
        assertFalse(panel.isUsingTheme());
        assertEquals(0f, ((Color) panel.getStyling().getBackgroundColor()).getA(), "back to see-through");
        assertEquals(0f, panel.getStyling().getCornerRadius());
        ThemeManager.setCurrent(dark);
        assertEquals(0f, ((Color) panel.getStyling().getBackgroundColor()).getA(), "and a theme change passes it by");
    }

    @Test
    void whatIsSetOnAPanelStillWinsOverTheTheme() {
        Container panel = (Container) UiKinds.get("container").create("p");
        panel.getStyling().setBackgroundColor(Color.RED);
        UiKinds.get("container").getProperty("panel").set(panel, true);
        ThemeManager.setCurrent(new DarkTheme());
        assertSame(Color.RED, panel.getStyling().getBackgroundColor());
        assertEquals(new DarkTheme().getCornerRadius(), panel.getStyling().getCornerRadius());
    }

    @Test
    void onlyAContainerThatHoldsElementsIsAPanel() {
        UiProperty panel = UiKinds.get("container").getProperty("panel");
        assertTrue(panel.appliesTo(UiKinds.get("vertical").create("v")));
        assertTrue(panel.appliesTo(UiKinds.get("scroll").create("s")));
        assertFalse(panel.appliesTo(UiKinds.get("button").create("b")), "a button themes itself");
        assertFalse(panel.appliesTo(UiKinds.get("number").create("n")), "a number field is a container but holds nothing of the layout's");
    }

    @Test
    void aPanelTakesItsThemesEffectAndGradient() {
        BaseTheme theme = new DarkTheme();
        theme.setContainerBackground(new LinearGradient(Color.RED, Color.BLUE, 90f));
        theme.setContainerEffect(Map.of("type", "dotgrid", "spacing", 12f));
        ThemeManager.setCurrent(theme);

        Container panel = (Container) UiKinds.get("container").create("p");
        panel.useTheme();
        assertInstanceOf(LinearGradient.class, panel.getStyling().getBackgroundColor());
        DotGridBackground dots = assertInstanceOf(DotGridBackground.class, panel.getBackground());
        assertEquals(12f, dots.getSpacing());
        assertFalse(panel.isBackgroundSet(), "it is the theme's, not set");

        ThemeManager.setCurrent(theme); // applying it again keeps the effect that is showing
        assertSame(dots, panel.getBackground());
        BaseTheme changed = new BaseTheme();
        changed.setContainerEffect(Map.of("type", "dotgrid", "spacing", 40f));
        ThemeManager.setCurrent(changed);
        assertSame(dots, panel.getBackground(), "the same sort is changed in place");
        assertEquals(40f, dots.getSpacing());
        ThemeManager.setCurrent(new LightTheme());
        assertNull(panel.getBackground(), "a theme with none takes it away");

        ThemeManager.setCurrent(theme);
        DotGridBackground mine = new DotGridBackground();
        panel.setBackground(mine);
        ThemeManager.setCurrent(changed);
        assertSame(mine, panel.getBackground(), "one set on purpose is left alone");
        assertEquals(24f, mine.getSpacing());
        panel.resetBackground();
        assertNotSame(mine, panel.getBackground());
        assertEquals(40f, ((DotGridBackground) panel.getBackground()).getSpacing(), "and handing it back gives the theme's");
    }

    @Test
    void eachPanelMakesItsOwnEffectFromTheThemesSettings() {
        BaseTheme theme = new BaseTheme();
        theme.setContainerEffect(Map.of("type", "constellation"));
        ThemeManager.setCurrent(theme);
        Container a = new Container(10, 10), b = new Container(10, 10);
        a.useTheme();
        b.useTheme();
        assertNotNull(a.getBackground());
        assertNotSame(a.getBackground(), b.getBackground(), "an effect has state of its own, so none is shared");
    }

    @Test
    void aBadThemeEffectGivesNoneRatherThanBreakingThePanel() {
        BaseTheme theme = new BaseTheme();
        theme.setContainerEffect(Map.of("type", "snow"));
        ThemeManager.setCurrent(theme);
        Container panel = new Container(10, 10);
        panel.useTheme();
        assertNull(panel.getBackground());
    }

    @Test
    void aWindowTakesItsThemesGradientAndEffectUnlessItWasGivenItsOwn() {
        BaseTheme theme = new BaseTheme();
        theme.setWindowBackground(new ScrollingGradient(Color.BLACK, Color.WHITE, 10f));
        theme.setWindowEffect(Map.of("type", "dotgrid"));
        Window window = new Window(new WindowOptions("test").setDimensions(100, 100));
        window.applyTheme(theme);
        assertInstanceOf(ScrollingGradient.class, window.getBackgroundColor());
        Background effect = assertInstanceOf(DotGridBackground.class, window.getBackground());
        window.applyTheme(theme);
        assertSame(effect, window.getBackground(), "applying it again keeps the effect that is showing");

        DotGridBackground mine = new DotGridBackground();
        window.setBackground(mine);
        window.applyTheme(new BaseTheme());
        assertSame(mine, window.getBackground(), "a window's own is left alone");

        window.setBackground(null);
        window.applyTheme(theme);
        assertNull(window.getBackground(), "and none set on purpose stays none");
        ThemeManager.setCurrent(theme);
        window.followThemeBackground();
        assertInstanceOf(DotGridBackground.class, window.getBackground());
    }

    @Test
    void theColorsOfAnElementAndItsPartsCanBeGradients() {
        assertEquals(Paint.class, UiKinds.get("button").getProperty("background").getType());
        assertEquals(Paint.class, UiKinds.get("button").getProperty("border").getType());
        assertEquals(Paint.class, UiKinds.get("button").getProperty("textColor").getType());
        assertEquals(Paint.class, UiKinds.get("textfield").getProperty("textColor").getType());
        assertEquals(Paint.class, UiKinds.get("checkbox").getProperty("checkColor").getType());
        assertEquals(Paint.class, UiKinds.get("slider").getProperty("trackColor").getType());
        assertEquals(Paint.class, UiKinds.get("textfield").getProperty("caretColor").getType());
    }

    @Test
    void theTextOfAControlHasMoreToStyle() {
        Container root = root();
        Element button = add(root, "button", "go");
        set(button, "textHover", new LinearGradient(Color.YELLOW, Color.WHITE, 0f));
        set(button, "textAlign", me.piitex.engine.ui.layout.Layout.Alignment.CENTER_LEFT);
        Element field = add(root, "textfield", "name");
        set(field, "placeholderColor", new LinearGradient(Color.GRAY, Color.DARK_GRAY, 0f));
        set(field, "caretColor", Color.RED);
        set(field, "selectionColor", new Color(0f, 1f, 0f, 0.4f));
        set(field, "prefix", "$");
        set(field, "rightAligned", true);
        set(field, "padding", 14f);
        Element area = add(root, "textarea", "notes");
        set(area, "caretColor", Color.BLUE);
        Element dropdown = add(root, "dropdown", "pick");
        set(dropdown, "textColor", Color.GREEN);
        set(dropdown, "textAlign", me.piitex.engine.ui.layout.Layout.Alignment.CENTER);

        Container back = again(root);
        assertInstanceOf(LinearGradient.class, named(back, "go").getStyling().getTextHoverColor());
        assertEquals(me.piitex.engine.ui.layout.Layout.Alignment.CENTER_LEFT, named(back, "go").getTextAlignment());
        me.piitex.engine.ui.overlays.TextFieldOverlay name = (me.piitex.engine.ui.overlays.TextFieldOverlay) named(back, "name");
        assertInstanceOf(LinearGradient.class, name.getPlaceholderColor());
        assertEquals(1f, ((Color) name.getCaretColor()).getR());
        assertEquals(0.4f, ((Color) name.getSelectionColor()).getA(), 1e-6f);
        assertEquals("$", name.getPrefix());
        assertTrue(name.isRightAligned());
        assertEquals(14f, name.getPadding());
        assertEquals(1f, ((Color) ((me.piitex.engine.ui.overlays.TextAreaOverlay) named(back, "notes")).getCaretColor()).getB());
        assertEquals(1f, ((Color) ((me.piitex.engine.ui.overlays.DropdownOverlay<?>) named(back, "pick")).getTextColor()).getG());
        assertEquals(me.piitex.engine.ui.layout.Layout.Alignment.CENTER, named(back, "pick").getTextAlignment());
    }

    @Test
    void aGradientOnAPartOfAControlSurvivesTheFile() {
        Container root = root();
        Element slider = add(root, "slider", "volume");
        set(slider, "trackColor", new LinearGradient(Color.RED, Color.BLUE, 0f));
        Element field = add(root, "textfield", "name");
        set(field, "caretColor", new LinearGradient(Color.GREEN, Color.BLUE, 90f));

        Container back = again(root);
        assertInstanceOf(LinearGradient.class, ((me.piitex.engine.ui.overlays.SliderOverlay) named(back, "volume")).getTrackColor());
        assertInstanceOf(LinearGradient.class, ((me.piitex.engine.ui.overlays.TextFieldOverlay) named(back, "name")).getCaretColor());
    }

    @Test
    void aTextPositionThatWasNeverSetIsNotWritten() {
        Container root = root();
        add(root, "button", "go");
        assertFalse(UiFile.toJson(root).contains("textAlign"));
        assertFalse(UiFile.toJson(root).contains("textHover"));
        assertTrue(UiKinds.get("button").getProperty("textAlign").isNullable());
        assertNull(UiKinds.get("button").getProperty("textAlign").get(named(root, "go")));
    }

    @Test
    void aScrollingGradientFromAThemeIsCopiedForEachPanelAndWindow() {
        BaseTheme theme = new BaseTheme();
        ScrollingGradient shared = new ScrollingGradient(List.of(Color.RED, Color.BLUE), 0f, 100f);
        theme.setContainerBackground(shared);
        theme.setWindowBackground(shared);
        ThemeManager.setCurrent(theme);

        Container a = new Container(10, 10), b = new Container(10, 10);
        a.useTheme();
        b.useTheme();
        ScrollingGradient fillA = assertInstanceOf(ScrollingGradient.class, a.getStyling().getBackgroundColor());
        ScrollingGradient fillB = assertInstanceOf(ScrollingGradient.class, b.getStyling().getBackgroundColor());
        assertNotSame(shared, fillA);
        assertNotSame(fillA, fillB, "each scrolls by itself, not in step and not faster the more there are");
        assertSame(fillA, a.getStyling().getHoverColor(), "its hover is the same copy, so it is advanced once");
        assertEquals(100f, fillA.getSpeed());
        assertEquals(0f, fillA.getAngle());
        assertEquals(2, fillA.getColors().size());

        a.update(1f);
        assertEquals(100f, fillA.getOffset(), 1e-3f);
        assertEquals(0f, fillB.getOffset(), 1e-3f);
        assertEquals(0f, shared.getOffset(), "the theme's own never moves");

        Window window = new Window(new WindowOptions("test").setDimensions(100, 100));
        window.applyTheme(theme);
        assertNotSame(shared, window.getBackgroundColor());
        assertInstanceOf(ScrollingGradient.class, window.getBackgroundColor());
    }

    @Test
    void aSolidColorAndARainbowAreSharedAsTheyHaveNoStateOfTheirOwn() {
        RainbowColor rainbow = new RainbowColor();
        assertSame(rainbow, me.piitex.engine.ui.color.Styling.ownCopy(rainbow));
        assertSame(Color.RED, me.piitex.engine.ui.color.Styling.ownCopy(Color.RED));
        LinearGradient still = new LinearGradient(Color.RED, Color.BLUE);
        assertSame(still, me.piitex.engine.ui.color.Styling.ownCopy(still));
    }
}
