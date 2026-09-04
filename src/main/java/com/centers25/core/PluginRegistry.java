package com.centers25.core;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class PluginRegistry {
    private final File file;
    private final Map<String, ManagedPlugin> plugins = new LinkedHashMap<>();

    PluginRegistry(CorePlugin core) {
        file = new File(core.getDataFolder(), "plugins.yml");
        if (!file.exists()) core.saveResource("plugins.yml", false);
        load();
    }

    synchronized void load() {
        plugins.clear();
        ConfigurationSection root = YamlConfiguration.loadConfiguration(file).getConfigurationSection("plugins");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            ConfigurationSection value = root.getConfigurationSection(id);
            if (value == null) continue;
            try {
                ManagedPlugin plugin = validated(id, value.getString("repository", ""), value.getString("asset", ""),
                        value.getString("plugin", id), value.getString("file", value.getString("asset", "")),
                        value.getString("release", ""));
                plugins.put(plugin.id(), plugin);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    synchronized ManagedPlugin add(String id, String repository, String asset, String plugin, String target) {
        ManagedPlugin value = validated(id, repository, asset, plugin, target, "");
        if (plugins.containsKey(value.id())) throw new IllegalArgumentException("Plugin id already exists");
        plugins.put(value.id(), value);
        save();
        return value;
    }

    synchronized ManagedPlugin get(String id) {
        ManagedPlugin value = plugins.get(id.toLowerCase());
        if (value == null) throw new IllegalArgumentException("Unknown plugin: " + id);
        return value;
    }

    synchronized List<ManagedPlugin> all() {
        return plugins.values().stream().sorted(Comparator.comparing(ManagedPlugin::id)).toList();
    }

    synchronized void release(String id, String release) {
        plugins.put(id, get(id).withRelease(release));
        save();
    }

    synchronized void remove(String id) {
        plugins.remove(get(id).id());
        save();
    }

    private ManagedPlugin validated(String id, String repository, String asset, String plugin, String target, String release) {
        id = id.toLowerCase();
        if (!id.matches("[a-z0-9][a-z0-9_-]{0,31}")) throw new IllegalArgumentException("Invalid plugin id");
        if (!repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) throw new IllegalArgumentException("Repository must be owner/name");
        if (!jar(asset)) throw new IllegalArgumentException("Invalid release asset name");
        if (!plugin.matches("[A-Za-z0-9_.-]+")) throw new IllegalArgumentException("Invalid Bukkit plugin name");
        if (!jar(target)) throw new IllegalArgumentException("Invalid target file name");
        return new ManagedPlugin(id, repository, asset, plugin, target, release);
    }

    private boolean jar(String value) {
        return value != null && value.length() <= 128 && value.toLowerCase().endsWith(".jar")
                && !value.contains("/") && !value.contains("\\") && !value.equals(".jar");
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (ManagedPlugin plugin : new ArrayList<>(plugins.values())) {
            String path = "plugins." + plugin.id() + ".";
            yaml.set(path + "repository", plugin.repository());
            yaml.set(path + "asset", plugin.asset());
            yaml.set(path + "plugin", plugin.plugin());
            yaml.set(path + "file", plugin.file());
            if (!plugin.release().isBlank()) yaml.set(path + "release", plugin.release());
        }
        try {
            yaml.save(file);
        } catch (IOException error) {
            throw new IllegalStateException("Could not save plugin registry", error);
        }
    }
}
