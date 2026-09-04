package com.centers25.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

final class CoreCommand implements CommandExecutor, TabCompleter {
    private static final Component PREFIX = Component.text("[Plugin Core] ", NamedTextColor.BLUE);
    private static final List<String> ACTIONS = List.of("list", "add", "download", "update", "reload", "delete", "discord", "github", "reloadcore");
    private final CorePlugin core;

    CoreCommand(CorePlugin core) {
        this.core = core;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("plugincore.admin")) return reply(sender, "Administrator permission is required.");
        if (args.length == 0) return help(sender, label);
        try {
            switch (args[0].toLowerCase()) {
                case "list" -> reply(sender, core.operations().list());
                case "add" -> add(sender, args);
                case "download" -> oneOrAll(sender, args, core.operations()::download, core.operations()::downloadAll);
                case "update" -> oneOrAll(sender, args, core.operations()::update, core.operations()::updateAll);
                case "reload" -> oneOrAll(sender, args, core.operations()::reload, core.operations()::reloadAll);
                case "delete" -> run(sender, args.length == 2 ? core.operations().delete(args[1]) : failed("Usage: /" + label + " delete <id>"));
                case "discord" -> discord(sender, args);
                case "github" -> github(sender, args);
                case "reloadcore" -> reply(sender, core.restart());
                default -> help(sender, label);
            }
        } catch (Exception error) {
            reply(sender, "Request failed: " + CorePlugin.message(error));
        }
        return true;
    }

    private void add(CommandSender sender, String[] args) {
        if (args.length < 5 || args.length > 6) {
            reply(sender, "Usage: /plugincore add <id> <owner/repo> <asset.jar> <plugin> [target.jar]");
            return;
        }
        ManagedPlugin plugin = core.registry().add(args[1], args[2], args[3], args[4], args.length == 6 ? args[5] : args[3]);
        reply(sender, "Plugin registered: " + plugin.id());
    }

    private void oneOrAll(CommandSender sender, String[] args, java.util.function.Function<String, CompletableFuture<String>> one,
                          java.util.function.Supplier<CompletableFuture<String>> all) {
        if (args.length != 2) {
            reply(sender, "Specify a plugin ID or 'all'.");
            return;
        }
        run(sender, args[1].equalsIgnoreCase("all") ? all.get() : one.apply(args[1]));
    }

    private void discord(CommandSender sender, String[] args) {
        if (!(sender instanceof ConsoleCommandSender)) {
            reply(sender, "Discord credentials can only be changed from the server console.");
            return;
        }
        if (args.length != 3) {
            reply(sender, "Usage: /plugincore discord <guild-id> <bot-token>");
            return;
        }
        core.getConfig().set("discord-guild-id", args[1]);
        core.getConfig().set("discord-token", args[2]);
        core.saveConfig();
        reply(sender, core.restartBot());
    }

    private void github(CommandSender sender, String[] args) {
        if (!(sender instanceof ConsoleCommandSender)) {
            reply(sender, "GitHub credentials can only be changed from the server console.");
            return;
        }
        if (args.length != 2) {
            reply(sender, "Usage: /plugincore github <token|clear>");
            return;
        }
        core.getConfig().set("github-token", args[1].equalsIgnoreCase("clear") ? "" : args[1]);
        core.saveConfig();
        core.reloadClients();
        reply(sender, "GitHub credentials updated.");
    }

    private void run(CommandSender sender, CompletableFuture<String> task) {
        reply(sender, "Processing request.");
        task.whenComplete((result, error) -> core.getServer().getScheduler().runTask(core,
                () -> reply(sender, error == null ? result : "Request failed: " + CorePlugin.message(error))));
    }

    private CompletableFuture<String> failed(String message) {
        return CompletableFuture.failedFuture(new IllegalArgumentException(message));
    }

    private boolean reply(CommandSender sender, String message) {
        for (String line : message.split("\n")) {
            sender.sendMessage(PREFIX.append(Component.text(line, NamedTextColor.GRAY)));
        }
        return true;
    }

    private boolean help(CommandSender sender, String label) {
        reply(sender, "Commands");
        reply(sender, "• /" + label + " list — View registered plugins");
        reply(sender, "• /" + label + " <download|update|reload> <id|all>");
        reply(sender, "• /" + label + " delete <id> — Remove a plugin");
        reply(sender, "• /" + label + " add <id> <owner/repo> <asset.jar> <plugin> [target.jar]");
        reply(sender, "• /" + label + " discord <guild-id> <bot-token> — Console only");
        reply(sender, "• /" + label + " github <token|clear> — Console only");
        reply(sender, "• /" + label + " reloadcore — Reload Plugin Core");
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) return match(ACTIONS, args[0]);
        if (args.length == 2 && List.of("download", "update", "reload", "delete").contains(args[0].toLowerCase())) {
            List<String> ids = new ArrayList<>(core.registry().all().stream().map(ManagedPlugin::id).toList());
            if (!args[0].equalsIgnoreCase("delete")) ids.add("all");
            return match(ids, args[1]);
        }
        return List.of();
    }

    private List<String> match(List<String> values, String input) {
        String prefix = input.toLowerCase();
        return values.stream().filter(value -> value.startsWith(prefix)).toList();
    }
}
