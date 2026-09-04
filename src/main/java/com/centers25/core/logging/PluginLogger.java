package com.centers25.core.logging;

import java.util.function.Supplier;

public interface PluginLogger {
    boolean debugEnabled();

    void debug(String message);

    default void debug(Supplier<String> message) {
        if (debugEnabled()) debug(message.get());
    }

    void info(String message);

    void warn(String message);

    void warn(String message, Throwable error);

    void error(String message);

    void error(String message, Throwable error);
}
