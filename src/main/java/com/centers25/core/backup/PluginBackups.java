package com.centers25.core.backup;

import org.bukkit.plugin.Plugin;

public final class PluginBackups {
    private PluginBackups() {
    }

    public static DataBackupService get(Plugin owner) {
        DataBackupService service = owner.getServer().getServicesManager().load(DataBackupService.class);
        if (service == null) {
            throw new IllegalStateException("Plugin Core backup service is unavailable");
        }
        return service;
    }
}
