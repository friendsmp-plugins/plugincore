package com.centers25.core.logging;

import org.bukkit.plugin.Plugin;

import java.nio.file.Path;

public interface LoggingService {
    PluginLogger logger(Plugin owner);

    boolean debugEnabled();

    void setDebugEnabled(boolean enabled);

    Path directory();

    Path currentFile(Plugin owner);
}
