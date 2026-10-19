package me.piitex.engine.ui.overlays;

import me.piitex.engine.ui.text.Font;
import me.piitex.engine.utils.flags.GeneratedClass;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
class TextAreaCodeEditingTest {
    private static TextAreaOverlay code(String text) {
        TextAreaOverlay area = new TextAreaOverlay(400, 200);
        area.getOptions().setIndentSize(4);
        area.getOptions().setAutoIndent(true);
        area.setText(text);
        return area;
    }

    private static void press(TextAreaOverlay area, int key, int mods) {
        area.onKeyPressed(key, GLFW.GLFW_PRESS, mods);
    }

    private static void type(TextAreaOverlay area, String text) {
        text.codePoints().forEach(area::onCharTyped);
    }

    private static void selectAll(TextAreaOverlay area) {
        press(area, GLFW.GLFW_KEY_A, GLFW.GLFW_MOD_CONTROL);
    }

    @Test
    void tabIsLeftToFocusUnlessThereIsAnIndentSize() {
        TextAreaOverlay plain = new TextAreaOverlay(400, 200);
        assertFalse(plain.wantsTab());
        press(plain, GLFW.GLFW_KEY_TAB, 0);
        assertEquals("", plain.getText(), "with no indent size a Tab does nothing here");
        assertTrue(code("").wantsTab());
    }

    @Test
    void tabGoesToTheNextStop() {
        TextAreaOverlay area = code("ab");
        press(area, GLFW.GLFW_KEY_TAB, 0);
        assertEquals("ab  ", area.getText());
        press(area, GLFW.GLFW_KEY_TAB, 0);
        assertEquals("ab      ", area.getText());
    }

    private static void backspace(TextAreaOverlay area) {
        press(area, GLFW.GLFW_KEY_BACKSPACE, 0);
    }

    @Test
    void backspaceOnALineOfOnlySpacesTakesTheWholeLine() {
        TextAreaOverlay area = code("a();\n        \nb();");
        area.setCaretIndex(13);
        backspace(area);
        assertEquals("a();\nb();", area.getText());
        assertEquals(4, area.getCaretIndex(), "the caret ends the line before");
        area.undo();
        assertEquals("a();\n        \nb();", area.getText(), "one step to undo");
    }

    @Test
    void backspaceOnAnEmptyLineTakesTheLine() {
        TextAreaOverlay area = code("a();\n\nb();");
        area.setCaretIndex(5);
        backspace(area);
        assertEquals("a();\nb();", area.getText());
        assertEquals(4, area.getCaretIndex());
    }

    @Test
    void backspaceOnABlankFirstLineTakesTheSpaces() {
        TextAreaOverlay area = code("    \nb();");
        area.setCaretIndex(4);
        backspace(area);
        assertEquals("\nb();", area.getText());
        assertEquals(0, area.getCaretIndex());
    }

    @Test
    void backspaceInTheIndentGoesBackToTheStopBefore() {
        TextAreaOverlay area = code("    a();\n          b();");
        area.setCaretIndex(9 + 10);
        backspace(area);
        assertEquals("    a();\n        b();", area.getText(), "10 spaces go to 8, not 9");
        backspace(area);
        assertEquals("    a();\n    b();", area.getText());
        backspace(area);
        assertEquals("    a();\nb();", area.getText());
        assertEquals(9, area.getCaretIndex());
        backspace(area);
        assertEquals("    a();b();", area.getText(), "at the start of a line it is the plain Backspace");
    }

    @Test
    void backspaceInTheIndentLinesUpWithEachParentInTurn() {
        String text = "if (a) {\n    if (b) {\n       x();\n    }\n}";
        TextAreaOverlay area = code(text);
        area.setCaretIndex(text.indexOf("x();"));
        backspace(area);
        assertEquals("if (a) {\n    if (b) {\n    x();\n    }\n}", area.getText(), "7 lines up with the inner if");
        backspace(area);
        assertEquals("if (a) {\n    if (b) {\nx();\n    }\n}", area.getText(), "then the outer one");
        backspace(area);
        assertEquals("if (a) {\n    if (b) {x();\n    }\n}", area.getText(), "and then it is the plain Backspace");
    }

