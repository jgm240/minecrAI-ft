package dev.minecraft;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Loaded AI players, tracked via entity load/unload events. */
public final class Bots {
	public static final Set<AiPlayerEntity> ALL = ConcurrentHashMap.newKeySet();

	private Bots() {}

	public static AiPlayerEntity find(String name) {
		for (AiPlayerEntity b : ALL) {
			if (b.botName().equalsIgnoreCase(name)) return b;
		}
		return null;
	}
}
