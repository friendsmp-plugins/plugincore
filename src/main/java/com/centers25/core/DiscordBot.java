package com.centers25.core;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

final class DiscordBot extends ListenerAdapter {
    private static final int EMBED_COLOR = 0x5865F2;
    private final CorePlugin core;

    DiscordBot(CorePlugin core) {
        this.core = core;
    }

    List<CommandData> commands() {
        OptionData id = new OptionData(OptionType.STRING, "id", "Plugin ID or all", true);
        return List.of(Commands.slash("plugins", "Manage server plugins")
                .addSubcommands(
                        new net.dv8tion.jda.api.interactions.commands.build.SubcommandData("list", "View managed plugins"),
                        new net.dv8tion.jda.api.interactions.commands.build.SubcommandData("add", "Register a plugin")
                                .addOption(OptionType.STRING, "id", "Plugin ID", true)
                                .addOption(OptionType.STRING, "repository", "GitHub repository in owner/name format", true)
                                .addOption(OptionType.STRING, "asset", "Release JAR filename", true)
                                .addOption(OptionType.STRING, "plugin", "Bukkit plugin name", true)
                                .addOption(OptionType.STRING, "file", "Installed JAR filename"),
                        new net.dv8tion.jda.api.interactions.commands.build.SubcommandData("download", "Download one or all plugins").addOptions(id),
                        new net.dv8tion.jda.api.interactions.commands.build.SubcommandData("update", "Update one or all plugins").addOptions(id),
                        new net.dv8tion.jda.api.interactions.commands.build.SubcommandData("reload", "Reload one or all plugins").addOptions(id),
                        new net.dv8tion.jda.api.interactions.commands.build.SubcommandData("delete", "Remove a plugin").addOptions(id)
                ));
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals("plugins") || event.getGuild() == null
                || !event.getGuild().getId().equals(core.discord().guildId())) return;
        event.deferReply(true).queue(hook -> {
            if (!allowed(event)) {
                reply(hook, "Access denied", "Administrator permission is required.");
                return;
            }
            try {
                String action = event.getSubcommandName();
                if (action == null) throw new IllegalArgumentException("Missing action");
                switch (action) {
                    case "list" -> reply(hook, "Managed plugins", core.operations().list());
                    case "add" -> {
                        String asset = option(event, "asset");
                        String file = event.getOption("file") == null ? asset : option(event, "file");
                        ManagedPlugin plugin = core.registry().add(option(event, "id"), option(event, "repository"), asset, option(event, "plugin"), file);
                        reply(hook, "Plugin registered", "Plugin: `" + plugin.id() + "`");
                    }
                    case "download" -> execute(hook, "Download complete", option(event, "id"), core.operations()::download, core.operations()::downloadAll);
                    case "update" -> execute(hook, "Update complete", option(event, "id"), core.operations()::update, core.operations()::updateAll);
                    case "reload" -> execute(hook, "Reload complete", option(event, "id"), core.operations()::reload, core.operations()::reloadAll);
                    case "delete" -> finish(hook, "Plugin removed", core.operations().delete(option(event, "id")));
                    default -> reply(hook, "Invalid action", "Select a valid plugin action.");
                }
            } catch (Exception error) {
                reply(hook, "Request failed", CorePlugin.message(error));
            }
        });
    }

    private boolean allowed(SlashCommandInteractionEvent event) {
        if (event.getMember() != null && event.getMember().hasPermission(Permission.ADMINISTRATOR)) return true;
        return event.getUser() != null && core.discordUsers().contains(event.getUser().getId());
    }

    private void execute(net.dv8tion.jda.api.interactions.InteractionHook hook, String title, String id,
                         Function<String, CompletableFuture<String>> one,
                         Supplier<CompletableFuture<String>> all) {
        finish(hook, title, id.equalsIgnoreCase("all") ? all.get() : one.apply(id));
    }

    private void finish(net.dv8tion.jda.api.interactions.InteractionHook hook, String title,
                        CompletableFuture<String> task) {
        task.whenComplete((result, error) -> {
            if (error == null) reply(hook, title, result);
            else reply(hook, "Request failed", CorePlugin.message(error));
        });
    }

    private String option(SlashCommandInteractionEvent event, String name) {
        return event.getOption(name).getAsString();
    }

    private String trim(String value) {
        return value.length() <= 3900 ? value : value.substring(0, 3897) + "...";
    }

    private void reply(net.dv8tion.jda.api.interactions.InteractionHook hook, String title, String message) {
        hook.editOriginalEmbeds(embed(title, trim(message))).queue();
    }

    private MessageEmbed embed(String title, String message) {
        return new EmbedBuilder()
                .setColor(EMBED_COLOR)
                .setTitle(title)
                .setDescription(message)
                .build();
    }
}
