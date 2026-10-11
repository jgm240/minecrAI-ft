package dev.minecraft;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public final class BotCommands {
	private BotCommands() {}

	private static void ok(ServerCommandSource s, String m) {
		s.sendFeedback(() -> Text.literal(m).formatted(Formatting.GREEN), false);
	}

	public static void register(CommandDispatcher<ServerCommandSource> d) {
		d.register(literal("ai").requires(s -> s.hasPermissionLevel(2))
			.then(literal("spawn").then(argument("name", StringArgumentType.word()).executes(ctx -> {
				ServerCommandSource src = ctx.getSource();
				String name = StringArgumentType.getString(ctx, "name");
				if (Bots.find(name) != null) {
					src.sendError(Text.literal("An AI player called " + name + " already exists."));
					return 0;
				}
				ServerPlayerEntity p = src.getPlayerOrThrow();
				ServerWorld world = p.getServerWorld();
				AiPlayerEntity bot = ModEntities.AI_PLAYER.create(world, SpawnReason.COMMAND);
				if (bot == null) return 0;
				bot.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ(), p.getYaw(), 0f);
				bot.setCustomName(Text.literal(name));
				bot.setCustomNameVisible(true);
				world.spawnEntity(bot);
				ok(src, "Spawned " + name + ".");
				return 1;
			})))
			.then(literal("remove").then(argument("name", StringArgumentType.word()).executes(ctx -> {
				AiPlayerEntity bot = Bots.find(StringArgumentType.getString(ctx, "name"));
				if (bot == null) {
					ctx.getSource().sendError(Text.literal("No such AI player."));
					return 0;
				}
				bot.discard();
				ok(ctx.getSource(), "Removed.");
				return 1;
			})))
			.then(literal("persona").then(argument("name", StringArgumentType.word())
				.then(argument("text", StringArgumentType.greedyString()).executes(ctx -> {
					AiPlayerEntity bot = Bots.find(StringArgumentType.getString(ctx, "name"));
					if (bot == null) {
						ctx.getSource().sendError(Text.literal("No such AI player."));
						return 0;
					}
					bot.setPersona(StringArgumentType.getString(ctx, "text"));
					ok(ctx.getSource(), "Persona updated.");
					return 1;
				}))))
			.then(literal("list").executes(ctx -> {
				String names = Bots.ALL.stream().map(AiPlayerEntity::botName).sorted()
					.reduce((a, b) -> a + ", " + b).orElse("(none)");
				ok(ctx.getSource(), "AI players: " + names);
				return 1;
			}))
			.then(literal("reload").executes(ctx -> {
				MinecrAIft.reloadConfig();
				ok(ctx.getSource(), "Config reloaded" + (MinecrAIft.CONFIG.key().isEmpty() ? " (no API key set!)." : "."));
				return 1;
			})));
	}
}
