package me.piitex.engine.ui.events;

public class MouseClickEvent extends MouseEvent {
    private final int button, action;

    public MouseClickEvent(double x, double y, int button, int action) {
        super(x, y);
        this.button = button;
        this.action = action;
    }

    public int getButton() {
        return button;
    }

    public int getAction() {
        return action;
    }
}
