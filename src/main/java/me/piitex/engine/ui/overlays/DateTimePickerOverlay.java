package me.piitex.engine.ui.overlays;

import me.piitex.engine.Window;
import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.input.MouseTracker;
import me.piitex.engine.ui.CursorMode;
import me.piitex.engine.ui.Focusable;
import me.piitex.engine.ui.PopupKeyListener;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.Paint;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.scroll.Scrollable;
import me.piitex.engine.ui.text.Font;
import me.piitex.engine.ui.text.FontScalable;
import me.piitex.engine.ui.theme.Overridable;
import me.piitex.engine.ui.theme.Theme;
import me.piitex.engine.ui.theme.ThemeManager;
import me.piitex.engine.ui.theme.Themeable;
import org.jetbrains.skia.Typeface;
import org.lwjgl.glfw.GLFW;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.time.temporal.WeekFields;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * A closed box showing a date, a time, or both; clicking it opens a panel with a month
 * calendar and/or hour/minute controls. One widget covers the three uses, chosen with
 * {@link Mode}: {@code DATE} (calendar only), {@code TIME} (clock only) and {@code DATE_TIME}
 * (calendar above the clock).
 * <p>
 * Values are {@code java.time} types and start out empty: {@link #getDate()}, {@link
 * #getTime()} and {@link #getDateTime()} return null until something is chosen. The
 * closed box then shows the placeholder. In {@code DATE_TIME} mode, choosing one half fills
 * the other with a default (today, or the current time) so the value is never half-set
 * through the UI.
 * <p>
 * The panel is shown through {@link Window#showPopup}, so it paints over everything, is not
 * clipped by a parent scroll container, closes on an outside click or Escape, and follows the
 * picker if the picker moves. In {@code
 * DATE} mode picking a day closes it; otherwise it stays open so you can adjust both parts.
 * {@link #onChange} fires on every user change. Days outside {@link #setMinDate}/{@link
 * #setMaxDate} are dimmed and cannot be picked.
 * <p>
 * The box is themed like {@link TextFieldOverlay}'s field, and the panel reuses this
 * overlay's own {@link #getStyling()} background and border. This overlay registers its
 * own {@link #onMouseClick} handler to open and close the panel; replacing that handler
 * stops the picker from opening. It is {@link Focusable} and a Tab stop, and Enter,
 * Space, or Down opens it.
 * <p>
 * <b>Keyboard in the open panel:</b> Left/Right move the highlighted day by one, Up/Down by a
 * week, Page Up/Down by a month (with Shift, a year), Enter or Space picks it. In {@code
 * DATE_TIME} mode Tab switches between the calendar and the clock; in the clock Left/Right
 * choose hours, minutes or AM/PM and Up/Down change the value. Enter picks and closes,
 * Escape closes.
 */
public class DateTimePickerOverlay extends Overlay implements Themeable, Focusable, FontScalable {

    /**
     * What the picker edits.
     */
    public enum Mode {DATE, TIME, DATE_TIME}

    private Mode mode;
    private LocalDate date;
    private LocalTime time;
    private LocalDate minDate, maxDate;
    private boolean use24Hour = true;
    private int minuteStep = 1;
    private boolean clearable;
    private DayOfWeek firstDay = WeekFields.of(Locale.getDefault()).getFirstDayOfWeek();
    private String placeholder = "Select...";
    private DateTimeFormatter dateFormatter, timeFormatter;
    private Consumer<DateTimePickerOverlay> onChange;
    private float fontSize = 15f;
    private final Overridable<Color> textColor = new Overridable<>(Color.BLACK);
    private final Overridable<Color> placeholderColor = new Overridable<>(Color.LIGHT_GRAY);
    private final Overridable<Color> selectionColor = new Overridable<>(new Color(0.60f, 0.78f, 1.0f, 0.55f));

    private org.jetbrains.skia.Font font;
    private Typeface fontTypeface;
    private PickerPopup popup;

    public DateTimePickerOverlay(double width, double height) {
        this(width, height, Mode.DATE);
    }

    public DateTimePickerOverlay(double width, double height, Mode mode) {
        this.mode = mode == null ? Mode.DATE : mode;
        setWidth(width);
        setHeight(height);
        setCursorMode(CursorMode.POINTER);
        setFocusTraversable(true);
        onMouseClick(event -> toggleOpen());
        ThemeManager.register(this);
    }

    /* Value */

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode == null ? Mode.DATE : mode;
        close();
    }

    /**
     * The chosen date, or null.
     */
    public LocalDate getDate() {
        return date;
    }

    /**
     * Sets the date programmatically. {@link #onChange} does not fire. Null clears it.
     */
    public void setDate(LocalDate date) {
        this.date = date;
    }

    /**
     * The chosen time (to the minute), or null.
     */
    public LocalTime getTime() {
        return time;
    }

    /**
     * Sets the time programmatically. {@link #onChange} does not fire. Null clears it, and seconds are dropped.
     */
    public void setTime(LocalTime time) {
        this.time = time == null ? null : time.truncatedTo(ChronoUnit.MINUTES);
    }

    /**
     * Date and time together, or null unless both are set.
     */
    public LocalDateTime getDateTime() {
        return date != null && time != null ? LocalDateTime.of(date, time) : null;
    }

    /**
     * Sets both parts programmatically. {@link #onChange} does not fire. Null clears both.
     */
    public void setDateTime(LocalDateTime dateTime) {
        this.date = dateTime == null ? null : dateTime.toLocalDate();
        setTime(dateTime == null ? null : dateTime.toLocalTime());
    }

    /**
     * Fired (with this picker, read the value from it) whenever the USER changes the value. One listener is held at a time.
     */
    public void onChange(Consumer<DateTimePickerOverlay> onChange) {
        this.onChange = onChange;
    }

    /**
     * Earliest pickable date, or null for no limit.
     */
    public void setMinDate(LocalDate minDate) {
        this.minDate = minDate;
    }

    public LocalDate getMinDate() {
        return minDate;
    }

    /**
     * Latest pickable date, or null for no limit.
     */
    public void setMaxDate(LocalDate maxDate) {
        this.maxDate = maxDate;
    }

    public LocalDate getMaxDate() {
        return maxDate;
    }

    /**
     * 24-hour clock (default) or 12-hour with an AM/PM control. Also changes the default time format.
     */
    public void setUse24Hour(boolean use24Hour) {
        this.use24Hour = use24Hour;
        close();
    }

    public boolean isUse24Hour() {
        return use24Hour;
    }

    /**
     * How many minutes the minute arrows move by (1, 5, 15, ...). Default 1.
     */
    public void setMinuteStep(int minuteStep) {
        this.minuteStep = Math.max(1, Math.min(30, minuteStep));
    }

    public int getMinuteStep() {
        return minuteStep;
    }

    /**
     * Whether the panel has a "Clear" button that empties the value. Default false.
     */
    public void setClearable(boolean clearable) {
        this.clearable = clearable;
        close();
    }

    /**
     * The first column of the calendar. Defaults to the system locale's (Sunday in the US, Monday in most of Europe).
     */
    public void setFirstDayOfWeek(DayOfWeek firstDay) {
        this.firstDay = firstDay == null ? DayOfWeek.MONDAY : firstDay;
    }

    /**
     * Text shown in the closed box while nothing is chosen.
     */
    public void setPlaceholder(String placeholder) {
        this.placeholder = placeholder == null ? "" : placeholder;
    }

    /**
     * How the date reads in the closed box. Default {@code yyyy-MM-dd}.
     */
    public void setDateFormatter(DateTimeFormatter dateFormatter) {
        this.dateFormatter = dateFormatter;
    }

    /**
     * How the time reads in the closed box. Default {@code HH:mm}, or {@code h:mm a} with a 12-hour clock.
     */
    public void setTimeFormatter(DateTimeFormatter timeFormatter) {
        this.timeFormatter = timeFormatter;
    }

    /**
     * The text the closed box shows for the current value, or null when it would show the placeholder.
     */
    public String getDisplayText() {
        DateTimeFormatter df = dateFormatter != null ? dateFormatter : DateTimeFormatter.ofPattern("yyyy-MM-dd");
        DateTimeFormatter tf = timeFormatter != null ? timeFormatter
                : DateTimeFormatter.ofPattern(use24Hour ? "HH:mm" : "h:mm a");
        return switch (mode) {
            case DATE -> date == null ? null : df.format(date);
            case TIME -> time == null ? null : tf.format(time);
            case DATE_TIME -> date == null || time == null ? null : df.format(date) + " " + tf.format(time);
        };
    }

    private boolean hasDate() {
        return mode != Mode.TIME;
    }

    private boolean hasTime() {
        return mode != Mode.DATE;
    }

    private boolean inRange(LocalDate d) {
        return (minDate == null || !d.isBefore(minDate)) && (maxDate == null || !d.isAfter(maxDate));
    }

    private void changedByUser() {
        if (onChange != null) onChange.accept(this);
    }

    /* Appearance */

    public float getFontSize() {
        return fontSize;
    }

    public void setFontSize(float fontSize) {
        this.fontSize = fontSize;
        closeFont();
    }

    @Override
    public float[] getFontSizes() {
        return new float[]{fontSize};
    }

    @Override
    public void setFontSizes(float[] sizes) {
        setFontSize(sizes[0]);
    }

    public Color getTextColor() {
        return textColor.get();
    }

    public void setTextColor(Color color) {
        textColor.set(color);
    }

    @Override
    public void applyTheme(Theme theme) {
        getStyling().applyThemeBackgroundColor(theme.getFieldBackground());
        getStyling().applyThemeHoverColor(theme.getFieldHoverBackground());
        getStyling().applyThemeBorderColor(theme.getFieldBorder());
        getStyling().applyThemeBorderThickness(theme.getBorderThickness());
        getStyling().applyThemeCornerRadius(theme.getCornerRadius());
        textColor.applyTheme(theme.getText());
        placeholderColor.applyTheme(theme.getPlaceholderText());
        selectionColor.applyTheme(theme.getSelection());
    }

    /* Open / close */

    public boolean isOpen() {
        return popup != null;
    }

    public void toggleOpen() {
        if (isOpen()) close();
        else open();
    }

    public void open() {
        if (isOpen()) return;
        Window window = getWindow();
        if (window == null) return;

        PickerPopup p = new PickerPopup();
        float[] at = PopupPlacement.below(this, window, (float) p.getWidth(), (float) p.getHeight());
        p.setPosition(at[0], at[1]);
        popup = p;
        window.showPopup(p, this, () -> {
            p.dispose();
            if (popup == p) popup = null;
        });
    }

    public void close() {
        PickerPopup p = popup;
        if (p == null) return;
        Window window = getWindow();
        if (window != null && window.getPopup() == p) {
            window.closePopup(); // the dismiss callback clears `popup`
        }
        popup = null;
    }

    /* Closed-box rendering */

    private org.jetbrains.skia.Font getFont() {
        Typeface typeface = Font.getDefault();
        if (font == null || typeface != fontTypeface) {
            closeFont();
            font = new org.jetbrains.skia.Font(typeface, fontSize);
            fontTypeface = typeface;
        }
        return font;
    }

    private void closeFont() {
        if (font != null) {
            font.close();
            font = null;
        }
    }

    @Override
    public void render(Renderer renderer) {
        if (!isEnabled()) return;
        super.render(renderer);

        float x = (float) getX(), y = (float) getY(), w = (float) getWidth(), h = (float) getHeight();
        float pad = 8f;
        float icon = Math.max(12f, Math.min(h * 0.5f, 18f));
        org.jetbrains.skia.Font f = getFont();
        String text = getDisplayText();
        float textH = f.getMetrics().getHeight();

        renderer.pushClip(x, y, w - icon - 2 * pad, h);
        String shown = text != null ? text : placeholder;
        float shift = Math.max(0f, w - icon - 2 * pad - f.measureTextWidth(shown)) * textAlignH(0f);
        renderer.drawText(shown, x + pad + shift, y + (h - textH) / 2f, f,
                text != null ? resolveTextColor(textColor.get()) : placeholderColor.get());
        renderer.popClip();

        float ix = x + w - pad - icon, iy = y + (h - icon) / 2f;
        if (mode == Mode.TIME) drawClockIcon(renderer, ix, iy, icon, textColor.get());
        else drawCalendarIcon(renderer, ix, iy, icon, textColor.get());
    }

    private static void drawCalendarIcon(Renderer r, float x, float y, float s, Color c) {
        r.drawBorder(x, y + s * 0.12f, s, s * 0.88f, c, 1.5f, 2f);
        r.drawLine(x, y + s * 0.38f, x + s, y + s * 0.38f, c, 1.5f);
        r.drawLine(x + s * 0.3f, y, x + s * 0.3f, y + s * 0.24f, c, 1.5f);
        r.drawLine(x + s * 0.7f, y, x + s * 0.7f, y + s * 0.24f, c, 1.5f);
    }

    private static void drawClockIcon(Renderer r, float x, float y, float s, Color c) {
        r.drawBorder(x, y, s, s, c, 1.5f, s / 2f);
        float cx = x + s / 2f, cy = y + s / 2f;
        r.drawLine(cx, cy, cx, y + s * 0.2f, c, 1.5f);
        r.drawLine(cx, cy, x + s * 0.74f, cy, c, 1.5f);
    }

    /* Keyboard (closed box) */

    @Override
    public void onFocusGained() {
    }

    @Override
    public void onFocusLost() {
    }

    @Override
    public void onCharTyped(int codepoint) {
    }

    @Override
    public void onKeyPressed(int key, int action, int mods) {
        if (action != GLFW.GLFW_PRESS) return;
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER || key == GLFW.GLFW_KEY_SPACE
                || key == GLFW.GLFW_KEY_DOWN) {
            open();
        }
    }

    /**
     * The open panel. A top-level Container (as {@link Window#showPopup} requires) that draws and
     * hit-tests its calendar and clock itself rather than holding child Elements, like the menu popup.
     */
    private final class PickerPopup extends Container implements PopupKeyListener, Scrollable {
        private static final float PAD = 8f, CELL_W = 36f, CELL_H = 30f;
        private static final float W = 7 * CELL_W + 2 * PAD;
        private static final float NAV_H = 32f, NAV_BTN = 32f, WEEKDAY_H = 22f, FOOT_H = 30f;
        private static final float TIME_H = 84f, ARROW_H = 22f, VALUE_H = 36f;
        private static final float COL_W = 56f, COLON_W = 20f, COL_GAP = 12f;

        private final boolean hasDate = hasDate(), hasTime = hasTime();
        private final float navY, weekdayY, gridY, timeY, footY;
        private final LocalTime baseTime;           // shown (dimmed) until the user commits a time
        private final org.jetbrains.skia.Font bigFont;
        private YearMonth view;
        private LocalDate cursor;                   // keyboard-highlighted day
        private boolean focusTime;                  // keyboard focus is in the clock
        private int timeCol;                        // 0 hour, 1 minute, 2 AM/PM
        private boolean keyboardMode;

        PickerPopup() {
            super(0, 0, W, 0);
            DateTimePickerOverlay owner = DateTimePickerOverlay.this;

            float y = PAD;
            navY = y;
            weekdayY = hasDate ? y + NAV_H : y;
            gridY = weekdayY + (hasDate ? WEEKDAY_H : 0f);
            y = gridY + (hasDate ? 6 * CELL_H : 0f);
            timeY = y + (hasDate ? 6f : 0f);
            y = timeY + (hasTime ? TIME_H : 0f);
            footY = y + 4f;
            setHeight(footY + FOOT_H + PAD);

            LocalTime now = LocalTime.now().truncatedTo(ChronoUnit.MINUTES);
            baseTime = now.withMinute(now.getMinute() / owner.minuteStep * owner.minuteStep);
            LocalDate today = LocalDate.now();
            LocalDate start = owner.date != null ? owner.date : today;
            view = YearMonth.from(start);
            cursor = start;
            focusTime = !hasDate;
            bigFont = new org.jetbrains.skia.Font(Font.getDefault(), owner.fontSize * 1.5f);

            Paint bg = owner.getStyling().getBackgroundColor();
            getStyling().setBackgroundColor(bg);
            getStyling().setHoverColor(bg); // hovering the panel must not tint it
            getStyling().setBorderColor(owner.getStyling().getBorderColor());
            getStyling().setBorderThickness(owner.getStyling().getBorderThickness());
            getStyling().setCornerRadius(owner.getStyling().getCornerRadius());

            onMouseClick(event -> {
                if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                    keyboardMode = false;
                    handleClick(event.getMouseX() - getX(), event.getMouseY() - getY());
                }
            });
        }

        void dispose() {
            bigFont.close();
        }

        /* Value changes */

        private LocalTime shownTime() {
            return time != null ? time : baseTime;
        }

        private void pickDate(LocalDate d, boolean closeInDateMode) {
            if (!inRange(d)) return;
            date = d;
            if (mode == Mode.DATE_TIME && time == null) time = baseTime;
            view = YearMonth.from(d);
            cursor = d;
            changedByUser();
            if (closeInDateMode && mode == Mode.DATE) close();
        }

        private void pickTime(LocalTime t) {
            time = t.truncatedTo(ChronoUnit.MINUTES);
            if (mode == Mode.DATE_TIME && date == null) {
                LocalDate today = LocalDate.now();
                date = minDate != null && today.isBefore(minDate) ? minDate
                        : maxDate != null && today.isAfter(maxDate) ? maxDate : today;
                view = YearMonth.from(date);
            }
            changedByUser();
        }

        private void adjustTime(int col, int dir) {
            LocalTime t = shownTime();
            switch (col) {
                case 0 -> t = t.plusHours(dir);
                case 1 -> {
                    int m = t.getMinute();
                    int n = dir > 0 ? (m / minuteStep + 1) * minuteStep
                            : (m % minuteStep == 0 ? m - minuteStep : (m / minuteStep) * minuteStep);
                    t = t.withMinute(Math.floorMod(n, 60));
                }
                default -> t = t.plusHours(12);
            }
            pickTime(t);
        }

        private void today() {
            LocalDate today = LocalDate.now();
            LocalTime now = LocalTime.now().truncatedTo(ChronoUnit.MINUTES);
            if (mode == Mode.TIME) {
                pickTime(now);
            } else {
                pickDate(today, true);
                if (mode == Mode.DATE_TIME) pickTime(now);
            }
        }

        private void clear() {
            date = null;
            time = null;
            changedByUser();
            close();
        }

        /* Geometry */

        private float timeX0() {
            float total = 2 * COL_W + COLON_W + (use24Hour ? 0f : COL_GAP + COL_W);
            return (W - total) / 2f;
        }

        private float colX(int col) {
            float x0 = timeX0();
            return switch (col) {
                case 0 -> x0;
                case 1 -> x0 + COL_W + COLON_W;
                default -> x0 + 2 * COL_W + COLON_W + COL_GAP;
            };
        }

        private int colCount() {
            return use24Hour ? 2 : 3;
        }

        /**
         * {col, zone} for a point in the clock (zone +1 = up arrow, 0 = value, -1 = down arrow), or null.
         */
        private int[] timeHit(double lx, double ly) {
            if (!hasTime || ly < timeY || ly >= timeY + TIME_H) return null;
            for (int c = 0; c < colCount(); c++) {
                if (lx >= colX(c) && lx < colX(c) + COL_W) {
                    double oy = ly - timeY;
                    return new int[]{c, oy < ARROW_H ? 1 : oy < ARROW_H + VALUE_H ? 0 : -1};
                }
            }
            return null;
        }

        private LocalDate cellDate(int row, int col) {
            LocalDate first = view.atDay(1);
            int offset = Math.floorMod(first.getDayOfWeek().getValue() - firstDay.getValue(), 7);
            return first.minusDays(offset).plusDays(row * 7L + col);
        }

        /* Input */

        private void handleClick(double lx, double ly) {
            if (hasDate) {
                if (ly >= navY && ly < navY + NAV_H) {
                    if (lx >= PAD && lx < PAD + NAV_BTN) view = view.minusYears(1);
                    else if (lx >= PAD + NAV_BTN && lx < PAD + 2 * NAV_BTN) view = view.minusMonths(1);
                    else if (lx >= W - PAD - NAV_BTN) view = view.plusYears(1);
                    else if (lx >= W - PAD - 2 * NAV_BTN) view = view.plusMonths(1);
                    return;
                }
                if (ly >= gridY && ly < gridY + 6 * CELL_H && lx >= PAD && lx < PAD + 7 * CELL_W) {
                    int row = (int) ((ly - gridY) / CELL_H), col = (int) ((lx - PAD) / CELL_W);
                    pickDate(cellDate(row, col), true);
                    focusTime = false;
                    return;
                }
            }
            int[] hit = timeHit(lx, ly);
            if (hit != null) {
                focusTime = true;
                timeCol = hit[0];
                if (hit[1] != 0 || hit[0] == 2) adjustTime(hit[0], hit[1] == 0 ? 1 : hit[1]);
                return;
            }
            if (ly >= footY && ly < footY + FOOT_H) {
                if (lx >= PAD && lx < PAD + 90f) today();
                else if (clearable && lx >= W - PAD - 90f) clear();
            }
        }

        @Override
        public void onScroll(double deltaX, double deltaY) {
            double lx = MouseTracker.getX() - getX(), ly = MouseTracker.getY() - getY();
            int dir = deltaY > 0 ? 1 : -1;
            int[] hit = timeHit(lx, ly);
            if (hit != null) {
                adjustTime(hit[0], dir);
            } else if (hasDate && ly >= navY && ly < gridY + 6 * CELL_H) {
                view = view.plusMonths(-dir); // wheel up = earlier
            }
        }

        @Override
        public void onPopupKey(int key, int mods) {
            keyboardMode = true;
            boolean shift = (mods & GLFW.GLFW_MOD_SHIFT) != 0;

            if (key == GLFW.GLFW_KEY_ESCAPE) {
                close();
                return;
            }
            if (key == GLFW.GLFW_KEY_TAB) {
                if (hasDate && hasTime) focusTime = !focusTime;
                return;
            }
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                if (hasDate && inRange(cursor) && (!focusTime || date == null)) pickDate(cursor, false);
                if (hasTime && time == null) pickTime(baseTime);
                close();
                return;
            }

            if (focusTime) {
                switch (key) {
                    case GLFW.GLFW_KEY_LEFT -> timeCol = Math.floorMod(timeCol - 1, colCount());
                    case GLFW.GLFW_KEY_RIGHT -> timeCol = Math.floorMod(timeCol + 1, colCount());
                    case GLFW.GLFW_KEY_UP -> adjustTime(timeCol, 1);
                    case GLFW.GLFW_KEY_DOWN -> adjustTime(timeCol, -1);
                    default -> {
                    }
                }
                return;
            }

            LocalDate next = switch (key) {
                case GLFW.GLFW_KEY_LEFT -> cursor.minusDays(1);
                case GLFW.GLFW_KEY_RIGHT -> cursor.plusDays(1);
                case GLFW.GLFW_KEY_UP -> cursor.minusWeeks(1);
                case GLFW.GLFW_KEY_DOWN -> cursor.plusWeeks(1);
                case GLFW.GLFW_KEY_PAGE_UP -> shift ? cursor.minusYears(1) : cursor.minusMonths(1);
                case GLFW.GLFW_KEY_PAGE_DOWN -> shift ? cursor.plusYears(1) : cursor.plusMonths(1);
                default -> null;
            };
            if (next != null) {
                cursor = next;
                view = YearMonth.from(next);
            } else if (key == GLFW.GLFW_KEY_SPACE) {
                pickDate(cursor, true);
            }
        }

        /* Rendering */

        @Override
        public void render(Renderer renderer) {
            renderBackground(renderer);
            DateTimePickerOverlay owner = DateTimePickerOverlay.this;
            float ax = (float) getX(), ay = (float) getY();
            org.jetbrains.skia.Font f = owner.getFont();
            float textH = f.getMetrics().getHeight();
            Color text = owner.textColor.get(), dim = owner.placeholderColor.get();
            Color hoverFill = new Color(owner.selectionColor.get().getR(), owner.selectionColor.get().getG(),
                    owner.selectionColor.get().getB(), owner.selectionColor.get().getA() * 0.5f);
            Color border = owner.getStyling().getBorderColor() instanceof Color c ? c : Color.GRAY;
            boolean mouseOn = MouseTracker.isInWindow();
            double mx = MouseTracker.getX() - getX(), my = MouseTracker.getY() - getY();
            float radius = Math.min(owner.getStyling().getCornerRadius(), 6f);

            if (hasDate) {
                // Header: << < Month Year > >>
                float[] btnX = {PAD, PAD + NAV_BTN, W - PAD - 2 * NAV_BTN, W - PAD - NAV_BTN};
                for (int i = 0; i < 4; i++) {
                    boolean hover = mouseOn && mx >= btnX[i] && mx < btnX[i] + NAV_BTN && my >= navY && my < navY + NAV_H;
                    if (hover) renderer.drawQuad(ax + btnX[i], ay + navY, NAV_BTN, NAV_H, hoverFill, radius);
                    drawChevrons(renderer, ax + btnX[i] + NAV_BTN / 2f, ay + navY + NAV_H / 2f, i % 3 == 0 ? 2 : 1,
                            i < 2 ? -1 : 1, text);
                }
                String title = view.getMonth().getDisplayName(TextStyle.FULL, Locale.getDefault()) + " " + view.getYear();
                float tw = f.measureTextWidth(title);
                renderer.drawText(title, ax + (W - tw) / 2f, ay + navY + (NAV_H - textH) / 2f, f, text);

                for (int c = 0; c < 7; c++) {
                    String name = firstDay.plus(c).getDisplayName(TextStyle.SHORT, Locale.getDefault());
                    float nw = f.measureTextWidth(name);
                    renderer.pushClip(ax + PAD + c * CELL_W, ay + weekdayY, CELL_W, WEEKDAY_H);
                    renderer.drawText(name, ax + PAD + c * CELL_W + (CELL_W - nw) / 2f, ay + weekdayY + (WEEKDAY_H - textH) / 2f, f, dim);
                    renderer.popClip();
                }

                LocalDate today = LocalDate.now();
                for (int r = 0; r < 6; r++) {
                    for (int c = 0; c < 7; c++) {
                        LocalDate d = cellDate(r, c);
                        float cx = ax + PAD + c * CELL_W, cy = ay + gridY + r * CELL_H;
                        boolean ok = inRange(d);
                        boolean selected = d.equals(owner.date);
                        boolean hover = ok && mouseOn && mx >= PAD + c * CELL_W && mx < PAD + (c + 1) * CELL_W
                                && my >= gridY + r * CELL_H && my < gridY + (r + 1) * CELL_H;
                        if (selected)
                            renderer.drawQuad(cx + 2f, cy + 2f, CELL_W - 4f, CELL_H - 4f, owner.selectionColor.get(), radius);
                        else if (hover)
                            renderer.drawQuad(cx + 2f, cy + 2f, CELL_W - 4f, CELL_H - 4f, hoverFill, radius);
                        if (d.equals(today))
                            renderer.drawBorder(cx + 2f, cy + 2f, CELL_W - 4f, CELL_H - 4f, border, 1.5f, radius);
                        if (keyboardMode && !focusTime && d.equals(cursor)) {
                            renderer.drawBorder(cx + 1f, cy + 1f, CELL_W - 2f, CELL_H - 2f, text, 2f, radius + 1f);
                        }
                        String label = Integer.toString(d.getDayOfMonth());
                        float lw = f.measureTextWidth(label);
                        boolean inMonth = YearMonth.from(d).equals(view);
                        renderer.drawText(label, cx + (CELL_W - lw) / 2f, cy + (CELL_H - textH) / 2f, f,
                                ok && inMonth ? text : dim);
                    }
                }
            }

            if (hasTime) {
                if (hasDate) renderer.drawLine(ax + PAD, ay + timeY - 3f, ax + W - PAD, ay + timeY - 3f, border, 1f);
                LocalTime t = shownTime();
                boolean unset = time == null;
                int[] hover = mouseOn ? timeHit(mx, my) : null;
                float bigH = bigFont.getMetrics().getHeight();

                for (int c = 0; c < colCount(); c++) {
                    float cx = ax + colX(c), cy = ay + timeY;
                    if (hover != null && hover[0] == c) {
                        float zoneY = hover[1] == 1 ? 0f : hover[1] == 0 ? ARROW_H : ARROW_H + VALUE_H;
                        float zoneH = hover[1] == 0 ? VALUE_H : ARROW_H;
                        renderer.drawQuad(cx, cy + zoneY, COL_W, zoneH, hoverFill, radius);
                    }
                    if (keyboardMode && focusTime && timeCol == c) {
                        renderer.drawBorder(cx, cy + ARROW_H, COL_W, VALUE_H, text, 2f, radius);
                    }
                    String value = switch (c) {
                        case 0 ->
                                String.format("%02d", use24Hour ? t.getHour() : (t.getHour() % 12 == 0 ? 12 : t.getHour() % 12));
                        case 1 -> String.format("%02d", t.getMinute());
                        default -> t.getHour() < 12 ? "AM" : "PM";
                    };
                    float vw = bigFont.measureTextWidth(value);
                    renderer.drawText(value, cx + (COL_W - vw) / 2f, cy + ARROW_H + (VALUE_H - bigH) / 2f, bigFont, unset ? dim : text);
                    drawChevronVertical(renderer, cx + COL_W / 2f, cy + ARROW_H / 2f, -1, text);
                    drawChevronVertical(renderer, cx + COL_W / 2f, cy + ARROW_H + VALUE_H + ARROW_H / 2f, 1, text);
                }
                float colonX = ax + colX(0) + COL_W + COLON_W / 2f, colonY = ay + timeY + ARROW_H + VALUE_H / 2f;
                renderer.drawCircle(colonX, colonY - 5f, 2f, unset ? dim : text);
                renderer.drawCircle(colonX, colonY + 5f, 2f, unset ? dim : text);
            }

            // Footer: Today/Now on the left, Clear on the right.
            String left = mode == Mode.TIME ? "Now" : "Today";
            drawFooterButton(renderer, f, ax + PAD, ay + footY, 90f, left, mouseOn && mx >= PAD && mx < PAD + 90f
                    && my >= footY && my < footY + FOOT_H, hoverFill, text, radius, textH);
            if (clearable) {
                drawFooterButton(renderer, f, ax + W - PAD - 90f, ay + footY, 90f, "Clear",
                        mouseOn && mx >= W - PAD - 90f && mx < W - PAD && my >= footY && my < footY + FOOT_H,
                        hoverFill, text, radius, textH);
            }
        }

        private void drawFooterButton(Renderer renderer, org.jetbrains.skia.Font f, float x, float y, float w, String label, boolean hover,
                                      Color hoverFill, Color text, float radius, float textH) {
            if (hover) renderer.drawQuad(x, y, w, FOOT_H, hoverFill, radius);
            float lw = f.measureTextWidth(label);
            renderer.drawText(label, x + (w - lw) / 2f, y + (FOOT_H - textH) / 2f, f, text);
        }

        /**
         * {@code count} left- or right-pointing chevrons ({@code dir} -1 = left) centered on (cx, cy).
         */
        private void drawChevrons(Renderer renderer, float cx, float cy, int count, int dir, Color color) {
            float gap = 5f;
            float start = cx - (count - 1) * gap / 2f;
            for (int i = 0; i < count; i++) {
                float x = start + i * gap;
                renderer.drawLine(x - dir * 2.5f, cy - 5f, x + dir * 2.5f, cy, color, 1.8f);
                renderer.drawLine(x + dir * 2.5f, cy, x - dir * 2.5f, cy + 5f, color, 1.8f);
            }
        }

        /**
         * A small up ({@code dir} -1) or down (+1) chevron.
         */
        private void drawChevronVertical(Renderer renderer, float cx, float cy, int dir, Color color) {
            renderer.drawLine(cx - 5f, cy - dir * 2.5f, cx, cy + dir * 2.5f, color, 1.8f);
            renderer.drawLine(cx, cy + dir * 2.5f, cx + 5f, cy - dir * 2.5f, color, 1.8f);
        }
    }
}
