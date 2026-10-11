package dev.minecraft;

import com.mojang.authlib.GameProfile;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.network.message.MessageType;
import net.minecraft.network.message.SignedMessage;
import net.minecraft.network.packet.c2s.common.SyncedClientOptions;
import net.minecraft.network.packet.s2c.play.ChatMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.ProfilelessChatMessageS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;

import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

/** A real server-side player driven by an AI model instead of a client. */
public class AiPlayer extends ServerPlayerEntity {
	public enum Mode { WANDER, FOLLOW, STAY }

	private static final int LOG_LINES = 15;

	private Mode mode = Mode.WANDER;
	private UUID followTarget;
	private String persona = "";
	private final Deque<String> log = new ArrayDeque<>();
	private boolean dead;
	private final Deque<Task> tasks = new ArrayDeque<>();
	private boolean reportedFailure;

	// wander state
	private int wanderTicks;
	private boolean walking;
	private float wanderYaw;

	// brain bookkeeping (server thread only)
	boolean busy;
	long nextReplyTick;

	public AiPlayer(MinecraftServer server, ServerWorld world, GameProfile profile) {
		super(server, world, profile, SyncedClientOptions.createDefault());
	}

	public String botName() {
		return getGameProfile().getName();
	}

	public boolean isDeadOrRespawning() {
		return dead;
	}

	public String persona() {
		return persona;
	}

	public void setPersona(String persona) {
		this.persona = persona;
	}

	public Mode mode() {
		return mode;
	}

	public void follow(UUID target) {
		this.followTarget = target;
		this.mode = Mode.FOLLOW;
	}

	public void setMode(Mode mode) {
		this.mode = mode;
		if (mode != Mode.FOLLOW) followTarget = null;
	}

	/** Replace the current plan. */
	public void setTasks(java.util.List<Task> list) {
		tasks.clear();
		tasks.addAll(list);
		reportedFailure = false;
	}

	public boolean hasTasks() {
		return !tasks.isEmpty();
	}

	/** A task couldn't be done: remember it for the model and tell the chat once per plan. */
	public void report(String problem) {
		hear("[you] " + problem);
		if (!reportedFailure) {
			reportedFailure = true;
			say(problem);
		}
	}

	/** Walk (with simulated key presses) toward a point. @return true once within stopDistance horizontally. */
	public boolean walkToward(Vec3d target, double stopDistance) {
		double dx = target.x - getX(), dz = target.z - getZ();
		double dist = Math.sqrt(dx * dx + dz * dz);
		if (dist <= stopDistance && Math.abs(target.y - getY()) < 3.0) return true;
		float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
		setYaw(yaw);
		setHeadYaw(yaw);
		setBodyYaw(yaw);
		forwardSpeed = 1f;
		setSprinting(dist > 8.0);
		if ((horizontalCollision && isOnGround()) || isTouchingWater() || target.y - getY() > 0.6 && isOnGround() && dist < 2.0) {
			setJumping(true);
		}
		return false;
	}

	public void lookAt(Vec3d p) {
		double dx = p.x - getX(), dy = p.y - getEyeY(), dz = p.z - getZ();
		double h = Math.sqrt(dx * dx + dz * dz);
		float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
		float pitch = (float) -Math.toDegrees(Math.atan2(dy, h));
		setYaw(yaw);
		setHeadYaw(yaw);
		setBodyYaw(yaw);
		setPitch(pitch);
	}

	public void hear(String line) {
		log.addLast(line);
		while (log.size() > LOG_LINES) log.removeFirst();
	}

	public String recentLog() {
		return String.join("\n", log);
	}

	/** Chat publicly, exactly like a player typing a message. */
	public void say(String text) {
		MinecraftServer server = getServer();
		if (server == null) return;
		server.getPlayerManager().broadcast(SignedMessage.ofUnsigned(text), this,
			MessageType.params(MessageType.CHAT, this));
		hear(botName() + ": " + text);
	}

