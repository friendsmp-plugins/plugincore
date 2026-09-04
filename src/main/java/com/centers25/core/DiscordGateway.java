package com.centers25.core;

import com.centers25.core.discord.DiscordService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.guild.GuildJoinEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.EventListener;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.bukkit.plugin.Plugin;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

final class DiscordGateway extends ListenerAdapter implements DiscordService {
    private final CorePlugin core;
    private final Map<String, Module> modules = new LinkedHashMap<>();
    private JDA jda;
    private String guildId = "";

    DiscordGateway(CorePlugin core) {
        this.core = core;
    }

    synchronized void start(String token, String guildId) {
        stop();
        this.guildId = guildId;
        JDABuilder builder = JDABuilder.createLight(token, GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT)
                .addEventListeners(this);
        modules.values().forEach(module -> builder.addEventListeners(module.listener()));
        jda = builder.build();
    }

    synchronized void stop() {
        if (jda != null) jda.shutdownNow();
        jda = null;
    }

    @Override
    public synchronized void register(Plugin owner, EventListener listener, Collection<? extends CommandData> commands) {
        String key = owner.getName().toLowerCase();
        List<CommandData> values = List.copyOf(commands);
        Set<String> names = values.stream().map(CommandData::getName).collect(Collectors.toSet());
        modules.forEach((other, module) -> {
            if (!other.equals(key) && module.commands().stream().map(CommandData::getName).anyMatch(names::contains))
                throw new IllegalArgumentException("Discord command already registered");
        });
        Module old = modules.remove(key);
        if (old != null && jda != null) {
            jda.removeEventListener(old.listener());
            Set<String> removed = old.commands().stream().map(CommandData::getName)
                    .filter(name -> !names.contains(name)).collect(Collectors.toSet());
            delete(guild(), removed);
        }
        Module module = new Module(owner, listener, values);
        modules.put(key, module);
        if (jda != null) jda.addEventListener(listener);
        Guild guild = guild();
        if (guild != null) publish(guild, module);
    }

    @Override
    public synchronized void unregister(Plugin owner) {
        Module module = modules.remove(owner.getName().toLowerCase());
        if (module == null) return;
        if (jda != null) jda.removeEventListener(module.listener());
        Guild guild = guild();
        Set<String> names = module.commands().stream().map(CommandData::getName).collect(Collectors.toSet());
        delete(guild, names);
    }

    @Override
    public synchronized JDA jda() {
        return jda;
    }

    @Override
    public synchronized Guild guild() {
        return jda == null || guildId.isBlank() ? null : jda.getGuildById(guildId);
    }

    @Override
    public synchronized String guildId() {
        return guildId;
    }

    @Override
    public synchronized boolean ready() {
        return guild() != null;
    }

    @Override
    public void onReady(ReadyEvent event) {
        publish(event.getJDA().getGuildById(guildId));
    }

    @Override
    public void onGuildJoin(GuildJoinEvent event) {
        if (event.getGuild().getId().equals(guildId)) publish(event.getGuild());
    }

    private synchronized void publish(Guild guild) {
        if (guild == null) {
            core.log().error("Discord bot cannot access guild " + guildId);
            return;
        }
        modules.values().forEach(module -> publish(guild, module));
    }

    private void publish(Guild guild, Module module) {
        module.commands().forEach(command -> guild.upsertCommand(command).queue(
                ignored -> { }, error -> core.logging().logger(module.owner())
                        .warn("Discord command failed: " + CorePlugin.message(error), error)));
    }

    private void delete(Guild guild, Set<String> names) {
        if (guild == null || names.isEmpty()) return;
        guild.retrieveCommands().queue(commands -> commands.stream()
                .filter(command -> names.contains(command.getName()) && !owned(command.getName()))
                .forEach(command -> command.delete().queue()));
    }

    private synchronized boolean owned(String name) {
        return modules.values().stream().flatMap(module -> module.commands().stream())
                .anyMatch(command -> command.getName().equals(name));
    }

    private record Module(Plugin owner, EventListener listener, List<CommandData> commands) {
    }
}