    @Test
    void backspaceUsesTheParentsWhateverTheIndentWidth() {
        String text = "void f() {\n  if (b) {\n    x();\n  }\n}";
        TextAreaOverlay area = code(text);
        area.setCaretIndex(text.indexOf("x();"));
        backspace(area);
        assertEquals("void f() {\n  if (b) {\n  x();\n  }\n}", area.getText(), "2 spaces is the if, not a multiple of 4");
        backspace(area);
        assertEquals("void f() {\n  if (b) {\nx();\n  }\n}", area.getText());
    }

    @Test
    void backspaceIgnoresBracketsInStringsAndTheOnesThatAreClosed() {
        String text = "a(\"{\"); b(() -> {});\n        x();";
        TextAreaOverlay area = code(text);
        area.setCaretIndex(text.indexOf("x();"));
        backspace(area);
        assertEquals("a(\"{\"); b(() -> {});\n    x();", area.getText(), "nothing is open, so the stop is a multiple of 4");
    }

    @Test
    void backspaceAfterTextIsStillOneCharacter() {
        TextAreaOverlay area = code("    ab  ");
        area.setCaretIndex(8);
        backspace(area);
        assertEquals("    ab ", area.getText());
        TextAreaOverlay plain = new TextAreaOverlay(400, 200);
        plain.setText("a\n    ");
        plain.setCaretIndex(6);
        backspace(plain);
        assertEquals("a\n   ", plain.getText(), "with no indent size it is a plain box");
    }

    @Test
    void shiftTabTakesOneLevelOffTheLine() {
        TextAreaOverlay area = code("        x");
        press(area, GLFW.GLFW_KEY_TAB, GLFW.GLFW_MOD_SHIFT);
        assertEquals("    x", area.getText());
        area.setText("  y");
        press(area, GLFW.GLFW_KEY_TAB, GLFW.GLFW_MOD_SHIFT);
        assertEquals("y", area.getText(), "less than a level is taken off whole");
    }

    @Test
    void tabOnASelectionIndentsEachLineItTouches() {
        TextAreaOverlay area = code("a\n\nb\nc");
        selectAll(area);
        press(area, GLFW.GLFW_KEY_TAB, 0);
        assertEquals("    a\n\n    b\n    c", area.getText(), "a blank line is left blank");
        assertTrue(area.hasSelection(), "the selection stays so it can be indented again");
        assertEquals("    a\n\n    b\n    c", area.getSelectedText());
        press(area, GLFW.GLFW_KEY_TAB, GLFW.GLFW_MOD_SHIFT);
        assertEquals("a\n\nb\nc", area.getText());
    }

    @Test
    void anIndentIsOneUndo() {
        TextAreaOverlay area = code("a\nb");
        selectAll(area);
        press(area, GLFW.GLFW_KEY_TAB, 0);
        area.undo();
        assertEquals("a\nb", area.getText());
    }

    @Test
    void enterKeepsTheIndentOfTheLine() {
        TextAreaOverlay area = code("    int x = 1;");
        press(area, GLFW.GLFW_KEY_ENTER, 0);
        type(area, "y");
        assertEquals("    int x = 1;\n    y", area.getText());
    }

    @Test
    void enterAfterABraceGoesOneLevelIn() {
        TextAreaOverlay area = code("void f() {");
        press(area, GLFW.GLFW_KEY_ENTER, 0);
        type(area, "x");
        assertEquals("void f() {\n    x", area.getText());
    }

    @Test
    void enterBetweenBracesPutsTheClosingOneOnItsOwnLine() {
        TextAreaOverlay area = code("void f() {}");
        press(area, GLFW.GLFW_KEY_LEFT, 0);
        press(area, GLFW.GLFW_KEY_ENTER, 0);
        type(area, "x");
        assertEquals("void f() {\n    x\n}", area.getText());
        assertEquals(2, area.getCursorLine());
    }

