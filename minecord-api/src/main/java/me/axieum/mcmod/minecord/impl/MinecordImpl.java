package me.axieum.mcmod.minecord.impl;

import java.util.Optional;

import me.shedaniel.autoconfig.ConfigHolder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.exceptions.InvalidTokenException;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.ChunkingFilter;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import net.minecraft.server.MinecraftServer;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import net.fabricmc.loader.impl.entrypoint.EntrypointUtils;

import me.axieum.mcmod.minecord.api.Minecord;
import me.axieum.mcmod.minecord.api.addon.MinecordAddon;
import me.axieum.mcmod.minecord.api.event.JDAEvents;
import me.axieum.mcmod.minecord.api.event.ServerShutdownCallback;
import me.axieum.mcmod.minecord.api.util.StringTemplate;
import me.axieum.mcmod.minecord.impl.callback.DiscordLifecycleListener;
import me.axieum.mcmod.minecord.impl.callback.ServerLifecycleCallback;
import me.axieum.mcmod.minecord.impl.config.MinecordConfig;

/**
 * Minecord (API) implementation.
 */
public final class MinecordImpl implements Minecord, PreLaunchEntrypoint, DedicatedServerModInitializer
{
    /** The Minecord instance. */
    public static final Minecord INSTANCE = new MinecordImpl();
    /** Minecord logger. */
    public static final Logger LOGGER = LogManager.getLogger("Minecord");
    /** Minecord configuration. */
    private static final ConfigHolder<MinecordConfig> CONFIG = MinecordConfig.init();

    // The captured Minecraft server, available once started
    private static @Nullable MinecraftServer minecraft = null;
    // The JDA client, available once authenticated
    private static @Nullable JDA client = null;

    @Override
    public void onPreLaunch()
    {
        LOGGER.info("Minecord is getting ready...");

        try {
            // Prepare the JDA client
            final JDABuilder builder = JDABuilder.createDefault(getConfig().bot.token)
                                                 // allow the bot to read message contents
                                                 .enableIntents(GatewayIntent.MESSAGE_CONTENT)
                                                 // set initial bot status
                                                 .setStatus(getConfig().bot.status.starting)
                                                 // add event listeners
                                                 .addEventListeners(new DiscordLifecycleListener());

            // Conditionally enable member caching
            if (getConfig().bot.cacheMembers) {
                builder.enableIntents(GatewayIntent.GUILD_MEMBERS) // enable required intents
                       .setMemberCachePolicy(MemberCachePolicy.ALL) // cache all members
                       .setChunkingFilter(ChunkingFilter.ALL); // eager-load all members
            }

            // Register any Minecord addons
            EntrypointUtils.invoke("minecord", MinecordAddon.class, addon -> addon.onInitializeMinecord(builder));

            // Build and login to the client
            JDAEvents.BUILD_CLIENT.invoker().onBuildClient(builder);
            LOGGER.info("Logging into Discord...");
            client = builder.build();
        } catch (InvalidTokenException | IllegalArgumentException e) {
            LOGGER.error("Unable to login to Discord: {}", e.getMessage());
        }
    }

    @Override
    public void onInitializeServer()
    {
        // Capture the server instance
        ServerLifecycleEvents.SERVER_STARTING.register(server -> minecraft = server);

        // Register server lifecycle callbacks
        final ServerLifecycleCallback lifecycleCallback = new ServerLifecycleCallback();
        ServerLifecycleEvents.SERVER_STARTING.register(lifecycleCallback);
        ServerLifecycleEvents.SERVER_STARTED.register(lifecycleCallback);
        ServerLifecycleEvents.SERVER_STOPPING.register(lifecycleCallback);
        ServerLifecycleEvents.SERVER_STARTING.register(server -> // register as late as possible
            ServerShutdownCallback.EVENT.register(lifecycleCallback));
    }

    @Override
    public Optional<MinecraftServer> getMinecraft()
    {
        return Optional.ofNullable(minecraft);
    }

    @Override
    public Optional<JDA> getJDA()
    {
        return Optional.ofNullable(client);
    }

    @Override
    public Optional<String> getAvatarUrl(@Nullable String username, int height)
    {
        // Only return an avatar URL if they are enabled and the provided username is valid
        if (getConfig().misc.enableAvatars && username != null && !username.isBlank()) {
            return Optional.ofNullable(
                new StringTemplate().add("username", username).add("size", height).format(getConfig().misc.avatarUrl)
            );
        }
        return Optional.empty();
    }

    /**
     * Returns the Minecord config instance.
     *
     * @return config instance
     */
    public static MinecordConfig getConfig()
    {
        return CONFIG.getConfig();
    }
}
