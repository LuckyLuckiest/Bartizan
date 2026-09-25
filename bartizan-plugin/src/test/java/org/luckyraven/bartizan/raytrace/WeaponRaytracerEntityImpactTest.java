package org.luckyraven.bartizan.raytrace;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.event.WeaponEntityDamageEvent;
import org.luckyraven.bartizan.api.raytrace.RaytraceContext;
import org.luckyraven.bartizan.api.raytrace.RaytraceRequest;
import org.luckyraven.bartizan.api.raytrace.WeaponVisualSpawner;
import org.luckyraven.bartizan.api.weapon.ProjectileState;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.weapon.dto.ModifiersData;
import org.luckyraven.bartizan.api.weapon.modifiers.BlockDamageManager;
import org.luckyraven.bartizan.effect.EffectRunner;
import org.luckyraven.bartizan.wearable.WearableService;
import org.mockito.MockedStatic;

import java.lang.reflect.Method;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BZ-RT-19 (final review, round 2): pins that {@code WeaponRaytracerImpl.handleEntityImpact}'s default damage path
 * counts absorption hearts — a shot fully soaked by them still landed, so the DIRECT
 * {@link WeaponEntityDamageEvent} and On_Hit_Taken fire. Health alone would read it as blocked.
 */
@DisplayName("WeaponRaytracerImpl.handleEntityImpact — a shot soaked by absorption hearts still lands")
class WeaponRaytracerEntityImpactTest {

	@Test
	@DisplayName("health unchanged, absorption dropped: the DIRECT event and On_Hit_Taken still fire")
	void absorbedShot_stillLands() throws Exception {
		WearableService wearableService = mock(WearableService.class);
		WeaponRaytracerImpl raytracer = new WeaponRaytracerImpl(wearableService, mock(BlockDamageManager.class),
		                                                        mock(WeaponVisualSpawner.class),
		                                                        mock(EffectRunner.class));

		World  world   = mock(World.class);
		Weapon weapon  = mock(Weapon.class);
		Player shooter = mock(Player.class);
		when(weapon.getName()).thenReturn("test_rifle");
		when(weapon.getModifiersData()).thenReturn(new ModifiersData());

		Player target = mock(Player.class);
		when(target.isValid()).thenReturn(true);
		when(target.isDead()).thenReturn(false);
		when(target.getHealth()).thenReturn(20.0, 20.0);
		when(target.getAbsorptionAmount()).thenReturn(8.0, 2.0);

		RaytraceRequest request = RaytraceRequest.builder()
		                                         .shooter(shooter)
		                                         .weapon(weapon)
		                                         .origin(new Location(world, 0, 0, 0))
		                                         .direction(new Vector(1, 0, 0))
		                                         .build();
		RaytraceContext ctx = new RaytraceContext(request, new ProjectileState(weapon, 6.0));

		Method handleEntityImpact = WeaponRaytracerImpl.class.getDeclaredMethod("handleEntityImpact", Entity.class,
				Location.class, RaytraceContext.class);
		handleEntityImpact.setAccessible(true);

		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			PluginManager pluginManager = mock(PluginManager.class);
			bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);

			handleEntityImpact.invoke(raytracer, target, new Location(world, 1, 0, 0), ctx);

			verify(pluginManager).callEvent(any(WeaponEntityDamageEvent.class));
			verify(wearableService).onHitTaken(any(), any(), anyDouble(), any());
		}
	}

}
