package me.piitex.engine.ui.file;

import me.piitex.engine.ui.Anchor;
import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.background.Background;
import me.piitex.engine.ui.background.BackgroundKinds;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.Paint;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.containers.ResizableContainer;
import me.piitex.engine.ui.containers.SplitContainer;
import me.piitex.engine.ui.containers.TabContainer;
import me.piitex.engine.ui.image.IconAsset;
import me.piitex.engine.ui.layout.DockLayout;
import me.piitex.engine.ui.layout.FlowLayout;
import me.piitex.engine.ui.layout.GridLayout;
import me.piitex.engine.ui.layout.HorizontalLayout;
import me.piitex.engine.ui.layout.Layout;
import me.piitex.engine.ui.layout.StackLayout;
import me.piitex.engine.ui.layout.VerticalLayout;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import me.piitex.engine.ui.overlays.CheckBoxOverlay;
import me.piitex.engine.ui.overlays.ColorPickerOverlay;
import me.piitex.engine.ui.overlays.DateTimePickerOverlay;
import me.piitex.engine.ui.overlays.DropdownOverlay;
import me.piitex.engine.ui.overlays.IconOverlay;
import me.piitex.engine.ui.overlays.ImageOverlay;
import me.piitex.engine.ui.overlays.LoadingSpinnerOverlay;
import me.piitex.engine.ui.overlays.PercentSliderOverlay;
import me.piitex.engine.ui.overlays.ProgressBarOverlay;
import me.piitex.engine.ui.overlays.RadioButtonOverlay;
import me.piitex.engine.ui.overlays.RadioGroup;
import me.piitex.engine.ui.overlays.SeparatorOverlay;
import me.piitex.engine.ui.overlays.SliderOverlay;
import me.piitex.engine.ui.overlays.SpinnerOverlay;
import me.piitex.engine.ui.overlays.TextAreaOverlay;
import me.piitex.engine.ui.overlays.TextFieldOverlay;
import me.piitex.engine.ui.overlays.TextFlowOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;
import me.piitex.engine.ui.overlays.ToggleSwitchOverlay;
import me.piitex.engine.ui.scroll.ScrollContainer;
import me.piitex.engine.ui.text.TextAlign;
import me.piitex.engine.utils.flags.GeneratedClass;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Every {@link UiKind} a layout file can hold. The built-in ones are
 * <ul>
 *   <li>containers: {@code container}, {@code scroll}, {@code resizable}, {@code tabs}, {@code hsplit}, {@code vsplit};</li>
 *   <li>layouts: {@code vertical}, {@code horizontal}, {@code stack}, {@code grid}, {@code flow}, {@code dock};</li>
 *   <li>display: {@code text}, {@code textflow}, {@code image}, {@code icon}, {@code progress}, {@code spinner},
 *   {@code separator}, {@code vseparator};</li>
 *   <li>input: {@code button}, {@code textfield}, {@code textarea}, {@code number}, {@code checkbox}, {@code radio},
 *   {@code toggle}, {@code slider}, {@code percent}, {@code dropdown}, {@code colorpicker}, {@code datepicker}.</li>
 * </ul>
 * <p>
 * Every element has an x and y, a size, an {@link UiAnchor anchor}, an opacity, a tooltip and the styling every
 * element has: a background that is a color, a gradient (scrolling or not) or a rainbow, a border, a corner radius, a
 * background for when the mouse is over it where that means something, and an {@code effect} behind it, one of the
 * {@link me.piitex.engine.ui.background.BackgroundKinds}. A container can also be a {@code panel}, which follows the
 * theme. Each kind adds its own, such as a button's text. An element also has
 * the properties the container it is in asks of it: a {@code tabTitle} in a tab container, a {@code dock} in a dock
 * layout.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
public final class UiKinds {
    private static final Map<String, UiKind> byId = new LinkedHashMap<>();
    private static final Map<Class<?>, UiKind> byClass = new LinkedHashMap<>();

    private static final String CONTAINERS = "Containers", LAYOUTS = "Layouts", DISPLAY = "Display", INPUT = "Input";
    private static final String LAYOUT = "Layout", STYLE = "Style", TEXT = "Text", VALUE = "Value", LOOK = "Look";

    static {
        containers();
        layouts();
        display();
        input();
    }

    private UiKinds() {
    }

    /** Every kind, containers first, in the order an editor lists them. */
    public static List<UiKind> all() {
        synchronized (byId) {
            return List.copyOf(byId.values());
        }
    }

    /** The kind saved as {@code id}, or null. */
    public static UiKind get(String id) {
        synchronized (byId) {
            return byId.get(id);
        }
    }

    /** The kind of {@code element}: the one for its class, or failing that for the nearest superclass that has one. */
    public static UiKind of(Element element) {
        synchronized (byId) {
            for (Class<?> c = element.getClass(); c != null; c = c.getSuperclass()) {
                UiKind kind = byClass.get(c);
                if (kind != null) return kind;
            }
        }
        return null;
    }

    /**
     * Adds a kind of your own, or replaces one with the same id, so files can hold it and an editor can offer it. Its
     * properties are what {@link #common} and {@link #property} build.
     */
    public static void register(UiKind kind) {
        for (UiProperty property : kind.getProperties()) property.defaultTip(UiTips.of(kind.getId(), property.getName()));
        synchronized (byId) {
            byId.put(kind.getId(), kind);
            byClass.put(kind.getType(), kind);
        }
    }

    // --- What a container holds -----------------------------------------------------------------------------------

    /**
     * The elements {@code container} holds as a layout, in order. A tab container's bar and a split container's divider
     * are parts of it, not elements, so they aren't here.
     */
    public static List<Element> children(Container container) {
        return holder(container).of(container);
    }

    /**
     * Puts {@code child} in {@code parent} as its {@code index}th element, the last if that is past the end or negative,
     * and keeps its anchor and radio group right.
     *
     * @throws IllegalStateException if the container has no room, which {@link #full} says
     */
    public static void add(Container parent, Element child, int index) {
        String full = full(parent);
        if (full != null) throw new IllegalStateException(full);
        holder(parent).add(parent, child, index);
        child.applyAnchor();
        linkGroups(top(parent));
    }

    /** Takes {@code child} out of {@code parent}, and out of any radio group it shared with what stays. */
    public static void remove(Container parent, Element child) {
        holder(parent).remove(parent, child);
        Element top = top(parent);
        unlinkGroups(child);
        linkGroups(top);
    }

    /** Why {@code container} can't take another element, such as a split with both panes, or null if it can. */
    public static String full(Container container) {
        return holder(container).full(container);
    }

    /** Brings {@code element} into view, by choosing its tab and those of what holds it. */
    public static void reveal(Element element) {
        for (Element e = element; e != null && e.owner != null; e = e.owner) holder(e.owner).show(e.owner, e);
    }

    /** Whether the container {@code element} is in decides its width or height, so setting it does nothing lasting. */
    public static boolean sizedByParent(Element element, boolean horizontal) {
        Container owner = element.owner;
        return owner != null && holder(owner).sizes(owner, element, horizontal);
    }

