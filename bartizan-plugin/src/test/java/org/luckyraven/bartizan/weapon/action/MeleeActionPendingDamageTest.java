package org.luckyraven.bartizan.weapon.action;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.raytrace.WeaponRaytracer;
import org.luckyraven.bartizan.api.support.WeaponFixtures;
import org.luckyraven.bartizan.api.weapon.MeleeWeapon;
import org.luckyraven.bartizan.effect.EffectRunner;
import org.luckyraven.bartizan.weapon.WeaponService;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BZ-FA-11: {@code MeleeAction.pendingDamage} used to be drained only by {@code WeaponInteract.onEntityDamage},
 * which fires exclusively on {@code EntityDamageByEntityEvent} — a call with no damager (the armor-piercing
 * follow-up hit) or a call against a fully invulnerable target raises no such event at all, stranding the UUID in
 * the static set until an unrelated later hit on that entity wrongly drained it and skipped WeaponInteract's cancel
 * guard.
 */
@DisplayName("MeleeAction.dealPendingDamage — synchronous drain (BZ-FA-11)")
class MeleeActionPendingDamageTest {

	@Test
	@DisplayName("drains pendingDamage in a finally, even when target.damage() throws")
	void dealPendingDamage_drainsEvenWhenDamageThrows() throws Exception {
		MeleeWeapon weapon = WeaponFixtures.meleeWeapon(5);
		MeleeAction action = new MeleeAction(weapon, mock(WeaponRaytracer.class), new HashMap<>(),
		                                     mock(EffectRunner.class), mock(WeaponService.class));

		LivingEntity target   = mock(LivingEntity.class);
		UUID          targetId = UUID.randomUUID();
		when(target.getUniqueId()).thenReturn(targetId);
		doThrow(new RuntimeException("boom")).when(target).damage(anyDouble(), any());

		Method dealPendingDamage = MeleeAction.class.getDeclaredMethod("dealPendingDamage", LivingEntity.class,
				double.class, Player.class);
		dealPendingDamage.setAccessible(true);

		assertThrows(InvocationTargetException.class,
				() -> dealPendingDamage.invoke(action, target, 5.0, mock(Player.class)));

		assertFalse(MeleeAction.pendingDamage.contains(targetId),
				"a real EntityDamageByEntityEvent never fires here (target.damage() threw before Bukkit could "
						+ "dispatch one) — only the finally block removes the UUID");
	}

}
