package dev.minecraft;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.ai.goal.LookAroundGoal;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.ai.goal.WanderAroundGoal;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.text.Text;
import net.minecraft.world.World;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.UUID;

/** A fake player whose chat and behaviour are driven by an AI model. */
public class AiPlayerEntity extends PathAwareEntity {
	public enum Mode { WANDER, FOLLOW, STAY }

	private static final int LOG_LINES = 15;

	private Mode mode = Mode.WANDER;
	private UUID followTarget;
	private String persona = "";
	private final Deque<String> log = new ArrayDeque<>();

	// server-thread-only bookkeeping for the brain
	boolean busy;
	long nextReplyTick;

	public AiPlayerEntity(EntityType<? extends PathAwareEntity> type, World world) {
		super(type, world);
		setPersistent();
	}

	public static DefaultAttributeContainer.Builder createAttributes() {
		return PathAwareEntity.createMobAttributes()
			.add(EntityAttributes.MAX_HEALTH, 20.0)
			.add(EntityAttributes.MOVEMENT_SPEED, 0.28);
	}

	@Override
	protected void initGoals() {
		goalSelector.add(1, new FollowGoal());
		goalSelector.add(2, new WanderAroundGoal(this, 0.7) {
			@Override
			public boolean canStart() {
				return mode == Mode.WANDER && super.canStart();
			}

			@Override
			public boolean shouldContinue() {
				return mode == Mode.WANDER && super.shouldContinue();
			}
		});
		goalSelector.add(3, new LookAtEntityGoal(this, PlayerEntity.class, 8.0f));
		goalSelector.add(4, new LookAroundGoal(this));
	}

	@Override
	public boolean canImmediatelyDespawn(double distanceSquared) {
		return false;
	}

	public String botName() {
		Text n = getCustomName();
		return n == null ? "Bot" : n.getString();
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
		if (mode == Mode.STAY) getNavigation().stop();
	}

	/** Remember something said nearby. */
	public void hear(String line) {
		log.addLast(line);
		while (log.size() > LOG_LINES) log.removeFirst();
	}

	public String recentLog() {
		return String.join("\n", log);
	}

	/** Say something in public chat, as if typed by this player. */
	public void say(String text) {
		MinecraftServer server = getServer();
		if (server == null) return;
		server.getPlayerManager().broadcast(Text.literal("<" + botName() + "> " + text), false);
		hear(botName() + ": " + text);
	}

	@Override
	public void writeCustomDataToNbt(NbtCompound nbt) {
		super.writeCustomDataToNbt(nbt);
		nbt.putString("Persona", persona);
		nbt.putInt("Mode", mode.ordinal());
		if (followTarget != null) nbt.putUuid("FollowTarget", followTarget);
	}

	@Override
	public void readCustomDataFromNbt(NbtCompound nbt) {
		super.readCustomDataFromNbt(nbt);
		persona = nbt.getString("Persona");
		Mode[] modes = Mode.values();
		int m = nbt.getInt("Mode");
		mode = m >= 0 && m < modes.length ? modes[m] : Mode.WANDER;
		followTarget = nbt.containsUuid("FollowTarget") ? nbt.getUuid("FollowTarget") : null;
	}

	private PlayerEntity target() {
		return followTarget == null ? null : getWorld().getPlayerByUuid(followTarget);
	}

	private class FollowGoal extends Goal {
		FollowGoal() {
			setControls(EnumSet.of(Control.MOVE));
		}

		@Override
		public boolean canStart() {
			PlayerEntity t = target();
			return mode == Mode.FOLLOW && t != null && squaredDistanceTo(t) > 9.0;
		}

		@Override
		public boolean shouldContinue() {
			PlayerEntity t = target();
			return mode == Mode.FOLLOW && t != null && squaredDistanceTo(t) > 4.0;
		}

		@Override
		public void tick() {
			PlayerEntity t = target();
			if (t != null && age % 10 == 0) getNavigation().startMovingTo(t, 1.1);
		}

		@Override
		public void stop() {
			getNavigation().stop();
		}
	}
}
