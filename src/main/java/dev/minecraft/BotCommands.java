package dev.minecraft;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
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
				ServerPlayerEntity p = src.getPlayerOrThrow();
				String err = Bots.spawn(src.getServer(), name, "", p.getServerWorld(), p.getX(), p.getY(), p.getZ(), p.getYaw());
				if (err != null) {
					src.sendError(Text.literal(err));
					return 0;
				}
				ok(src, "Spawned " + name + ".");
				return 1;
			})))
			.then(literal("remove").then(argument("name", StringArgumentType.word()).executes(ctx -> {
				AiPlayer bot = Bots.find(StringArgumentType.getString(ctx, "name"));
				if (bot == null) {
					ctx.getSource().sendError(Text.literal("No such AI player."));
					return 0;
				}
				Bots.remove(bot, "Removed by an operator");
				ok(ctx.getSource(), "Removed.");
				return 1;
			})))
			.then(literal("persona").then(argument("name", StringArgumentType.word())
				.then(argument("text", StringArgumentType.greedyString()).executes(ctx -> {
					AiPlayer bot = Bots.find(StringArgumentType.getString(ctx, "name"));
					if (bot == null) {
						ctx.getSource().sendError(Text.literal("No such AI player."));
						return 0;
					}
					bot.setPersona(StringArgumentType.getString(ctx, "text"));
					Bots.updatePersona(bot);
					ok(ctx.getSource(), "Persona updated.");
					return 1;
				}))))
			.then(literal("list").executes(ctx -> {
				String names = Bots.ALL.stream().map(AiPlayer::botName).sorted()
					.reduce((a, b) -> a + ", " + b).orElse("(none)");
				ok(ctx.getSource(), "AI players: " + names);
				return 1;
			}))
			.then(literal("reload").executes(ctx -> {
				MinecrAIft.reloadConfig();
				AiConfig c = MinecrAIft.CONFIG;
				ok(ctx.getSource(), "Config reloaded. Provider: " + (c.isOllama() ? "ollama (" + c.ollama_model + ")" : "anthropic (" + c.model + ")")
					+ (c.ready() ? "." : " - no API key set!"));
				return 1;
			})));
	}
}
