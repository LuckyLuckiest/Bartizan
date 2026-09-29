package org.luckyraven.bartizan.weapon;

import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.ViaAPI;
import org.apache.logging.log4j.Level;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.UnsafeValues;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.luckyraven.bartizan.api.support.WeaponFixtures;
import org.luckyraven.bartizan.api.testsupport.BukkitRegistryFixture;
import org.luckyraven.bartizan.api.weapon.GunWeapon;
import org.luckyraven.bartizan.api.weapon.SelectiveFire;
import org.luckyraven.bartizan.api.weapon.WeaponType;
import org.luckyraven.bartizan.api.weapon.dto.HandlingData;
import org.luckyraven.bartizan.api.weapon.dto.ScopeData;
import org.luckyraven.bartizan.api.weapon.dto.ScopeType;
import org.luckyraven.bartizan.support.LogCapture;
import org.luckyraven.keystone.nms.NmsVersion;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link TriggerRelease} (0.5.2): which guns are tracked through the 1.21.11+ vanilla use state, the startup mode
 * log, and the item components it adds and strips through {@code UnsafeValues#modifyItemStack}. The component
 * strings themselves were checked against the Paper 1.21.11 server's own item parser.
 */
@DisplayName("TriggerRelease - exact trigger-release detection")
class TriggerReleaseTest {

	private final ItemStack               item   = mock(ItemStack.class);
	private final ItemMeta                meta   = mock(ItemMeta.class);
	private final PersistentDataContainer data   = mock(PersistentDataContainer.class);
	private final UnsafeValues            unsafe  = mock(UnsafeValues.class);
	private final PluginManager           plugins = mock(PluginManager.class);

	private MockedStatic<Bukkit> bukkit;

	@BeforeAll
	static void bootstrapBukkitRegistry() {
		BukkitRegistryFixture.install();
	}

	@BeforeEach
	void setUp() {
		bukkit = mockStatic(Bukkit.class);
		bukkit.when(Bukkit::getUnsafe).thenReturn(unsafe);
		bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
		bukkit.when(Bukkit::getItemFactory).thenCallRealMethod(); // the fixture's, for the probe's hasItemMeta

		// configure()'s probe: this server's item parser takes the use-state components, so the probe item comes
		// back carrying them (a meta)
		ItemMeta probeMeta = mock(ItemMeta.class);
		when(probeMeta.clone()).thenReturn(probeMeta);
		when(unsafe.modifyItemStack(any(), anyString())).thenAnswer(invocation -> {
			ItemStack stack = invocation.getArgument(0);
			if (stack != null && stack != item) stack.setItemMeta(probeMeta);
			return stack;
		});
	}

	@AfterEach
	void reset() {
		mode(false, false);
		bukkit.close();
	}

	private static void mode(boolean serverSupportsIt, boolean enabled) {
		try (MockedStatic<NmsVersion> version = mockStatic(NmsVersion.class)) {
			version.when(NmsVersion::current)
			       .thenReturn(serverSupportsIt ? new NmsVersion(21, 11) : new NmsVersion(20, 6));
			TriggerRelease.configure(enabled);
		}
	}

	private static GunWeapon gun(Material material) {
		return new GunWeapon(UUID.randomUUID(), "test_gun", "&fTest Gun", WeaponType.GUN, material, 0, (short) 100,
		                     List.of(), false, null, SelectiveFire.AUTO, 0,
		                     WeaponFixtures.gunWeapon(30, 1).getProjectileData(), WeaponFixtures.instantReload(),
		                     WeaponFixtures.ammoData(30, 1, 30));
	}

	private void heldItem(boolean marked) {
		when(item.getType()).thenReturn(Material.IRON_HOE);
		when(item.getItemMeta()).thenReturn(meta);
		when(meta.getPersistentDataContainer()).thenReturn(data);
		when(data.has(any(NamespacedKey.class), eq(PersistentDataType.BYTE))).thenReturn(marked);
	}

	private String applied(GunWeapon gun) {
		TriggerRelease.applyItemState(gun, item);
		ArgumentCaptor<String> arguments = ArgumentCaptor.forClass(String.class);
		verify(unsafe).modifyItemStack(eq(item), arguments.capture());
		return arguments.getValue();
	}

	// eligibility

	@Test
	@DisplayName("right-click-trigger guns on plain items are eligible - the shipped hoes, pickaxes, axes, horse armor")
	void eligible_plainItemGuns() {
		for (Material material : List.of(Material.IRON_HOE, Material.GOLDEN_PICKAXE, Material.DIAMOND_AXE,
		                                 Material.IRON_SHOVEL, Material.IRON_HORSE_ARMOR, Material.STICK)) {
			assertTrue(TriggerRelease.eligible(gun(material)), material.name());
		}
	}

	@Test
	@DisplayName("an item whose right-click is already some vanilla use is never made usable")
	void notEligible_itemsWithTheirOwnUse() {
		for (Material material : List.of(Material.CROSSBOW, Material.BOW, Material.TRIDENT,
		                                 Material.SNOWBALL, Material.WATER_BUCKET, Material.OAK_BOAT,
		                                 Material.GLASS_BOTTLE, Material.POTION, Material.PIG_SPAWN_EGG,
		                                 Material.APPLE, Material.STONE)) {
			assertFalse(TriggerRelease.eligible(gun(material)), material.name());
		}
	}

	@Test
	@DisplayName("a left_click trigger, a spyglass scope or a non-gun is never made usable")
	void notEligible_leftClickTriggerSpyglassScopeNonGun() {
		GunWeapon    leftClick = gun(Material.IRON_HOE);
		HandlingData handling  = new HandlingData();
		handling.setTrigger(HandlingData.Trigger.LEFT_CLICK);
		leftClick.setHandlingData(handling);

		GunWeapon spyglassScoped = gun(Material.IRON_HOE);
		ScopeData scope          = new ScopeData();
		scope.setType(ScopeType.SPYGLASS);
		spyglassScoped.setScopeData(scope);

		assertFalse(TriggerRelease.eligible(leftClick));
		assertFalse(TriggerRelease.eligible(spyglassScoped));
		assertFalse(TriggerRelease.eligible(WeaponFixtures.meleeWeapon(1)));
	}

	// mode

	@Test
	@DisplayName("1.21.11+ with the switch on: exact, and the startup log says so")
	void configure_supportedAndEnabled_isExact() {
		try (LogCapture logs = LogCapture.attach(TriggerRelease.class)) {
			mode(true, true);

			assertTrue(logs.any(Level.INFO, "Trigger release: exact"));
		}
		assertTrue(TriggerRelease.isExact(gun(Material.IRON_HOE)));
		assertFalse(TriggerRelease.isExact(gun(Material.CROSSBOW)));
	}

	@Test
	@DisplayName("the switch off on 1.21.11: the fallback, and the log names the switch")
	void configure_switchOff_fallsBack() {
		try (LogCapture logs = LogCapture.attach(TriggerRelease.class)) {
			mode(true, false);

			assertTrue(logs.any(Level.INFO, "Exact_Release_Detection is false"));
		}
		assertFalse(TriggerRelease.isExact(gun(Material.IRON_HOE)));
	}

	@Test
	@DisplayName("an older server with the switch on: the fallback, and the log names the version")
	void configure_unsupportedServer_fallsBack() {
		try (LogCapture logs = LogCapture.attach(TriggerRelease.class)) {
			mode(false, true);

			assertTrue(logs.any(Level.INFO, "needs a 1.21.11+ server"));
		}
		assertFalse(TriggerRelease.isExact(gun(Material.IRON_HOE)));
	}

	@Test
	@DisplayName("a 1.21.11+ server whose item parser rejects the components: the fallback, and a warning says why")
	void configure_probeRejected_fallsBack() {
		// CraftMagicNumbers#modifyItemStack logs the parse error itself and hands the item back unchanged
		doAnswer(invocation -> invocation.getArgument(0)).when(unsafe).modifyItemStack(any(), anyString());

		try (LogCapture logs = LogCapture.attach(TriggerRelease.class)) {
			mode(true, true);

			assertTrue(logs.any(Level.WARN, "rejected the use-state item components"));
		}
		assertFalse(TriggerRelease.isExact(gun(Material.IRON_HOE)));

		heldItem(false);
		TriggerRelease.applyItemState(gun(Material.IRON_HOE), item);
		assertFalse(TriggerRelease.carriesUseState(item));
		verify(unsafe, never()).modifyItemStack(eq(item), anyString());
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {"ViaBackwards", "ViaRewind"})
	@DisplayName("a plugin letting older Java clients join: still exact, decided per client through ViaVersion")
	void configure_olderClientBridge_staysExactPerClient(String bridge) {
		when(plugins.getPlugin(bridge)).thenReturn(mock(Plugin.class));
		when(plugins.getPlugin("ViaVersion")).thenReturn(mock(Plugin.class));

		try (LogCapture logs = LogCapture.attach(TriggerRelease.class)) {
			mode(true, true);

			assertTrue(logs.any(Level.INFO, "Trigger release: exact for 1.21.2+ clients"));
			assertFalse(logs.any(Level.WARN, "is installed"));
		}
		assertTrue(TriggerRelease.isExact(gun(Material.IRON_HOE)));
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {"Geyser-Spigot", "floodgate"})
	@DisplayName("a plugin letting Bedrock clients join: the fallback, a warning naming it, items stripped")
	void configure_bedrockBridge_fallsBackAndStrips(String bridge) {
		when(plugins.getPlugin(bridge)).thenReturn(mock(Plugin.class));

		try (LogCapture logs = LogCapture.attach(TriggerRelease.class)) {
			mode(true, true);

			assertTrue(logs.any(Level.WARN, bridge + " is installed"));
		}
		assertFalse(TriggerRelease.isExact(gun(Material.IRON_HOE)));

		// an item given the use state before the plugin was added loses it on its next rebuild
		heldItem(true);
		assertEquals("minecraft:iron_hoe[!minecraft:consumable,minecraft:use_effects={}]",
		             applied(gun(Material.IRON_HOE)));
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {"Geyser-Spigot", "floodgate"})
	@DisplayName("a Bedrock plugin next to ViaVersion: still the fallback for everyone, ViaVersion never asked")
	void configure_bedrockBridgeWithViaVersion_fallsBackServerWide(String bridge) {
		when(plugins.getPlugin(bridge)).thenReturn(mock(Plugin.class));
		when(plugins.getPlugin("ViaVersion")).thenReturn(mock(Plugin.class));

		try (MockedStatic<Via> via = mockStatic(Via.class);
		     LogCapture logs = LogCapture.attach(TriggerRelease.class)) {
			mode(true, true);

			assertTrue(logs.any(Level.WARN, bridge + " is installed"));
			assertFalse(logs.any(Level.INFO, "asked per player through ViaVersion"));
			assertFalse(TriggerRelease.isExact(gun(Material.IRON_HOE)));
			// ViaVersion left unbound: no per-player question, the server-wide fallback alone decides
			assertTrue(TriggerRelease.clientFollowsUseState(mock(Player.class)));
			via.verifyNoInteractions();
		}
	}

	// per client

	private Player viaClient(int protocol, MockedStatic<Via> via) {
		Player  player = mock(Player.class);
		UUID    uuid   = UUID.randomUUID();
		ViaAPI<?> api  = mock(ViaAPI.class);
		when(player.getUniqueId()).thenReturn(uuid);
		when(api.getPlayerVersion(uuid)).thenReturn(protocol);
		via.when(Via::getAPI).thenReturn(api);
		return player;
	}

	@ParameterizedTest(name = "protocol {0}")
	@CsvSource({"47, false", "-1, false", "767, false", "768, true", "773, true", "774, true", "775, true", "778, true"})
	@DisplayName("with ViaVersion, only a 1.21.2+ client (protocol 768, consumable) follows the use state")
	void clientFollowsUseState_byProtocol(int protocol, boolean follows) {
		when(plugins.getPlugin("ViaVersion")).thenReturn(mock(Plugin.class));
		mode(true, true);

		try (MockedStatic<Via> via = mockStatic(Via.class)) {
			assertEquals(follows, TriggerRelease.clientFollowsUseState(viaClient(protocol, via)));
		}
	}

	@Test
	@DisplayName("without ViaVersion every client joins at the server's version, so every client follows it")
	void clientFollowsUseState_noViaVersion_true() {
		mode(true, true);

		assertTrue(TriggerRelease.clientFollowsUseState(mock(Player.class)));
	}

	@Test
	@DisplayName("a ViaVersion that can't be asked: the fallback, and one warning, not one per call")
	void clientFollowsUseState_viaFails_warnsOnceAndFallsBack() {
		when(plugins.getPlugin("ViaVersion")).thenReturn(mock(Plugin.class));
		mode(true, true);

		try (MockedStatic<Via> via = mockStatic(Via.class);
		     LogCapture logs = LogCapture.attach(TriggerRelease.class)) {
			via.when(Via::getAPI).thenThrow(new IllegalArgumentException("ViaVersion not loaded yet"));

			assertFalse(TriggerRelease.clientFollowsUseState(mock(Player.class)));
			assertFalse(TriggerRelease.clientFollowsUseState(mock(Player.class)));

			assertEquals(1, logs.count(Level.WARN, "ViaVersion"));
		}
	}

	@Test
	@DisplayName("a ViaVersion that failed once stays unasked until the next reload, as its warning says")
	void clientFollowsUseState_viaFailedOnce_fallbackUntilReload() {
		when(plugins.getPlugin("ViaVersion")).thenReturn(mock(Plugin.class));
		mode(true, true);

		try (MockedStatic<Via> via = mockStatic(Via.class)) {
			Player    modern = mock(Player.class);
			UUID      uuid   = UUID.randomUUID();
			ViaAPI<?> api    = mock(ViaAPI.class);
			when(modern.getUniqueId()).thenReturn(uuid);
			when(api.getPlayerVersion(uuid)).thenReturn(774);
			via.when(Via::getAPI).thenThrow(new IllegalArgumentException("ViaVersion not loaded yet")).thenReturn(api);

			assertFalse(TriggerRelease.clientFollowsUseState(modern));
			assertFalse(TriggerRelease.clientFollowsUseState(modern), "asked again after its failure");

			mode(true, true);
			assertTrue(TriggerRelease.clientFollowsUseState(modern), "a reload binds ViaVersion afresh");
		}
	}

	// item components

	@Test
	@DisplayName("an exact gun's unmarked item gets the never-finishing, silent, full-speed use state and the marker")
	void applyItemState_addsTheUseStateOnce() {
		mode(true, true);
		heldItem(false);

		String argument = applied(gun(Material.IRON_HOE));

		assertEquals("minecraft:iron_hoe[minecraft:consumable={consume_seconds:1000000.0f,animation:\"none\","
		             + "sound:\"minecraft:intentionally_empty\",has_consume_particles:false},"
		             + "minecraft:use_effects={can_sprint:true,speed_multiplier:1.0f,interact_vibrations:false}]",
		             argument);
		verify(data).set(any(NamespacedKey.class), eq(PersistentDataType.BYTE), eq((byte) 1));
		verify(item).setItemMeta(meta);
	}

	@Test
	@DisplayName("an item already carrying the use state is left alone - the per-shot rebuild costs one PDC read")
	void applyItemState_markedItem_untouched() {
		mode(true, true);
		heldItem(true);

		TriggerRelease.applyItemState(gun(Material.IRON_HOE), item);

		verify(unsafe, never()).modifyItemStack(eq(item), anyString());
		verify(item, never()).setItemMeta(any());
	}

	@Test
	@DisplayName("the switch turned off strips the use state and the marker from an item that carries them")
	void applyItemState_switchOff_strips() {
		mode(true, false);
		heldItem(true);

		String argument = applied(gun(Material.IRON_HOE));

		assertEquals("minecraft:iron_hoe[!minecraft:consumable,minecraft:use_effects={}]", argument);
		verify(data).remove(any(NamespacedKey.class));
	}

	@Test
	@DisplayName("a server without item components never touches the item")
	void applyItemState_unsupportedServer_noop() {
		mode(false, true);
		heldItem(false);

		TriggerRelease.applyItemState(gun(Material.IRON_HOE), item);

		verify(unsafe, never()).modifyItemStack(any(), anyString());
		verify(item, never()).getItemMeta();
	}

	@Test
	@DisplayName("an ineligible gun's plain item is never given the use state")
	void applyItemState_ineligibleGun_noop() {
		mode(true, true);
		heldItem(false);

		TriggerRelease.applyItemState(gun(Material.CROSSBOW), item);

		verify(unsafe, never()).modifyItemStack(eq(item), anyString());
	}

	@Test
	@DisplayName("a modifyItemStack that throws turns the use state off once, instead of breaking the gun item")
	void applyItemState_modifyThrows_fallsBackOnce() {
		mode(true, true);
		heldItem(false);
		doThrow(new IllegalStateException("a fork's item parser")).when(unsafe).modifyItemStack(eq(item),
		                                                                                         anyString());

		try (LogCapture logs = LogCapture.attach(TriggerRelease.class)) {
			TriggerRelease.applyItemState(gun(Material.IRON_HOE), item);
			TriggerRelease.applyItemState(gun(Material.IRON_HOE), item);

			assertTrue(logs.any(Level.WARN, "repeat-based fallback"));
		}
		verify(unsafe, times(1)).modifyItemStack(eq(item), anyString());
		verify(data, never()).set(any(), any(), any());
		assertFalse(TriggerRelease.isExact(gun(Material.IRON_HOE)));
	}

	@Test
	@DisplayName("carriesUseState reads the marker, and is always false on a server without item components")
	void carriesUseState_readsTheMarker() {
		heldItem(true);
		mode(false, true);
		assertFalse(TriggerRelease.carriesUseState(item));

		mode(true, true);
		assertTrue(TriggerRelease.carriesUseState(item));
		assertFalse(TriggerRelease.carriesUseState(null));
	}

	// the hook it rides on

	@Test
	@DisplayName("Weapon#buildItem hands the built item to the finisher, which every copy of the template shares")
	void itemFinisher_runsOnBuildAndIsSharedByCopies() {
		GunWeapon           template = gun(Material.IRON_HOE);
		List<ItemStack>     finished = new ArrayList<>();
		Consumer<ItemStack> finisher = finished::add;
		template.setItemFinisher(finisher);

		GunWeapon copy  = template.copyWithUUID(UUID.randomUUID());
		ItemStack built = copy.buildItem();

		assertSame(finisher, copy.getItemFinisher());
		assertEquals(1, finished.size());
		assertSame(built, finished.get(0));
	}

}
