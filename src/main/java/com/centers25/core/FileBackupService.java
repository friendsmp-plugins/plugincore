package com.centers25.core;

import com.centers25.core.backup.DataBackupService;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

final class FileBackupService implements DataBackupService, AutoCloseable {
    private static final long INTERVAL_TICKS = Duration.ofHours(6).toSeconds() * 20L;
    private static final Duration RETENTION = Duration.ofDays(7);
    private static final DateTimeFormatter NAME_FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd_HH-mm-ss_SSS").withZone(ZoneOffset.UTC);
    private final CorePlugin core;
    private final Path directory;
    private final Map<String, Registration> registrations = new ConcurrentHashMap<>();
    private final BukkitTask task;

    FileBackupService(CorePlugin core) {
        this.core = core;
        directory = core.getDataFolder().toPath().resolve("backups").toAbsolutePath().normalize();
        try {
            Files.createDirectories(directory);
        } catch (IOException error) {
            throw new IllegalStateException("Could not create backup directory " + directory, error);
        }
        task = core.getServer().getScheduler().runTaskTimerAsynchronously(core, this::backupAll, INTERVAL_TICKS, INTERVAL_TICKS);
    }

    @Override
    public void register(Plugin owner) {
        String name = safeName(owner.getName());
        Registration registration = new Registration(name, owner.getDataFolder().toPath().toAbsolutePath().normalize());
        registrations.put(name, registration);
        core.getServer().getScheduler().runTaskAsynchronously(core, () -> backup(registration));
    }

    @Override
    public void unregister(Plugin owner) {
        registrations.remove(safeName(owner.getName()));
    }

    @Override
    public Path directory() {
        return directory;
    }

    @Override
    public void close() {
        task.cancel();
        registrations.clear();
    }

    private void backupAll() {
        registrations.values().forEach(this::backup);
    }

    private void backup(Registration registration) {
        if (!Files.isDirectory(registration.source())) {
            return;
        }
        Path pluginDirectory = directory.resolve(registration.name());
        String name = NAME_FORMAT.format(Instant.now());
        Path temporary = pluginDirectory.resolve(".pending-" + name);
        Path target = pluginDirectory.resolve(name);
        try {
            Files.createDirectories(temporary);
            try (Stream<Path> paths = Files.walk(registration.source())) {
                paths.forEach(path -> copy(registration.source(), temporary, path));
            }
            move(temporary, target);
            purge(pluginDirectory);
        } catch (IOException | RuntimeException error) {
            delete(temporary);
            core.getLogger().warning("Could not back up " + registration.name() + " data: " + error.getMessage());
        }
    }

    private void copy(Path source, Path target, Path path) {
        try {
            Path destination = target.resolve(source.relativize(path));
            if (Files.isDirectory(path)) {
                Files.createDirectories(destination);
            } else if (Files.isRegularFile(path)) {
                Files.copy(path, destination, StandardCopyOption.COPY_ATTRIBUTES);
            }
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    private void purge(Path pluginDirectory) throws IOException {
        Instant cutoff = Instant.now().minus(RETENTION);
        try (Stream<Path> paths = Files.list(pluginDirectory)) {
            paths.filter(Files::isDirectory)
                    .filter(path -> modified(path).isBefore(cutoff))
                    .forEach(this::delete);
        }
    }

    private void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(source, target);
        }
    }

    private Instant modified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant();
        } catch (IOException error) {
            return Instant.MAX;
        }
    }

    private void delete(Path path) {
        try (Stream<Path> paths = Files.walk(path)) {
            paths.sorted(Comparator.reverseOrder()).forEach(candidate -> {
                try {
                    Files.deleteIfExists(candidate);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    private static String safeName(String value) {
        String safe = value.toLowerCase().replaceAll("[^a-z0-9._-]", "_");
        return safe.isBlank() ? "unknown" : safe;
    }

    private record Registration(String name, Path source) {
    }
}
