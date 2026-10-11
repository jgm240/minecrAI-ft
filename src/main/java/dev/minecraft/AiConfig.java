package dev.minecraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** config/minecraift.json. The API key may also come from the ANTHROPIC_API_KEY environment variable. */
public final class AiConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public String api_key = "";
	public String model = "claude-haiku-4-5-20251001";
	public int chat_radius = 32;
	public int cooldown_seconds = 4;
	public String default_persona = "a friendly, curious Minecraft player who likes building and exploring";

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
