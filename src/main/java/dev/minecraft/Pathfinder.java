package dev.minecraft;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/** A* over standing positions: walk, step up one block, drop up to three. Avoids water, lava, fire and cobwebs. */
public final class Pathfinder {
	private static final Set<Block> HAZARDS = Set.of(Blocks.FIRE, Blocks.SOUL_FIRE, Blocks.COBWEB, Blocks.SWEET_BERRY_BUSH,
		Blocks.CACTUS, Blocks.MAGMA_BLOCK, Blocks.WITHER_ROSE, Blocks.CAMPFIRE, Blocks.SOUL_CAMPFIRE, Blocks.POWDER_SNOW,
		Blocks.NETHER_PORTAL);

	private static final class Node {
		final BlockPos pos;
		final double g, f;
		final Node parent;

		Node(BlockPos pos, double g, double f, Node parent) {
			this.pos = pos;
			this.g = g;
			this.f = f;
			this.parent = parent;
		}
	}

	private Pathfinder() {}

	private static boolean passable(ServerWorld w, BlockPos p) {
		if (!w.isChunkLoaded(p)) return false;
		BlockState s = w.getBlockState(p);
		if (!s.getFluidState().isEmpty()) return false;
		if (HAZARDS.contains(s.getBlock())) return false;
		return s.getCollisionShape(w, p).isEmpty();
	}

	private static boolean solid(ServerWorld w, BlockPos p) {
		if (!w.isChunkLoaded(p)) return false;
		BlockState s = w.getBlockState(p);
		if (!s.getFluidState().isEmpty() || HAZARDS.contains(s.getBlock())) return false;
		return !s.getCollisionShape(w, p).isEmpty();
	}

	/** Can a player stand with feet at p? */
	public static boolean standable(ServerWorld w, BlockPos p) {
		return passable(w, p) && passable(w, p.up()) && solid(w, p.down());
	}

	/**
	 * @return waypoints from start (exclusive) toward goal, or the closest reachable point if the goal can't be reached,
	 *         or an empty list if there is nowhere better to go.
	 */
	public static List<BlockPos> find(ServerWorld w, BlockPos start, BlockPos goal, int maxNodes, double stopRadius) {
		PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
		Map<Long, Double> best = new HashMap<>();
		open.add(new Node(start, 0, h(start, goal), null));
		best.put(start.asLong(), 0.0);

		Node closest = null;
		double closestH = Double.MAX_VALUE;
		int expanded = 0;

		while (!open.isEmpty() && expanded++ < maxNodes) {
			Node n = open.poll();
			if (n.g > best.getOrDefault(n.pos.asLong(), Double.MAX_VALUE)) continue;

			double hd = h(n.pos, goal);
			if (hd < closestH) {
				closestH = hd;
				closest = n;
			}
			double dx = n.pos.getX() - goal.getX(), dz = n.pos.getZ() - goal.getZ();
			if (Math.sqrt(dx * dx + dz * dz) <= stopRadius && Math.abs(n.pos.getY() - goal.getY()) <= 3) {
				return build(n);
			}

			for (Direction d : Direction.Type.HORIZONTAL) {
				BlockPos q = n.pos.offset(d);
				if (Math.abs(q.getX() - start.getX()) > 80 || Math.abs(q.getZ() - start.getZ()) > 80) continue;

				if (passable(w, q) && passable(w, q.up())) {
					if (solid(w, q.down())) {
						push(open, best, q, n, 1.0, goal);
					} else {
						for (int i = 1; i <= 3; i++) { // drop
							BlockPos r = q.down(i);
							if (!passable(w, r)) break;
							if (solid(w, r.down())) {
								push(open, best, r, n, 1.0 + i * 0.5, goal);
								break;
							}
						}
					}
				} else if (solid(w, q) && passable(w, q.up()) && passable(w, q.up(2)) && passable(w, n.pos.up(2))) {
					push(open, best, q.up(), n, 1.6, goal); // step up one block
				}
			}
		}
		return closest == null ? Collections.emptyList() : build(closest);
	}

	private static void push(PriorityQueue<Node> open, Map<Long, Double> best, BlockPos p, Node parent, double cost, BlockPos goal) {
		double g = parent.g + cost;
		Double old = best.get(p.asLong());
		if (old != null && old <= g) return;
		best.put(p.asLong(), g);
		open.add(new Node(p, g, g + h(p, goal), parent));
	}

	private static double h(BlockPos a, BlockPos b) {
		return Math.abs(a.getX() - b.getX()) + Math.abs(a.getZ() - b.getZ()) + Math.abs(a.getY() - b.getY()) * 0.5;
	}

	private static List<BlockPos> build(Node end) {
		List<BlockPos> out = new ArrayList<>();
		for (Node n = end; n.parent != null; n = n.parent) out.add(n.pos);
		Collections.reverse(out);
		return out;
	}
}
