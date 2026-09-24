package org.luckyraven.bartizan.raytrace;

import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.event.WeaponEntityDamageEvent;
import org.luckyraven.bartizan.api.raytrace.WeaponRaytracer;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.weapon.dto.ExplosionData;
import org.luckyraven.bartizan.api.weapon.modifiers.BlockDamageManager;
import org.luckyraven.bartizan.effect.EffectRunner;
import org.mockito.InOrder;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers {@link ExplosionHandler#damageVictim} (BZ-RT-19) — split out of {@link ExplosionHandler#explode} so the
 * event/knockback/fire "did the damage actually land" gating is directly testable with a mocked target, without
 * the surrounding particle burst / {@code World#getNearbyEntities} scan that needs a live server. Mirrors {@code
 * DamageRulesTest}/{@code ExplosionHandlerTest}'s mocking style.
 */
@DisplayName("ExplosionHandler.damageVictim — event/knockback/fire only after damage actually lands")
class ExplosionHandlerDamageVictimTest {

	private ExplosionHandler newHandler() {
		return new ExplosionHandler(mock(JavaPlugin.class), mock(WeaponRaytracer.class),
				mock(BlockDamageManager.class), mock(EffectRunner.class));
	}

	@Test
	@DisplayName("damage lands: setNoDamageTicks, then damage, then the WeaponEntityDamageEvent — in that order")
	void damageLands_resetsNoDamageTicksThenDamagesThenFiresEvent() {
		ExplosionHandler handler = newHandler();
		Player           shooter = mock(Player.class);
		Player           target  = mock(Player.class);
		Weapon           weapon  = mock(Weapon.class);
		when(weapon.getName()).thenReturn("test_rocket");

		when(target.isValid()).thenReturn(true);
		when(target.isDead()).thenReturn(false);
		// healthBefore capture, then the post-damage check — health actually dropped.
		when(target.getHealth()).thenReturn(20.0, 10.0);

		ExplosionData data = new ExplosionData();
		data.setRadius(5);
		data.setDamage(10);

		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			PluginManager pluginManager = mock(PluginManager.class);
			bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);

			handler.damageVictim(weapon, data, shooter, target, new Vector(1, 0, 0), 9.0);

			InOrder order = inOrder(target, pluginManager);
			order.verify(target).setNoDamageTicks(0);
			order.verify(target).damage(9.0, shooter);
			order.verify(pluginManager).callEvent(any(WeaponEntityDamageEvent.class));
		}
	}

	@Test
	@DisplayName("damage blocked (health unchanged after target.damage()): no event, no knockback, no fire ticks")
	void damageBlocked_skipsEventKnockbackAndFire() {
		ExplosionHandler handler = newHandler();
		Player           shooter = mock(Player.class);
		Player           target  = mock(Player.class);
		Weapon           weapon  = mock(Weapon.class);

		when(target.isValid()).thenReturn(true);
		when(target.isDead()).thenReturn(false);
		when(target.getHealth()).thenReturn(20.0, 20.0); // unchanged: something blocked the hit
		when(target.getVelocity()).thenReturn(new Vector(0, 0, 0));

		ExplosionData data = new ExplosionData();
		data.setRadius(5);
		data.setDamage(10);
		data.setFireTicks(40);
		data.setKnockback(2.0);

		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			PluginManager pluginManager = mock(PluginManager.class);
			bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);

			handler.damageVictim(weapon, data, shooter, target, new Vector(1, 0, 0), 9.0);

			verify(pluginManager, never()).callEvent(any());
			verify(target, never()).setFireTicks(anyInt());
			verify(target, never()).setVelocity(any());
		}
	}

	@Test
	@DisplayName("null shooter (environmental explosion): still damages the target, never fires a player-attributed event")
	void nullShooter_stillDamagesNoEvent() {
		ExplosionHandler handler = newHandler();
		LivingEntity     target  = mock(LivingEntity.class);
		Weapon           weapon  = mock(Weapon.class);

		when(target.isValid()).thenReturn(true);
		when(target.isDead()).thenReturn(false);
		when(target.getHealth()).thenReturn(20.0, 10.0);

		ExplosionData data = new ExplosionData();
		data.setRadius(5);
		data.setDamage(10);

		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			PluginManager pluginManager = mock(PluginManager.class);
			bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);

			handler.damageVictim(weapon, data, null, target, new Vector(1, 0, 0), 9.0);

			verify(target).damage(9.0, null);
			verify(pluginManager, never()).callEvent(any());
			assertTrue(true, "no exception for a non-player victim/null shooter");
		}
	}

}
