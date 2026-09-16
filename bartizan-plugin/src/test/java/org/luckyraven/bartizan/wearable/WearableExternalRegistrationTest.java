package org.luckyraven.bartizan.wearable;

import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.testsupport.BukkitRegistryFixture;
import org.luckyraven.bartizan.api.wearable.Wearable;
import org.luckyraven.bartizan.item.WearableConverter;
import org.luckyraven.bartizan.item.WearableItemSerializer;
import org.luckyraven.bartizan.item.WearableRefresher;
import org.luckyraven.bartizan.support.PerStackNbtAccessor;
import org.luckyraven.keystone.item.ItemBuilder;
import org.luckyraven.keystone.item.nbt.NbtBridge;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * WS7-D4 fix round 1: pins the settled "external wearable" contract (review findings C1/C2/I3/I4, orchestrator
 * ruling W11). An external entry is registered via {@link org.luckyraven.bartizan.api.wearable.WearableCatalog#register}
 * for damage-reduction/effects purposes only — it must resolve and apply a reduction exactly like a
 * {@code wearables.yml} entry, but every item-producing/listing path must skip it since {@link Wearable#buildItem()}
 * throws on its incomplete {@code Material} (Critical 1) and an unregistered permission node must never silently
 * block equip (Critical 2, covered by {@link #getPermission_nullForExternal}).
 *
 * <p>{@code WearableGiveCommand}/{@code WearableListCommand}/{@code WearableInfoCommand}'s give-tab/list/info
 * guards are not separately driven end-to-end here: this codebase has zero existing unit tests over the
 * {@code command/} package (no {@code SubArgument}/{@code Tree<Argument>} test scaffolding to build on), and all
 * three guards read the exact same {@link Wearable#isExternal()} flag pinned directly by this class and by
 * {@link #getPermission_nullForExternal} — flagged in the fix-round report rather than silently skipped.
 */
@DisplayName("WearableService - external registration (WS7-D4)")
class WearableExternalRegistrationTest {

	@BeforeAll
	static void bootstrapBukkitRegistry() {
		BukkitRegistryFixture.install();
	}

	@BeforeEach
	void setUp() {
		NbtBridge.install(new PerStackNbtAccessor());
	}

	@AfterEach
	void tearDown() {
		NbtBridge.reset();
	}

	private static Wearable externalWearable() {
		return Wearable.builder()
		               .wearableKey("test_external")
		               .baseDamageReduction(0.05)
		               .traits(Map.of("reinforced", 1))
		               .external(true)
		               .build();
	}

	/** Simulates the registrant (Gangland's future bridge) building its own item and stamping the tag itself. */
	private static ItemStack taggedItem(String key) {
		ItemBuilder builder = new ItemBuilder(Material.IRON_CHESTPLATE);
		builder.addTag(Wearable.NBT_KEY, key);
		return builder.build();
	}

	@Test
	@DisplayName("register -> resolveWearable finds it -> applyWearableReduction applies the reduction")
	void register_resolvesAndAppliesReduction() {
		WearableService service = new WearableService();
		service.register("test_external", externalWearable());

		ItemStack registered = taggedItem("test_external");
		Wearable  resolved   = service.resolveWearable(registered);

		assertNotNull(resolved);
		assertTrue(resolved.isExternal());

		EntityEquipment equipment = mock(EntityEquipment.class);
		when(equipment.getItem(EquipmentSlot.HEAD)).thenReturn(new ItemStack(Material.AIR));
		when(equipment.getItem(EquipmentSlot.CHEST)).thenReturn(registered);
		when(equipment.getItem(EquipmentSlot.LEGS)).thenReturn(new ItemStack(Material.AIR));
		when(equipment.getItem(EquipmentSlot.FEET)).thenReturn(new ItemStack(Material.AIR));

		LivingEntity target = mock(LivingEntity.class);
		when(target.getEquipment()).thenReturn(equipment);

		double result = service.applyWearableReduction(100, target, false);

		// getGenericDamageReduction = min(0.05 base + 1*0.05 reinforced, 0.80) = 0.10.
		assertEquals(90.0, result, 1e-9);
	}

	@Test
	@DisplayName("WearableRefresher.canRefresh does not claim an external entry (Critical 1)")
	void refresher_skipsExternal() {
		WearableService    service   = new WearableService();
		service.register("test_external", externalWearable());
		WearableRefresher refresher = new WearableRefresher(service);

		assertFalse(refresher.canRefresh(taggedItem("test_external")));
	}

	@Test
	@DisplayName("WearableConverter resolves null for a wearable:<key> string naming an external entry (Critical 1)")
	void converter_skipsExternal() {
		WearableService  service   = new WearableService();
		service.register("test_external", externalWearable());
		WearableConverter converter = new WearableConverter(service);

		assertNull(converter.convert("wearable", "test_external", Map.of()));
	}

	@Test
	@DisplayName("WearableItemSerializer#claims does not claim an external entry's stack (Important 3)")
	void serializer_doesNotClaimExternal() {
		WearableService       service    = new WearableService();
		service.register("test_external", externalWearable());
		WearableItemSerializer serializer = new WearableItemSerializer(service);

		assertFalse(serializer.claims(taggedItem("test_external")));
	}

	@Test
	@DisplayName("a registered (non-external) entry is still claimed/refreshed/converted normally")
	void nonExternalEntry_stillHandledNormally() {
		WearableService service = new WearableService();
		Wearable registered = Wearable.builder()
		                              .material(Material.IRON_HELMET)
		                              .name("Test Helmet")
		                              .wearableKey("test_registered")
		                              .baseDamageReduction(0.05)
		                              .traits(Map.of())
		                              .build();
		service.register("test_registered", registered);

		ItemStack             item       = taggedItem("test_registered");
		WearableRefresher     refresher  = new WearableRefresher(service);
		WearableConverter     converter  = new WearableConverter(service);
		WearableItemSerializer serializer = new WearableItemSerializer(service);

		assertTrue(refresher.canRefresh(item));
		assertNotNull(converter.convert("wearable", "test_registered", Map.of()));
		assertTrue(serializer.claims(item));
	}

	@Test
	@DisplayName("Wearable#getPermission is null for an external entry - never blocks equip with an unregistered node (Critical 2)")
	void getPermission_nullForExternal() {
		assertNull(externalWearable().getPermission());
	}

}