    private static UiChildren holder(Container container) {
        UiKind kind = of(container);
        return kind == null ? UiChildren.PLAIN : kind.getChildren();
    }

    private static Element top(Element element) {
        Element e = element;
        while (e.owner != null) e = e.owner;
        return e;
    }

    // --- Radio groups ---------------------------------------------------------------------------------------------

    /**
     * Puts the radio buttons in {@code root}, and what it holds, into groups by their {@code group} name: those with the
     * same name choose one between them, those with none choose alone. A group a button is already in is kept, with
     * what listens to it, as far as it matches. Loading a layout does this, and so does adding, moving and renaming.
     */
    public static void linkGroups(Element root) {
        Map<String, List<RadioButtonOverlay>> named = new LinkedHashMap<>();
        for (RadioButtonOverlay radio : radios(root, new ArrayList<>())) {
            String name = groupName(radio);
            if (name.isEmpty()) {
                if (radio.getGroup() != null) radio.getGroup().remove(radio);
            } else {
                named.computeIfAbsent(name, n -> new ArrayList<>()).add(radio);
            }
        }
        for (List<RadioButtonOverlay> members : named.values()) {
            RadioGroup group = null;
            for (RadioButtonOverlay m : members) {
                if (m.getGroup() != null) {
                    group = m.getGroup();
                    break;
                }
            }
            if (group == null) group = new RadioGroup();
            for (RadioButtonOverlay m : members) group.add(m);
            Set<RadioButtonOverlay> keep = new HashSet<>(members);
            for (RadioButtonOverlay b : group.getButtons()) if (!keep.contains(b)) group.remove(b);
        }
    }

    private static void unlinkGroups(Element removed) {
        for (RadioButtonOverlay radio : radios(removed, new ArrayList<>())) {
            if (radio.getGroup() != null) radio.getGroup().remove(radio);
        }
    }

    private static List<RadioButtonOverlay> radios(Element element, List<RadioButtonOverlay> into) {
        if (element instanceof RadioButtonOverlay radio) into.add(radio);
        UiKind kind = of(element);
        if (element instanceof Container container && kind != null && kind.isContainer()) {
            for (Element child : children(container)) radios(child, into);
        }
        return into;
    }

    private static String groupName(RadioButtonOverlay radio) {
        return stash(radio, "group") instanceof String s ? s : "";
    }

    // --- Definitions ----------------------------------------------------------------------------------------------

    private static void containers() {
        add("container", "Container", CONTAINERS, Container.class, () -> new Container(200, 150), true,
                property(Container.class, LAYOUT, "clipping", "Clip Children", Boolean.class, Container::isClipping, Container::setClipping));
        add("scroll", "Scroll Container", CONTAINERS, ScrollContainer.class, () -> new ScrollContainer(240, 200), true,
                property(ScrollContainer.class, LAYOUT, "verticalScroll", "Scroll Vertically", Boolean.class, ScrollContainer::isVerticalScrollEnabled, ScrollContainer::setVerticalScrollEnabled),
                property(ScrollContainer.class, LAYOUT, "horizontalScroll", "Scroll Horizontally", Boolean.class, ScrollContainer::isHorizontalScrollEnabled, ScrollContainer::setHorizontalScrollEnabled),
                property(ScrollContainer.class, LAYOUT, "scrollbar", "Show Scrollbar", Boolean.class, ScrollContainer::isScrollbarVisible, ScrollContainer::setScrollbarVisible),
                property(ScrollContainer.class, LAYOUT, "scrollSpeed", "Scroll Speed", Float.class, ScrollContainer::getScrollSpeed, ScrollContainer::setScrollSpeed).range(0, 1000));

        add("resizable", "Resizable Container", CONTAINERS, ResizableContainer.class, () -> new ResizableContainer(240, 160), true,
                property(ResizableContainer.class, LAYOUT, "userResizable", "User Can Resize", Boolean.class, ResizableContainer::isUserResizable, ResizableContainer::setUserResizable)
                        .tip("Lets the player drag its corners to change its size"),
                property(ResizableContainer.class, LAYOUT, "showHandles", "Show Handles", Boolean.class, ResizableContainer::isShowResizeHandles, ResizableContainer::setShowResizeHandles),
                property(ResizableContainer.class, LAYOUT, "scaleChildren", "Scale Contents", Boolean.class, ResizableContainer::isScaleChildren, ResizableContainer::setScaleChildren)
                        .tip("Whether what is inside grows and shrinks with it"),
                property(ResizableContainer.class, LAYOUT, "scaleFonts", "Scale Text", Boolean.class, ResizableContainer::isScaleFonts, ResizableContainer::setScaleFonts),
                property(ResizableContainer.class, LAYOUT, "minWidth", "Minimum Width", Double.class, ResizableContainer::getMinWidth,
                        (c, v) -> c.setMinSize(v, c.getMinHeight())).range(0, 100000),
                property(ResizableContainer.class, LAYOUT, "minHeight", "Minimum Height", Double.class, ResizableContainer::getMinHeight,
                        (c, v) -> c.setMinSize(c.getMinWidth(), v)).range(0, 100000),
                property(ResizableContainer.class, LAYOUT, "maxWidth", "Maximum Width", Double.class, c -> finite(c.getMaxWidth()),
                        (c, v) -> c.setMaxSize(v <= 0 ? Double.POSITIVE_INFINITY : v, c.getMaxHeight())).range(0, 100000).tip("0 for no limit").omitWhen(0.0),
                property(ResizableContainer.class, LAYOUT, "maxHeight", "Maximum Height", Double.class, c -> finite(c.getMaxHeight()),
                        (c, v) -> c.setMaxSize(c.getMaxWidth(), v <= 0 ? Double.POSITIVE_INFINITY : v)).range(0, 100000).tip("0 for no limit").omitWhen(0.0));

        register(kind("tabs", "Tab Container", CONTAINERS, TabContainer.class, () -> new TabContainer(320, 220), true,
                property(TabContainer.class, LAYOUT, "tabPosition", "Tab Bar", TabContainer.TabPosition.class, TabContainer::getTabPosition, TabContainer::setTabPosition),
                padding(TabContainer.class)).holding(UiChildren.TABS).startingWith(tabs -> {
            TabContainer container = (TabContainer) tabs;
            container.addTab("Tab 1", page());
            container.addTab("Tab 2", page());
        }));

        register(kind("hsplit", "Split Left and Right", CONTAINERS, HorizontalSplit.class, () -> new HorizontalSplit(320, 200), true,
                split()).holding(UiChildren.SPLIT).startingWith(UiKinds::twoPanes));
        register(kind("vsplit", "Split Top and Bottom", CONTAINERS, VerticalSplit.class, () -> new VerticalSplit(240, 240), true,
                split()).holding(UiChildren.SPLIT).startingWith(UiKinds::twoPanes));
    }