	/** Reply privately with /msg, as a normal player would. */
	public void whisper(String to, String text) {
		MinecraftServer server = getServer();
		if (server == null) return;
		server.getCommandManager().executeWithPrefix(getCommandSource(), "msg " + to + " " + text);
		hear(botName() + " (to " + to + "): " + text);
	}

	/** Called by the fake connection for every signed chat packet this player "receives". */
	void onChatPacket(ChatMessageS2CPacket packet) {
		var params = packet.serializedParameters();
		String text = packet.unsignedContent() != null ? packet.unsignedContent().getString() : packet.body().content();
		received(params.name().getString(), text, params.type().matchesKey(MessageType.MSG_COMMAND_INCOMING));
	}

	/** Chat without a signing profile, e.g. messages sent by other bots. */
	void onChatPacket(ProfilelessChatMessageS2CPacket packet) {
		var params = packet.chatType();
		received(params.name().getString(), packet.message().getString(),
			params.type().matchesKey(MessageType.MSG_COMMAND_INCOMING));
	}

	private void received(String sender, String text, boolean dm) {
		MinecraftServer server = getServer();
		if (server == null || sender.equals(botName())) return;
		server.execute(() -> AiBrain.onChat(server, this, sender, text, dm));
	}

	@Override
	public void tick() {
		MinecraftServer server = getServer();
		if (server != null && server.getTicks() % 10 == 0) {
			networkHandler.syncWithPlayerPosition();
			getServerWorld().getChunkManager().updatePosition(this);
		}
		try {
			super.tick();
			playerTick();
		} catch (NullPointerException ignored) {
			// partially-initialised fake connection; harmless
		}
		if (!dead) drive();
	}

	@Override
	public void onDeath(DamageSource source) {
		super.onDeath(source);
		if (dead) return;
		dead = true;
		Bots.onDeath(this);
	}

	/** Pretend to press movement keys. */
	private void drive() {
		forwardSpeed = 0f;
		sidewaysSpeed = 0f;
		setJumping(false);
		setSprinting(false);

		Task current = tasks.peekFirst();
		if (current != null) {
			boolean done;
			try {
				done = current.tick(this);
			} catch (RuntimeException e) {
				MinecrAIft.LOGGER.warn("Task '{}' failed for {}: {}", current.describe(), botName(), e.toString());
				done = true;
			}
			if (done) tasks.pollFirst();
			return;
		}

		float desiredYaw = getYaw();
		float speed = 0f;

		if (mode == Mode.FOLLOW) {
			ServerPlayerEntity t = followTarget == null ? null : getServer().getPlayerManager().getPlayer(followTarget);
			if (t == null) {
				mode = Mode.WANDER;
			} else if (t.getServerWorld() != getServerWorld() || squaredDistanceTo(t) > 48 * 48) {
				networkHandler.requestTeleport(t.getX(), t.getY(), t.getZ(), t.getYaw(), 0f);
			} else {
				double dx = t.getX() - getX(), dz = t.getZ() - getZ();
				double dist = Math.sqrt(dx * dx + dz * dz);
				desiredYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
				if (dist > 3.0) {
					speed = 1f;
					setSprinting(dist > 9.0);
				}
			}
		} else if (mode == Mode.WANDER) {
			if (--wanderTicks <= 0) {
				walking = random.nextInt(3) == 0;
				wanderYaw = random.nextFloat() * 360f;
				wanderTicks = walking ? 20 + random.nextInt(60) : 40 + random.nextInt(80);
			}
			if (walking) {
				desiredYaw = wanderYaw;
				speed = 0.6f;
			}
		}

		if (speed > 0f) {
			setYaw(desiredYaw);
			setHeadYaw(desiredYaw);
			setBodyYaw(desiredYaw);
			forwardSpeed = speed;
			if ((horizontalCollision && isOnGround()) || isTouchingWater()) setJumping(true);
		}
	}
}
