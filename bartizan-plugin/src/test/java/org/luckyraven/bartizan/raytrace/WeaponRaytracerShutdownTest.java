package org.luckyraven.bartizan.raytrace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.raytrace.WeaponVisualSpawner;
import org.luckyraven.bartizan.api.weapon.modifiers.BlockDamageManager;
import org.luckyraven.bartizan.effect.EffectRunner;
import org.luckyraven.bartizan.wearable.WearableService;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * BZ-RT-15: a server stop / plugin disable never called {@link BlockDamageManager#clearAll()}, so a block that was
 * mid-{@code RESTORE} (set to {@code AIR}, waiting on its {@code runTaskLater} restore) stayed {@code AIR} for
 * good once Bukkit cancelled the pending task on disable — {@code onShutdown} only cleaned up the cosmetic visual
 * spawner. Pins that {@code onShutdown} now also restores any block still mid-restore.
 */
@DisplayName("WeaponRaytracerImpl.onShutdown — BZ-RT-15")
class WeaponRaytracerShutdownTest {

	@Test
	@DisplayName("onShutdown clears block damage state (restoring mid-RESTORE blocks) alongside the visual spawner")
	void onShutdown_clearsBlockDamageManager() {
		WearableService     wearableService    = mock(WearableService.class);
		BlockDamageManager  blockDamageManager = mock(BlockDamageManager.class);
		WeaponVisualSpawner visualSpawner      = mock(WeaponVisualSpawner.class);
		EffectRunner        effectRunner       = mock(EffectRunner.class);

		WeaponRaytracerImpl raytracer =
				new WeaponRaytracerImpl(wearableService, blockDamageManager, visualSpawner, effectRunner);

		raytracer.onShutdown();

		verify(blockDamageManager).clearAll();
		verify(visualSpawner).removeAll();
	}

}
