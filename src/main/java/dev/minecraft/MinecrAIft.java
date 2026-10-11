package dev.minecraft;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MinecrAIft implements ModInitializer {
	public static final String MOD_ID = "minecraift";
	public static final Logger LOGGER = LoggerFactory.getLogger("minecrAI-ft");
	public static volatile AiConfig CONFIG = new AiConfig();

	public static void reloadConfig() {
		CONFIG = AiConfig.load(FabricLoader.getInstance().getConfigDir().resolve("minecraift.json"));
	}

	@Override
	public void onInitialize() {
		reloadConfig();
		CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) -> BotCommands.register(dispatcher));

		ServerLifecycleEvents.SERVER_STARTED.register(Bots::loadAndRestore);
		ServerTickEvents.END_SERVER_TICK.register(Bots::tick);

		// Keep the tracked set in sync; a bot that left for any reason (kick, ban, /ai remove) is not restored,
		// unless the whole server is just shutting down.
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			if (handler.player instanceof AiPlayer bot) {
				Bots.ALL.remove(bot);
				if (!server.isStopping() && !bot.isDeadOrRespawning()) Bots.forget(bot.botName());
			}
		});
	}
}
