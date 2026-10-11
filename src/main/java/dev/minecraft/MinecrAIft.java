package dev.minecraft;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
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
		ModEntities.register();
		CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) -> BotCommands.register(dispatcher));

		ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
			if (entity instanceof AiPlayerEntity bot) Bots.ALL.add(bot);
		});
		ServerEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
			if (entity instanceof AiPlayerEntity bot) Bots.ALL.remove(bot);
		});

		// Bots hear nearby chat. A named bot answers; otherwise the nearest bot does.
		ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
			MinecraftServer server = sender.getServer();
			String text = message.getContent().getString();
			String lower = text.toLowerCase();
			double r2 = (double) CONFIG.chat_radius * CONFIG.chat_radius;

			AiPlayerEntity named = null, nearest = null;
			double best = Double.MAX_VALUE;
			for (AiPlayerEntity bot : Bots.ALL) {
				if (bot.getWorld() != sender.getWorld()) continue;
				double d = bot.squaredDistanceTo(sender);
				if (d > r2) continue;
				bot.hear(sender.getName().getString() + ": " + text);
				if (lower.contains(bot.botName().toLowerCase())) named = bot;
				if (d < best) {
					best = d;
					nearest = bot;
				}
			}
			AiPlayerEntity responder = named != null ? named : nearest;
			if (responder != null) AiBrain.respond(server, responder, sender, text);
		});
	}
}
