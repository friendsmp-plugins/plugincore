package com.centers25.core.discord;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.hooks.EventListener;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import org.bukkit.plugin.Plugin;

import java.util.Collection;

public interface DiscordService {
    void register(Plugin owner, EventListener listener, Collection<? extends CommandData> commands);

    void unregister(Plugin owner);

    JDA jda();

    Guild guild();

    String guildId();

    boolean ready();
}