    private static void layouts() {
        add("vertical", "Vertical Layout", LAYOUTS, VerticalLayout.class, () -> spaced(new VerticalLayout(200, 200)), true,
                layout(VerticalLayout.class, true, true));
        add("horizontal", "Horizontal Layout", LAYOUTS, HorizontalLayout.class, () -> spaced(new HorizontalLayout(240, 60)), true,
                layout(HorizontalLayout.class, true, true));
        add("stack", "Stack Layout", LAYOUTS, StackLayout.class, () -> new StackLayout(200, 200), true,
                layout(StackLayout.class, false, true));
        List<UiProperty> grid = new ArrayList<>(layout(GridLayout.class, false, true));
        grid.add(property(GridLayout.class, LAYOUT, "columns", "Columns", Integer.class, GridLayout::getColumns, GridLayout::setColumns).range(1, 100));
        grid.add(property(GridLayout.class, LAYOUT, "columnSpacing", "Column Spacing", Float.class, GridLayout::getColumnSpacing, GridLayout::setColumnSpacing).range(0, 1000));
        grid.add(property(GridLayout.class, LAYOUT, "rowSpacing", "Row Spacing", Float.class, GridLayout::getRowSpacing, GridLayout::setRowSpacing).range(0, 1000));
        add("grid", "Grid Layout", LAYOUTS, GridLayout.class, () -> {
            GridLayout g = new GridLayout(2, 240, 160);
            g.setSpacing(8);
            return g;
        }, true, grid);

        List<UiProperty> flow = new ArrayList<>(layout(FlowLayout.class, true, true));
        flow.add(property(FlowLayout.class, LAYOUT, "lineSpacing", "Row Spacing", Float.class, FlowLayout::getLineSpacing, FlowLayout::setLineSpacing).range(0, 1000));
        add("flow", "Flow Layout", LAYOUTS, FlowLayout.class, () -> {
            FlowLayout f = new FlowLayout(260, 120);
            f.setSpacing(8);
            f.setLineSpacing(8);
            return f;
        }, true, flow);

        register(kind("dock", "Dock Layout", LAYOUTS, DockLayout.class, () -> spaced(new DockLayout(300, 220)), true,
                layout(DockLayout.class, true, false)).holding(UiChildren.DOCK));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void display() {
        List<UiProperty> label = new ArrayList<>(text(TextOverlay.class));
        add("text", "Text", DISPLAY, TextOverlay.class, () -> new TextOverlay("Text", 16f, Color.BLACK), false, label);

        List<UiProperty> flow = new ArrayList<>(text(TextFlowOverlay.class));
        flow.add(property(TextFlowOverlay.class, TEXT, "align", "Align", TextAlign.class, TextFlowOverlay::getTextAlign, TextFlowOverlay::setTextAlign));
        flow.add(property(TextFlowOverlay.class, TEXT, "lineSpacing", "Line Spacing", Float.class, TextFlowOverlay::getLineSpacing, TextFlowOverlay::setLineSpacing).range(0, 200));
        flow.add(property(TextFlowOverlay.class, TEXT, "padding", "Padding", Float.class, TextFlowOverlay::getPadding, TextFlowOverlay::setPadding).range(0, 1000));
        flow.add(property(TextFlowOverlay.class, TEXT, "maxLines", "Max Lines", Integer.class, TextFlowOverlay::getMaxLines, TextFlowOverlay::setMaxLines).range(0, 100000)
                .tip("0 for as many as the text needs"));
        flow.add(property(TextFlowOverlay.class, TEXT, "autoHeight", "Height Fits Text", Boolean.class, TextFlowOverlay::isAutoHeight, TextFlowOverlay::setAutoHeight));
        add("textflow", "Wrapped Text", DISPLAY, TextFlowOverlay.class,
                () -> new TextFlowOverlay("Text that wraps onto more lines when it is wider than the element.", 240, 16f, Color.BLACK), true, flow);

        add("image", "Image", DISPLAY, ImageOverlay.class, () -> {
            ImageOverlay image = ImageOverlay.of((org.jetbrains.skia.Image) null, 96, 96);
            return image;
        }, true,
                new UiProperty("image", "Image", String.class, e -> stash(e, "image"), (e, v, base) -> {
                    UiMeta.of(e).stash.put("image", v);
                    if (v instanceof String path && !path.isBlank()) {
                        try {
                            ((ImageOverlay) e).setImageFile(base.resolve(path).toFile());
                        } catch (RuntimeException missing) {
                            // The path is kept and written back; the picture just can't be shown.
                        }
                    }
                }).group(LOOK).hint(UiProperty.Hint.IMAGE),
                property(ImageOverlay.class, LOOK, "tint", "Tint", Color.class, ImageOverlay::getTint, ImageOverlay::setTint, true)
                        .themed().onReset(e -> ((ImageOverlay) e).setTint(null)));

        add("icon", "Icon", DISPLAY, IconOverlay.class, () -> {
            IconOverlay icon = new IconOverlay(UiIcons.get(UiIcons.DEFAULT), 24, 24);
            UiMeta.of(icon).stash.put("icon", UiIcons.DEFAULT);
            return icon;
        }, true,
                new UiProperty("icon", "Icon", String.class, e -> stash(e, "icon"), (e, v, base) -> {
                    IconAsset asset = UiIcons.get((String) v);
                    if (asset == null) return;
                    UiMeta.of(e).stash.put("icon", v);
                    ((IconOverlay) e).setIcon(asset);
                }).group(LOOK).hint(UiProperty.Hint.ICON),
                property(IconOverlay.class, LOOK, "tint", "Tint", Color.class, IconOverlay::getTint, IconOverlay::setTint, true)
                        .themed().onReset(e -> ((IconOverlay) e).setTint(null)));

        add("progress", "Progress Bar", DISPLAY, ProgressBarOverlay.class, () -> {
            ProgressBarOverlay bar = new ProgressBarOverlay(200, 12);
            bar.setProgress(0.5);
            return bar;
        }, true,
                property(ProgressBarOverlay.class, VALUE, "progress", "Progress", Double.class, ProgressBarOverlay::getProgress, ProgressBarOverlay::setProgress).range(0, 1),
                property(ProgressBarOverlay.class, VALUE, "indeterminate", "Indeterminate", Boolean.class, ProgressBarOverlay::isIndeterminate, ProgressBarOverlay::setIndeterminate),
                color(ProgressBarOverlay.class, LOOK, "fillColor", "Fill Color", ProgressBarOverlay::getFillColor, ProgressBarOverlay::setFillColor));

        add("spinner", "Loading Spinner", DISPLAY, LoadingSpinnerOverlay.class, () -> new LoadingSpinnerOverlay(32), true,
                property(LoadingSpinnerOverlay.class, VALUE, "spinning", "Spinning", Boolean.class, LoadingSpinnerOverlay::isSpinning, LoadingSpinnerOverlay::setSpinning),
                color(LoadingSpinnerOverlay.class, LOOK, "color", "Color", LoadingSpinnerOverlay::getColor, LoadingSpinnerOverlay::setColor));

        add("separator", "Separator", DISPLAY, SeparatorOverlay.class, () -> new SeparatorOverlay(200), true, separator());
        // A separator's direction is fixed when it is made, so a vertical one is a kind of its own.
        add("vseparator", "Vertical Separator", DISPLAY, VerticalSeparator.class, () -> new VerticalSeparator(40), true, separator());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void input() {
        List<UiProperty> button = new ArrayList<>(text(ButtonOverlay.class));
        button.add(textHover());
        button.add(textPosition());
        add("button", "Button", INPUT, ButtonOverlay.class, () -> new ButtonOverlay("Button", 120, 36), true, button);

        add("textfield", "Text Field", INPUT, TextFieldOverlay.class, () -> new TextFieldOverlay(200, 32), true,
                property(TextFieldOverlay.class, TEXT, "text", "Text", String.class, TextFieldOverlay::getText, TextFieldOverlay::setText),
                property(TextFieldOverlay.class, TEXT, "placeholder", "Placeholder", String.class, TextFieldOverlay::getPlaceholder, TextFieldOverlay::setPlaceholder),
                property(TextFieldOverlay.class, TEXT, "fontSize", "Font Size", Float.class, TextFieldOverlay::getFontSize, TextFieldOverlay::setFontSize).range(4, 400),
                color(TextFieldOverlay.class, TEXT, "textColor", "Text Color", TextFieldOverlay::getTextColor, TextFieldOverlay::setTextColor),
                color(TextFieldOverlay.class, TEXT, "placeholderColor", "Placeholder Color", TextFieldOverlay::getPlaceholderColor, TextFieldOverlay::setPlaceholderColor),
                property(TextFieldOverlay.class, TEXT, "prefix", "Prefix", String.class, TextFieldOverlay::getPrefix, TextFieldOverlay::setPrefix)
                        .tip("Text shown before what is typed, such as a currency sign"),
                property(TextFieldOverlay.class, TEXT, "rightAligned", "Align Right", Boolean.class, TextFieldOverlay::isRightAligned, TextFieldOverlay::setRightAligned),
                property(TextFieldOverlay.class, TEXT, "padding", "Padding", Float.class, TextFieldOverlay::getPadding, TextFieldOverlay::setPadding).range(0, 200),
                color(TextFieldOverlay.class, LOOK, "caretColor", "Caret Color", TextFieldOverlay::getCaretColor, TextFieldOverlay::setCaretColor),
                color(TextFieldOverlay.class, LOOK, "selectionColor", "Selection Color", TextFieldOverlay::getSelectionColor, TextFieldOverlay::setSelectionColor),
                property(TextFieldOverlay.class, VALUE, "password", "Password", Boolean.class, TextFieldOverlay::isPassword, TextFieldOverlay::setPassword),
                property(TextFieldOverlay.class, VALUE, "readOnly", "Read Only", Boolean.class, TextFieldOverlay::isReadOnly, TextFieldOverlay::setReadOnly),
                property(TextFieldOverlay.class, VALUE, "maxLength", "Max Length", Integer.class, TextFieldOverlay::getMaxLength, TextFieldOverlay::setMaxLength).range(-1, 100000)
                        .tip("-1 for no limit"));

        add("textarea", "Text Area", INPUT, TextAreaOverlay.class, () -> new TextAreaOverlay(240, 120), true,
                property(TextAreaOverlay.class, TEXT, "text", "Text", String.class, TextAreaOverlay::getText, TextAreaOverlay::setText),
                property(TextAreaOverlay.class, TEXT, "placeholder", "Placeholder", String.class, TextAreaOverlay::getPlaceholder, TextAreaOverlay::setPlaceholder),
                property(TextAreaOverlay.class, TEXT, "fontSize", "Font Size", Float.class, TextAreaOverlay::getFontSize, TextAreaOverlay::setFontSize).range(4, 400),
                color(TextAreaOverlay.class, TEXT, "textColor", "Text Color", TextAreaOverlay::getTextColor, TextAreaOverlay::setTextColor),
                property(TextAreaOverlay.class, TEXT, "lineSpacing", "Line Spacing", Float.class, TextAreaOverlay::getLineSpacing, TextAreaOverlay::setLineSpacing).range(0, 200),
                property(TextAreaOverlay.class, VALUE, "wordWrap", "Wrap Lines", Boolean.class, TextAreaOverlay::isWordWrap, TextAreaOverlay::setWordWrap),
                property(TextAreaOverlay.class, VALUE, "readOnly", "Read Only", Boolean.class, TextAreaOverlay::isReadOnly, TextAreaOverlay::setReadOnly),
                property(TextAreaOverlay.class, VALUE, "maxLength", "Max Length", Integer.class, TextAreaOverlay::getMaxLength, TextAreaOverlay::setMaxLength).range(-1, 1000000)
                        .tip("-1 for no limit"),
                property(TextAreaOverlay.class, LOOK, "scrollbar", "Show Scrollbar", Boolean.class, TextAreaOverlay::isScrollbarVisible, TextAreaOverlay::setScrollbarVisible),
                color(TextAreaOverlay.class, TEXT, "placeholderColor", "Placeholder Color", TextAreaOverlay::getPlaceholderColor, TextAreaOverlay::setPlaceholderColor),
                color(TextAreaOverlay.class, LOOK, "caretColor", "Caret Color", TextAreaOverlay::getCaretColor, TextAreaOverlay::setCaretColor),
                color(TextAreaOverlay.class, LOOK, "selectionColor", "Selection Color", TextAreaOverlay::getSelectionColor, TextAreaOverlay::setSelectionColor));

        // A number field is a layout of a text field and two buttons, but what is in it isn't the layout's to edit.
        register(kind("number", "Number Field", INPUT, SpinnerOverlay.class, () -> new SpinnerOverlay(120, 32, 0, 100, 0), true,
                property(SpinnerOverlay.class, VALUE, "min", "Minimum", Double.class, SpinnerOverlay::getMin, SpinnerOverlay::setMin),
                property(SpinnerOverlay.class, VALUE, "max", "Maximum", Double.class, SpinnerOverlay::getMax, SpinnerOverlay::setMax),
                property(SpinnerOverlay.class, VALUE, "value", "Value", Double.class, SpinnerOverlay::getValue, SpinnerOverlay::setValue),
                property(SpinnerOverlay.class, VALUE, "step", "Step", Double.class, SpinnerOverlay::getStep, SpinnerOverlay::setStep).range(0, 1000000),
                property(SpinnerOverlay.class, VALUE, "decimals", "Decimals", Integer.class, SpinnerOverlay::getDecimals, SpinnerOverlay::setDecimals).range(0, 10),
                property(SpinnerOverlay.class, TEXT, "placeholder", "Placeholder", String.class, SpinnerOverlay::getPlaceholder, SpinnerOverlay::setPlaceholder))
                .holding(UiChildren.NONE));

        add("checkbox", "Check Box", INPUT, CheckBoxOverlay.class, () -> new CheckBoxOverlay(20), true,
                property(CheckBoxOverlay.class, VALUE, "checked", "Checked", Boolean.class, CheckBoxOverlay::isChecked, CheckBoxOverlay::setChecked),
                color(CheckBoxOverlay.class, LOOK, "checkColor", "Check Color", CheckBoxOverlay::getCheckColor, CheckBoxOverlay::setCheckColor));

        add("radio", "Radio Button", INPUT, RadioButtonOverlay.class, () -> new RadioButtonOverlay(20), true,
                new UiProperty("group", "Group", String.class, e -> groupName((RadioButtonOverlay) e), (e, v, base) -> {
                    UiMeta.of(e).stash.put("group", v == null ? "" : ((String) v).strip());
                    if (e.owner != null) linkGroups(top(e));
                }).group(VALUE).omitWhen("").tip("Radio buttons with the same group name choose one between them"),
                property(RadioButtonOverlay.class, VALUE, "selected", "Selected", Boolean.class, RadioButtonOverlay::isSelected, RadioButtonOverlay::setSelected),
                color(RadioButtonOverlay.class, LOOK, "dotColor", "Dot Color", RadioButtonOverlay::getDotColor, RadioButtonOverlay::setDotColor));

        add("toggle", "Toggle Switch", INPUT, ToggleSwitchOverlay.class, () -> new ToggleSwitchOverlay(44, 24), true,
                property(ToggleSwitchOverlay.class, VALUE, "on", "On", Boolean.class, ToggleSwitchOverlay::isOn, ToggleSwitchOverlay::setOn),
                color(ToggleSwitchOverlay.class, LOOK, "onColor", "On Color", ToggleSwitchOverlay::getOnColor, ToggleSwitchOverlay::setOnColor),
                color(ToggleSwitchOverlay.class, LOOK, "offColor", "Off Color", ToggleSwitchOverlay::getOffColor, ToggleSwitchOverlay::setOffColor),
                color(ToggleSwitchOverlay.class, LOOK, "knobColor", "Knob Color", ToggleSwitchOverlay::getKnobColor, ToggleSwitchOverlay::setKnobColor));

        add("slider", "Slider", INPUT, SliderOverlay.class, () -> new SliderOverlay(200, 0, 100, 50), true,
                property(SliderOverlay.class, VALUE, "min", "Minimum", Double.class, SliderOverlay::getMin, SliderOverlay::setMin),
                property(SliderOverlay.class, VALUE, "max", "Maximum", Double.class, SliderOverlay::getMax, SliderOverlay::setMax),
                property(SliderOverlay.class, VALUE, "value", "Value", Double.class, SliderOverlay::getValue, SliderOverlay::setValue),
                property(SliderOverlay.class, VALUE, "step", "Step", Double.class, SliderOverlay::getStep, SliderOverlay::setStep).range(0, 1000000),
                color(SliderOverlay.class, LOOK, "trackColor", "Track Color", SliderOverlay::getTrackColor, SliderOverlay::setTrackColor),
                color(SliderOverlay.class, LOOK, "fillColor", "Fill Color", SliderOverlay::getFillColor, SliderOverlay::setFillColor),
                color(SliderOverlay.class, LOOK, "thumbColor", "Thumb Color", SliderOverlay::getThumbColor, SliderOverlay::setThumbColor));

        add("percent", "Percent Slider", INPUT, PercentSliderOverlay.class, () -> new PercentSliderOverlay(200, 50), true,
                property(SliderOverlay.class, VALUE, "value", "Percent", Double.class, SliderOverlay::getValue, SliderOverlay::setValue).range(0, 100),
                color(SliderOverlay.class, LOOK, "trackColor", "Track Color", SliderOverlay::getTrackColor, SliderOverlay::setTrackColor),
                color(SliderOverlay.class, LOOK, "fillColor", "Fill Color", SliderOverlay::getFillColor, SliderOverlay::setFillColor),
                color(SliderOverlay.class, LOOK, "thumbColor", "Thumb Color", SliderOverlay::getThumbColor, SliderOverlay::setThumbColor));

        add("dropdown", "Dropdown", INPUT, DropdownOverlay.class, () -> {
            DropdownOverlay<String> dropdown = new DropdownOverlay<>(200, 32);
            dropdown.setItems(List.of("Option 1", "Option 2", "Option 3"));
            UiMeta.of(dropdown).stash.put("placeholder", "Select...");
            return dropdown;
        }, true,
                new UiProperty("items", "Items", List.class, e -> new ArrayList<>(((DropdownOverlay<String>) e).getItems()), (e, v, base) -> {
                    DropdownOverlay<String> d = (DropdownOverlay<String>) e;
                    int selected = d.getSelectedIndex();
                    d.setItems((List<String>) v);
                    d.setSelectedIndex(selected < d.getItems().size() ? selected : -1);
                }).group(VALUE),
                property(DropdownOverlay.class, VALUE, "selected", "Selected Item", Integer.class, DropdownOverlay::getSelectedIndex, DropdownOverlay::setSelectedIndex)
                        .range(-1, 100000).tip("The number of the chosen item, from 0, or -1 for none"),
                new UiProperty("placeholder", "Placeholder", String.class, e -> stash(e, "placeholder"), (e, v, base) -> {
                    UiMeta.of(e).stash.put("placeholder", v);
                    ((DropdownOverlay<String>) e).setPlaceholder((String) v);
                }).group(TEXT),
                property(DropdownOverlay.class, TEXT, "fontSize", "Font Size", Float.class, DropdownOverlay::getFontSize, DropdownOverlay::setFontSize).range(4, 400),
                color(DropdownOverlay.class, TEXT, "textColor", "Text Color", DropdownOverlay::getTextColor, DropdownOverlay::setTextColor),
                textPosition());

        add("colorpicker", "Color Picker", INPUT, ColorPickerOverlay.class, () -> new ColorPickerOverlay(200, 32, new Color(0.2f, 0.5f, 0.9f, 1f)), true,
                property(ColorPickerOverlay.class, VALUE, "color", "Color", Color.class, ColorPickerOverlay::getColor, ColorPickerOverlay::setColor),
                property(ColorPickerOverlay.class, VALUE, "alpha", "Allow Transparency", Boolean.class, ColorPickerOverlay::isAlphaEnabled, ColorPickerOverlay::setAlphaEnabled),
                property(ColorPickerOverlay.class, TEXT, "fontSize", "Font Size", Float.class, ColorPickerOverlay::getFontSize, ColorPickerOverlay::setFontSize).range(4, 400),
                color(ColorPickerOverlay.class, TEXT, "textColor", "Text Color", ColorPickerOverlay::getTextColor, ColorPickerOverlay::setTextColor));

        add("datepicker", "Date and Time Picker", INPUT, DateTimePickerOverlay.class, () -> new DateTimePickerOverlay(200, 32), true,
                property(DateTimePickerOverlay.class, VALUE, "mode", "Picks", DateTimePickerOverlay.Mode.class, DateTimePickerOverlay::getMode, DateTimePickerOverlay::setMode),
                property(DateTimePickerOverlay.class, VALUE, "date", "Date", String.class, p -> p.getDate() == null ? null : p.getDate().toString(),
                        (p, v) -> p.setDate(v.isBlank() ? null : parse(v, LocalDate::parse, "a date like 2026-10-03")))
                        .tip("Year-month-day, such as 2026-10-03; empty for none")
                        .when(e -> ((DateTimePickerOverlay) e).getMode() != DateTimePickerOverlay.Mode.TIME),
                property(DateTimePickerOverlay.class, VALUE, "time", "Time", String.class, p -> p.getTime() == null ? null : p.getTime().toString(),
                        (p, v) -> p.setTime(v.isBlank() ? null : parse(v, LocalTime::parse, "a time like 14:30")))
                        .tip("Hour:minute, such as 14:30; empty for none")
                        .when(e -> ((DateTimePickerOverlay) e).getMode() != DateTimePickerOverlay.Mode.DATE),
                property(DateTimePickerOverlay.class, VALUE, "use24Hour", "24-Hour Clock", Boolean.class, DateTimePickerOverlay::isUse24Hour, DateTimePickerOverlay::setUse24Hour)
                        .when(e -> ((DateTimePickerOverlay) e).getMode() != DateTimePickerOverlay.Mode.DATE),
                property(DateTimePickerOverlay.class, VALUE, "minuteStep", "Minute Step", Integer.class, DateTimePickerOverlay::getMinuteStep, DateTimePickerOverlay::setMinuteStep).range(1, 60)
                        .when(e -> ((DateTimePickerOverlay) e).getMode() != DateTimePickerOverlay.Mode.DATE),
                new UiProperty("placeholder", "Placeholder", String.class, e -> stash(e, "placeholder"), (e, v, base) -> {
                    UiMeta.of(e).stash.put("placeholder", v);
                    ((DateTimePickerOverlay) e).setPlaceholder((String) v);
                }).group(TEXT),
                property(DateTimePickerOverlay.class, TEXT, "fontSize", "Font Size", Float.class, DateTimePickerOverlay::getFontSize, DateTimePickerOverlay::setFontSize).range(4, 400),
                color(DateTimePickerOverlay.class, TEXT, "textColor", "Text Color", DateTimePickerOverlay::getTextColor, DateTimePickerOverlay::setTextColor),
                textPosition());
    }

    /** Split containers, as classes of their own so a side-by-side one is told from a stacked one. */
    private static final class HorizontalSplit extends SplitContainer {
        HorizontalSplit(double width, double height) {
            super(Orientation.HORIZONTAL, width, height);
        }
    }

    private static final class VerticalSplit extends SplitContainer {
        VerticalSplit(double width, double height) {
            super(Orientation.VERTICAL, width, height);
        }
    }

    private static List<UiProperty> split() {
        return List.of(
                property(SplitContainer.class, LAYOUT, "dividerPosition", "Divider Position", Double.class, SplitContainer::getDividerPosition, SplitContainer::setDividerPosition)
                        .range(0, 1).tip("How much of the space the first pane has, from 0 to 1"),
                property(SplitContainer.class, LAYOUT, "dividerThickness", "Divider Thickness", Float.class, SplitContainer::getDividerThickness, SplitContainer::setDividerThickness).range(0, 100),
                property(SplitContainer.class, LAYOUT, "minPaneSize", "Smallest Pane", Float.class, SplitContainer::getMinPaneSize, SplitContainer::setMinPaneSize).range(0, 100000),
                property(SplitContainer.class, LAYOUT, "maxPaneSize", "Largest First Pane", Float.class, SplitContainer::getMaxPaneSize, SplitContainer::setMaxPaneSize).range(-1, 100000)
                        .tip("-1 for no limit"),
                padding(SplitContainer.class));
    }

    private static void twoPanes(Container split) {
        SplitContainer container = (SplitContainer) split;
        container.setFirst(page());
        container.setSecond(page());
    }

    /** An empty container for a tab or a pane to start with. It has no name, so nothing in a layout shares it. */
    private static Container page() {
        return (Container) get("container").create("");
    }

    private static <T> T parse(String text, Function<String, T> parser, String expected) {
        try {
            return parser.apply(text);
        } catch (java.time.DateTimeException e) {
            throw new IllegalArgumentException("expected " + expected + ", found \"" + text + "\"", e);
        }
    }

    private static double finite(double value) {
        return Double.isInfinite(value) ? 0 : value;
    }

    private static <L extends Layout> L spaced(L layout) {
        layout.setSpacing(8);
        return layout;
    }

    private static <L extends Layout> UiProperty padding(Class<L> type) {
        return property(type, LAYOUT, "padding", "Padding", Float.class, Layout::getPadding, Layout::setPadding).range(0, 1000);
    }

    private static <L extends Layout> List<UiProperty> layout(Class<L> type, boolean spacing, boolean alignment) {
        List<UiProperty> list = new ArrayList<>();
        if (spacing) list.add(property(type, LAYOUT, "spacing", "Spacing", Float.class, Layout::getSpacing, Layout::setSpacing).range(0, 1000));
        list.add(padding(type));
        if (alignment) list.add(property(type, LAYOUT, "alignment", "Alignment", Layout.Alignment.class, Layout::getAlignment, Layout::setAlignment));
        list.add(property(type, LAYOUT, "fitWidth", "Fit Width to Content", Boolean.class, Layout::isSizeToContentWidth,
                (l, v) -> l.setSizeToContent(v, l.isSizeToContentHeight())));
        list.add(property(type, LAYOUT, "fitHeight", "Fit Height to Content", Boolean.class, Layout::isSizeToContentHeight,
                (l, v) -> l.setSizeToContent(l.isSizeToContentWidth(), v)));
        return list;
    }

    /** A vertical {@link SeparatorOverlay}, as a class of its own so it is told from a horizontal one. */
    private static final class VerticalSeparator extends SeparatorOverlay {
        VerticalSeparator(double length) {
            super(length, Orientation.VERTICAL);
        }
    }

    private static List<UiProperty> separator() {
        return List.of(
                property(SeparatorOverlay.class, LOOK, "thickness", "Thickness", Float.class, SeparatorOverlay::getThickness, SeparatorOverlay::setThickness).range(0.5, 100),
                color(SeparatorOverlay.class, LOOK, "lineColor", "Line Color", SeparatorOverlay::getLineColor, SeparatorOverlay::setLineColor));
    }

    /** Where an element's text sits inside its box. Not set, it sits where that kind of element puts it. */
    private static UiProperty textPosition() {
        return property(Element.class, TEXT, "textAlign", "Text Position", Layout.Alignment.class, Element::getTextAlignment, Element::setTextAlignment, true)
                .nullable().tip("Where the text sits inside the element, when it is bigger than the text");
    }

    /** The color of a button's text while the mouse is over it, which it keeps its own color without. */
    private static UiProperty textHover() {
        return new UiProperty("textHover", "Text On Hover", Paint.class, e -> e.getStyling().getTextHoverColor(),
                (e, v, base) -> e.getStyling().setTextHoverColor((Paint) v)).group(TEXT).nullable()
                .tip("The color of the text while the mouse is over the element; the same color as always when not set");
    }

    private static <T extends TextOverlay> List<UiProperty> text(Class<T> type) {
        return List.of(
                property(type, TEXT, "text", "Text", String.class, TextOverlay::getText, TextOverlay::setText),
                property(type, TEXT, "fontSize", "Font Size", Float.class, TextOverlay::getFontSize, TextOverlay::setFontSize).range(4, 400),
                property(type, TEXT, "bold", "Bold", Boolean.class, TextOverlay::isBold, TextOverlay::setBold),
                property(type, TEXT, "italic", "Italic", Boolean.class, TextOverlay::isItalic, TextOverlay::setItalic),
                color(type, TEXT, "textColor", "Text Color", TextOverlay::getTextColor, TextOverlay::setTextColor));
    }

    // --- Building properties --------------------------------------------------------------------------------------

    private static void add(String id, String label, String group, Class<? extends Element> type,
                            Supplier<? extends Element> maker, boolean sized, UiProperty... own) {
        register(kind(id, label, group, type, maker, sized, List.of(own)));
    }

    private static void add(String id, String label, String group, Class<? extends Element> type,
                            Supplier<? extends Element> maker, boolean sized, List<UiProperty> own) {
        register(kind(id, label, group, type, maker, sized, own));
    }

    private static UiKind kind(String id, String label, String group, Class<? extends Element> type,
                               Supplier<? extends Element> maker, boolean sized, UiProperty... own) {
        return kind(id, label, group, type, maker, sized, List.of(own));
    }

    private static UiKind kind(String id, String label, String group, Class<? extends Element> type,
                               Supplier<? extends Element> maker, boolean sized, List<UiProperty> own) {
        List<UiProperty> all = new ArrayList<>(common(sized));
        all.forEach(UiProperty::common);
        all.addAll(own);
        all.addAll(UiEvents.properties(type));
        return new UiKind(id, label, group, type, maker, all);
    }

    /**
     * The properties every element has. A text element works out its own size from its text, so it has none.
     */
    private static List<UiProperty> common(boolean sized) {
        List<UiProperty> list = new ArrayList<>();
        list.add(property(Element.class, LAYOUT, "x", "X", Double.class, Element::getX, Element::setX)
                .when(UiKinds::isFree).tip("Measured from the left of the container"));
        list.add(property(Element.class, LAYOUT, "y", "Y", Double.class, Element::getY, Element::setY)
                .when(UiKinds::isFree).tip("Measured from the top of the container"));
        if (sized) {
            list.add(property(Element.class, LAYOUT, "width", "Width", Double.class, Element::getWidth, Element::setWidth).range(0, 100000)
                    .when(e -> !sizedByParent(e, true)));
            list.add(property(Element.class, LAYOUT, "height", "Height", Double.class, Element::getHeight, Element::setHeight).range(0, 100000)
                    .when(e -> !sizedByParent(e, false)));
        }
        // What the container an element is in asks of it.
        list.add(new UiProperty("tabTitle", "Tab Title", String.class, e -> {
            if (e.owner instanceof TabContainer tabs) {
                for (TabContainer.Tab tab : tabs.getTabs()) if (tab.getContent() == e) return tab.getTitle();
            }
            return tabTitle(e);
        }, (e, v, base) -> {
            String title = v == null ? "" : (String) v;
            UiMeta.of(e).stash.put("tabTitle", title);
            if (e.owner instanceof TabContainer tabs) {
                for (TabContainer.Tab tab : tabs.getTabs()) if (tab.getContent() == e) tab.setTitle(tabTitle(e));
            }
        }).group(LAYOUT).when(e -> e.owner instanceof TabContainer).tip("The name on the tab"));
        list.add(new UiProperty("dock", "Dock", DockLayout.Dock.class, e -> e.owner instanceof DockLayout dock ? dock.getDock(e) : dockOf(e), (e, v, base) -> {
            DockLayout.Dock dock = v == null ? DockLayout.Dock.TOP : (DockLayout.Dock) v;
            UiMeta.of(e).stash.put("dock", dock);
            if (e.owner instanceof DockLayout layout) layout.setDock(e, dock);
        }).group(LAYOUT).when(e -> e.owner instanceof DockLayout).omitWhen(DockLayout.Dock.TOP)
                .tip("The edge of the space that is left that it sticks to; Fill takes all that is left"));
        list.add(new UiProperty("anchor", "Anchor", UiAnchor.class, e -> UiAnchor.of(e.getAnchor()), (e, v, base) -> {
            UiAnchor anchor = v == null ? UiAnchor.NONE : (UiAnchor) v;
            if (anchor == UiAnchor.NONE) {
                e.setX(e.getX()); // setting the position drops the anchor and keeps the element where it is
                return;
            }
            // Pinning to a new point keeps the element where it is, by working out the offset that does.
            double offsetX = 0, offsetY = 0;
            if (e.owner != null && !(e.owner instanceof Layout)) {
                Anchor p = anchor.point();
                offsetX = e.getX() - (e.owner.getWidth() * p.x() - e.getWidth() * p.x());
                offsetY = e.getY() - (e.owner.getHeight() * p.y() - e.getHeight() * p.y());
            }
            pin(e, anchor.point(), offsetX, offsetY);
        }).group(LAYOUT).when(UiKinds::inFreeContainer).omitWhen(UiAnchor.NONE)
                .tip("Keeps the element in the same place in its container when the container is resized"));
        list.add(new UiProperty("offsetX", "Offset X", Double.class, e -> offset(e, true), (e, v, base) -> {
            if (e.getAnchor() != null) pin(e, e.getAnchor(), (Double) v, offset(e, false));
        }).group(LAYOUT).when(e -> e.getAnchor() != null).tip("How far from the anchor, in points"));
        list.add(new UiProperty("offsetY", "Offset Y", Double.class, e -> offset(e, false), (e, v, base) -> {
            if (e.getAnchor() != null) pin(e, e.getAnchor(), offset(e, true), (Double) v);
        }).group(LAYOUT).when(e -> e.getAnchor() != null).tip("How far from the anchor, in points"));
        list.add(property(Element.class, LAYOUT, "relativeWidth", "Relative Width", Double.class,
                e -> Double.isNaN(e.getRelativeWidth()) ? 0.0 : e.getRelativeWidth(), (e, v) -> e.setRelativeWidth(v <= 0 ? -1 : v))
                .range(0, 10).omitWhen(0.0).when(e -> e.owner != null && !sizedByParent(e, true))
                .tip("Keeps the width this fraction of the container's; 0 leaves the width as set"));
        list.add(property(Element.class, LAYOUT, "relativeHeight", "Relative Height", Double.class,
                e -> Double.isNaN(e.getRelativeHeight()) ? 0.0 : e.getRelativeHeight(), (e, v) -> e.setRelativeHeight(v <= 0 ? -1 : v))
                .range(0, 10).omitWhen(0.0).when(e -> e.owner != null && !sizedByParent(e, false))
                .tip("Keeps the height this fraction of the container's; 0 leaves the height as set"));
        list.add(property(Element.class, LAYOUT, "relativeInset", "Relative Inset", Double.class, Element::getRelativeInset, Element::setRelativeInset)
                .range(0, 100000).omitWhen(0.0)
                .when(e -> e.owner != null && (!Double.isNaN(e.getRelativeWidth()) || !Double.isNaN(e.getRelativeHeight())))
                .tip("Points taken off each side of a relative size"));
        list.add(property(Element.class, LAYOUT, "weight", "Weight", Double.class, Element::getLayoutWeight, Element::setLayoutWeight)
                .range(0, 1000).omitWhen(0.0).when(e -> e.owner instanceof HorizontalLayout || e.owner instanceof VerticalLayout)
                .tip("Takes a share of the space the row or column has left; 0 keeps the element's own size"));
        list.add(UiEvents.controller());
        list.add(property(Element.class, STYLE, "opacity", "Opacity", Float.class, Element::getOpacity, Element::setOpacity).range(0, 1));
        list.add(new UiProperty("tooltip", "Tooltip", String.class, Element::getTooltip,
                (e, v, base) -> e.setTooltip(v == null || ((String) v).isEmpty() ? null : (String) v)).group(STYLE));
        list.add(property(Container.class, STYLE, "panel", "Themed Panel", Boolean.class, Container::isUsingTheme, Container::useTheme)
                .omitWhen(false).when(UiKinds::holdsElements)
                .tip("Takes its background, border and corners from the app's theme, as a panel does. What is set here still wins"));
        list.add(new UiProperty("background", "Background", Paint.class, e -> e.getStyling().getBackgroundColor(),
                (e, v, base) -> e.getStyling().setBackgroundColor((Paint) v)).group(STYLE).themed()
                .tip("A color, a gradient, a gradient that moves, or a rainbow"));
        list.add(new UiProperty("hoverBackground", "Hover Background", Paint.class, e -> e.getStyling().getHoverColor(),
                (e, v, base) -> e.getStyling().setHoverColor((Paint) v)).group(STYLE).themed().when(UiKinds::hasHover)
                .tip("What the background is while the mouse is over the element"));
        list.add(new UiProperty("effect", "Background Effect", Background.class, e -> snapshot(e.getBackground()), (e, v, base) -> {
            Background effect = (Background) v;
            if (effect == null) {
                e.setBackground(null);
                return;
            }
            // Changes the effect that is there when it is the same sort, so editing one setting doesn't start it over.
            Background mine = e.isBackgroundSet() ? e.getBackground() : null;
            e.setBackground(BackgroundKinds.apply(mine, BackgroundKinds.write(effect), base));
        }).group(STYLE).themed().nullable().onReset(Element::resetBackground)
                .tip("A picture, dots or moving shapes behind the element's contents"));
        list.add(new UiProperty("border", "Border Color", Paint.class, e -> e.getStyling().getBorderColor(),
                (e, v, base) -> e.getStyling().setBorderColor((Paint) v)).group(STYLE).themed());
        list.add(new UiProperty("borderWidth", "Border Width", Float.class, e -> e.getStyling().getBorderThickness(),
                (e, v, base) -> e.getStyling().setBorderThickness((Float) v)).group(STYLE).themed().range(0, 100));
        list.add(new UiProperty("cornerRadius", "Corner Radius", Float.class, e -> e.getStyling().getCornerRadius(),
                (e, v, base) -> e.getStyling().setCornerRadius((Float) v)).group(STYLE).themed().range(0, 1000));
        return list;
    }


    /** The title a tab gets for {@code content}: the one set on it, else its name, else "Tab". */
    static String tabTitle(Element content) {
        if (stash(content, "tabTitle") instanceof String title && !title.isBlank()) return title;
        String name = UiFile.getName(content);
        return name.isBlank() ? "Tab" : name;
    }

    /** Where {@code child} was told to dock, for when it goes into a dock layout. */
    static DockLayout.Dock dockOf(Element child) {
        return stash(child, "dock") instanceof DockLayout.Dock dock ? dock : DockLayout.Dock.TOP;
    }

    // An element's position means something only where nothing else decides it.
    private static boolean inFreeContainer(Element e) {
        return e.owner != null && !(e.owner instanceof Layout);
    }

    private static boolean isFree(Element e) {
        return inFreeContainer(e) && e.getAnchor() == null;
    }

    /** Anchors the element at {@code anchor}, with the same point of it on that point, and keeps the offsets. */
    private static void pin(Element e, Anchor anchor, double offsetX, double offsetY) {
        UiMeta stash = UiMeta.of(e);
        stash.stash.put("offsetX", offsetX);
        stash.stash.put("offsetY", offsetY);
        e.setAnchor(anchor, anchor, offsetX, offsetY);
    }

    /**
     * How far the element is from its anchor. Worked out from where it is when it is in a container, which stays true
     * when something else moves it; the value last set when it isn't in one yet, as while a file is loading.
     */
    private static double offset(Element e, boolean horizontal) {
        Anchor anchor = e.getAnchor();
        if (anchor == null) return 0;
        if (e.owner == null) {
            Object saved = stash(e, horizontal ? "offsetX" : "offsetY");
            return saved instanceof Number n ? n.doubleValue() : 0;
        }
        Anchor pivot = e.getPivot();
        return horizontal
                ? e.getX() - (e.owner.getWidth() * anchor.x() - e.getWidth() * pivot.x())
                : e.getY() - (e.owner.getHeight() * anchor.y() - e.getHeight() * pivot.y());
    }

    /** A copy of the effect that shares nothing with it, so a change to either leaves the other alone; null for none. */
    private static Background snapshot(Background effect) {
        Map<String, Object> settings = BackgroundKinds.write(effect);
        return settings == null ? null : BackgroundKinds.read(settings, Path.of(""));
    }

    private static boolean holdsElements(Element e) {
        UiKind kind = of(e);
        return e instanceof Container && kind != null && kind.isContainer();
    }

    /** Whether an element has a look of its own for when the mouse is over it: controls, and containers as buttons. */
    private static boolean hasHover(Element e) {
        return e instanceof Container || e instanceof ButtonOverlay || e instanceof TextFieldOverlay
                || e instanceof TextAreaOverlay || e instanceof DropdownOverlay || e instanceof ColorPickerOverlay
                || e instanceof DateTimePickerOverlay;
    }

    private static Object stash(Element e, String key) {
        UiMeta meta = UiMeta.peek(e);
        return meta == null ? null : meta.stash.get(key);
    }

    /** A property of one class, read and changed through its getter and setter. A null never reaches the setter. */
    private static <E extends Element, T> UiProperty property(Class<E> owner, String group, String name, String label,
                                                              Class<T> type, Function<E, T> get, BiConsumer<E, T> set) {
        return property(owner, group, name, label, type, get, set, false);
    }

    private static <E extends Element, T> UiProperty property(Class<E> owner, String group, String name, String label,
                                                              Class<T> type, Function<E, T> get, BiConsumer<E, T> set,
                                                              boolean nullable) {
        return new UiProperty(name, label, type, e -> get.apply(owner.cast(e)), (e, v, base) -> {
            if (v != null || nullable) set.accept(owner.cast(e), type.cast(v));
        }).group(group);
    }

    /** A color, gradient or rainbow that follows the theme until it is set. */
    private static <E extends Element> UiProperty color(Class<E> owner, String group, String name, String label,
                                                        Function<E, Paint> get, BiConsumer<E, Paint> set) {
        return property(owner, group, name, label, Paint.class, get, set).themed();
    }

    /** The unmodifiable list of kind ids, for messages. */
    static List<String> ids() {
        synchronized (byId) {
            return Collections.unmodifiableList(new ArrayList<>(byId.keySet()));
        }
    }
}
