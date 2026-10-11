package dev.minecraft;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.Locale;

/** Everything a bot can do, as small tasks the model can chain together. */
public final class Actions {
	private static final double REACH = 4.5;

	private Actions() {}

	/** Build a task from one JSON action object, or null if it isn't understood. */
	public static Task parse(JsonObject o, MinecraftServer server) {
		String type = str(o, "type").toLowerCase(Locale.ROOT);
		switch (type) {
			case "goto": return new Goto(pos(o));
			case "mine": return new Mine(pos(o));
			case "place": return new Place(pos(o), str(o, "item"));
			case "use_block": return new UseBlock(pos(o));
			case "use_item": return new UseItem();
			case "select": return new Select(str(o, "item"));
			case "drop": return new Drop(str(o, "item"), o.has("count") ? o.get("count").getAsInt() : 1);
			case "attack": return new Attack(str(o, "target"));
			case "craft": return new Skills.Craft(str(o, "item"), o.has("count") ? o.get("count").getAsInt() : 1);
			case "smelt": return new Skills.Smelt(str(o, "item"), o.has("count") ? o.get("count").getAsInt() : 1);
			case "gather": return new Skills.Gather(str(o, "block"), o.has("count") ? o.get("count").getAsInt() : 1);
			case "hunt": return new Skills.Hunt(str(o, "target"), o.has("count") ? o.get("count").getAsInt() : 1);
			case "collect": return new Skills.Collect();
			case "portal": return new PortalTask();
			case "eat": return new Eat();
			case "command": return new Command(str(o, "command"));
			case "follow": return new SetMode(AiPlayer.Mode.FOLLOW, str(o, "target"));
			case "stay": return new SetMode(AiPlayer.Mode.STAY, "");
			case "wander": return new SetMode(AiPlayer.Mode.WANDER, "");
			default: return null;
		}
	}

	private static String str(JsonObject o, String k) {
		JsonElement e = o.get(k);
		return e == null || e.isJsonNull() ? "" : e.getAsString();
	}

	private static BlockPos pos(JsonObject o) {
		return new BlockPos(o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt());
	}

