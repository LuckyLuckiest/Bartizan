package org.luckyraven.bartizan.data;

import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.keystone.persistence.repository.RepositoryRegistry;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * {@link WeaponAutoSaveTask} — B1 (gate-GG-review blocker): before this task existed, nothing ever called
 * {@code RepositoryRegistry.saveAll(...)}, so every persisted weapon UUID was lost on restart. This pins the one
 * behaviour that matters: {@code run()} invokes {@code saveAll} on the registry it was built with.
 */
@DisplayName("WeaponAutoSaveTask")
class WeaponAutoSaveTaskTest {

	@Test
	@DisplayName("run() saves every registered repository through RepositoryRegistry.saveAll")
	void run_savesThroughRepositoryRegistry() {
		JavaPlugin          plugin             = mock(JavaPlugin.class);
		RepositoryRegistry  repositoryRegistry = mock(RepositoryRegistry.class);
		WeaponAutoSaveTask  task               = new WeaponAutoSaveTask(plugin, repositoryRegistry);

		task.run();

		verify(repositoryRegistry).saveAll(any(Runnable.class));
	}

}
