package com.centers25.core;

import com.centers25.core.logging.LoggingService;
import com.centers25.core.logging.PluginLogger;
import org.bukkit.plugin.Plugin;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

final class FileLoggingService implements LoggingService, AutoCloseable {
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS xxx");
    private final CorePlugin core;
    private final Path directory;
    private final Map<String, FilePluginLogger> loggers = new ConcurrentHashMap<>();
    private final ExecutorService dispatcher = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("plugincore-log-writer").factory());
    private volatile boolean debugEnabled;

    FileLoggingService(CorePlugin core) {
        this.core = core;
        directory = core.getDataFolder().toPath().resolve("logs").toAbsolutePath().normalize();
        try {
            Files.createDirectories(directory);
            ownerOnly(directory, "rwx------");
        } catch (IOException error) {
            throw new IllegalStateException("Could not create log directory " + directory, error);
        }
        reloadSettings();
    }

    @Override
    public PluginLogger logger(Plugin owner) {
        String name = safeName(owner.getName());
        return loggers.computeIfAbsent(name, FilePluginLogger::new);
    }

    @Override
    public boolean debugEnabled() {
        return debugEnabled;
    }

    @Override
    public void setDebugEnabled(boolean enabled) {
        debugEnabled = enabled;
        core.getConfig().set("logging.debug", enabled);
        core.saveConfig();
        logger(core).info("Debug logging " + (enabled ? "enabled" : "disabled") + ".");
    }

    @Override
    public Path directory() {
        return directory;
    }

    @Override
    public Path currentFile(Plugin owner) {
        return file(safeName(owner.getName()), LocalDate.now());
    }

    Path pluginDirectory(String plugin) {
        return directory.resolve(safeName(plugin));
    }

    void reloadSettings() {
        debugEnabled = core.getConfig().getBoolean("logging.debug", false);
        removeExpired(core.getConfig().getInt("logging.retention-days", 30));
    }

    @Override
    public void close() {
        dispatcher.shutdown();
        try {
            if (!dispatcher.awaitTermination(5, TimeUnit.SECONDS)) dispatcher.shutdownNow();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            dispatcher.shutdownNow();
        }
        loggers.values().forEach(FilePluginLogger::close);
        loggers.clear();
    }

    private Path file(String plugin, LocalDate date) {
        return directory.resolve(plugin).resolve(date + ".log");
    }

    private String safeName(String value) {
        String safe = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
        return safe.isBlank() ? "unknown" : safe;
    }

    private void removeExpired(int requestedDays) {
        int days = Math.clamp(requestedDays, 1, 3650);
        LocalDate oldest = LocalDate.now().minusDays(days - 1L);
        try (Stream<Path> paths = Files.exists(directory) ? Files.walk(directory) : Stream.empty()) {
            paths.filter(Files::isRegularFile).filter(path -> path.getFileName().toString().endsWith(".log"))
                    .filter(path -> date(path).isBefore(oldest)).forEach(this::deleteQuietly);
        } catch (IOException ignored) {
        }
        try (Stream<Path> paths = Files.exists(directory) ? Files.walk(directory) : Stream.empty()) {
            paths.filter(Files::isDirectory).sorted(Comparator.reverseOrder()).filter(path -> !path.equals(directory))
                    .forEach(this::deleteIfEmpty);
        } catch (IOException ignored) {
        }
    }

    private LocalDate date(Path path) {
        String name = path.getFileName().toString();
        try {
            return LocalDate.parse(name.substring(0, name.length() - 4));
        } catch (RuntimeException ignored) {
            return LocalDate.MAX;
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    private void deleteIfEmpty(Path path) {
        try (Stream<Path> children = Files.list(path)) {
            if (children.findAny().isEmpty()) Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    private final class FilePluginLogger implements PluginLogger {
        private final String plugin;
        private LocalDate openDate;
        private BufferedWriter writer;

        private FilePluginLogger(String plugin) {
            this.plugin = plugin;
        }

        @Override
        public boolean debugEnabled() {
            return FileLoggingService.this.debugEnabled;
        }

        @Override
        public void debug(String message) {
            if (debugEnabled) write("DEBUG", message, null);
        }

        @Override
        public void info(String message) {
            write("INFO", message, null);
        }

        @Override
        public void warn(String message) {
            write("WARN", message, null);
        }

        @Override
        public void warn(String message, Throwable error) {
            write("WARN", message, error);
        }

        @Override
        public void error(String message) {
            write("ERROR", message, null);
        }

        @Override
        public void error(String message, Throwable error) {
            write("ERROR", message, error);
        }

        private void write(String level, String message, Throwable error) {
            OffsetDateTime created = OffsetDateTime.now();
            String thread = Thread.currentThread().getName();
            try {
                dispatcher.execute(() -> writeNow(created, thread, level, message, error));
            } catch (RejectedExecutionException ignored) {
            }
        }

        private synchronized void writeNow(OffsetDateTime created, String thread, String level, String message, Throwable error) {
            try {
                LocalDate today = created.toLocalDate();
                if (writer == null || !today.equals(openDate)) rotate(today);
                String clean = message == null ? "null" : message.replace("\r", "\\r").replace("\n", "\\n");
                writer.write("[" + TIMESTAMP.format(created) + "] [" + thread + "] [" + level + "] " + clean);
                writer.newLine();
                if (error != null) {
                    StringWriter stack = new StringWriter();
                    error.printStackTrace(new PrintWriter(stack));
                    for (String line : stack.toString().split("\\R")) {
                        writer.write("    " + line);
                        writer.newLine();
                    }
                }
                writer.flush();
            } catch (IOException ioError) {
                core.getLogger().severe("Could not write Plugin Core log file: " + ioError.getMessage());
            }
        }

        private void rotate(LocalDate today) throws IOException {
            close();
            Path path = file(plugin, today);
            Files.createDirectories(path.getParent());
            ownerOnly(path.getParent(), "rwx------");
            writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            ownerOnly(path, "rw-------");
            openDate = today;
        }

        private synchronized void close() {
            if (writer == null) return;
            try {
                writer.close();
            } catch (IOException ignored) {
            }
            writer = null;
            openDate = null;
        }
    }

    private void ownerOnly(Path path, String permissions) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
        } catch (IOException | UnsupportedOperationException ignored) {
        }
    }
}