    @Test
    void aClosingBraceOnABlankLineTakesAnIndentOff() {
        TextAreaOverlay area = code("void f() {\n    x();\n    ");
        type(area, "}");
        assertEquals("void f() {\n    x();\n}", area.getText());
        area.setText("a = {");
        type(area, "}");
        assertEquals("a = {}", area.getText(), "a brace after other text is left where it is");
    }

    @Test
    void withoutAutoIndentEnterIsPlain() {
        TextAreaOverlay area = code("    x {");
        area.getOptions().setAutoIndent(false);
        press(area, GLFW.GLFW_KEY_ENTER, 0);
        assertEquals("    x {\n", area.getText());
    }

    @Test
    void linesAndColumnsCountFromOne() {
        TextAreaOverlay area = code("one\ntwo\nthree");
        assertEquals(3, area.getLineCount());
        assertEquals(3, area.getCursorLine());
        assertEquals(6, area.getCursorColumn());
        area.goToLine(2);
        assertEquals(2, area.getCursorLine());
        assertEquals(1, area.getCursorColumn());
        area.goToLine(99);
        assertEquals(3, area.getCursorLine(), "a line past the end goes to the last");
        area.goToLine(0);
        assertEquals(1, area.getCursorLine());
        assertEquals(1, code("").getLineCount());
        assertEquals(2, code("a\n").getLineCount());
    }

