package org.luckyraven.bartizan.listener.wearable;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryType.SlotType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.testsupport.BukkitRegistryFixture;
import org.luckyraven.bartizan.api.wearable.Wearable;
import org.luckyraven.bartizan.file.BartizanMessages;
import org.luckyraven.bartizan.file.BartizanSettings;
import org.luckyraven.bartizan.support.PerStackNbtAccessor;
import org.luckyraven.bartizan.wearable.WearableService;
import org.luckyraven.keystone.item.ItemBuilder;
import org.luckyraven.keystone.item.nbt.NbtBridge;
import org.luckyraven.keystone.message.MessageProvider;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BZ-WE-01: {@code WearableEquipListener} only ever inspected {@link InventoryClickEvent}, so two ordinary vanilla
 * equip paths bypassed the {@code bartizan.wearables.<key>} permission gate entirely: pressing a hotbar number key
 * over an armor slot ({@code InventoryAction.HOTBAR_SWAP}, previously absent from the recognised-action whitelist)
 * and right-clicking the item on ({@link PlayerInteractEvent}, never handled at all). Both are covered here
 * directly; the pre-existing {@code PLACE_ALL}/shift-click paths are unchanged and untouched by this fix.
 */
@DisplayName("WearableEquipListener - equip permission gate (BZ-WE-01)")
class WearableEquipListenerTest {

	private static final String KEY        = "forbidden_helmet";
	private static final String PERMISSION = "bartizan.wearables." + KEY;

	@BeforeAll
	static void bootstrapBukkitRegistry() {
		BukkitRegistryFixture.install();
	}

	@BeforeEach
	void setUp() {
		NbtBridge.install(new PerStackNbtAccessor());
	}

	@AfterEach
	void tearDown() throws Exception {
		NbtBridge.reset();
		resetMessageProvider();
	}

	/**
	 * {@code BartizanMessages.provider} is a shared static, set once at plugin startup - primed directly here the
	 * same way {@code WeaponDeathListenerTest#primeMoneySymbol} primes {@code BartizanSettings.moneySymbol}, and
	 * reset in {@link #tearDown} so the mock doesn't leak into another test class in the same fork.
	 */
	private static void primeMessageProvider(String denialMessage) throws ReflectiveOperationException {
		Field moneySymbol = BartizanSettings.class.getDeclaredField("moneySymbol");
		moneySymbol.setAccessible(true);
		moneySymbol.set(null, "$");

		MessageProvider provider = mock(MessageProvider.class);
		when(provider.getString("Errors.Prefix")).thenReturn("&4Error&7: ");
		when(provider.getString("Errors.Wearable.Equip_Denied")).thenReturn(denialMessage);
		BartizanMessages.init(provider);
	}

	private static void resetMessageProvider() throws ReflectiveOperationException {
		Field provider = BartizanMessages.class.getDeclaredField("provider");
		provider.setAccessible(true);
		provider.set(null, null);
	}

	private static ItemStack wearableItem(String key) {
		ItemBuilder builder = new ItemBuilder(Material.IRON_HELMET);
		builder.addTag(Wearable.NBT_KEY, key);
		return builder.build();
	}

	private static WearableService serviceWith(String key) {
		WearableService service = new WearableService();
		Wearable wearable = Wearable.builder()
		                            .wearableKey(key)
		                            .material(Material.IRON_HELMET)
		                            .traits(Map.of())
		                            .temporary(false)
		                            .build();
		service.register(key, wearable);
		return service;
	}

	private static Player playerLackingPermission() {
		Player player = mock(Player.class);
		when(player.hasPermission(PERMISSION)).thenReturn(false);
		return player;
	}

	private static Player playerWithPermission() {
		Player player = mock(Player.class);
		when(player.hasPermission(PERMISSION)).thenReturn(true);
		return player;
	}

