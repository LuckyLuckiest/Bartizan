package org.luckyraven.bartizan.support;

import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.mockito.stubbing.Answer;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A mock {@link BukkitScheduler} driven by a hand-cranked tick clock, following CraftScheduler's rules: a task
 * scheduled during tick {@code T} first runs at {@code T + max(delay, 1)}, a timer then repeats every
 * {@code max(period, 1)} ticks, tasks due on the same tick run in the order they were scheduled, and a task
 * cancelled earlier in the same tick does not run. Whatever a test does between two {@link #tick()} calls lands
 * after that tick's heartbeat - which is where the server handles client packets (the scheduler heartbeat opens
 * {@code MinecraftServer#tickChildren}; packets are drained between ticks).
 */
public final class TickScheduler {

	private final BukkitScheduler scheduler = mock(BukkitScheduler.class);
	private final List<Task>      tasks     = new ArrayList<>();

	private long now;
	private int  lastId;

	public TickScheduler() {
		Answer<BukkitTask> timer = invocation -> schedule(invocation.getArgument(1), invocation.getArgument(2),
		                                                  Math.max(1L, invocation.<Long>getArgument(3)));
		when(scheduler.runTaskTimer(any(Plugin.class), any(Runnable.class), anyLong(), anyLong())).thenAnswer(timer);
		when(scheduler.runTaskTimerAsynchronously(any(Plugin.class), any(Runnable.class), anyLong(), anyLong()))
				.thenAnswer(timer);
		when(scheduler.runTaskLater(any(Plugin.class), any(Runnable.class), anyLong()))
				.thenAnswer(invocation -> schedule(invocation.getArgument(1), invocation.getArgument(2), 0L));
		when(scheduler.runTask(any(Plugin.class), any(Runnable.class)))
				.thenAnswer(invocation -> schedule(invocation.getArgument(1), 0L, 0L));
		// BukkitRunnable#cancel() (Keystone 1.9.0 Timer does not override it) cancels through the scheduler by id
		doAnswer(invocation -> {
			int id = invocation.getArgument(0);
			tasks.stream().filter(task -> task.id == id).forEach(task -> task.cancelled = true);
			return null;
		}).when(scheduler).cancelTask(anyInt());
	}

	public BukkitScheduler scheduler() {
		return scheduler;
	}

	/**
	 * The current tick.
	 */
	public long now() {
		return now;
	}

	/**
	 * Advances the clock one tick and runs every task due on it.
	 */
	public void tick() {
		now++;
		for (Task task : List.copyOf(tasks)) {
			if (task.cancelled || task.nextRun > now) continue;

			task.runnable.run();

			if (task.period > 0) task.nextRun = now + task.period;
			else task.cancelled = true;
		}
		tasks.removeIf(task -> task.cancelled);
	}

	/**
	 * Timers still scheduled.
	 */
	public int pending() {
		return (int) tasks.stream().filter(task -> !task.cancelled).count();
	}

	private BukkitTask schedule(Runnable runnable, long delay, long period) {
		Task task = new Task(++lastId, runnable, now + Math.max(delay, 1L), period);
		tasks.add(task);

		BukkitTask handle = mock(BukkitTask.class);
		doAnswer(invocation -> {
			task.cancelled = true;
			return null;
		}).when(handle).cancel();
		when(handle.isCancelled()).thenAnswer(invocation -> task.cancelled);
		when(handle.getTaskId()).thenReturn(task.id);
		return handle;
	}

	private static final class Task {

		private final int      id;
		private final Runnable runnable;
		private final long     period;
		private       long     nextRun;
		private       boolean  cancelled;

		private Task(int id, Runnable runnable, long nextRun, long period) {
			this.id       = id;
			this.runnable = runnable;
			this.nextRun  = nextRun;
			this.period   = period;
		}

	}

}
