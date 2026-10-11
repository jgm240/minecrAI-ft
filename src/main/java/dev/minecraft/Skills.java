package dev.minecraft;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/** Higher-level skills built from the primitive tasks: craft, smelt, gather, hunt, portal, collect. */
public final class Skills {
	private Skills() {}

	// ---------- helpers ----------

	static boolean tableNearby(AiPlayer bot) {
		ServerWorld w = bot.getServerWorld();
		BlockPos c = bot.getBlockPos();
		for (BlockPos p : BlockPos.iterate(c.add(-4, -3, -4), c.add(4, 3, 4))) {
			if (w.getBlockState(p).isOf(net.minecraft.block.Blocks.CRAFTING_TABLE)) return true;
		}
		return false;
	}

	/** Place `item` on the ground next to the bot. @return where it went, or null. */
	static BlockPos placeNearby(AiPlayer bot, Item item) {
		var inv = bot.getInventory();
		int slot = -1;
		for (int i = 0; i < inv.size(); i++) {
			if (inv.getStack(i).isOf(item)) {
				slot = i;
				break;
			}
		}
		if (slot < 0) return null;
		Actions.equip(bot, Registries.ITEM.getId(item).getPath());
		ServerWorld w = bot.getServerWorld();
		BlockPos me = bot.getBlockPos();
		for (int r = 1; r <= 2; r++) {
			for (Direction d : Direction.Type.HORIZONTAL) {
				BlockPos target = me.offset(d, r);
				if (!w.getBlockState(target).isReplaceable() || w.getBlockState(target.down()).isAir()) continue;
				BlockPos below = target.down();
				BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(below).add(0, 0.5, 0), Direction.UP, below, false);
				bot.lookAt(hit.getPos());
				ActionResult res = bot.interactionManager.interactBlock(bot, w, bot.getMainHandStack(), Hand.MAIN_HAND, hit);
				if (res.isAccepted()) {
					bot.swingHand(Hand.MAIN_HAND, true);
					return target;
				}
			}
		}
		return null;
	}

	static int count(AiPlayer bot, Item item) {
		return Crafting.inventoryCounts(bot).getOrDefault(item, 0);
	}

	// ---------- craft ----------

	static final class Craft implements Task {
		private final String name;
		private final int count;

		Craft(String name, int count) {
			this.name = name;
			this.count = Math.max(1, Math.min(count, 64));
		}

		public boolean tick(AiPlayer bot) {
			Item item = Crafting.item(name);
			if (item == null || item == Items.AIR) {
				bot.report("I don't know an item called " + name + ".");
				return true;
			}
			var server = bot.getServer();
			Crafting.Plan plan = Crafting.plan(server, bot, item, count);
			if (plan == null) {
				bot.report("I can't craft " + count + " " + name + " with what I have.");
				return true;
			}
			if (plan.needsTable && !tableNearby(bot)) {
				Item table = net.minecraft.block.Blocks.CRAFTING_TABLE.asItem();
				if (count(bot, table) == 0) {
					Crafting.Plan tp = Crafting.plan(server, bot, table, 1);
					if (tp == null) {
						bot.report("I need a crafting table nearby to make " + name + ".");
						return true;
					}
					for (Crafting.Entry e : tp.steps) Crafting.run(bot, e);
				}
				if (placeNearby(bot, table) == null) {
					bot.report("I couldn't find space to put down a crafting table.");
					return true;
				}
				plan = Crafting.plan(server, bot, item, count); // inventory changed
				if (plan == null) {
					bot.report("I can't craft " + name + " after making the table.");
					return true;
				}
			}
			for (Crafting.Entry e : plan.steps) {
				if (!Crafting.run(bot, e)) {
					bot.report("Crafting " + name + " failed partway.");
					return true;
				}
			}
			bot.hear("[you] crafted " + name + " (now have " + count(bot, item) + ")");
			return true;
		}

		public String describe() {
			return "craft " + count + " " + name;
		}
	}

	// ---------- smelt ----------

	static final class Smelt implements Task {
		private final String name;
		private final int count;
		private BlockPos furnace;
		private int waited;
		private int limit;
		private boolean started;

		Smelt(String name, int count) {
			this.name = name;
			this.count = Math.max(1, Math.min(count, 64));
		}

		public boolean tick(AiPlayer bot) {
			ServerWorld w = bot.getServerWorld();
			if (!started) {
				started = true;
				Item item = Crafting.item(name);
				if (item == null || count(bot, item) == 0) {
					bot.report("I don't have " + name + " to smelt.");
					return true;
				}
				// find or place a furnace
				BlockPos c = bot.getBlockPos();
				for (BlockPos p : BlockPos.iterate(c.add(-4, -2, -4), c.add(4, 2, 4))) {
					if (w.getBlockState(p).isOf(net.minecraft.block.Blocks.FURNACE)) {
						furnace = p.toImmutable();
						break;
					}
				}
				if (furnace == null) furnace = placeNearby(bot, Items.FURNACE);
				if (furnace == null) {
					bot.report("I need a furnace to smelt " + name + ".");
					return true;
				}
				BlockEntity be = w.getBlockEntity(furnace);
				if (!(be instanceof AbstractFurnaceBlockEntity f)) return true;
				// fuel: cheapest burnable that isn't the input
				var fuels = w.getFuelRegistry();
				var inv = bot.getInventory();
				int need = count * 200;
				ItemStack fuelStack = ItemStack.EMPTY;
				for (int i = 0; i < inv.size(); i++) {
					ItemStack s = inv.getStack(i);
					if (!s.isEmpty() && !s.isOf(item) && fuels.isFuel(s)) {
						int each = fuels.getFuelTicks(s);
						int n = Math.min(s.getCount(), Math.max(1, (need + each - 1) / each));
						fuelStack = s.split(n);
						break;
					}
				}
				if (fuelStack.isEmpty()) {
					bot.report("I have no fuel for the furnace.");
					return true;
				}
				int moved = 0;
				ItemStack input = ItemStack.EMPTY;
				for (int i = 0; i < inv.size() && moved < count; i++) {
					ItemStack s = inv.getStack(i);
					if (s.isOf(item)) {
						int n = Math.min(count - moved, s.getCount());
						if (input.isEmpty()) input = s.split(n);
						else input.increment(s.split(n).getCount());
						moved += n;
					}
				}
				f.setStack(0, input);
				f.setStack(1, fuelStack);
				limit = count * 200 + 100;
				return false;
			}
			if (!(w.getBlockEntity(furnace) instanceof AbstractFurnaceBlockEntity f)) return true;
			ItemStack out = f.getStack(2);
			boolean inputGone = f.getStack(0).isEmpty();
			if ((inputGone && !out.isEmpty()) || ++waited > limit) {
				if (!out.isEmpty()) {
					bot.getInventory().offerOrDrop(out.copy());
					f.setStack(2, ItemStack.EMPTY);
				}
				// give back anything unprocessed
				if (!f.getStack(0).isEmpty()) {
					bot.getInventory().offerOrDrop(f.getStack(0).copy());
					f.setStack(0, ItemStack.EMPTY);
				}
				bot.hear("[you] smelted " + name);
				return true;
			}
			return false;
		}

		public String describe() {
			return "smelt " + count + " " + name;
		}
	}

	// ---------- collect drops ----------

	static final class Collect implements Task {
		private int ticks;

		public boolean tick(AiPlayer bot) {
			if (++ticks > 160) return true;
			List<ItemEntity> items = bot.getServerWorld().getEntitiesByClass(ItemEntity.class,
				bot.getBoundingBox().expand(8.0, 3.0, 8.0), e -> e.isAlive() && !e.cannotPickup());
			if (items.isEmpty()) return true;
			ItemEntity nearest = items.get(0);
			for (ItemEntity e : items) if (bot.squaredDistanceTo(e) < bot.squaredDistanceTo(nearest)) nearest = e;
			bot.navigateTo(nearest.getPos(), 0.4);
			return false;
		}

		public String describe() {
			return "pick up nearby items";
		}
	}

	// ---------- gather ----------

	static boolean matches(BlockState st, String spec) {
		String id = Registries.BLOCK.getId(st.getBlock()).getPath();
		switch (spec) {
			case "log", "logs", "wood", "tree", "trees":
				return st.isIn(BlockTags.LOGS);
			case "ore", "ores":
				return id.endsWith("_ore") || id.equals("ancient_debris");
			case "stone":
				return id.equals("stone") || id.equals("cobblestone") || id.equals("deepslate")
					|| id.equals("andesite") || id.equals("diorite") || id.equals("granite");
			default:
				return id.equals(spec) || id.equals("deepslate_" + spec);
		}
	}

	static final class Gather implements Task {
		private final String spec;
		private final int count;
		private int mined;
		private Task current;
		private final Deque<BlockPos> skipped = new ArrayDeque<>();
		private int idle;

		Gather(String spec, int count) {
			this.spec = spec.toLowerCase(Locale.ROOT).replace("minecraft:", "");
			this.count = Math.max(1, Math.min(count, 64));
		}

		private BlockPos nearest(AiPlayer bot) {
			ServerWorld w = bot.getServerWorld();
			BlockPos c = bot.getBlockPos();
			BlockPos best = null;
			double bestD = Double.MAX_VALUE;
			for (int r : new int[] {10, 20}) {
				for (BlockPos p : BlockPos.iterate(c.add(-r, -6, -r), c.add(r, 8, r))) {
					if (skipped.contains(p)) continue;
					BlockState st = w.getBlockState(p);
					if (st.isAir() || !matches(st, spec)) continue;
					// prefer low blocks (trunks are mined from the bottom) and near ones
					double d = c.getSquaredDistance(p) + Math.max(0, p.getY() - c.getY()) * 6.0;
					if (d < bestD) {
						bestD = d;
						best = p.toImmutable();
					}
				}
				if (best != null) break;
			}
			return best;
		}

		public boolean tick(AiPlayer bot) {
			if (current != null) {
				if (!current.tick(bot)) return false;
				current = null;
				return false;
			}
			if (mined >= count) {
				bot.hear("[you] gathered " + mined + " " + spec);
				return true;
			}
			BlockPos target = nearest(bot);
			if (target == null) {
				bot.report("I can't find any " + spec + " nearby" + (mined > 0 ? " (got " + mined + ")." : "."));
				return true;
			}
			Mine m = new Mine(target);
			Seq s = new Seq(List.of(m, new Collect()));
			current = new Task() {
				public boolean tick(AiPlayer b) {
					boolean done = s.tick(b);
					if (done) {
						if (m.succeeded()) mined++;
						else skipped.add(target);
					}
					return done;
				}

				public String describe() {
					return "mine " + target.toShortString();
				}
			};
			return false;
		}

		public String describe() {
			return "gather " + count + " " + spec;
		}
	}

	/** The Actions.Mine task, exposed so skills can check how it ended. */
	static final class Mine implements Task {
		private final Actions.Mine inner;
		private boolean done;

		Mine(BlockPos p) {
			inner = new Actions.Mine(p);
		}

		boolean succeeded() {
			return inner.broke;
		}

		public boolean tick(AiPlayer bot) {
			return inner.tick(bot);
		}

		public String describe() {
			return inner.describe();
		}
	}

	// ---------- hunt ----------

	static final class Hunt implements Task {
		private final String target;
		private final int count;
		private int killed;
		private Actions.Attack current;

		Hunt(String target, int count) {
			this.target = target;
			this.count = Math.max(1, Math.min(count, 20));
		}

		public boolean tick(AiPlayer bot) {
			if (current == null) {
				if (killed >= count) return true;
				current = new Actions.Attack(target.equals("hostile") || target.equals("any") ? "" : target);
			}
			if (current.tick(bot)) {
				if (current.killed()) killed++;
				else return true; // couldn't find / refused
				current = null;
			}
			return false;
		}

		public String describe() {
			return "hunt " + count + " " + target;
		}
	}

	// ---------- sequence ----------

	static final class Seq implements Task {
		private final Deque<Task> steps;
		private final String label;

		Seq(List<Task> steps) {
			this(steps, "sequence");
		}

		Seq(List<Task> steps, String label) {
			this.steps = new ArrayDeque<>(steps);
			this.label = label;
		}

		public boolean tick(AiPlayer bot) {
			Task t = steps.peekFirst();
			if (t == null) return true;
			if (t.tick(bot)) steps.pollFirst();
			return steps.isEmpty();
		}

		public String describe() {
			return label;
		}
	}

	// ---------- nether portal ----------

	static Task portal(AiPlayer bot) {
		Item obsidian = net.minecraft.block.Blocks.OBSIDIAN.asItem();
		boolean haveScaffold = false;
		for (String id : new String[] {"dirt", "cobblestone", "cobbled_deepslate", "netherrack", "oak_planks", "stone", "sand"}) {
			if (count(bot, Crafting.item(id)) > 0) haveScaffold = true;
		}
		int needObsidian = haveScaffold ? 10 : 11; // without a spare block, one extra obsidian serves as scaffold
		if (count(bot, obsidian) < needObsidian) {
			bot.report("I need " + needObsidian + " obsidian to build a portal (I have " + count(bot, obsidian) + ").");
			return null;
		}
		if (count(bot, Items.FLINT_AND_STEEL) == 0) {
			bot.report("I need flint and steel to light a portal.");
			return null;
		}
		Direction facing = bot.getHorizontalFacing();
		Direction across = facing.rotateYClockwise(); // frame extends along this axis
		// The bottom row is sunk into the ground (like players do), so the side columns can rest on the ground beside it.
		BlockPos base = bot.getBlockPos().down().offset(facing, 3).offset(across, -1); // left-bottom corner of the 4-wide frame
		List<Task> steps = new java.util.ArrayList<>();
		for (int x = 1; x <= 2; x++) { // dig out the two bottom blocks, then set obsidian in their place
			steps.add(new Actions.Mine(base.offset(across, x)));
			steps.add(new Actions.Place(base.offset(across, x), "obsidian"));
		}
		for (int x = 1; x <= 2; x++) { // clear the interior
			for (int y = 1; y <= 3; y++) steps.add(new Actions.Mine(base.offset(across, x).up(y)));
		}
		for (int y = 1; y <= 3; y++) { // side columns stand on the ground blocks at x = 0 and x = 3
			steps.add(new Actions.Place(base.offset(across, 0).up(y), "obsidian"));
			steps.add(new Actions.Place(base.offset(across, 3).up(y), "obsidian"));
		}
		// The top row touches the columns only diagonally, so it needs a throwaway block at a corner to rest on.
		String scaffold = null;
		for (String id : new String[] {"dirt", "cobblestone", "cobbled_deepslate", "netherrack", "oak_planks", "stone", "sand"}) {
			if (count(bot, Crafting.item(id)) > 0) {
				scaffold = id;
				break;
			}
		}
		BlockPos corner = base.offset(across, 0).up(4);
		steps.add(new Actions.Place(corner, scaffold != null ? scaffold : "obsidian"));
		steps.add(new Actions.Place(base.offset(across, 1).up(4), "obsidian"));
		steps.add(new Actions.Place(base.offset(across, 2).up(4), "obsidian"));
		if (scaffold != null) steps.add(new Actions.Mine(corner)); // take the scaffold back down
		BlockPos lightAt = base.offset(across, 1); // interior bottom: top face of the lower obsidian
		steps.add(new Task() {
			private int waited = -1;

			public boolean tick(AiPlayer b) {
				ServerWorld w = b.getServerWorld();
				BlockPos inside = lightAt.up();
				if (waited >= 0) { // verify the portal formed
					if (w.getBlockState(inside).isOf(net.minecraft.block.Blocks.NETHER_PORTAL)) {
						b.hear("[you] the nether portal is lit");
						return true;
					}
					if (++waited > 10) {
						b.report("I built the frame but the portal didn't light.");
						return true;
					}
					return false;
				}
				if (!Actions.equip(b, "flint_and_steel")) {
					b.report("I lost my flint and steel.");
					return true;
				}
				// face the frame from in front of it, aiming at the top of the lower obsidian
				BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(lightAt).add(0, 0.5, 0), Direction.UP, lightAt, false);
				b.lookAt(hit.getPos());
				ActionResult r = b.interactionManager.interactBlock(b, w, b.getMainHandStack(), Hand.MAIN_HAND, hit);
				if (r.isAccepted()) b.swingHand(Hand.MAIN_HAND, true);
				else {
					b.report("The flint and steel wouldn't light (" + r + ").");
					return true;
				}
				waited = 0;
				return false;
			}

			public String describe() {
				return "light the portal";
			}
		});
		return new Seq(steps, "build a nether portal");
	}

	// ---------- eat ----------

	static boolean isFood(ItemStack s) {
		return !s.isEmpty() && s.get(DataComponentTypes.FOOD) != null;
	}
}