	@Test
	@DisplayName("HOTBAR_SWAP onto an armor slot with a permission-gated wearable is blocked (number-key equip)")
	void onArmorEquip_hotbarSwap_permissionDenied_isCancelled() throws Exception {
		primeMessageProvider("&cYou are not authorized to equip this armor.");
		WearableEquipListener listener = new WearableEquipListener(serviceWith(KEY));

		Player          player    = playerLackingPermission();
		PlayerInventory inventory = mock(PlayerInventory.class);
		when(inventory.getItem(0)).thenReturn(wearableItem(KEY));
		when(player.getInventory()).thenReturn(inventory);

		InventoryView view = mock(InventoryView.class);
		when(view.getPlayer()).thenReturn(player);
		when(view.convertSlot(anyInt())).thenAnswer(invocation -> invocation.getArgument(0));

		InventoryClickEvent event = new InventoryClickEvent(view, SlotType.ARMOR, 5, ClickType.NUMBER_KEY,
		                                                    InventoryAction.HOTBAR_SWAP, 0);

		listener.onArmorEquip(event);

		assertTrue(event.isCancelled(), "hotbar-swap equip of a permission-gated wearable must be blocked");
	}

	@Test
	@DisplayName("HOTBAR_SWAP onto an armor slot with a permitted wearable is allowed")
	void onArmorEquip_hotbarSwap_permissionGranted_notCancelled() {
		WearableEquipListener listener = new WearableEquipListener(serviceWith(KEY));

		Player          player    = playerWithPermission();
		PlayerInventory inventory = mock(PlayerInventory.class);
		when(inventory.getItem(0)).thenReturn(wearableItem(KEY));
		when(player.getInventory()).thenReturn(inventory);

		InventoryView view = mock(InventoryView.class);
		when(view.getPlayer()).thenReturn(player);
		when(view.convertSlot(anyInt())).thenAnswer(invocation -> invocation.getArgument(0));

		InventoryClickEvent event = new InventoryClickEvent(view, SlotType.ARMOR, 5, ClickType.NUMBER_KEY,
		                                                    InventoryAction.HOTBAR_SWAP, 0);

		listener.onArmorEquip(event);

		assertFalse(event.isCancelled());
	}

	@Test
	@DisplayName("right-clicking a permission-gated wearable in hand is blocked (vanilla right-click equip)")
	void onArmorRightClickEquip_permissionDenied_blocksItemUse() throws Exception {
		primeMessageProvider("&cYou are not authorized to equip this armor.");
		WearableEquipListener listener = new WearableEquipListener(serviceWith(KEY));

		Player    player = playerLackingPermission();
		ItemStack helmet = wearableItem(KEY);

		PlayerInteractEvent event = new PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR, helmet, null,
		                                                    BlockFace.SELF, EquipmentSlot.HAND);

		listener.onArmorRightClickEquip(event);

