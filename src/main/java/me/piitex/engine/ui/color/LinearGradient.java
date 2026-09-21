package me.piitex.engine.ui.color;

public class LinearGradient implements Paint {
    private final Color startColor;
    private final Color endColor;

    public LinearGradient(Color startColor, Color endColor) {
        this.startColor = startColor;
        this.endColor = endColor;
    }

    public Color getStartColor() {
        return startColor;
    }

    public Color getEndColor() {
        return endColor;
    }
}