package org.luckyraven.bartizan.weapon.action;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.ServicesManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.event.WeaponEntityDamageEvent;
import org.luckyraven.bartizan.api.event.WeaponEntityDamageEvent.DamageKind;
import org.luckyraven.bartizan.api.event.WeaponRaytraceImpactEvent;
import org.luckyraven.bartizan.api.raytrace.WeaponRaytracer;
import org.luckyraven.bartizan.api.support.WeaponFixtures;
import org.luckyraven.bartizan.api.weapon.dto.IncendiaryData;
import org.luckyraven.bartizan.api.weapon.modifiers.action.ArmorPiercingModifier;
import org.luckyraven.bartizan.effect.EffectRunner;
import org.luckyraven.bartizan.fire.PluginFireRegistry;
import org.luckyraven.bartizan.weapon.WeaponService;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BZ-RT-19 (final review, round 2): a hit fully soaked by absorption hearts still landed, so the melee and
 * incendiary impact handlers must count absorption in their "damageBlocked" check the same way
 * {@code WeaponRaytracerImpl.handleEntityImpact} and {@code ExplosionHandler.damageVictim} do. Before, health alone
 * was compared, so a knife hit under a golden apple fired no MELEE event, and any absorption at all swallowed the
 * flamethrower's 0.001 attribution hit, so no FIRE claim was recorded for a later burn death.
 */
@DisplayName("Melee/Incendiary impact — a hit soaked by absorption hearts still lands")
class ActionAbsorptionTest {

	/**
	 * A target whose health never moves while its absorption drops from 8 to 2 across the damage call.
	 */
	private static Player absorbingTarget() {
		Player target = mock(Player.class);
		when(target.getUniqueId()).thenReturn(UUID.randomUUID());
		when(target.isValid()).thenReturn(true);
		when(target.isDead()).thenReturn(false);
		when(target.getHealth()).thenReturn(20.0, 20.0);
		when(target.getAbsorptionAmount()).thenReturn(8.0, 2.0);
		return target;
	}

	private static WeaponRaytraceImpactEvent impact(Player shooter, Player target) {
		World world = mock(World.class);
		when(shooter.getEyeLocation()).thenReturn(new Location(world, 0, 0, 0));

		WeaponRaytraceImpactEvent event = mock(WeaponRaytraceImpactEvent.class);
		when(event.getHitEntity()).thenReturn(target);
		when(event.getShooter()).thenReturn(shooter);
		when(event.getImpactPoint()).thenReturn(new Location(world, 1, 0, 0));
		return event;
	}

	private static PluginManager mockBukkit(MockedStatic<Bukkit> bukkit) {
		PluginManager pluginManager = mock(PluginManager.class);
		bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);
		bukkit.when(Bukkit::getServicesManager).thenReturn(mock(ServicesManager.class));
		return pluginManager;
	}

	@Test
	@DisplayName("melee: a knife hit soaked by absorption still fires the MELEE WeaponEntityDamageEvent")
	void melee_absorbedHit_stillFiresEvent() throws Exception {
		MeleeAction action = new MeleeAction(WeaponFixtures.meleeWeapon(5), mock(WeaponRaytracer.class),
		                                     new HashMap<>(), mock(EffectRunner.class), mock(WeaponService.class));
		Player shooter = mock(Player.class);
		Player target  = absorbingTarget();

		Method applyMeleeImpact = MeleeAction.class.getDeclaredMethod("applyMeleeImpact",
				WeaponRaytraceImpactEvent.class, Set.class, double.class, ArmorPiercingModifier.class, Vector.class,
				double.class);
		applyMeleeImpact.setAccessible(true);

		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			PluginManager pluginManager = mockBukkit(bukkit);

			applyMeleeImpact.invoke(action, impact(shooter, target), new HashSet<UUID>(), 5.0, null,
			                        new Vector(1, 0, 0), 0.0);

			ArgumentCaptor<WeaponEntityDamageEvent> fired = ArgumentCaptor.forClass(WeaponEntityDamageEvent.class);
			verify(pluginManager).callEvent(fired.capture());
			assertEquals(DamageKind.MELEE, fired.getValue().kind());
		}
	}

	@Test
	@DisplayName("incendiary: the 0.001 attribution hit soaked by absorption still fires the FIRE event")
	void incendiary_absorbedHit_stillFiresEvent() throws Exception {
		IncendiaryAction action = new IncendiaryAction(mock(JavaPlugin.class), mock(WeaponService.class),
		                                               WeaponFixtures.incendiaryWeapon(5, 1),
		                                               mock(WeaponRaytracer.class), mock(PluginFireRegistry.class),
		                                               mock(EffectRunner.class));
		Player shooter = mock(Player.class);
		Player target  = absorbingTarget();

		Method applyIncendiaryImpact = IncendiaryAction.class.getDeclaredMethod("applyIncendiaryImpact",
				WeaponRaytraceImpactEvent.class, IncendiaryData.class, double.class, Set.class);
		applyIncendiaryImpact.setAccessible(true);

		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			PluginManager pluginManager = mockBukkit(bukkit);

			applyIncendiaryImpact.invoke(action, impact(shooter, target), new IncendiaryData(), 0.0,
			                             new HashSet<UUID>());

			ArgumentCaptor<WeaponEntityDamageEvent> fired = ArgumentCaptor.forClass(WeaponEntityDamageEvent.class);
			verify(pluginManager).callEvent(fired.capture());
			assertEquals(DamageKind.FIRE, fired.getValue().kind());
		}
	}

}
