# AccordionContainer

[Documentation](../README.md) › [Containers](README.md)

`me.piitex.engine.ui.containers.AccordionContainer` is a stack of sections, each a clickable title bar over a body that folds
away: the collapsible groups of a settings page, an "Advanced" fold under a form, a sidebar of expandable panels.

```java
AccordionContainer accordion = new AccordionContainer(320);
accordion.useTheme();
accordion.addSection("Graphics", graphicsPanel, true);   // starts open
accordion.addSection("Audio", audioPanel);                // starts folded
accordion.addSection("Controls", controlsPanel);
accordion.onToggle((section, open) -> prefs.remember(section.getTitle(), open));
root.addElement(accordion);
```

A section's body is any [`Element`](../core/element.md), usually a [`Layout`](../layouts/README.md). The accordion sets its width to
its own (less the padding) and leaves its height alone, which may change while the section is open. The accordion's **own height
follows its sections**: only the title bars when everything is folded, taller as sections open. Put it in a
[`ScrollContainer`](scroll-container.md) when the sections can outgrow the window.

A body slides open and shut. A folded body is disabled, so it is not drawn and takes no clicks or Tab focus.

## Sections

```java
AccordionContainer.Section audio = accordion.addSection("Audio", panel);
audio.setExpanded(true);      // silent; slides open
audio.toggle();
audio.setTitle("Sound");
audio.isExpanded();

accordion.expandAll();        // does nothing in an exclusive accordion
accordion.collapseAll();
accordion.removeSection(audio);
List<AccordionContainer.Section> all = accordion.getSections();
```

`onToggle` fires only when the **user** opens or folds a section, by click or key. The setters are silent.

### One open at a time

```java
accordion.setExclusive(true);
```

Opening a section then folds the others. Turning it on keeps only the first open section open.

## Keyboard

Tab to the accordion. Up, Down, Home and End move between the title bars (the focused one has an outline), and Enter or Space opens
or folds it.

## Look

```java
accordion.setHeaderHeight(40);       // default 34
accordion.setContentPadding(12);     // between a body and the edges of its area, default 10
accordion.setSpacing(4);             // gap between sections, default 0
accordion.setAnimationSeconds(0.25f);  // 0 for no slide; default 0.18
accordion.setFontSize(17);
accordion.setTextColor(Color.WHITE); // pins the title color against theme switches
accordion.setAccentColor(Color.BLUE);  // the focus outline
```

It is a `Container`, so `useTheme()` gives it the themed background, border and corner radius, and everything else a container does
(`setPosition`, tooltips, anchors) works. `dispose()` frees its font.

## Notes

- A body that is itself a `ScrollContainer` or has a relative size will not size to its section; give it a fixed height.
- The accordion resizes its bodies' widths every frame, so a width you set on a body is overridden.
