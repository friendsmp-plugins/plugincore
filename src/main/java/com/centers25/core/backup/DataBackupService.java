package com.centers25.core.backup;

import org.bukkit.plugin.Plugin;

import java.nio.file.Path;

public interface DataBackupService {
    void register(Plugin owner);

    void unregister(Plugin owner);

    Path directory();
}
