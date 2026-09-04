package com.centers25.core.logging;

import org.bukkit.plugin.Plugin;

public final class PluginLogs {
    private PluginLogs() {
    }

    public static PluginLogger get(Plugin owner) {
        LoggingService service = owner.getServer().getServicesManager().load(LoggingService.class);
        if (service == null) {
            throw new IllegalStateException("Plugin Core logging service is unavailable");
        }
        return service.logger(owner);
    }
}
