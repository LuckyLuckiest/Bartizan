package org.luckyraven.bartizan.api.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.weapon.Weapon;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Pins the BZ-EV-08 fix: every sibling {@code bartizan-api} event (WeaponReloadEvent, WeaponEntityDamageEvent,
 * WeaponRaytraceImpactEvent, WeaponReloadStartEvent, WeaponReloadCompleteEvent, WeaponShootEvent,
 * WeaponKillEntityEvent, ...) declares {@code getHandlerList()} {@code public static}, matching the Bukkit event
 * contract so a consumer plugin can call it to unregister. {@code WeaponChangeSelectiveFireEvent} was the one
 * class in the package that declared it {@code private static}.
 */
@DisplayName("WeaponChangeSelectiveFireEvent.getHandlerList")
class WeaponChangeSelectiveFireEventTest {

	@Test
	@DisplayName("getHandlerList is public static, matching every sibling event in the package")
	void getHandlerList_isPublicStatic() throws NoSuchMethodException {
		Method method = WeaponChangeSelectiveFireEvent.class.getDeclaredMethod("getHandlerList");

		assertTrue(Modifier.isPublic(method.getModifiers()), "getHandlerList must be public, like every sibling event");
		assertTrue(Modifier.isStatic(method.getModifiers()), "getHandlerList must be static, per the Bukkit event contract");
	}

	@Test
	@DisplayName("getHandlers() and the static getHandlerList() return the same HandlerList")
	void getHandlers_matchesStaticHandlerList() throws Exception {
		WeaponChangeSelectiveFireEvent event = new WeaponChangeSelectiveFireEvent(mock(Weapon.class));

		Method method = WeaponChangeSelectiveFireEvent.class.getDeclaredMethod("getHandlerList");
		Object staticHandlerList = method.invoke(null);

		org.junit.jupiter.api.Assertions.assertSame(staticHandlerList, event.getHandlers());
	}

}