		assertEquals(Event.Result.DENY, event.useItemInHand(),
		            "right-click equip of a permission-gated wearable must be denied");
	}

	@Test
	@DisplayName("right-clicking a permitted wearable in hand is left alone")
	void onArmorRightClickEquip_permissionGranted_leavesItemUseAlone() {
		WearableEquipListener listener = new WearableEquipListener(serviceWith(KEY));

		Player    player = playerWithPermission();
		ItemStack helmet = wearableItem(KEY);

		PlayerInteractEvent event = new PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR, helmet, null,
		                                                    BlockFace.SELF, EquipmentSlot.HAND);

		listener.onArmorRightClickEquip(event);

		assertEquals(Event.Result.DEFAULT, event.useItemInHand());
	}

	@Test
	@DisplayName("right-clicking with an off-hand item is ignored - armor only ever equips from the main hand")
	void onArmorRightClickEquip_offHand_ignored() {
		WearableEquipListener listener = new WearableEquipListener(serviceWith(KEY));

		Player    player = playerLackingPermission();
		ItemStack helmet = wearableItem(KEY);

		PlayerInteractEvent event = new PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR, helmet, null,
		                                                    BlockFace.SELF, EquipmentSlot.OFF_HAND);

		listener.onArmorRightClickEquip(event);

		assertEquals(Event.Result.DEFAULT, event.useItemInHand());
	}

	@Test
	@DisplayName("BZ-WE-01 fix round 1: onArmorRightClickEquip must not ignoreCancelled, since a RIGHT_CLICK_AIR "
			+ "event is already isCancelled() == true at construction (CraftBukkit sets useClickedBlock = DENY "
			+ "whenever the clicked block is null) - ignoreCancelled would make Bukkit's RegisteredListener filter "
			+ "skip this handler for every plain right-click-to-equip, the ordinary vanilla equip path")
	void onArmorRightClickEquip_annotationDoesNotIgnoreCancelled() throws Exception {
		PlayerInteractEvent rightClickAir = new PlayerInteractEvent(playerWithPermission(), Action.RIGHT_CLICK_AIR,
				wearableItem(KEY), null, BlockFace.SELF, EquipmentSlot.HAND);
		assertTrue(rightClickAir.isCancelled(),
				"precondition: a constructed RIGHT_CLICK_AIR event with no clicked block is already cancelled - "
						+ "this is what ignoreCancelled = true would filter out before the handler ever runs");

		EventHandler annotation = WearableEquipListener.class
				.getMethod("onArmorRightClickEquip", PlayerInteractEvent.class)
				.getAnnotation(EventHandler.class);
		assertFalse(annotation.ignoreCancelled(),
				"onArmorRightClickEquip must not ignoreCancelled, or Bukkit's real event dispatcher would never "
						+ "call it for a RIGHT_CLICK_AIR equip");
	}

	@Test
	@DisplayName("BZ-WE-01 fix round 1: right-click denial only blocks item use, not an unrelated block "
			+ "interaction (e.g. opening a chest while a denied wearable happens to be in hand)")
	void onArmorRightClickEquip_permissionDenied_leavesBlockInteractionAlone() throws Exception {
		primeMessageProvider("&cYou are not authorized to equip this armor.");
		WearableEquipListener listener = new WearableEquipListener(serviceWith(KEY));

		Player player = playerLackingPermission();
		Block  block  = mock(Block.class);

		PlayerInteractEvent event = new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, wearableItem(KEY),
				block, BlockFace.UP, EquipmentSlot.HAND);

		listener.onArmorRightClickEquip(event);

		assertEquals(Event.Result.DENY, event.useItemInHand());
		assertEquals(Event.Result.ALLOW, event.useInteractedBlock(),
				"the block interaction itself (opening a chest/door) must still work");
	}

	@Test
	@DisplayName("BZ-WE-01 fix round 1: pressing F (SWAP_OFFHAND) over an armor slot also fires HOTBAR_SWAP but "
			+ "with getHotbarButton() == -1 (5-arg InventoryClickEvent constructor) - must resolve the off-hand "
			+ "item, not call getItem(-1)")
	void onArmorEquip_hotbarSwapOffhand_permissionDenied_isCancelled() throws Exception {
		primeMessageProvider("&cYou are not authorized to equip this armor.");
		WearableEquipListener listener = new WearableEquipListener(serviceWith(KEY));

		Player          player    = playerLackingPermission();
		PlayerInventory inventory = mock(PlayerInventory.class);
		when(inventory.getItemInOffHand()).thenReturn(wearableItem(KEY));
		when(player.getInventory()).thenReturn(inventory);

		InventoryView view = mock(InventoryView.class);
		when(view.getPlayer()).thenReturn(player);
		when(view.convertSlot(anyInt())).thenAnswer(invocation -> invocation.getArgument(0));

		// 5-arg constructor: no hotbar button carried, getHotbarButton() defaults to -1.
		InventoryClickEvent event = new InventoryClickEvent(view, SlotType.ARMOR, 5, ClickType.SWAP_OFFHAND,
				InventoryAction.HOTBAR_SWAP);

		listener.onArmorEquip(event);

		assertTrue(event.isCancelled(), "offhand-swap equip of a permission-gated wearable must be blocked");
	}

	@Test
	@DisplayName("BZ-WE-04: permission-denied equip sends the message through BartizanMessages, not a hardcoded "
			+ "raw Keystone ChatUtil string")
	void onArmorEquip_permissionDenied_sendsThroughBartizanMessages() throws Exception {
		primeMessageProvider("&cYou are not authorized to equip this armor.");
		String expected = BartizanMessages.WEARABLE_EQUIP_DENIED.toString();

		WearableEquipListener listener = new WearableEquipListener(serviceWith(KEY));

		Player          player    = playerLackingPermission();
		PlayerInventory inventory = mock(PlayerInventory.class);
		when(inventory.getItem(0)).thenReturn(wearableItem(KEY));
		when(player.getInventory()).thenReturn(inventory);

		InventoryView view = mock(InventoryView.class);
		when(view.getPlayer()).thenReturn(player);
		when(view.convertSlot(anyInt())).thenAnswer(invocation -> invocation.getArgument(0));

		InventoryClickEvent event = new InventoryClickEvent(view, SlotType.ARMOR, 5, ClickType.NUMBER_KEY,
		                                                    InventoryAction.HOTBAR_SWAP, 0);

		listener.onArmorEquip(event);

		verify(player).sendMessage(expected);
	}

}
