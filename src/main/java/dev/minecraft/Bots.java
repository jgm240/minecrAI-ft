package dev.minecraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ConnectedClientData;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Uuids;
import net.minecraft.util.WorldSavePath;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Creates, tracks, persists and respawns AI players. */
public final class Bots {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
	private static final int RESPAWN_DELAY_TICKS = 100;

	public static final Set<AiPlayer> ALL = ConcurrentHashMap.newKeySet();

	/** name -> persona, for bots that should be online (restored on server start). */
	private static final Map<String, String> ROSTER = new LinkedHashMap<>();
	private static final List<Pending> RESPAWNS = new ArrayList<>();
	private static Path rosterFile;

	private record Pending(String name, String persona, long atTick) {}

	private Bots() {}

	public static AiPlayer find(String name) {
		for (AiPlayer b : ALL) {
			if (b.botName().equalsIgnoreCase(name)) return b;
		}
		return null;
	}

	/** @return an error message, or null on success. Pass world == null to restore a saved bot where it was. */
	public static String spawn(MinecraftServer server, String name, String persona, ServerWorld world,
		double x, double y, double z, float yaw) {
		if (!NAME.matcher(name).matches()) return "Names must be 1-16 letters, digits or underscores.";
		PlayerManager pm = server.getPlayerManager();
		if (pm.getPlayer(name) != null) return name + " is already online.";

		GameProfile profile = new GameProfile(Uuids.getOfflinePlayerUuid(name), name);
		if (pm.getUserBanList().contains(profile)) return name + " is banned. Use /pardon " + name + " first.";

		ServerWorld spawnWorld = world != null ? world : server.getOverworld();
		AiPlayer bot = new AiPlayer(server, spawnWorld, profile);
		bot.setPersona(persona);
		ALL.add(bot);
		pm.onPlayerConnect(new FakeConnection(bot), bot, ConnectedClientData.createDefault(profile, false));
		if (world != null) {
			bot.teleport(world, x, y, z, java.util.Set.of(), yaw, 0f, true);
		}
		ROSTER.put(name, persona);
		saveRoster();
		return null;
	}

	/** Make the bot leave the game (shows the normal "left the game" message). */
	public static void remove(AiPlayer bot, String reason) {
		forget(bot.botName());
		bot.networkHandler.disconnect(Text.literal(reason));
	}

	public static void forget(String name) {
		if (ROSTER.remove(name) != null) saveRoster();
	}

	public static void onDeath(AiPlayer bot) {
		MinecraftServer server = bot.getServer();
		RESPAWNS.add(new Pending(bot.botName(), bot.persona(), server.getTicks() + RESPAWN_DELAY_TICKS));
		server.execute(() -> bot.networkHandler.disconnect(Text.literal("Died")));
	}

	public static void tick(MinecraftServer server) {
		if (RESPAWNS.isEmpty()) return;
		for (Pending p : new ArrayList<>(RESPAWNS)) {
			if (server.getTicks() < p.atTick()) continue;
			RESPAWNS.remove(p);
			String err = spawn(server, p.name(), p.persona(), null, 0, 0, 0, 0);
			if (err != null) {
				MinecrAIft.LOGGER.warn("Could not respawn {}: {}", p.name(), err);
				forget(p.name());
			}
		}
	}

	// --- persistence ---

	public static void loadAndRestore(MinecraftServer server) {
		rosterFile = server.getSavePath(WorldSavePath.ROOT).resolve("minecraift_bots.json");
		ROSTER.clear();
		try {
			if (Files.exists(rosterFile)) {
				Map<String, String> m = GSON.fromJson(Files.readString(rosterFile), new TypeToken<Map<String, String>>() {}.getType());
				if (m != null) ROSTER.putAll(m);
			}
		} catch (IOException | RuntimeException e) {
			MinecrAIft.LOGGER.error("Could not read bot roster", e);
		}
		for (Map.Entry<String, String> e : new ArrayList<>(ROSTER.entrySet())) {
			String err = spawn(server, e.getKey(), e.getValue(), null, 0, 0, 0, 0);
			if (err != null) {
				MinecrAIft.LOGGER.warn("Not restoring bot {}: {}", e.getKey(), err);
				ROSTER.remove(e.getKey());
			}
		}
		saveRoster();
	}

	public static void updatePersona(AiPlayer bot) {
		ROSTER.put(bot.botName(), bot.persona());
		saveRoster();
	}

	private static void saveRoster() {
		if (rosterFile == null) return;
		try {
			Files.writeString(rosterFile, GSON.toJson(ROSTER));
		} catch (IOException e) {
			MinecrAIft.LOGGER.error("Could not save bot roster", e);
		}
	}
}
