package dev.minecraft;

/** One step of a bot's plan, ticked every server tick until it reports done. */
public interface Task {
	/** @return true when finished (successfully or not). */
	boolean tick(AiPlayer bot);

	String describe();
}
