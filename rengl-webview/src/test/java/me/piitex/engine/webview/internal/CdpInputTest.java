package me.piitex.engine.webview.internal;

import me.piitex.engine.ui.CursorMode;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The protocol calls the engine's input becomes. Needs no browser, so it runs everywhere. */
class CdpInputTest {
    private final List<String> calls = new ArrayList<>();
    private final CdpInput input = new CdpInput((method, params) -> calls.add(method + " " + params));

    @Test
    void aClickIsAPressAndARelease() {
        input.pointerButton(10, 20, 0, true, 0);
        input.pointerButton(10, 20, 0, false, 0);
        assertEquals(2, calls.size());
        assertTrue(calls.get(0).contains("\"type\":\"mousePressed\"") && calls.get(0).contains("\"button\":\"left\"")
                && calls.get(0).contains("\"clickCount\":1") && calls.get(0).contains("\"buttons\":1"), calls.get(0));
        assertTrue(calls.get(1).contains("\"type\":\"mouseReleased\"") && calls.get(1).contains("\"buttons\":0"), calls.get(1));
    }

    @Test
    void aSecondPressAtOnceInTheSamePlaceIsADoubleClick() {
        input.pointerButton(10, 20, 0, true, 0);
        input.pointerButton(10, 20, 0, false, 0);
        input.pointerButton(12, 21, 0, true, 0);
        assertTrue(calls.get(2).contains("\"clickCount\":2"), calls.get(2));
        input.pointerButton(12, 21, 0, false, 0);
        input.pointerButton(300, 300, 0, true, 0);
        assertTrue(calls.get(4).contains("\"clickCount\":1"), "a press elsewhere starts again: " + calls.get(4));
    }

    @Test
    void theWheelIsScaledAndFlippedToThePagesDirection() {
        input.pointerScroll(5, 6, 0, -3, 0);
        assertTrue(calls.getFirst().contains("\"deltaY\":300.0") && calls.getFirst().contains("\"deltaX\":0.0"), calls.getFirst());
    }

    @Test
    void aKeyPressIsARawKeyDownAndAReleaseAKeyUp() {
        input.key(GLFW.GLFW_KEY_A, GLFW.GLFW_PRESS, GLFW.GLFW_MOD_CONTROL);
        input.key(GLFW.GLFW_KEY_A, GLFW.GLFW_RELEASE, GLFW.GLFW_MOD_CONTROL);
        assertTrue(calls.get(0).contains("rawKeyDown") && calls.get(0).contains("\"commands\":[\"selectAll\"]")
                && calls.get(0).contains("\"modifiers\":2"), calls.get(0));
        assertTrue(calls.get(1).contains("keyUp"), calls.get(1));
    }

    @Test
    void enterAlsoSendsTheLineBreakAFormNeeds() {
        input.key(GLFW.GLFW_KEY_ENTER, GLFW.GLFW_PRESS, 0);
        assertEquals(2, calls.size());
        assertTrue(calls.get(1).contains("\"type\":\"char\"") && calls.get(1).contains("\\r"), calls.get(1));
    }

    @Test
    void aKeyThePageHasNoUseForSendsNothingAndControlCharactersAreNotTyped() {
        input.key(GLFW.GLFW_KEY_UNKNOWN, GLFW.GLFW_PRESS, 0);
        input.text(0x08);
        input.text(0x7F);
        assertTrue(calls.isEmpty(), calls.toString());
        input.text('é');
        assertEquals(1, calls.size());
        assertTrue(calls.getFirst().contains("\"text\":\"é\""), calls.getFirst());
    }

    @Test
    void compositionCarriesTheCaretAndKeepsItInsideTheText() {
        input.composition("かな", 1);
        assertTrue(calls.getFirst().contains("Input.imeSetComposition") && calls.getFirst().contains("\"selectionStart\":1"), calls.getFirst());
        calls.clear();
        input.composition("", 5);
        assertTrue(calls.getFirst().contains("\"selectionStart\":0"), calls.getFirst());
    }

    @Test
    void quotingEscapesWhatJsonRequires() {
        assertEquals("\"a\\\"b\\\\c\\n\\u0001\"", CdpInput.quote("a\"b\\c\n\u0001"));
    }

    @Test
    void theProbeAnswerIsReadBack() {
        CdpInput.Probe probe = CdpInput.parseProbe("\"pointer|1\"");
        assertEquals(CursorMode.POINTER, probe.cursor());
        assertTrue(probe.editing());
        assertNull(CdpInput.parseProbe("\"default|0\"").cursor());
        assertNull(CdpInput.parseProbe(null));
        assertNull(CdpInput.parseProbe("\"nonsense\""));
        assertTrue(CdpInput.probeScript(12.9, 4.2).contains("(12, 4)"), "whole pixels");
    }
}
