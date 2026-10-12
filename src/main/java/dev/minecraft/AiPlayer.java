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
	private final java.util.List<String> notes = new java.util.ArrayList<>();
	private final Deque<String> log = new ArrayDeque<>();
	private boolean dead;
	private boolean loggedTickFailure;
	private final Deque<Task> tasks = new ArrayDeque<>();
	private boolean reportedFailure;
	private int failStreak;

	// pathfinding state
	private java.util.List<net.minecraft.util.math.BlockPos> path;
	private int pathIdx;
	private net.minecraft.util.math.BlockPos pathGoal;
	private Vec3d lastCheckPos = Vec3d.ZERO;
	private int stuckTicks;
	private int repathIn;

	// standing objective the model keeps working on between chats
	private String goal = "";
	int goalSteps;
	long nextThinkTick;

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

	/** Things an operator taught this bot with /ai train. */
	public java.util.List<String> notes() {
		return java.util.List.copyOf(notes);
	}

	public void setNotes(java.util.List<String> n) {
		notes.clear();
		notes.addAll(n);
	}

	/** @return false if the notebook is full. */
	public boolean addNote(String text) {
		if (notes.size() >= 40) return false;
		notes.add(text.length() > 600 ? text.substring(0, 600) : text);
		return true;
	}

	public void clearNotes() {
		notes.clear();
	}

	public void markDead() {
		dead = true;
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
		failStreak = reportedFailure ? failStreak + 1 : 0; // did the previous plan hit a problem?
		reportedFailure = false;
	}

	/** How many plans in a row ended with something going wrong. */
	public int failStreak() {
		return failStreak;
	}

	public boolean hasTasks() {
		return !tasks.isEmpty();
	}

	public String goal() {
		return goal;
	}

	public void setGoal(String g) {
		String n = g == null ? "" : g.trim();
		if (!n.equals(goal)) {
			goalSteps = 0;
			failStreak = 0;
		}
		goal = n;
	}

	/** Walk to a spot using A* pathfinding, falling back to straight-line steering. @return true on arrival. */
	public boolean navigateTo(Vec3d target, double stopDistance) {
		double dx = target.x - getX(), dz = target.z - getZ();
		double dist = Math.sqrt(dx * dx + dz * dz);
		if (dist <= stopDistance && Math.abs(target.y - getY()) < 3.0) {
			path = null;
			return true;
		}
		net.minecraft.util.math.BlockPos goalPos = net.minecraft.util.math.BlockPos.ofFloored(target);
		boolean goalMoved = pathGoal == null || pathGoal.getSquaredDistance(goalPos) > 4;
		if (path == null || goalMoved || path.isEmpty() || pathIdx >= path.size() || --repathIn <= 0) {
			path = Pathfinder.find(getServerWorld(), getBlockPos(), goalPos, 4000, Math.max(0.8, stopDistance - 0.5));
			pathGoal = goalPos;
			pathIdx = 0;
			repathIn = 100;
			if (path.isEmpty()) return walkToward(target, stopDistance); // nowhere better; just push toward it
		}

		// advance along waypoints
		while (pathIdx < path.size()) {
			net.minecraft.util.math.BlockPos wp = path.get(pathIdx);
			double wx = wp.getX() + 0.5 - getX(), wz = wp.getZ() + 0.5 - getZ();
			if (Math.sqrt(wx * wx + wz * wz) < 0.55 && Math.abs(wp.getY() - getY()) < 1.3) pathIdx++;
			else break;
		}
		if (pathIdx >= path.size()) return walkToward(target, stopDistance);
		net.minecraft.util.math.BlockPos next = path.get(pathIdx);
		walkToward(Vec3d.ofBottomCenter(next), 0.1);
		if (next.getY() > getY() + 0.4 && isOnGround()) setJumping(true);

		// stuck detection: not moving -> replan and hop
		if (age % 20 == 0) {
			if (getPos().squaredDistanceTo(lastCheckPos) < 0.09) {
				if (++stuckTicks >= 2) {
					repathIn = 0;
					path = null;
					setJumping(true);
					stuckTicks = 0;
				}
			} else {
				stuckTicks = 0;
			}
			lastCheckPos = getPos();
		}
		return false;
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
		// A real client confirms a teleport (dimension change) itself; until it does, the portal cooldown never counts down.
		if (isInTeleportationState()) onTeleportationDone();
		try {
			super.tick();
			playerTick();
		} catch (NullPointerException e) {
			// partially-initialised fake connection; normally harmless, but log the first one so it can be diagnosed
			if (!loggedTickFailure) {
				loggedTickFailure = true;
				MinecrAIft.LOGGER.warn("Tick of {} threw a NullPointerException (further ones are suppressed)", botName(), e);
			}
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

		// reflex: eat when hungry and idle
		if (getHungerManager().getFoodLevel() <= 12 && !isUsingItem()) {
			for (int i = 0; i < getInventory().size(); i++) {
				if (Skills.isFood(getInventory().getStack(i))) {
					setTasks(java.util.List.of(new Actions.Eat()));
					return;
				}
			}
		}

		// keep working toward the goal: ask the model for the next steps once the plan is empty
		MinecraftServer srv = getServer();
		if (!goal.isEmpty() && !busy && srv != null && srv.getTicks() >= nextThinkTick) {
			nextThinkTick = srv.getTicks() + 40;
			AiBrain.think(srv, this);
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
				navigateTo(t.getPos(), 3.0);
				return;
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
