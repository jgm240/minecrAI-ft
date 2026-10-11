package dev.minecraft;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.ShapedRecipe;
import net.minecraft.recipe.ShapelessRecipe;
import net.minecraft.recipe.input.CraftingRecipeInput;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Recipe lookup and a small planner that can craft intermediate items (logs -> planks -> sticks -> pickaxe). */
public final class Crafting {
	private static final int MAX_DEPTH = 4;
	private static Map<Item, List<Entry>> byOutput;

	public static final class Entry {
		final List<Ingredient> ingredients;
		final ItemStack result;
		final boolean needsTable;

		Entry(List<Ingredient> ingredients, ItemStack result, boolean needsTable) {
			this.ingredients = ingredients;
			this.result = result;
			this.needsTable = needsTable;
		}
	}

	/** The result of planning: the recipes to run in order, and the simulated leftover inventory. */
	public static final class Plan {
		final Map<Item, Integer> inv;
		final List<Entry> steps = new ArrayList<>();
		boolean needsTable;

		Plan(Map<Item, Integer> inv) {
			this.inv = inv;
		}

		Plan copy() {
			Plan p = new Plan(new HashMap<>(inv));
			p.steps.addAll(steps);
			p.needsTable = needsTable;
			return p;
		}

		void assign(Plan o) {
			inv.clear();
			inv.putAll(o.inv);
			steps.clear();
			steps.addAll(o.steps);
			needsTable = o.needsTable;
		}
	}

	private Crafting() {}

	private static synchronized Map<Item, List<Entry>> index(MinecraftServer server) {
		if (byOutput != null) return byOutput;
		Map<Item, List<Entry>> map = new HashMap<>();
		for (RecipeEntry<?> re : server.getRecipeManager().values()) {
			if (!(re.value() instanceof CraftingRecipe cr)) continue;
			try {
				List<Ingredient> ings = cr.getIngredientPlacement().getIngredients();
				if (ings.isEmpty()) continue;
				CraftingRecipeInput probe;
				boolean table;
				if (cr instanceof ShapedRecipe sr) {
					List<ItemStack> stacks = new ArrayList<>();
					for (Optional<Ingredient> o : sr.getIngredients()) stacks.add(o.map(Crafting::firstStack).orElse(ItemStack.EMPTY));
					probe = CraftingRecipeInput.create(sr.getWidth(), sr.getHeight(), stacks);
					table = sr.getWidth() > 2 || sr.getHeight() > 2;
				} else if (cr instanceof ShapelessRecipe) {
					List<ItemStack> stacks = new ArrayList<>();
					for (Ingredient i : ings) stacks.add(firstStack(i));
					probe = CraftingRecipeInput.create(stacks.size(), 1, stacks);
					table = ings.size() > 4;
				} else {
					continue; // special recipes (dyeing, fireworks, ...)
				}
				ItemStack result = cr.craft(probe, server.getRegistryManager());
				if (result.isEmpty()) continue;
				map.computeIfAbsent(result.getItem(), k -> new ArrayList<>()).add(new Entry(ings, result, table));
			} catch (RuntimeException ignored) {
				// unusual recipe; skip it
			}
		}
		for (List<Entry> l : map.values()) {
			l.sort(Comparator.comparingInt((Entry e) -> e.needsTable ? 1 : 0).thenComparingInt(e -> e.ingredients.size()));
		}
		byOutput = map;
		return map;
	}

	private static ItemStack firstStack(Ingredient i) {
		return i.getMatchingItems().findFirst().map(h -> new ItemStack(h.value())).orElse(ItemStack.EMPTY);
	}

	public static List<Item> matching(Ingredient ing) {
		List<Item> out = new ArrayList<>();
		ing.getMatchingItems().forEach(h -> out.add(h.value()));
		return out;
	}

	public static Map<Item, Integer> inventoryCounts(AiPlayer bot) {
		Map<Item, Integer> m = new HashMap<>();
		var inv = bot.getInventory();
		for (int i = 0; i < inv.size(); i++) {
			ItemStack s = inv.getStack(i);
			if (!s.isEmpty()) m.merge(s.getItem(), s.getCount(), Integer::sum);
		}
		return m;
	}

	/** Plan to hold at least `count` of `item`, or null if impossible with the current inventory. */
	public static Plan plan(MinecraftServer server, AiPlayer bot, Item item, int count) {
		Plan p = new Plan(inventoryCounts(bot));
		return make(index(server), item, p.inv.getOrDefault(item, 0) + count, p, 0) ? p : null;
	}

	private static boolean make(Map<Item, List<Entry>> idx, Item item, int need, Plan p, int depth) {
		if (p.inv.getOrDefault(item, 0) >= need) return true;
		if (depth > MAX_DEPTH) return false;
		List<Entry> recipes = idx.get(item);
		if (recipes == null) return false;
		for (Entry e : recipes) {
			Plan trial = p.copy();
			int missing = need - trial.inv.getOrDefault(item, 0);
			int times = (missing + e.result.getCount() - 1) / e.result.getCount();
			boolean ok = true;
			for (int t = 0; t < times && ok; t++) {
				for (Ingredient ing : e.ingredients) {
					if (!take(idx, ing, trial, depth)) {
						ok = false;
						break;
					}
				}
				if (ok) {
					trial.inv.merge(item, e.result.getCount(), Integer::sum);
					trial.steps.add(e);
					trial.needsTable |= e.needsTable;
				}
			}
			if (ok) {
				p.assign(trial);
				return true;
			}
		}
		return false;
	}

	private static boolean take(Map<Item, List<Entry>> idx, Ingredient ing, Plan p, int depth) {
		List<Item> options = matching(ing);
		for (Item m : options) {
			if (p.inv.getOrDefault(m, 0) > 0) {
				p.inv.merge(m, -1, Integer::sum);
				return true;
			}
		}
		for (Item m : options) {
			if (make(idx, m, p.inv.getOrDefault(m, 0) + 1, p, depth + 1)) {
				p.inv.merge(m, -1, Integer::sum);
				return true;
			}
		}
		return false;
	}

	/** Consume ingredients from the real inventory and hand over the result. */
	public static boolean run(AiPlayer bot, Entry e) {
		var inv = bot.getInventory();
		for (Ingredient ing : e.ingredients) {
			boolean taken = false;
			for (Item m : matching(ing)) {
				for (int i = 0; i < inv.size() && !taken; i++) {
					ItemStack s = inv.getStack(i);
					if (s.isOf(m)) {
						s.decrement(1);
						taken = true;
					}
				}
				if (taken) break;
			}
			if (!taken) return false;
		}
		inv.offerOrDrop(e.result.copy());
		return true;
	}

	public static Item item(String name) {
		var id = net.minecraft.util.Identifier.tryParse(name.contains(":") ? name : "minecraft:" + name);
		if (id == null || !Registries.ITEM.containsId(id)) return null;
		return Registries.ITEM.get(id);
	}
}
