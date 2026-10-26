package me.piitex.engine.ui.containers;

import me.piitex.engine.gl.renderer.OffscreenRenderer;
import me.piitex.engine.ui.color.Color;
import org.jetbrains.skia.Bitmap;
import org.jetbrains.skia.Image;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConsoleLogTest {
    private ConsoleLog console;
    private final List<String> commands = new ArrayList<>();

    @BeforeEach
    void setUp() {
        console = new ConsoleLog(300, 200);
        console.setPosition(0, 0);
        console.onCommand(commands::add);
    }

    @AfterEach
    void tearDown() {
        console.dispose();
    }

    private void enter(String text) {
        console.getInput().setText(text);
        console.getInput().onKeyPressed(GLFW.GLFW_KEY_ENTER, GLFW.GLFW_PRESS, 0);
    }

    private void key(int key) {
        console.getInput().onKeyPressed(key, GLFW.GLFW_PRESS, 0);
    }

    @Test
    void linesAreKeptInOrderWithTheirText() {
        console.println("one");
        console.warn("two");
        console.error("three");
        assertEquals(3, console.getLineCount());
        assertEquals("one\ntwo\nthree", console.getText());
    }

    @Test
    void aChatLineShowsItsSenderBeforeTheMessage() {
        console.chat("Ana", "anyone seen the key?", Color.CYAN);
        assertEquals("Ana: anyone seen the key?", console.getText());
    }

    @Test
    void theOldestLinesGoWhenThereAreTooMany() {
        console.setMaxLines(3);
        for (int i = 1; i <= 5; i++) console.println("line " + i);
        assertEquals(3, console.getLineCount());
        assertEquals("line 3\nline 4\nline 5", console.getText());
        console.setMaxLines(2);
        assertEquals("line 4\nline 5", console.getText());
    }

    @Test
    void clearEmptiesTheLog() {
        console.println("x");
        console.clear();
        assertEquals(0, console.getLineCount());
        assertEquals(0, console.getRowCount());
    }

    @Test
    void aShortLineIsOneRowAndALongOneWraps() {
        console.println("short");
        assertEquals(1, console.getRowCount());
        console.println("the quick brown fox jumps over the lazy dog and keeps running across the whole field until it tires");
        assertTrue(console.getRowCount() >= 4, "wrapped to several rows at 300 points: " + console.getRowCount());
    }

    @Test
    void newlinesStartNewRows() {
        console.println("a\nb\n\nc");
        assertEquals(4, console.getRowCount());
    }

    @Test
    void aWordWiderThanTheRowIsBrokenRatherThanClipped() {
        console.println("x".repeat(400));
        assertTrue(console.getRowCount() > 1);
    }

    @Test
    void aNarrowerConsoleWrapsMore() {
        console.println("the quick brown fox jumps over the lazy dog");
        int wide = console.getRowCount();
        console.setWidth(120);
        assertTrue(console.getRowCount() > wide);
    }

    @Test
    void theMinimumLevelHidesQuieterLinesButKeepsThem() {
        console.debug("d");
        console.info("i");
        console.warn("w");
        console.error("e");
        assertEquals(4, console.getRowCount());
        console.setMinLevel(ConsoleLog.Level.WARN);
        assertEquals(2, console.getRowCount());
        assertEquals(4, console.getLineCount());
        console.setMinLevel(ConsoleLog.Level.DEBUG);
        assertEquals(4, console.getRowCount());
    }

    @Test
    void timestampsStartEachLineWhenAsked() {
        console.println("hello");
        console.setShowTimestamps(true);
        assertTrue(console.getText().matches("\\d\\d:\\d\\d:\\d\\d hello"), console.getText());
        console.setShowTimestamps(false);
        assertEquals("hello", console.getText());
    }

    @Test
    void enterRunsTheCommandEchoesItAndEmptiesTheField() {
        enter("  help me ");
        assertEquals(List.of("help me"), commands);
        assertEquals("", console.getInput().getText());
        assertEquals("> help me", console.getText());
    }

    @Test
    void echoCanBeTurnedOff() {
        console.setEchoCommands(false);
        enter("quiet");
        assertEquals(List.of("quiet"), commands);
        assertEquals(0, console.getLineCount());
    }

    @Test
    void anEmptyCommandIsNotRun() {
        enter("   ");
        assertTrue(commands.isEmpty());
        assertEquals(0, console.getLineCount());
    }

    @Test
    void upAndDownWalkThroughTheCommandsAndBackToTheDraft() {
        enter("first");
        enter("second");
        console.getInput().setText("draft");
        key(GLFW.GLFW_KEY_UP);
        assertEquals("second", console.getInput().getText());
        key(GLFW.GLFW_KEY_UP);
        assertEquals("first", console.getInput().getText());
        key(GLFW.GLFW_KEY_UP); // no older command: stays
        assertEquals("first", console.getInput().getText());
        key(GLFW.GLFW_KEY_DOWN);
        assertEquals("second", console.getInput().getText());
        key(GLFW.GLFW_KEY_DOWN);
        assertEquals("draft", console.getInput().getText());
        key(GLFW.GLFW_KEY_DOWN); // already at the draft
        assertEquals("draft", console.getInput().getText());
    }

    @Test
    void repeatingTheLastCommandDoesNotFillTheHistory() {
        enter("again");
        enter("again");
        assertEquals(List.of("again"), console.getHistory());
        enter("other");
        assertEquals(List.of("other", "again"), console.getHistory());
    }

    @Test
    void upWithNoHistoryLeavesTheTextAlone() {
        console.getInput().setText("typing");
        key(GLFW.GLFW_KEY_UP);
        assertEquals("typing", console.getInput().getText());
    }

    @Test
    void tabCompletesAUniqueMatch() {
        console.setCompleter(prefix -> List.of("status").stream().filter(c -> c.startsWith(prefix)).toList());
        console.getInput().setText("sta");
        key(GLFW.GLFW_KEY_TAB);
        assertEquals("status", console.getInput().getText());
    }

    @Test
    void tabFillsInWhatSeveralMatchesShareAndListsThem() {
        List<String> all = List.of("start", "status", "stop");
        console.setCompleter(prefix -> all.stream().filter(c -> c.startsWith(prefix)).toList());
        console.getInput().setText("sta");
        key(GLFW.GLFW_KEY_TAB);
        assertEquals("sta", console.getInput().getText(), "start and status share only what was typed");
        assertTrue(console.getText().contains("start") && console.getText().contains("status"));
        assertFalse(console.getText().contains("stop"));

        console.clear();
        console.getInput().setText("s");
        key(GLFW.GLFW_KEY_TAB);
        assertEquals("st", console.getInput().getText());
    }

    @Test
    void tabWithNoCompleterIsLeftToTheField() {
        assertFalse(console.getInput().wantsTab());
        console.setCompleter(prefix -> List.of());
        assertTrue(console.getInput().wantsTab());
        console.getInput().setText("x");
        key(GLFW.GLFW_KEY_TAB);
        assertEquals("x", console.getInput().getText());
    }

    @Test
    void theLogFollowsTheNewestLineUntilScrolledBack() {
        for (int i = 0; i < 60; i++) console.println("line " + i);
        assertTrue(console.isAtBottom());
        console.onScroll(0, 2);
        assertFalse(console.isAtBottom());
        console.scrollToBottom();
        assertTrue(console.isAtBottom());
    }

    @Test
    void scrollingBackStopsAtTheOldestLine() {
        for (int i = 0; i < 60; i++) console.println("line " + i);
        for (int i = 0; i < 500; i++) console.onScroll(0, 5);
        assertFalse(console.isAtBottom());
        console.onScroll(0, -1000);
        assertTrue(console.isAtBottom());
    }

    @Test
    void pageKeysScrollTheLog() {
        for (int i = 0; i < 60; i++) console.println("line " + i);
        key(GLFW.GLFW_KEY_PAGE_UP);
        assertFalse(console.isAtBottom());
        key(GLFW.GLFW_KEY_PAGE_DOWN);
        assertTrue(console.isAtBottom());
    }

    @Test
    void aReadOnlyLogHasNoCommandLine() {
        console.setInputVisible(false);
        assertFalse(console.isInputVisible());
        assertFalse(console.getInput().isEnabled());
        console.setInputVisible(true);
        assertTrue(console.getInput().isEnabled());
    }

    @Test
    void theCommandLineSitsAtTheBottomAndFollowsResizes() {
        assertEquals(170, console.getInput().getY(), 0.001);
        assertEquals(300, console.getInput().getWidth(), 0.001);
        console.setHeight(400);
        console.setWidth(500);
        assertEquals(370, console.getInput().getY(), 0.001);
        assertEquals(500, console.getInput().getWidth(), 0.001);
    }

    @Test
    void linesCanBeAddedFromManyThreads() throws InterruptedException {
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            Thread thread = new Thread(() -> {
                for (int i = 0; i < 200; i++) console.println("msg");
            });
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) thread.join();
        assertEquals(800, console.getLineCount());
    }

    @Test
    void theLogIsDrawnWithItsLevelColors() {
        console.getStyling().setBackgroundColor(Color.BLACK);
        console.setTextColor(Color.WHITE);
        console.println("white text on black");
        console.error("an error line");
        console.chat("Ana", "hello", Color.CYAN);
        console.setShowTimestamps(true);
        try (OffscreenRenderer r = new OffscreenRenderer(300, 200, 1f)) {
            r.begin();
            console.render(r);
            r.end();
            try (Image img = r.makeImage()) {
                Bitmap bmp = new Bitmap();
                bmp.allocPixels(img.getImageInfo());
                img.readPixels(bmp);
                boolean text = false, red = false;
                for (int y = 0; y < 100; y++) {
                    for (int x = 0; x < 300; x++) {
                        int p = bmp.getColor(x, y);
                        int rr = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
                        if (rr > 200 && g > 200 && b > 200) text = true;
                        if (rr > 180 && g < 120 && b < 120) red = true;
                    }
                }
                assertTrue(text, "some white text was drawn");
                assertTrue(red, "the error line is red");
                bmp.close();
            }
        }
    }
}
