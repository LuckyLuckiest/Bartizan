package org.luckyraven.bartizan.data;

import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.weapon.WeaponManager;
import org.luckyraven.bartizan.database.WeaponRepository;
import org.luckyraven.keystone.persistence.repository.IRepository;
import org.mockito.InOrder;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link WeaponDataCleanupTask} — since bartizan.md §1.1's PLG/L-M-N table, a plain self-scheduling Keystone
 * {@code Timer} (Bartizan has no {@code PluginDataCleanupService} to discover a passive
 * {@code DataCleanupTask} bean the way Gangland did) constructed with a {@code JavaPlugin} reference. The
 * cleanup logic — the {@code instanceof WeaponRepository} guard included — is verbatim: {@code cleanup()} returns
 * the pre-clear weapon count, calls {@code deleteAll()} on the repository before {@code clear()} on the manager,
 * and a plain {@link IRepository} double never throws a {@code ClassCastException}.
 *
 * <p><b>Setup adaptation (bartizan.md §1.1):</b> the constructor now takes a leading {@code JavaPlugin} (the
 * {@code Timer} superclass requires one) — a mock is threaded through; {@code Timer}'s constructor only stores
 * fields, so this is safe with no live Bukkit scheduler. The three test bodies/expectations are unchanged.
 */
@DisplayName("WeaponDataCleanupTask")
class WeaponDataCleanupTaskTest {

	@Test
	@DisplayName("cleanup() returns the pre-clear count, deletes every row, then clears the cache")
	void cleanup_deletesThenClears_returnsPreClearCount() {
		JavaPlugin       plugin           = mock(JavaPlugin.class);
		WeaponManager    weaponManager    = mock(WeaponManager.class);
		WeaponRepository weaponRepository = mock(WeaponRepository.class);
		when(weaponManager.getWeapons()).thenReturn(Map.of(UUID.randomUUID(), mock(Weapon.class),
		                                                    UUID.randomUUID(), mock(Weapon.class)));

		WeaponDataCleanupTask task = new WeaponDataCleanupTask(plugin, weaponManager, weaponRepository);

		int cleared = task.cleanup();

		assertEquals(2, cleared);
		InOrder inOrder = inOrder(weaponRepository, weaponManager);
		inOrder.verify(weaponRepository).deleteAll();
		inOrder.verify(weaponManager).clear();
	}

	@Test
	@DisplayName("name() identifies the task as 'weapons'")
	void name_isWeapons() {
		JavaPlugin plugin = mock(JavaPlugin.class);
		WeaponDataCleanupTask task = new WeaponDataCleanupTask(plugin, mock(WeaponManager.class), mock(WeaponRepository.class));

		assertEquals("weapons", task.name());
	}

	@Test
	@DisplayName("a plain IRepository<Weapon> (not a WeaponRepository) is skipped by the instanceof guard, but the cache still clears")
	void cleanup_nonWeaponRepositoryImplementation_skipsDeleteAll() {
		JavaPlugin    plugin        = mock(JavaPlugin.class);
		WeaponManager weaponManager = mock(WeaponManager.class);
		when(weaponManager.getWeapons()).thenReturn(Map.of());
		@SuppressWarnings("unchecked")
		IRepository<Weapon> genericRepository = mock(IRepository.class);

		WeaponDataCleanupTask task = new WeaponDataCleanupTask(plugin, weaponManager, genericRepository);

		assertDoesNotThrow(task::cleanup,
				"the `if (weaponRepository instanceof WeaponRepository repo)` guard is exactly what keeps this from " +
						"a ClassCastException against a plain IRepository<Weapon>");

		verify(weaponManager).clear();
	}
}
