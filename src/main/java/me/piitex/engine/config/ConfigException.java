package me.piitex.engine.config;

/**
 * Thrown when configuration text cannot be parsed. The message includes the offending line number.
 */
public class ConfigException extends RuntimeException {
    public ConfigException(int line, String message) {
        super("Config line " + line + ": " + message);
    }
}
