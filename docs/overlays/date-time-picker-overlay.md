# DateTimePickerOverlay

[Documentation](../README.md) › [Overlays](README.md)

`me.piitex.engine.ui.overlays.DateTimePickerOverlay` is a closed box showing a date, a time, or both. Clicking it opens a
panel with a month calendar and/or hour and minute controls. One widget covers all three uses, chosen with a `Mode`.

```java
DateTimePickerOverlay due = new DateTimePickerOverlay(200, 34, DateTimePickerOverlay.Mode.DATE);
due.setPlaceholder("Pick a date");
due.onChange(p -> System.out.println("due: " + p.getDate()));
layout.addElement(due);
```

The constructor is `DateTimePickerOverlay(double width, double height)` (a date picker) or `(width, height, Mode mode)`.

| `Mode` | The panel shows | Reads with |
|---|---|---|
| `DATE` | A month calendar | `getDate()` |
| `TIME` | Hour and minute controls | `getTime()` |
| `DATE_TIME` | The calendar above the clock | `getDateTime()`, or `getDate()` and `getTime()` |

The panel is shown through `Window.showPopup`, so it paints over sibling elements, is **not clipped** by a parent
[`ScrollContainer`](../containers/scroll-container.md), and closes on an outside click or Escape. It follows the picker if the
picker moves while open, and closes if the picker is removed, disabled or scrolled out of view.

## The value

Values are `java.time` types, and the picker starts **empty**: the getters return null until something is chosen and the closed
box shows the placeholder.

```java
LocalDate d = picker.getDate(); // or null
LocalTime t = picker.getTime(); // or null; always to the minute
LocalDateTime both = picker.getDateTime(); // null unless BOTH are set

picker.setDate(LocalDate.of(2026, 9, 19)); // silent: onChange does NOT fire
picker.setTime(LocalTime.of(14, 30)); // seconds are dropped
picker.setDateTime(LocalDateTime.now()); // null clears both

picker.onChange(p -> ...); // user changes only; the callback receives the picker itself
```

In `DATE_TIME` mode, choosing one half through the panel fills the other with a default (today, or the current time) so the
value is never half-set by the user. Setting only one half from code does leave `getDateTime()` null.

`getDisplayText()` returns the text the closed box shows, or null when it would show the placeholder. This is useful for a
status line.

## Using the panel

| Control | What it does |
|---|---|
| `«` `‹` `›` `»` | Previous year, previous month, next month, next year |
| Day | Picks it. In `DATE` mode the panel then closes. Days from neighbouring months are dimmed but pickable |
| Hour and minute arrows | Step the value. Hours wrap at midnight without changing the date. Minutes wrap within the hour |
| AM/PM | Adds or removes 12 hours (12-hour clock only) |
| Mouse wheel | Over the clock, changes the column under the pointer. Over the calendar, changes the month |
| Today / Now | Picks today, or the current time. In `DATE_TIME` mode it sets both |
| Clear | Empties the value and closes. Only present with `setClearable(true)` |

In `DATE_TIME` mode the panel stays open after a pick so you can adjust both halves. Close it with Escape, Enter or an
outside click. Until a time is chosen the clock shows the current time dimmed.

## Options

```java
picker.setMinDate(LocalDate.now()); // earlier days are dimmed and cannot be picked
picker.setMaxDate(LocalDate.now().plusDays(30)); // likewise for later days; null removes a limit
picker.setUse24Hour(false); // 12-hour clock with an AM/PM column. Default true
picker.setMinuteStep(15); // the minute arrows move by this many minutes. Default 1, range 1 to 30
picker.setClearable(true); // adds a Clear button. Default false
picker.setFirstDayOfWeek(DayOfWeek.MONDAY); // default: the system locale's first day of the week
picker.setPlaceholder("Pick a date");
picker.setDateFormatter(DateTimeFormatter.ofPattern("d MMM yyyy")); // closed-box text. Default yyyy-MM-dd
picker.setTimeFormatter(DateTimeFormatter.ofPattern("HH:mm")); // default HH:mm, or h:mm a with a 12-hour clock
picker.setFontSize(15f);
picker.setTextColor(Color.WHITE); // pins the text color
```

`setMode`, `setUse24Hour` and `setClearable` close an open panel so it can be rebuilt. Month and weekday names come from the
default locale. Min and max limits are not applied to values you set from code, only to what the user can pick.

## Opening and closing from code

```java
picker.open(); // no-op with no window
picker.close();
picker.toggleOpen();
boolean open = picker.isOpen();
```

## Keyboard

The closed box is [`Focusable`](../input/input-and-focus.md#keyboard-focus) and a Tab stop. Enter, Space or Down opens the
panel. While the panel is open it takes every key, including Tab.

| Key | Effect |
|---|---|
| Left / Right | Move the highlighted day by one. In the clock, choose hours, minutes or AM/PM |
| Up / Down | Move the highlighted day by a week. In the clock, change the value |
| Page Up / Page Down | Move by a month. With Shift, by a year |
| Space | Pick the highlighted day |
| Enter | Pick and close |
| Tab | In `DATE_TIME` mode, switch between the calendar and the clock |
| Escape | Close without changing anything further |

The highlighted day only shows after a key press, and clicking with the mouse hides it again.

## Look

The box uses the text-field theme tokens, and the panel reuses the overlay's own styling. The selected day uses the theme
selection color, and today gets an outline.

```java
picker.getStyling().setBackgroundColor(new Color(0.12f, 0.12f, 0.14f, 1f));
picker.getStyling().setCornerRadius(8f);
```

The background and border may be a [`Color`](../styling/colors-and-paints.md#color) or a gradient, on the box and the popup alike. The thin
outlines inside the popup use the border color when it is a plain `Color`, and gray otherwise.

> **Do not call `onMouseClick` on a picker.** The constructor registers an internal handler that opens the panel, and
> replacing it breaks the widget. Use `onChange`.

## Limitations

- **No seconds, time zones or date ranges.** The time is to the minute and the value is a local date and time.
- **No typing a date into the closed box.** Choose it in the panel.
- **The calendar is always six rows**, so it does not change height between months.

## Method reference

| Group | Methods |
|---|---|
| Value | `getMode`, `setMode`, `get/setDate`, `get/setTime`, `get/setDateTime` (`getDateTime`, `setDateTime`), `getDisplayText`, `onChange` |
| Limits | `get/setMinDate`, `get/setMaxDate` |
| Options | `is/setUse24Hour`, `get/setMinuteStep`, `setClearable`, `setFirstDayOfWeek`, `setPlaceholder`, `setDateFormatter`, `setTimeFormatter`, `get/setFontSize`, `get/setTextColor` |
| Panel | `isOpen`, `open`, `close`, `toggleOpen` |
