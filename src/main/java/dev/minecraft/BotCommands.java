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

	private static int writeModelfile(ServerCommandSource src, String base) {
		try {
			var file = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("minecraift.Modelfile");
			java.nio.file.Files.writeString(file, AiBrain.modelfile(base));
			ok(src, "Wrote " + file + ". Build it with: ollama create minecraift-bot -f \"" + file + "\"");
			return 1;
		} catch (java.io.IOException e) {
			src.sendError(Text.literal("Could not write the Modelfile: " + e.getMessage()));
			return 0;
		}
	}

	public static void register(CommandDispatcher<ServerCommandSource> d) {
		d.register(literal("ai").requires(s -> s.hasPermissionLevel(2))
			.then(literal("spawn").then(argument("name", StringArgumentType.word()).executes(ctx -> {
				ServerCommandSource src = ctx.getSource();
				String name = StringArgumentType.getString(ctx, "name");
				String err;
				if (src.getEntity() instanceof ServerPlayerEntity p) {
					err = Bots.spawn(src.getServer(), name, new Bots.Brain(), p.getServerWorld(), p.getX(), p.getY(), p.getZ(), p.getYaw());
				} else {
					err = Bots.spawn(src.getServer(), name, new Bots.Brain(), null, 0, 0, 0, 0); // console: join at world spawn
				}
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
					Bots.updateBrain(bot);
					ok(ctx.getSource(), "Persona updated.");
					return 1;
				}))))
			.then(literal("train").then(argument("name", StringArgumentType.word())
				.then(argument("info", StringArgumentType.greedyString()).executes(ctx -> {
					ServerCommandSource src = ctx.getSource();
					String name = StringArgumentType.getString(ctx, "name");
					String info = StringArgumentType.getString(ctx, "info").trim();
					java.util.List<AiPlayer> targets = new java.util.ArrayList<>();
					if (name.equalsIgnoreCase("all") || name.equals("*")) {
						targets.addAll(Bots.ALL);
					} else {
						AiPlayer b = Bots.find(name);
						if (b != null) targets.add(b);
					}
					if (targets.isEmpty()) {
						src.sendError(Text.literal("No such AI player."));
						return 0;
					}
					int taught = 0;
					for (AiPlayer b : targets) {
						if (b.addNote(info)) {
							Bots.updateBrain(b);
							taught++;
						}
					}
					if (taught == 0) {
						src.sendError(Text.literal("Their notebook is full (40 notes). Use /ai forget <name> first."));
						return 0;
					}
					ok(src, "Taught " + taught + " bot(s): " + info);
					return taught;
				}))))
			.then(literal("notes").then(argument("name", StringArgumentType.word()).executes(ctx -> {
				AiPlayer b = Bots.find(StringArgumentType.getString(ctx, "name"));
				if (b == null) {
					ctx.getSource().sendError(Text.literal("No such AI player."));
					return 0;
				}
				var notes = b.notes();
				if (notes.isEmpty()) {
					ok(ctx.getSource(), b.botName() + " hasn't been taught anything.");
				} else {
					for (int i = 0; i < notes.size(); i++) ok(ctx.getSource(), (i + 1) + ". " + notes.get(i));
				}
				return notes.size();
			})))
			.then(literal("forget").then(argument("name", StringArgumentType.word()).executes(ctx -> {
				AiPlayer b = Bots.find(StringArgumentType.getString(ctx, "name"));
				if (b == null) {
					ctx.getSource().sendError(Text.literal("No such AI player."));
					return 0;
				}
				b.clearNotes();
				Bots.updateBrain(b);
				ok(ctx.getSource(), b.botName() + " forgot everything it was taught.");
				return 1;
			})))
			.then(literal("say").then(argument("name", StringArgumentType.word())
				.then(argument("text", StringArgumentType.greedyString()).executes(ctx -> {
					AiPlayer bot = Bots.find(StringArgumentType.getString(ctx, "name"));
					if (bot == null) {
						ctx.getSource().sendError(Text.literal("No such AI player."));
						return 0;
					}
					bot.say(StringArgumentType.getString(ctx, "text"));
					return 1;
				}))))
			.then(literal("do").then(argument("name", StringArgumentType.word())
				.then(argument("action", StringArgumentType.greedyString()).executes(ctx -> {
					AiPlayer bot = Bots.find(StringArgumentType.getString(ctx, "name"));
					if (bot == null) {
						ctx.getSource().sendError(Text.literal("No such AI player."));
						return 0;
					}
					try {
						var obj = com.google.gson.JsonParser.parseString(StringArgumentType.getString(ctx, "action")).getAsJsonObject();
						Task t = Actions.parse(obj, ctx.getSource().getServer());
						if (t == null) {
							ctx.getSource().sendError(Text.literal("Unknown action type."));
							return 0;
						}
						bot.setTasks(java.util.List.of(t));
						ok(ctx.getSource(), bot.botName() + ": " + t.describe());
						return 1;
					} catch (RuntimeException e) {
						ctx.getSource().sendError(Text.literal("Bad action JSON: " + e.getMessage()));
						return 0;
					}
				}))))
			.then(literal("goal").then(argument("name", StringArgumentType.word())
				.then(argument("text", StringArgumentType.greedyString()).executes(ctx -> {
					AiPlayer bot = Bots.find(StringArgumentType.getString(ctx, "name"));
					if (bot == null) {
						ctx.getSource().sendError(Text.literal("No such AI player."));
						return 0;
					}
					bot.setGoal(StringArgumentType.getString(ctx, "text"));
					ok(ctx.getSource(), bot.botName() + "'s goal: " + bot.goal());
					return 1;
				}))))
			.then(literal("modelfile").executes(ctx -> writeModelfile(ctx.getSource(), "llama3.2"))
				.then(argument("base", StringArgumentType.word()).executes(ctx ->
					writeModelfile(ctx.getSource(), StringArgumentType.getString(ctx, "base")))))
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
