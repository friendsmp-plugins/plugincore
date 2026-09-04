package com.centers25.core;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;

import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.jar.JarFile;

final class PluginOperations {
    private final CorePlugin core;
    private final PluginRegistry registry;
    private final Set<String> busy = ConcurrentHashMap.newKeySet();

    PluginOperations(CorePlugin core, PluginRegistry registry) {
        this.core = core;
        this.registry = registry;
    }

    CompletableFuture<String> download(String id) {
        return transfer(id, false);
    }

    CompletableFuture<String> update(String id) {
        return transfer(id, true);
    }

    CompletableFuture<String> downloadAll() {
        return all(this::download);
    }

    CompletableFuture<String> updateAll() {
        return all(this::update);
    }

    CompletableFuture<String> reload(String id) {
        return core.main(() -> {
            ManagedPlugin managed = registry.get(id);
            rejectCore(managed);
            Plugin plugin = core.getServer().getPluginManager().getPlugin(managed.plugin());
            Path target = plugins().resolve(managed.file());
            if (plugin == null) {
                if (!Files.exists(target)) return managed.id() + " — not installed";
                plugin = core.getServer().getPluginManager().loadPlugin(target.toFile());
                core.getServer().getPluginManager().enablePlugin(plugin);
                return managed.id() + " — loaded";
            }
            core.getServer().getPluginManager().disablePlugin(plugin);
            core.getServer().getPluginManager().enablePlugin(plugin);
            String staged = Files.exists(updates().resolve(managed.file())) ? "; restart required for staged update" : "";
            return managed.id() + " — reloaded" + staged;
        });
    }

    CompletableFuture<String> reloadAll() {
        return all(this::reload);
    }

    CompletableFuture<String> delete(String id) {
        return core.main(() -> {
            ManagedPlugin managed = registry.get(id);
            rejectCore(managed);
            Plugin plugin = core.getServer().getPluginManager().getPlugin(managed.plugin());
            if (plugin != null && plugin.isEnabled()) core.getServer().getPluginManager().disablePlugin(plugin);
            boolean removed = Files.deleteIfExists(plugins().resolve(managed.file()));
            removed |= Files.deleteIfExists(updates().resolve(managed.file()));
            registry.remove(managed.id());
            return managed.id() + " — removed" + (removed ? "; JAR deleted" : "");
        });
    }

    String list() {
        List<String> lines = new ArrayList<>();
        for (ManagedPlugin managed : registry.all()) {
            Plugin plugin = core.getServer().getPluginManager().getPlugin(managed.plugin());
            String state = plugin == null ? "missing" : plugin.isEnabled() ? "enabled" : "disabled";
            if (Files.exists(updates().resolve(managed.file()))) state += ", staged";
            lines.add("• " + managed.id() + " — " + state + " — " + managed.repository() + " → " + managed.file());
        }
        return lines.isEmpty() ? "No plugins are registered." : String.join("\n", lines);
    }

    private CompletableFuture<String> transfer(String id, boolean updateOnly) {
        ManagedPlugin managed;
        try {
            managed = registry.get(id);
            rejectCore(managed);
        } catch (Exception error) {
            return CompletableFuture.failedFuture(error);
        }
        if (!busy.add(managed.id())) return CompletableFuture.failedFuture(new IllegalStateException(managed.id() + " is busy"));
        return CompletableFuture.supplyAsync(() -> {
            Path temp = null;
            try {
                GitHubClient.Release release = core.github().latest(managed);
                Path live = plugins().resolve(managed.file());
                Path staged = updates().resolve(managed.file());
                if (updateOnly && release.tag().equals(managed.release()) && (Files.exists(live) || Files.exists(staged)))
                    return managed.id() + " — current (" + release.tag() + ")";
                boolean loaded = core.getServer().getPluginManager().getPlugin(managed.plugin()) != null;
                Path destination = loaded || Files.exists(live) ? staged : live;
                Files.createDirectories(destination.getParent());
                temp = Files.createTempFile(destination.getParent(), managed.id() + "-", ".tmp");
                core.github().download(release, temp);
                validate(temp, managed.plugin());
                move(temp, destination);
                temp = null;
                registry.release(managed.id(), release.tag());
                if (destination.equals(staged)) return managed.id() + " — staged " + release.tag() + "; restart required";
                String loadedResult = core.main(() -> load(managed, live)).join();
                return managed.id() + " — downloaded " + release.tag() + "; " + loadedResult;
            } catch (Exception error) {
                throw new RuntimeException(CorePlugin.message(error), error);
            } finally {
                busy.remove(managed.id());
                if (temp != null) try {
                    Files.deleteIfExists(temp);
                } catch (Exception ignored) {
                }
            }
        }, core.pool());
    }

    private CompletableFuture<String> all(Function<String, CompletableFuture<String>> action) {
        List<CompletableFuture<String>> tasks = registry.all().stream().map(plugin -> action.apply(plugin.id())
                .exceptionally(error -> plugin.id() + " — failed: " + CorePlugin.message(error))).toList();
        return CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> String.join("\n", tasks.stream().map(CompletableFuture::join).toList()));
    }

    private String load(ManagedPlugin managed, Path live) throws Exception {
        Plugin existing = core.getServer().getPluginManager().getPlugin(managed.plugin());
        if (existing != null) return existing.isEnabled() ? "enabled" : "restart required";
        Plugin plugin = core.getServer().getPluginManager().loadPlugin(live.toFile());
        core.getServer().getPluginManager().enablePlugin(plugin);
        return "loaded";
    }

    private void validate(Path file, String expected) throws Exception {
        try (JarFile jar = new JarFile(file.toFile())) {
            var entry = jar.getJarEntry("plugin.yml");
            if (entry == null) throw new IllegalArgumentException("Downloaded JAR has no plugin.yml");
            try (InputStream input = jar.getInputStream(entry)) {
                String actual = new PluginDescriptionFile(input).getName();
                if (!actual.equalsIgnoreCase(expected)) throw new IllegalArgumentException("Expected plugin " + expected + " but found " + actual);
            }
        }
    }

    private void move(Path source, Path target) throws Exception {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException error) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path plugins() {
        return core.getServer().getPluginsFolder().toPath();
    }

    private Path updates() {
        return core.getServer().getUpdateFolderFile().toPath();
    }

    private void rejectCore(ManagedPlugin managed) {
        if (managed.plugin().equalsIgnoreCase(core.getName())) throw new IllegalArgumentException("plugincore cannot manage itself");
    }
}