    @Test
    void goingToALineScrollsItIntoView() {
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 200; i++) many.append("line ").append(i).append('\n');
        TextAreaOverlay area = code(many.toString());
        area.goToLine(1);
        area.scrollTo(0);
        area.goToLine(150);
        assertTrue(area.getScrollOffsetY() > 0, "the box scrolled down to it");
        double shown = area.getScrollOffsetY();
        area.goToLine(150);
        assertEquals(shown, area.getScrollOffsetY(), "a line already in view doesn't scroll again");
    }

    @Test
    void lineNumbersAndMarksCanBeTurnedOn() {
        TextAreaOverlay area = code("a\nb");
        area.setLineNumbers(true);
        assertTrue(area.isLineNumbers());
        area.setLineMarks(Map.of(2, new me.piitex.engine.ui.color.Color(1f, 0f, 0f, 0.3f)));
        assertEquals(0.0, area.getMaxScrollY(), "laying out with a gutter works");
        area.setLineMarks(null);
    }

    @Test
    void theMonospaceFontHasLettersOfOneWidth() {
        try (org.jetbrains.skia.Font font = new org.jetbrains.skia.Font(Font.monospace(), 20f)) {
            assertEquals(font.measureTextWidth("i"), font.measureTextWidth("W"), 0.01f);
        }
    }

    @Test
    void aKeyFilterGetsKeysFirstAndCanUseThem() {
        TextAreaOverlay area = code("");
        java.util.List<String> seen = new java.util.ArrayList<>();
        area.setKeyFilter((key, mods) -> {
            seen.add(key + "/" + mods);
            return key == GLFW.GLFW_KEY_S && mods == GLFW.GLFW_MOD_CONTROL;
        });
        press(area, GLFW.GLFW_KEY_S, GLFW.GLFW_MOD_CONTROL);
        press(area, GLFW.GLFW_KEY_TAB, 0);
        assertEquals(2, seen.size());
        assertEquals("    ", area.getText(), "a key the filter doesn't use is the box's, a key it does isn't");
        area.onKeyPressed(GLFW.GLFW_KEY_S, GLFW.GLFW_RELEASE, GLFW.GLFW_MOD_CONTROL);
        assertEquals(2, seen.size(), "a release isn't offered");
        area.setKeyFilter(null);
        press(area, GLFW.GLFW_KEY_TAB, 0);
        assertEquals("        ", area.getText());
    }

    @Test
    void theCaretCanBeReadAndPlaced() {
        TextAreaOverlay area = code("abc\ndef");
        area.setCaretIndex(5);
        assertEquals(5, area.getCaretIndex());
        assertEquals(2, area.getCursorLine());
        area.setCaretIndex(99);
        assertEquals(7, area.getCaretIndex(), "clamped to the text");
        area.setCaretIndex(-3);
        assertEquals(0, area.getCaretIndex());
        assertFalse(area.hasSelection());
    }

    @Test
    void textCanBeReplacedAsOneStepToUndo() {
        TextAreaOverlay area = code("foo.ba");
        java.util.List<String> changes = new java.util.ArrayList<>();
        area.onTextChanged(changes::add);
        area.setCaretIndex(6);
        area.replaceText(4, 6, "bar()");
        assertEquals("foo.bar()", area.getText());
        assertEquals(9, area.getCaretIndex(), "after what went in");
        assertEquals(java.util.List.of("foo.bar()"), changes);
        area.undo();
        assertEquals("foo.ba", area.getText());
        area.getOptions().setReadOnly(true);
        area.replaceText(0, 3, "x");
        assertEquals("foo.ba", area.getText(), "a read-only box keeps its text");
    }

    @Test
    void aPointOnTheBoxIsAtALine() {
        TextAreaOverlay area = code("one\ntwo\nthree");
        area.setCaretIndex(0);
        double[] first = area.getCaretBounds();
        double pitch = first[2];
        assertTrue(pitch > 0);
        assertEquals(1, area.getLineAt(20, first[1] + pitch / 2));
        assertEquals(2, area.getLineAt(20, first[1] + pitch * 1.6));
        assertEquals(3, area.getLineAt(20, first[1] + pitch * 2.6));
        assertEquals(3, area.getLineAt(20, 5000), "below the text is its last line");
    }

    @Test
    void theCaretSaysWhereItIs() {
        TextAreaOverlay area = code("ab\ncdefgh");
        area.setCaretIndex(0);
        double[] start = area.getCaretBounds();
        area.setCaretIndex(2);
        double[] later = area.getCaretBounds();
        assertEquals(start[1], later[1], 0.01, "the same line");
        assertTrue(later[0] > start[0], "further along");
        area.setCaretIndex(8);
        double[] lower = area.getCaretBounds();
        assertTrue(lower[1] > start[1], "the next line is lower");
        assertEquals(start[2], lower[2], 0.01, "a line is as tall as another");
    }

    @Test
    void aFieldTakesAKeyFilterToo() {
        TextFieldOverlay field = new TextFieldOverlay(200, 30);
        java.util.List<Integer> seen = new java.util.ArrayList<>();
        field.setKeyFilter((key, mods) -> {
            seen.add(key);
            return key == GLFW.GLFW_KEY_ESCAPE;
        });
        field.onCharTyped('7');
        field.onKeyPressed(GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_PRESS, 0);
        field.onKeyPressed(GLFW.GLFW_KEY_BACKSPACE, GLFW.GLFW_PRESS, 0);
        assertEquals(java.util.List.of(GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_KEY_BACKSPACE), seen);
        assertEquals("", field.getText(), "the key the filter didn't use still did its work");
    }

    private static TextAreaOverlay pairing(String text) {
        TextAreaOverlay area = code(text);
        area.getOptions().setAutoCloseBrackets(true);
        return area;
    }

    @Test
    void anOpeningBracketBringsItsClosingOne() {
        TextAreaOverlay area = pairing("run");
        type(area, "(");
        assertEquals("run()", area.getText());
        assertEquals(4, area.getCaretIndex(), "the caret is between the brackets");
        type(area, "x");
        assertEquals("run(x)", area.getText());
    }

    @Test
    void theClosingBracketGoesOverTheOneThatIsThere() {
        TextAreaOverlay area = pairing("f");
        type(area, "(g(1)");
        assertEquals("f(g(1))", area.getText());
        assertEquals(6, area.getCaretIndex());
        type(area, ")");
        assertEquals("f(g(1))", area.getText(), "the last one is gone over too");
        assertEquals(7, area.getCaretIndex());
        type(area, ")");
        assertEquals("f(g(1)))", area.getText(), "with nothing open, a bracket is just typed");
    }

    @Test
    void noClosingBracketIsAddedBeforeAWord() {
        TextAreaOverlay area = pairing("ab");
        area.setCaretIndex(0);
        type(area, "(");
        assertEquals("(ab", area.getText());
    }

    @Test
    void backspaceBetweenAnEmptyPairTakesBoth() {
        TextAreaOverlay area = pairing("run");
        type(area, "(");
        press(area, GLFW.GLFW_KEY_BACKSPACE, 0);
        assertEquals("run", area.getText());
        type(area, "(a");
        press(area, GLFW.GLFW_KEY_BACKSPACE, 0);
        assertEquals("run()", area.getText(), "a pair with something in it loses only what is cut");
        press(area, GLFW.GLFW_KEY_BACKSPACE, 0);
        assertEquals("run", area.getText(), "and once it is empty, both go");
    }

    @Test
    void aBracketPairIsOneStepToUndo() {
        TextAreaOverlay area = pairing("run");
        type(area, "(");
        area.undo();
        assertEquals("run", area.getText());
        assertEquals(3, area.getCaretIndex());
    }

    @Test
    void theCloserCanAddMoreThanTheBracket() {
        TextAreaOverlay area = pairing("run");
        area.setParenCloser((text, at) -> ");");
        type(area, "(");
        assertEquals("run();", area.getText());
        assertEquals(4, area.getCaretIndex());
        press(area, GLFW.GLFW_KEY_BACKSPACE, 0);
        assertEquals("run", area.getText(), "the semicolon that came with the pair goes with it");
        area.setText("run");
        type(area, "(");
        assertEquals("run();", area.getText());
        type(area, ")");
        type(area, ";");
        assertEquals("run();", area.getText(), "typing the semicolon goes over the one that is there");
        assertEquals(6, area.getCaretIndex());
        area.setParenCloser((text, at) -> null);
        area.setText("run");
        type(area, "(");
        assertEquals("run(", area.getText(), "no closer, just the bracket");
    }

    @Test
    void squareAndCurlyBracketsPairToo() {
        TextAreaOverlay area = pairing("int a");
        type(area, "[");
        assertEquals("int a[]", area.getText());
        type(area, "3]");
        assertEquals("int a[3]", area.getText());
        assertEquals(8, area.getCaretIndex(), "the ] went over the one that was there");
        area.setText("");
        type(area, "{");
        assertEquals("{}", area.getText());
        press(area, GLFW.GLFW_KEY_BACKSPACE, 0);
        assertEquals("", area.getText());
    }

    @Test
    void aClosingBracketOnlyGoesOverItsOwnKind() {
        TextAreaOverlay area = pairing("a");
        type(area, "[(");
        assertEquals("a[()]", area.getText());
        type(area, "]");
        assertEquals("a[(])]", area.getText(), "a ] doesn't go over a ) that is next to it");
    }

    @Test
    void quotesPairAndTheClosingOneIsGoneOver() {
        TextAreaOverlay area = pairing("String s = ");
        type(area, "\"");
        assertEquals("String s = \"\"", area.getText());
        type(area, "hi\"");
        assertEquals("String s = \"hi\"", area.getText());
        assertEquals(15, area.getCaretIndex());
        type(area, ";");
        assertEquals("String s = \"hi\";", area.getText());
    }

    @Test
    void anEmptyPairOfQuotesGoesWithBackspace() {
        TextAreaOverlay area = pairing("x = ");
        type(area, "'");
        assertEquals("x = ''", area.getText());
        press(area, GLFW.GLFW_KEY_BACKSPACE, 0);
        assertEquals("x = ", area.getText());
    }

    @Test
    void nothingIsPairedInAStringOrAComment() {
        TextAreaOverlay area = pairing("String s = \"a");
        type(area, "(");
        assertEquals("String s = \"a(", area.getText(), "a bracket in a string is a bracket");
        area.setText("// don");
        type(area, "'");
        assertEquals("// don'", area.getText());
        area.setText("/* see ");
        type(area, "(");
        assertEquals("/* see (", area.getText());
    }

    @Test
    void aQuoteAfterALetterIsAnApostropheAndAThirdOneOpensATextBlock() {
        TextAreaOverlay area = pairing("don");
        type(area, "'");
        assertEquals("don'", area.getText());
        area.setText("");
        type(area, "\"\"\"");
        assertEquals("\"\"\"", area.getText());
    }

    @Test
    void aBracketInAStringDoesNotCountWhenAClosingOneIsGoneOver() {
        TextAreaOverlay area = pairing("f");
        type(area, "(\"(\"");
        assertEquals("f(\"(\")", area.getText());
        assertEquals(5, area.getCaretIndex());
        type(area, ")");
        assertEquals("f(\"(\")", area.getText());
        assertEquals(6, area.getCaretIndex(), "the ) went over the one that is there");
    }

    @Test
    void enterAfterACallLeavesItsSemicolonOnTheLine() {
        TextAreaOverlay area = pairing("run");
        area.setParenCloser((text, at) -> ");");
        type(area, "()");
        press(area, GLFW.GLFW_KEY_ENTER, 0);
        assertEquals("run();\n", area.getText());
        assertEquals(7, area.getCaretIndex());
    }

    @Test
    void anOpeningBracketOrQuoteWrapsTheSelection() {
        TextAreaOverlay area = pairing("call x");
        area.setCaretIndex(6);
        press(area, GLFW.GLFW_KEY_LEFT, GLFW.GLFW_MOD_SHIFT);
        type(area, "(");
        assertEquals("call (x)", area.getText());
        assertTrue(area.hasSelection(), "the selection stays on x, so another pair can go round it");
        type(area, "\"");
        assertEquals("call (\"x\")", area.getText());
        area.undo();
        assertEquals("call (x)", area.getText(), "one step to undo");
    }

    @Test
    void aSelectionIsReplacedByAClosingBracketOrInAString() {
        TextAreaOverlay area = pairing("a \"bc\"");
        area.setCaretIndex(3);
        press(area, GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_MOD_SHIFT);
        type(area, "(");
        assertEquals("a \"(c\"", area.getText(), "inside a string it is only typed");
        area.setText("x y");
        selectAll(area);
        type(area, ")");
        assertEquals(")", area.getText());
    }

    @Test
    void withoutWordWrapALongLineScrollsSideways() {
        TextAreaOverlay area = new TextAreaOverlay(200, 100);
        area.setWordWrap(false);
        area.setText("short\n" + "x".repeat(200));
        assertTrue(area.getMaxScrollX() > 0, "the long line is wider than the box");
        assertEquals(0, area.getScrollOffsetX());
        area.setCaretIndex(area.getText().length());
        assertTrue(area.getScrollOffsetX() > 0, "the caret at the end of the line is brought into view");
        area.setCaretIndex(0);
        assertEquals(0, area.getScrollOffsetX());
        area.scrollByX(30);
        assertEquals(30, area.getScrollOffsetX(), 0.01);
        area.scrollByX(100000);
        assertEquals(area.getMaxScrollX(), area.getScrollOffsetX(), 0.01, "it stops at the end of the line");
        area.setWordWrap(true);
        assertEquals(0, area.getMaxScrollX());
    }

    @Test
    void aRangeCanBeSelectedFromCode() {
        TextAreaOverlay area = code("find the needle here");
        area.setSelection(9, 15);
        assertTrue(area.hasSelection());
        assertEquals("needle", area.getSelectedText());
        assertEquals(15, area.getCaretIndex());
        area.setSelection(3, 3);
        assertFalse(area.hasSelection());
        area.setSelection(-5, 1000);
        assertEquals("find the needle here", area.getSelectedText(), "kept to the text");
    }

    @Test
    void bracketsAreLeftAloneUnlessAskedFor() {
        TextAreaOverlay area = code("run");
        type(area, "()");
        assertEquals("run()", area.getText());
        area.setCaretIndex(4);
        type(area, ")");
        assertEquals("run())", area.getText());
    }

    @Test
    void highlightsCanBeSetInAnyOrderAndCleared() {
        TextAreaOverlay area = code("one two one\nthree one");
        var wash = new me.piitex.engine.ui.color.Color(1f, 1f, 0f, 0.3f);
        area.setHighlights(java.util.List.of(new int[]{18, 21}, new int[]{0, 3}, new int[]{8, 11}, new int[]{5, 5}), wash);
        assertEquals(0.0, area.getMaxScrollY(), "laying out with highlights works");
        assertEquals(3, area.getHighlightCount(), "an empty range is left out");
        area.setHighlights(null, null);
        assertEquals(0, area.getHighlightCount());
        area.setHighlights(java.util.List.of(), wash);
    }

    /** Double clicks on the middle of the character at {@code index}, or {@code pastEnd} pixels right of its left edge when that is above 0. */
    private static void doubleClickOn(TextAreaOverlay area, int index, double pastEnd) {
        area.setCaretIndex(index);
        double[] at = area.getCaretBounds();
        area.setCaretIndex(Math.min(index + 1, area.getText().length()));
        double[] after = area.getCaretBounds();
        double x = pastEnd > 0 ? at[0] + pastEnd : (at[0] + after[0]) / 2;
        double y = at[1] + at[2] / 2;
        area.onDragStart(x, y);
        area.onDragEnd(x, y);
        area.onDragStart(x, y);
        area.onDragEnd(x, y);
    }

    @Test
    void theCharacterUnderAPointIsFoundWhereThereIsOne() {
        TextAreaOverlay area = code("foo(bar);\n\nx");
        for (int i : new int[]{0, 1, 3, 4, 8}) {
            area.setCaretIndex(i);
            double[] at = area.getCaretBounds();
            area.setCaretIndex(i + 1);
            double[] after = area.getCaretBounds();
            assertEquals(i, area.getCharAt((at[0] + after[0]) / 2, at[1] + at[2] / 2), "the middle of character " + i);
        }
        area.setCaretIndex(9);
        double[] end = area.getCaretBounds();
        assertEquals(-1, area.getCharAt(end[0] + 40, end[1] + end[2] / 2), "past the end of the line");
        area.setCaretIndex(10);
        double[] empty = area.getCaretBounds();
        assertEquals(-1, area.getCharAt(empty[0] + 5, empty[1] + empty[2] / 2), "on an empty line");
        assertEquals(-1, area.getCharAt(end[0] - 100, end[1] + end[2] / 2), "left of the text");
        assertEquals(-1, area.getCharAt(end[0], empty[1] + 400), "under the text");
        assertEquals(-1, area.getCharAt(end[0], -50), "over the text");
    }

    @Test
    void theBoundsAtAPlaceAreThoseTheCaretHasThere() {
        TextAreaOverlay area = code("foo(bar);\nnext");
        for (int i : new int[]{0, 4, 9, 10, 14}) {
            area.setCaretIndex(i);
            assertArrayEquals(area.getCaretBounds(), area.getBoundsAt(i), "at " + i);
        }
        area.setCaretIndex(0);
        assertArrayEquals(area.getBoundsAt(999), new double[]{area.getBoundsAt(14)[0], area.getBoundsAt(14)[1], area.getBoundsAt(14)[2]}, "past the end is the end");
        assertTrue(area.getBoundsAt(10)[1] > area.getBoundsAt(0)[1], "a later line is lower");
    }

    @Test
    void doubleClickStopsAtBrackets() {
        TextAreaOverlay area = code("foo(bar, baz);\nnext");
        doubleClickOn(area, 1, 0);
        assertEquals("foo", area.getSelectedText(), "up to the bracket");
        doubleClickOn(area, 5, 0);
        assertEquals("bar", area.getSelectedText(), "from the bracket to the comma");
        doubleClickOn(area, 3, 0);
        assertEquals("(", area.getSelectedText(), "a bracket on its own");
        doubleClickOn(area, 7, 0);
        assertEquals(",", area.getSelectedText());
        doubleClickOn(area, 13, 0);
        assertEquals(";", area.getSelectedText());
        doubleClickOn(area, 8, 0);
        assertEquals(" ", area.getSelectedText(), "a run of spaces");
    }

    @Test
    void doubleClickPastTheEndOfALineTakesTheLine() {
        TextAreaOverlay area = code("    foo(bar);\nnext\n\nlast");
        doubleClickOn(area, 12, 80);
        assertEquals("    foo(bar);", area.getSelectedText(), "the whole line, not its break");
        doubleClickOn(area, 17, 80);
        assertEquals("next", area.getSelectedText());
        doubleClickOn(area, 22, 80);
        assertEquals("last", area.getSelectedText());
    }
}
