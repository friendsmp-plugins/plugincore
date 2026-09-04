package com.centers25.core;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

final class DiscordBot extends ListenerAdapter {
    private final CorePlugin core;

    DiscordBot(CorePlugin core) {
        this.core = core;
    }

    List<CommandData> commands() {
        OptionData id = new OptionData(OptionType.STRING, "id", "Managed plugin id", true);
        return List.of(Commands.slash("plugins", "Manage Minecraft plugins")
                .addSubcommands(
                        new net.dv8tion.jda.api.interactions.commands.build.SubcommandData("list", "List managed plugins"),
                        new net.dv8tion.jda.api.interactions.commands.build.SubcommandData("add", "Add a plugin")
                                .addOption(OptionType.STRING, "id", "Managed plugin id", true)
                                .addOption(OptionType.STRING, "repository", "GitHub owner/repository", true)
                                .addOption(OptionType.STRING, "asset", "Release JAR asset", true)
                                .addOption(OptionType.STRING, "plugin", "Bukkit plugin name", true)
                                .addOption(OptionType.STRING, "file", "Target JAR name"),
                        new net.dv8tion.jda.api.interactions.commands.build.SubcommandData("download", "Download a plugin or all").addOptions(id),
                        new net.dv8tion.jda.api.interactions.commands.build.SubcommandData("update", "Update a plugin or all").addOptions(id),
                        new net.dv8tion.jda.api.interactions.commands.build.SubcommandData("reload", "Reload a plugin or all").addOptions(id),
                        new net.dv8tion.jda.api.interactions.commands.build.SubcommandData("delete", "Delete a plugin").addOptions(id)
                ));
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals("plugins") || event.getGuild() == null
                || !event.getGuild().getId().equals(core.discord().guildId())) return;
        event.deferReply(true).queue(hook -> {
            if (!allowed(event)) {
                hook.editOriginal("Administrator permission is required.").queue();
                return;
            }
            try {
                String action = event.getSubcommandName();
                if (action == null) throw new IllegalArgumentException("Missing action");
                switch (action) {
                    case "list" -> hook.editOriginal(trim(core.operations().list())).queue();
                    case "add" -> {
                        String asset = option(event, "asset");
                        String file = event.getOption("file") == null ? asset : option(event, "file");
                        ManagedPlugin plugin = core.registry().add(option(event, "id"), option(event, "repository"), asset, option(event, "plugin"), file);
                        hook.editOriginal("Added " + plugin.id()).queue();
                    }
                    case "download" -> execute(message -> hook.editOriginal(message).queue(), option(event, "id"), core.operations()::download, core.operations()::downloadAll);
                    case "update" -> execute(message -> hook.editOriginal(message).queue(), option(event, "id"), core.operations()::update, core.operations()::updateAll);
                    case "reload" -> execute(message -> hook.editOriginal(message).queue(), option(event, "id"), core.operations()::reload, core.operations()::reloadAll);
                    case "delete" -> finish(message -> hook.editOriginal(message).queue(), core.operations().delete(option(event, "id")));
                    default -> hook.editOriginal("Unknown action").queue();
                }
            } catch (Exception error) {
                hook.editOriginal("Failed: " + CorePlugin.message(error)).queue();
            }
        });
    }

    private boolean allowed(SlashCommandInteractionEvent event) {
        if (event.getMember() != null && event.getMember().hasPermission(Permission.ADMINISTRATOR)) return true;
        return event.getUser() != null && core.discordUsers().contains(event.getUser().getId());
    }

    private void execute(Consumer<String> reply, String id, Function<String, CompletableFuture<String>> one,
                         Supplier<CompletableFuture<String>> all) {
        finish(reply, id.equalsIgnoreCase("all") ? all.get() : one.apply(id));
    }

    private void finish(Consumer<String> reply, CompletableFuture<String> task) {
        task.whenComplete((result, error) -> reply.accept(trim(error == null ? result : "Failed: " + CorePlugin.message(error))));
    }

    private String option(SlashCommandInteractionEvent event, String name) {
        return event.getOption(name).getAsString();
    }

    private String trim(String value) {
        return value.length() <= 1900 ? value : value.substring(0, 1897) + "...";
    }
}