	private static Item item(String id) {
		Identifier i = Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id);
		return i == null ? null : Registries.ITEM.get(i);
	}

	/** Put the first stack of this item into the selected hotbar slot. */
	static boolean equip(AiPlayer bot, String id) {
		Item want = item(id);
		if (want == null) return false;
		var inv = bot.getInventory();
		for (int i = 0; i < inv.size(); i++) {
			ItemStack st = inv.getStack(i);
			if (st.isOf(want)) {
				if (i < 9) {
					inv.selectedSlot = i;
				} else {
					int slot = inv.selectedSlot;
					ItemStack tmp = inv.getStack(slot);
					inv.setStack(slot, st);
					inv.setStack(i, tmp);
				}
				return true;
			}
		}
		return false;
	}

	private static BlockHitResult hitFor(AiPlayer bot, BlockPos p) {
		Vec3d c = Vec3d.ofCenter(p);
		Direction side = Direction.getFacing(bot.getX() - c.x, bot.getEyeY() - c.y, bot.getZ() - c.z);
		return new BlockHitResult(c.offset(side, 0.5), side, p, false);
	}

	private static boolean inReach(AiPlayer bot, BlockPos p) {
		return bot.getEyePos().squaredDistanceTo(Vec3d.ofCenter(p)) <= REACH * REACH;
	}

	// ---- tasks ----

	static final class Goto implements Task {
		private final BlockPos target;
		private int ticks;

		Goto(BlockPos t) {
			target = t;
		}

		public boolean tick(AiPlayer bot) {
			return bot.navigateTo(Vec3d.ofBottomCenter(target), 1.0) || ++ticks > 1200;
		}

		public String describe() {
			return "go to " + target.toShortString();
		}
	}

	static final class Mine implements Task {
		private final BlockPos pos;
		private float progress;
		private int ticks;
		boolean broke;

		Mine(BlockPos p) {
			pos = p;
		}

		public boolean tick(AiPlayer bot) {
			ServerWorld world = bot.getServerWorld();
			BlockState st = world.getBlockState(pos);
			if (st.isAir() || ++ticks > 1500) {
				world.setBlockBreakingInfo(bot.getId(), pos, -1);
				return true;
			}
			if (!inReach(bot, pos)) {
				bot.navigateTo(Vec3d.ofCenter(pos), 2.5);
				return false;
			}
			if (!world.canPlayerModifyAt(bot, pos) || bot.isBlockBreakingRestricted(world, pos, bot.interactionManager.getGameMode())) {
				bot.report("I'm not allowed to break the block at " + pos.toShortString());
				return true;
			}
			bot.lookAt(Vec3d.ofCenter(pos));
			// use the fastest tool on the hotbar
			var inv = bot.getInventory();
			int best = inv.selectedSlot;
			float bestSpeed = inv.getStack(best).getMiningSpeedMultiplier(st);
			for (int i = 0; i < 9; i++) {
				float sp = inv.getStack(i).getMiningSpeedMultiplier(st);
				if (sp > bestSpeed) {
					best = i;
					bestSpeed = sp;
				}
			}
			inv.selectedSlot = best;
			if (st.isToolRequired() && !inv.getStack(best).isSuitableFor(st)) {
				bot.report("I need a better tool to mine " + Perception.blockId(st) + ".");
				world.setBlockBreakingInfo(bot.getId(), pos, -1);
				return true;
			}

			float delta = st.calcBlockBreakingDelta(bot, world, pos);
			if (delta <= 0f) {
				bot.report("I can't break " + Perception.blockId(st) + ".");
				return true;
			}
			progress += delta;
			bot.swingHand(Hand.MAIN_HAND, true);
			world.setBlockBreakingInfo(bot.getId(), pos, Math.min(9, (int) (progress * 10f)));
			if (progress >= 1f) {
				world.setBlockBreakingInfo(bot.getId(), pos, -1);
				broke = bot.interactionManager.tryBreakBlock(pos);
				return true;
			}
			return false;
		}

		public String describe() {
			return "mine " + pos.toShortString();
		}
	}

	static final class Place implements Task {
		private final BlockPos pos;
		private final String itemId;
		private int ticks;

		Place(BlockPos p, String itemId) {
			pos = p;
			this.itemId = itemId;
		}

		public boolean tick(AiPlayer bot) {
			ServerWorld world = bot.getServerWorld();
			if (++ticks > 300) return true;
			if (!itemId.isEmpty() && !equip(bot, itemId)) {
				bot.report("I don't have " + itemId + " to place.");
				return true;
			}
			if (!inReach(bot, pos)) {
				bot.navigateTo(Vec3d.ofCenter(pos), 2.5);
				return false;
			}
			if (!world.canPlayerModifyAt(bot, pos)) {
				bot.report("I'm not allowed to build at " + pos.toShortString());
				return true;
			}
			for (Direction d : Direction.values()) {
				BlockPos support = pos.offset(d);
				if (world.getBlockState(support).isAir() || !world.getBlockState(support).getFluidState().isEmpty()) continue;
				Direction face = d.getOpposite();
				BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(support).offset(face, 0.5), face, support, false);
				bot.lookAt(hit.getPos());
				ActionResult r = bot.interactionManager.interactBlock(bot, world, bot.getMainHandStack(), Hand.MAIN_HAND, hit);
				if (r.isAccepted()) bot.swingHand(Hand.MAIN_HAND, true);
				else bot.report("I couldn't place that at " + pos.toShortString());
				return true;
			}
			bot.report("There's nothing to place against at " + pos.toShortString());
			return true;
		}

		public String describe() {
			return "place " + itemId + " at " + pos.toShortString();
		}
	}

	static final class UseBlock implements Task {
		private final BlockPos pos;
		private int ticks;

		UseBlock(BlockPos p) {
			pos = p;
		}

		public boolean tick(AiPlayer bot) {
			if (++ticks > 300) return true;
			if (!inReach(bot, pos)) {
				bot.navigateTo(Vec3d.ofCenter(pos), 2.5);
				return false;
			}
			bot.lookAt(Vec3d.ofCenter(pos));
			ServerWorld world = bot.getServerWorld();
			ActionResult r = bot.interactionManager.interactBlock(bot, world, bot.getMainHandStack(), Hand.MAIN_HAND, hitFor(bot, pos));
			if (r.isAccepted()) bot.swingHand(Hand.MAIN_HAND, true);
			return true;
		}

		public String describe() {
			return "use block at " + pos.toShortString();
		}
	}

	static final class UseItem implements Task {
		private int ticks;
		private boolean started;

		public boolean tick(AiPlayer bot) {
			if (!started) {
				started = true;
				bot.interactionManager.interactItem(bot, bot.getServerWorld(), bot.getMainHandStack(), Hand.MAIN_HAND);
				return !bot.isUsingItem();
			}
			return !bot.isUsingItem() || ++ticks > 80; // e.g. eating
		}

		public String describe() {
			return "use held item";
		}
	}

	static final class Select implements Task {
		private final String itemId;

		Select(String id) {
			itemId = id;
		}

		public boolean tick(AiPlayer bot) {
			if (!equip(bot, itemId)) bot.report("I don't have " + itemId + ".");
			return true;
		}

		public String describe() {
			return "hold " + itemId;
		}
	}

	static final class Drop implements Task {
		private final String itemId;
		private final int count;

		Drop(String id, int count) {
			itemId = id;
			this.count = Math.max(1, count);
		}

		public boolean tick(AiPlayer bot) {
			Item want = item(itemId);
			var inv = bot.getInventory();
			int left = count;
			for (int i = 0; i < inv.size() && left > 0 && want != null; i++) {
				ItemStack st = inv.getStack(i);
				if (!st.isOf(want)) continue;
				int n = Math.min(left, st.getCount());
				bot.dropItem(st.split(n), false, true);
				left -= n;
			}
			if (left == count) bot.report("I don't have " + itemId + " to drop.");
			return true;
		}

		public String describe() {
			return "drop " + count + " " + itemId;
		}
	}

	static final class Attack implements Task {
		private final String target;
		private Entity entity;
		private int ticks;
		private boolean killed;

		boolean killed() {
			return killed;
		}

		Attack(String target) {
			this.target = target;
		}

		public boolean tick(AiPlayer bot) {
			if (++ticks > 1200) return true;
			if (entity == null || !entity.isAlive()) {
				if (entity != null) {
					killed = true;
					return true;
				}
				entity = find(bot);
				if (entity == null) {
					bot.report("I can't find " + target + " to attack.");
					return true;
				}
				if (entity instanceof PlayerEntity && !MinecrAIft.CONFIG.allow_pvp) {
					bot.report("I'm not allowed to attack players.");
					return true;
				}
			}
			if (bot.squaredDistanceTo(entity) > 9.0) {
				bot.navigateTo(entity.getPos(), 2.0);
				return false;
			}
			bot.lookAt(entity.getEyePos());
			if (bot.getAttackCooldownProgress(0.5f) >= 1f) {
				bot.attack(entity);
				bot.swingHand(Hand.MAIN_HAND, true);
			}
			return false;
		}

		private Entity find(AiPlayer bot) {
			String want = target.toLowerCase(Locale.ROOT).replace("minecraft:", "");
			boolean anyHostile = want.isEmpty();
			List<Entity> near = bot.getServerWorld().getOtherEntities(bot, bot.getBoundingBox().expand(24.0),
				e -> e instanceof LivingEntity && e.isAlive());
			Entity best = null;
			for (Entity e : near) {
				if (anyHostile) {
					if (e instanceof net.minecraft.entity.mob.HostileEntity && (best == null || bot.squaredDistanceTo(e) < bot.squaredDistanceTo(best))) best = e;
					continue;
				}
				boolean nameMatch = e.getName().getString().equalsIgnoreCase(target);
				boolean typeMatch = Registries.ENTITY_TYPE.getId(e.getType()).getPath().equals(want);
				if ((nameMatch || typeMatch) && (best == null || bot.squaredDistanceTo(e) < bot.squaredDistanceTo(best))) best = e;
			}
			return best;
		}

		public String describe() {
			return "attack " + target;
		}
	}

	static final class Command implements Task {
		private final String command;

		Command(String c) {
			command = c;
		}

		/** Strip "/" and a namespace, lower-case, and take the first word. */
		static String root(String c) {
			String s = c.trim();
			while (s.startsWith("/")) s = s.substring(1).trim();
			String first = s.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
			int colon = first.indexOf(':');
			return colon >= 0 ? first.substring(colon + 1) : first;
		}

		public boolean tick(AiPlayer bot) {
			String c = command.replace("\n", " ").replace("\r", " ").trim();
			String root = root(c);
			if (root.isEmpty() || MinecrAIft.CONFIG.blocked_commands.contains(root)) {
				bot.report("I'm not allowed to run /" + root + ".");
				return true;
			}
			MinecrAIft.LOGGER.info("{} runs: /{}", bot.botName(), c);
			bot.getServer().getCommandManager().executeWithPrefix(bot.getCommandSource(), c);
			return true;
		}

		public String describe() {
			return "run /" + command;
		}
	}

	static final class SetMode implements Task {
		private final AiPlayer.Mode mode;
		private final String target;

		SetMode(AiPlayer.Mode m, String target) {
			mode = m;
			this.target = target;
		}

		public boolean tick(AiPlayer bot) {
			if (mode == AiPlayer.Mode.FOLLOW) {
				ServerPlayerEntity t = bot.getServer().getPlayerManager().getPlayer(target);
				if (t != null) bot.follow(t.getUuid());
			} else {
				bot.setMode(mode);
			}
			return true;
		}

		public String describe() {
			return mode.name().toLowerCase(Locale.ROOT);
		}
	}

	static final class PortalTask implements Task {
		private Task inner;

		public boolean tick(AiPlayer bot) {
			if (inner == null) {
				inner = Skills.portal(bot);
				if (inner == null) return true;
			}
			return inner.tick(bot);
		}

		public String describe() {
			return "build a nether portal";
		}
	}

	static final class Eat implements Task {
		private boolean started;
		private int ticks;

		public boolean tick(AiPlayer bot) {
			var inv = bot.getInventory();
			if (!started) {
				for (int i = 0; i < inv.size(); i++) {
					if (Skills.isFood(inv.getStack(i))) {
						if (i < 9) {
							inv.selectedSlot = i;
						} else {
							ItemStack tmp = inv.getStack(inv.selectedSlot);
							inv.setStack(inv.selectedSlot, inv.getStack(i));
							inv.setStack(i, tmp);
						}
						started = true;
						bot.interactionManager.interactItem(bot, bot.getServerWorld(), bot.getMainHandStack(), Hand.MAIN_HAND);
						return false;
					}
				}
				bot.report("I have nothing to eat.");
				return true;
			}
			return !bot.isUsingItem() || ++ticks > 80;
		}

		public String describe() {
			return "eat";
		}
	}
}
