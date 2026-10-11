package dev.minecraft;

import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** A compact text description of what a bot can see and carry, for the model's prompt. */
public final class Perception {
	private static final int RADIUS = 6;

	private Perception() {}

	public static String describe(AiPlayer bot) {
		ServerWorld world = bot.getServerWorld();
		StringBuilder sb = new StringBuilder();
		BlockPos me = bot.getBlockPos();
		sb.append("Position: ").append(me.getX()).append(' ').append(me.getY()).append(' ').append(me.getZ())
			.append(" in ").append(world.getRegistryKey().getValue()).append('\n');
		sb.append("Health: ").append((int) bot.getHealth()).append("/20, food: ")
			.append(bot.getHungerManager().getFoodLevel()).append("/20, gamemode: ")
			.append(bot.interactionManager.getGameMode().getName()).append('\n');
		ItemStack held = bot.getMainHandStack();
		sb.append("Holding: ").append(held.isEmpty() ? "nothing" : held.getCount() + " " + id(held)).append('\n');

		// inventory
		Map<String, Integer> items = new LinkedHashMap<>();
		PlayerInventory inv = bot.getInventory();
		for (int i = 0; i < inv.size(); i++) {
			ItemStack st = inv.getStack(i);
			if (!st.isEmpty()) items.merge(id(st), st.getCount(), Integer::sum);
		}
		sb.append("Inventory: ").append(items.isEmpty() ? "empty" : items.entrySet().stream().limit(25)
			.map(e -> e.getValue() + " " + e.getKey()).collect(Collectors.joining(", "))).append('\n');

		// what we're looking at
		HitResult hit = bot.raycast(5.0, 0f, false);
		if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) {
			BlockPos p = bhr.getBlockPos();
			sb.append("Looking at: ").append(blockId(world.getBlockState(p))).append(" at ")
				.append(p.getX()).append(' ').append(p.getY()).append(' ').append(p.getZ()).append('\n');
		}

		// nearest block of each type nearby
		Map<String, BlockPos> nearest = new LinkedHashMap<>();
		BlockPos.Mutable m = new BlockPos.Mutable();
		for (int dx = -RADIUS; dx <= RADIUS; dx++) {
			for (int dy = -3; dy <= 4; dy++) {
				for (int dz = -RADIUS; dz <= RADIUS; dz++) {
					m.set(me.getX() + dx, me.getY() + dy, me.getZ() + dz);
					BlockState st = world.getBlockState(m);
					if (st.isAir()) continue;
					String id = blockId(st);
					BlockPos cur = nearest.get(id);
					if (cur == null || me.getSquaredDistance(m) < me.getSquaredDistance(cur)) nearest.put(id, m.toImmutable());
				}
			}
		}
		sb.append("Nearby blocks (nearest of each): ").append(nearest.entrySet().stream()
			.sorted(Comparator.comparingDouble(e -> me.getSquaredDistance(e.getValue())))
			.limit(18)
			.map(e -> e.getKey() + "@" + e.getValue().getX() + "," + e.getValue().getY() + "," + e.getValue().getZ())
			.collect(Collectors.joining("; "))).append('\n');

		// entities
		List<Entity> ents = world.getOtherEntities(bot, bot.getBoundingBox().expand(12.0),
			e -> e instanceof LivingEntity || e instanceof ItemEntity);
		sb.append("Nearby entities: ").append(ents.isEmpty() ? "none" : ents.stream().limit(12).map(e -> {
			String t = e instanceof ItemEntity ie ? "item:" + id(ie.getStack()) : Registries.ENTITY_TYPE.getId(e.getType()).getPath();
			String n = e.hasCustomName() || e instanceof net.minecraft.entity.player.PlayerEntity ? "(" + e.getName().getString() + ")" : "";
			return t + n + "@" + e.getBlockX() + "," + e.getBlockY() + "," + e.getBlockZ();
		}).collect(Collectors.joining("; "))).append('\n');
		return sb.toString();
	}

	public static String id(ItemStack s) {
		return Registries.ITEM.getId(s.getItem()).getPath();
	}

	public static String blockId(BlockState s) {
		return Registries.BLOCK.getId(s.getBlock()).getPath();
	}
}
