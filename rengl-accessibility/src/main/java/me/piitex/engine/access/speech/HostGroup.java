package me.piitex.engine.access.speech;

import me.piitex.engine.Window;
import me.piitex.engine.access.AccessibilityHost;
import me.piitex.engine.ui.Element;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;

/**
 * Passes every accessibility event to each of several hosts, so a window can speak for itself and be read by a screen
 * reader at once. One that fails does not stop the others.
 */
public final class HostGroup implements AccessibilityHost {
    private static final Logger log = LogManager.getLogger("Engine");

    private final List<AccessibilityHost> hosts;

    public HostGroup(AccessibilityHost... hosts) {
        this.hosts = List.of(hosts);
    }

    public List<AccessibilityHost> getHosts() {
        return hosts;
    }

    /** The first member of this type, or null. */
    public <T extends AccessibilityHost> T find(Class<T> type) {
        for (AccessibilityHost host : hosts) if (type.isInstance(host)) return type.cast(host);
        return null;
    }

    @Override
    public void focusChanged(Window window, Element previous, Element now, boolean byKeyboard) {
        for (AccessibilityHost host : hosts) {
            try {
                host.focusChanged(window, previous, now, byKeyboard);
            } catch (RuntimeException e) {
                log.warn("An accessibility host failed", e);
            }
        }
    }

    @Override
    public void valueChanged(Window window, Element element) {
        for (AccessibilityHost host : hosts) {
            try {
                host.valueChanged(window, element);
            } catch (RuntimeException e) {
                log.warn("An accessibility host failed", e);
            }
        }
    }

    @Override
    public void announce(Window window, String text, boolean urgent) {
        for (AccessibilityHost host : hosts) {
            try {
                host.announce(window, text, urgent);
            } catch (RuntimeException e) {
                log.warn("An accessibility host failed", e);
            }
        }
    }

    @Override
    public void close() {
        for (AccessibilityHost host : hosts) {
            try {
                host.close();
            } catch (RuntimeException e) {
                log.warn("An accessibility host failed to close", e);
            }
        }
    }
}
