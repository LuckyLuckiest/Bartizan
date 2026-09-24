package org.luckyraven.bartizan.weapon.action;

import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.raytrace.WeaponRaytracer;
import org.luckyraven.bartizan.api.support.WeaponFixtures;
import org.luckyraven.bartizan.api.weapon.IncendiaryWeapon;
import org.luckyraven.bartizan.effect.EffectRunner;
import org.luckyraven.bartizan.fire.PluginFireRegistry;
import org.luckyraven.bartizan.weapon.WeaponService;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BZ-FA-11: {@code IncendiaryAction.pendingDamage} leaked the same way {@code MeleeAction}'s did — see
 * {@code MeleeActionPendingDamageTest}'s javadoc. Here the sourced spray-tick damage call still leaks against a
 * fully invulnerable/creative target, since {@code target.damage()} raises no event at all in that case either.
 */
@DisplayName("IncendiaryAction.dealPendingDamage — synchronous drain (BZ-FA-11)")
class IncendiaryActionPendingDamageTest {

	@Test
	@DisplayName("drains pendingDamage in a finally, even when target.damage() throws")
	void dealPendingDamage_drainsEvenWhenDamageThrows() throws Exception {
		IncendiaryWeapon weapon = WeaponFixtures.incendiaryWeapon(5, 1);
		IncendiaryAction action = new IncendiaryAction(mock(JavaPlugin.class), mock(WeaponService.class), weapon,
		                                               mock(WeaponRaytracer.class), mock(PluginFireRegistry.class),
		                                               mock(EffectRunner.class));

		LivingEntity target   = mock(LivingEntity.class);
		UUID          targetId = UUID.randomUUID();
		when(target.getUniqueId()).thenReturn(targetId);
		doThrow(new RuntimeException("boom")).when(target).damage(anyDouble(), any());

		Method dealPendingDamage = IncendiaryAction.class.getDeclaredMethod("dealPendingDamage", LivingEntity.class,
				double.class, Entity.class);
		dealPendingDamage.setAccessible(true);

		assertThrows(InvocationTargetException.class,
				() -> dealPendingDamage.invoke(action, target, 0.001, mock(Entity.class)));

		assertFalse(IncendiaryAction.pendingDamage.contains(targetId),
				"a real EntityDamageByEntityEvent never fires here (target.damage() threw before Bukkit could "
						+ "dispatch one) — only the finally block removes the UUID");
	}

}
