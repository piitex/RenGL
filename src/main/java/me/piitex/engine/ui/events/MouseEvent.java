package me.piitex.engine.ui.events;

public class MouseEvent {
    private final double mouseX, mouseY;

    public MouseEvent(double mouseX, double mouseY) {
        this.mouseX = mouseX;
        this.mouseY = mouseY;
    }

    public double getMouseX() {
        return mouseX;
    }

    public double getMouseY() {
        return mouseY;
    }
}
