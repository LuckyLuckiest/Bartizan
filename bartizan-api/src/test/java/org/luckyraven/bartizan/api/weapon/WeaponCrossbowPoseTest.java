package org.luckyraven.bartizan.api.weapon;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.CrossbowMeta;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.support.WeaponFixtures;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Coverage for gate {@code HP}'s crossbow aim-pose addition: a {@code Material: CROSSBOW} weapon's item build puts
 * one arrow in {@link CrossbowMeta} (the vanilla client renders the charged-crossbow hold pose from that alone -
 * no packets, no NMS); every other material is left untouched. Exercises the private
 * {@code applyCrossbowChargedProjectile} directly against a mocked {@code ItemStack}/{@code CrossbowMeta} (mirrors
 * {@code WeaponSkinResolutionTest}'s {@code applySkinRendering} tests) rather than going through the real
 * {@code buildItem()} - {@code initializeTags}' reflective NBT write needs log4j, which isn't on this module's test
 * classpath.
 */
@DisplayName("Weapon crossbow charged-projectile pose (gate HP)")
class WeaponCrossbowPoseTest {

	private static GunWeapon crossbowWeapon() {
		return new GunWeapon(UUID.randomUUID(), "test_crossbow", "&fTest Crossbow", WeaponType.GUN, Material.CROSSBOW,
		                     0, (short) 100, List.of(), false, null, SelectiveFire.SINGLE, 0,
		                     WeaponFixtures.gunWeapon(10, 1).getProjectileData(), WeaponFixtures.instantReload(),
		                     WeaponFixtures.ammoData(10, 1, 10));
	}

	@Test
	@DisplayName("Material: CROSSBOW gets exactly one charged arrow")
	void crossbowMaterial_getsOneChargedArrow() throws Exception {
		GunWeapon    weapon = crossbowWeapon();
		ItemStack    stack  = mock(ItemStack.class);
		CrossbowMeta meta   = mock(CrossbowMeta.class);
		when(stack.getItemMeta()).thenReturn(meta);

		invokeApplyCrossbow(weapon, stack);

		verify(meta).setChargedProjectiles(List.of(new ItemStack(Material.ARROW)));
		verify(stack).setItemMeta(meta);
	}

	@Test
	@DisplayName("a non-crossbow weapon never touches the item's meta")
	void nonCrossbowMaterial_leavesItemUntouched() throws Exception {
		GunWeapon weapon = WeaponFixtures.gunWeapon(10, 1); // Material.IRON_HOE
		ItemStack stack  = mock(ItemStack.class);

		invokeApplyCrossbow(weapon, stack);

		verify(stack, never()).getItemMeta();
		verify(stack, never()).setItemMeta(any());
	}

	private static void invokeApplyCrossbow(GunWeapon weapon, ItemStack stack) throws Exception {
		Method applyCrossbow = Weapon.class.getDeclaredMethod("applyCrossbowChargedProjectile", ItemStack.class);
		applyCrossbow.setAccessible(true);
		applyCrossbow.invoke(weapon, stack);
	}

}
