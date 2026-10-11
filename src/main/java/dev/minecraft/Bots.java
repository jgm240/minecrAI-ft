package dev.minecraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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

	/** What a bot keeps across restarts and deaths: its personality and everything it was taught. */
	public static final class Brain {
		public String persona = "";
		public List<String> notes = new ArrayList<>();

		public Brain() {}

		public Brain(String persona, List<String> notes) {
			this.persona = persona == null ? "" : persona;
			this.notes = new ArrayList<>(notes);
		}
	}

	/** name -> brain, for bots that should be online (restored on server start). */
	private static final Map<String, Brain> ROSTER = new LinkedHashMap<>();
	private static final List<Pending> RESPAWNS = new ArrayList<>();
	private static Path rosterFile;

	private record Pending(String name, Brain brain, long atTick) {}

	private Bots() {}

	/** Drop tracked bots that are no longer the server's live player of that name. */
	private static void prune(MinecraftServer server) {
		PlayerManager pm = server.getPlayerManager();
		ALL.removeIf(b -> b.isRemoved() || pm.getPlayer(b.getUuid()) != b);
	}

	public static AiPlayer find(String name) {
		for (AiPlayer b : ALL) {
			if (b.botName().equalsIgnoreCase(name) && !b.isRemoved()) return b;
		}
		return null;
	}

	/** @return an error message, or null on success. Pass world == null to restore a saved bot where it was. */
	public static String spawn(MinecraftServer server, String name, Brain brain, ServerWorld world,
		double x, double y, double z, float yaw) {
		if (!NAME.matcher(name).matches()) return "Names must be 1-16 letters, digits or underscores.";
		PlayerManager pm = server.getPlayerManager();
		prune(server);
		if (pm.getPlayer(name) != null) return name + " is already online.";

		GameProfile profile = new GameProfile(Uuids.getOfflinePlayerUuid(name), name);
		if (pm.getUserBanList().contains(profile)) return name + " is banned. Use /pardon " + name + " first.";

		ServerWorld spawnWorld = world != null ? world : server.getOverworld();
		AiPlayer bot = new AiPlayer(server, spawnWorld, profile);
		bot.setPersona(brain.persona);
		bot.setNotes(brain.notes);
		ALL.add(bot);
		pm.onPlayerConnect(new FakeConnection(bot), bot, ConnectedClientData.createDefault(profile, false));
		if (world != null) {
			bot.teleport(world, x, y, z, java.util.Set.of(), yaw, 0f, true);
		}
		// A player saved while dead (or the server stopped mid-death) reloads with 0 health and never comes alive.
		if (bot.getHealth() <= 0f || bot.isDead()) {
			bot.markDead();
			RESPAWNS.add(new Pending(name, brain, server.getTicks() + 20));
			server.execute(() -> bot.networkHandler.disconnect(Text.literal("Respawning")));
			return null;
		}
		ROSTER.put(name, brain);
		saveRoster();
		return null;
	}

	/** Vanilla gives a respawned player a clean slate (items were dropped on death); mirror that. */
	private static void deletePlayerData(MinecraftServer server, String name) {
		Path dir = server.getSavePath(WorldSavePath.PLAYERDATA);
		String uuid = Uuids.getOfflinePlayerUuid(name).toString();
		for (String ext : new String[] {".dat", ".dat_old"}) {
			try {
				Files.deleteIfExists(dir.resolve(uuid + ext));
			} catch (IOException e) {
				MinecrAIft.LOGGER.warn("Could not delete {}{}: {}", uuid, ext, e.toString());
			}
		}
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
		RESPAWNS.add(new Pending(bot.botName(), new Brain(bot.persona(), bot.notes()), server.getTicks() + RESPAWN_DELAY_TICKS));
		server.execute(() -> bot.networkHandler.disconnect(Text.literal("Died")));
	}

	public static void tick(MinecraftServer server) {
		if (server.getTicks() % 20 == 0) prune(server);
		if (RESPAWNS.isEmpty()) return;
		for (Pending p : new ArrayList<>(RESPAWNS)) {
			if (server.getTicks() < p.atTick()) continue;
			RESPAWNS.remove(p);
			deletePlayerData(server, p.name());
			String err = spawn(server, p.name(), p.brain(), null, 0, 0, 0, 0);
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
				JsonObject root = JsonParser.parseString(Files.readString(rosterFile)).getAsJsonObject();
				for (Map.Entry<String, JsonElement> e : root.entrySet()) {
					if (e.getValue().isJsonPrimitive()) { // older format: name -> persona
						ROSTER.put(e.getKey(), new Brain(e.getValue().getAsString(), List.of()));
					} else {
						Brain b = GSON.fromJson(e.getValue(), Brain.class);
						ROSTER.put(e.getKey(), b == null ? new Brain() : b);
					}
				}
			}
		} catch (IOException | RuntimeException e) {
			MinecrAIft.LOGGER.error("Could not read bot roster", e);
		}
		for (Map.Entry<String, Brain> e : new ArrayList<>(ROSTER.entrySet())) {
			String err = spawn(server, e.getKey(), e.getValue(), null, 0, 0, 0, 0);
			if (err != null) {
				MinecrAIft.LOGGER.warn("Not restoring bot {}: {}", e.getKey(), err);
				ROSTER.remove(e.getKey());
			}
		}
		saveRoster();
	}

	/** Persist a bot's persona and notes after they change. */
	public static void updateBrain(AiPlayer bot) {
		ROSTER.put(bot.botName(), new Brain(bot.persona(), bot.notes()));
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
