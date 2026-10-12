package dev.minecraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** config/minecraift.json. The API key may also come from the ANTHROPIC_API_KEY environment variable. */
public final class AiConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** "ollama" (local, free) or "anthropic" (Claude API, needs a key). */
	/** Log what bots hear and plan (INFO level). */
	public boolean debug = false;

	public String provider = "ollama";
	public String ollama_url = "http://localhost:11434";
	public String ollama_model = "llama3.2";
	/** Send only the bot's identity and notes, relying on a Modelfile (/ai modelfile) for the format. Auto-on for minecraift* models. */
	public boolean ollama_slim_prompt = false;
	/** How long Ollama keeps the model loaded between requests; keeps replies fast. */
	public String ollama_keep_alive = "30m";

	public String api_key = "";
	public String model = "claude-haiku-4-5-20251001";
	public int chat_radius = 32;
	public int cooldown_seconds = 4;
	/** Whether bots may attack other players. They may always fight mobs. */
	public boolean allow_pvp = false;
	/** Command names bots may never run (first word, namespace ignored). */
	public java.util.List<String> blocked_commands = new java.util.ArrayList<>(java.util.List.of(
		"stop", "kill", "ban", "ban-ip", "banlist", "pardon", "pardon-ip", "kick", "op", "deop", "whitelist",
		"fill", "fillbiome", "setblock", "clone", "data", "execute", "function", "reload", "save-all", "save-off",
		"save-on", "setworldspawn", "spawnpoint", "gamerule", "difficulty", "defaultgamemode", "worldborder",
		"forceload", "publish", "debug", "damage", "clear", "summon", "place", "item", "loot", "transfer", "perf",
		"jfr", "tick", "adminecraft", "ae", "ac", "ai", "datapack", "return", "rd", "random"));

	public String default_persona = "a friendly, curious Minecraft player who likes building and exploring";

	/** Slim prompts rely on a Modelfile for the format (see /ai modelfile); auto-on for minecraift* models. */
	public boolean useSlim() {
		return isOllama() && (ollama_slim_prompt || ollama_model.toLowerCase().startsWith("minecraift"));
	}

	public boolean isOllama() {
		return !"anthropic".equalsIgnoreCase(provider);
	}

	/** Ollama needs no credentials; Anthropic needs a key. */
	public boolean ready() {
		return isOllama() || !key().isEmpty();
	}

	public String key() {
		if (api_key != null && !api_key.isBlank()) return api_key.trim();
		String env = System.getenv("ANTHROPIC_API_KEY");
		return env == null ? "" : env.trim();
	}

	public static AiConfig load(Path file) {
		try {
			if (Files.exists(file)) {
				AiConfig c = GSON.fromJson(Files.readString(file), AiConfig.class);
				if (c != null) return c;
			} else {
				Files.writeString(file, GSON.toJson(new AiConfig()));
			}
		} catch (IOException | RuntimeException e) {
			MinecrAIft.LOGGER.error("Could not read {}", file, e);
		}
		return new AiConfig();
	}
}
