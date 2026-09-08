package com.centers25.core;

import com.centers25.core.discord.DiscordService;
import com.centers25.core.backup.DataBackupService;
import com.centers25.core.logging.LoggingService;
import com.centers25.core.logging.PluginLogger;
import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class CorePlugin extends JavaPlugin {
    private ExecutorService pool;
    private PluginRegistry registry;
    private PluginOperations operations;
    private GitHubClient github;
    private DiscordGateway discord;
    private DiscordBot discordModule;
    private FileLoggingService logging;
    private FileBackupService backups;
    private PluginLogger log;
    private Set<String> discordUsers = Set.of();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        logging = new FileLoggingService(this);
        getServer().getServicesManager().register(LoggingService.class, logging, this, ServicePriority.Highest);
        backups = new FileBackupService(this);
        getServer().getServicesManager().register(DataBackupService.class, backups, this, ServicePriority.Highest);
        log = logging.logger(this);
        log.info("Plugin Core starting. Debug logging is " + (logging.debugEnabled() ? "enabled" : "disabled") + ".");
        pool = Executors.newVirtualThreadPerTaskExecutor();
        registry = new PluginRegistry(this);
        operations = new PluginOperations(this, registry);
        discord = new DiscordGateway(this);
        getServer().getServicesManager().register(DiscordService.class, discord, this, ServicePriority.Normal);
        discordModule = new DiscordBot(this);
        discord.register(this, discordModule, discordModule.commands());
        CoreCommand command = new CoreCommand(this);
        getCommand("plugincore").setExecutor(command);
        getCommand("plugincore").setTabCompleter(command);
        reloadClients();
        restartBot();
    }

    @Override
    public void onDisable() {
        if (log != null) log.info("Plugin Core stopping.");
        getServer().getServicesManager().unregisterAll(this);
        if (discord != null) discord.stop();
        if (pool != null) pool.shutdownNow();
        if (logging != null) logging.close();
        if (backups != null) backups.close();
    }

    synchronized String restart() {
        reloadConfig();
        logging.reloadSettings();
        registry.load();
        reloadClients();
        return restartBot();
    }

    synchronized String restartBot() {
        discord.stop();
        discordUsers = Set.copyOf(new HashSet<>(getConfig().getStringList("discord-allowed-user-ids")));
        String token = env("PLUGINCORE_DISCORD_TOKEN", getConfig().getString("discord-token", ""));
        String guild = env("PLUGINCORE_DISCORD_GUILD_ID", getConfig().getString("discord-guild-id", ""));
        if (token.isBlank() || guild.isBlank()) return "Plugin Core reloaded. Discord is not configured.";
        try {
            discord.start(token, guild);
            return "Plugin Core reloaded. Discord is connecting.";
        } catch (RuntimeException error) {
            log.error("Discord failed: " + message(error), error);
            return "Plugin Core reloaded. Discord connection failed: " + message(error);
        }
    }

    synchronized void reloadClients() {
        long seconds = Math.max(5, getConfig().getLong("http-timeout-seconds", 30));
        long megabytes = Math.clamp(getConfig().getLong("max-download-megabytes", 100), 1, 1024);
        String token = env("PLUGINCORE_GITHUB_TOKEN", getConfig().getString("github-token", ""));
        github = new GitHubClient(Duration.ofSeconds(seconds), token, megabytes * 1024 * 1024);
    }

    <T> CompletableFuture<T> main(Callable<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Runnable run = () -> {
            try {
                future.complete(task.call());
            } catch (Throwable error) {
                future.completeExceptionally(error);
            }
        };
        if (Bukkit.isPrimaryThread()) run.run();
        else getServer().getScheduler().runTask(this, run);
        return future;
    }

    PluginRegistry registry() {
        return registry;
    }

    PluginOperations operations() {
        return operations;
    }

    GitHubClient github() {
        return github;
    }

    ExecutorService pool() {
        return pool;
    }

    Set<String> discordUsers() {
        return discordUsers;
    }

    DiscordService discord() {
        return discord;
    }

    FileLoggingService logging() {
        return logging;
    }

    PluginLogger log() {
        return log;
    }

    private String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    static String message(Throwable error) {
        while ((error instanceof java.util.concurrent.CompletionException || error instanceof java.util.concurrent.ExecutionException)
                && error.getCause() != null) error = error.getCause();
        return error.getMessage() == null || error.getMessage().isBlank() ? error.getClass().getSimpleName() : error.getMessage();
    }
}
