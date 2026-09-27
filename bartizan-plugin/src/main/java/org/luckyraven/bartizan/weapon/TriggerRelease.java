package org.luckyraven.bartizan.weapon;

import lombok.CustomLog;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;
import org.luckyraven.bartizan.api.weapon.GunWeapon;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.weapon.dto.HandlingData;
import org.luckyraven.bartizan.api.weapon.dto.ScopeData;
import org.luckyraven.bartizan.api.weapon.dto.ScopeType;
import org.luckyraven.keystone.nms.NmsVersion;

import java.util.Set;

/**
 * Exact trigger-release detection (0.5.2, {@code settings.yml} {@code Weapons.Trigger.Exact_Release_Detection}).
 * <p>
 * Spigot has no "released right-click" event: a vanilla client re-sends USE_ITEM every 4 ticks while right-click is
 * held on an item it is not using, and nothing on release, so {@code WeaponInteract} can otherwise only infer a
 * release from the repeats stopping - up to 4 ticks late. On a server with item components and
 * {@code minecraft:use_effects} (1.21.11+; {@code minecraft:consumable} alone is 1.21.2+, but without
 * {@code use_effects} a held use slows the player to 20% and stops sprinting), a gun item is instead made usable
 * while held: a {@code consumable} that never finishes, with no animation, sound or particles, plus
 * {@code use_effects} keeping full speed and sprinting. Holding right-click then puts the player in a vanilla use
 * state and releasing it sends RELEASE_USE_ITEM, so {@code Player#isHandRaised()} tracks the physical button to the
 * tick. The components go on through {@code UnsafeValues#modifyItemStack} (Bukkit API on the 1.16.5 compile floor,
 * no NMS), from {@code Weapon#buildItem}/{@code #updateWeaponData}'s item finisher - so new items get them when built
 * and existing ones on their next rebuild (the first shot, a reload, a refresh) - and come off the same way when the
 * switch is turned off.
 * <p>
 * The component values were checked against the Paper 1.21.11 server: {@code consume_seconds: 1000000} is
 * {@code (int) (seconds * 20)} = 20,000,000 use ticks (about 11.5 days) before a consume could even be attempted, the
 * consume sound/particles only start after 21.875% of that, and {@code WeaponInteract} cancels
 * {@code PlayerItemConsumeEvent} for guns as the guard.
 */
@CustomLog
public final class TriggerRelease {

	private static final String USE_STATE    = "[minecraft:consumable={consume_seconds:1000000.0f,animation:\"none\","
	                                           + "sound:\"minecraft:intentionally_empty\",has_consume_particles:false},"
	                                           + "minecraft:use_effects={can_sprint:true,speed_multiplier:1.0f,"
	                                           + "interact_vibrations:false}]";
	/**
	 * Every item's prototype carries the default {@code use_effects} (1.21.11: all but the spears), so it is reset to
	 * the defaults rather than removed - a removal would stay on the item as an explicit {@code !use_effects} patch
	 * and it would no longer match a freshly built one.
	 */
	private static final String NO_USE_STATE = "[!minecraft:consumable,minecraft:use_effects={}]";

	/**
	 * Marks an item that carries {@link #USE_STATE}, so the per-shot rebuild only touches the components when the
	 * wanted state changes.
	 */
	private static final NamespacedKey MARKER = NamespacedKey.fromString("bartizan:trigger_use");

	/**
	 * Vanilla items whose own {@code Item#use} does something on right-click - the {@code consumable} path never
	 * runs for them, so allowing their vanilla use would bow-draw, throw, fill, place or fire instead. Taken from the
	 * Paper 1.21.11 item registry (every item class overriding {@code use}, plus the non-food prototype consumables).
	 * // ponytail: a fixed 1.21.11 list - an item a later Minecraft version gives its own right-click use needs adding
	 * // here, or a gun made of it would have that vanilla use allowed.
	 */
	private static final Set<String> OWN_USE = Set.of("BOW", "CROSSBOW", "TRIDENT", "SPYGLASS", "FISHING_ROD",
	                                                  "CARROT_ON_A_STICK", "WARPED_FUNGUS_ON_A_STICK", "GOAT_HORN",
	                                                  "SNOWBALL", "EGG", "BLUE_EGG", "BROWN_EGG", "ENDER_PEARL",
	                                                  "ENDER_EYE", "EXPERIENCE_BOTTLE", "POTION", "SPLASH_POTION",
	                                                  "LINGERING_POTION", "OMINOUS_BOTTLE", "WIND_CHARGE",
	                                                  "FIREWORK_ROCKET", "GLASS_BOTTLE", "WRITABLE_BOOK",
	                                                  "WRITTEN_BOOK", "KNOWLEDGE_BOOK", "MAP");

	private static volatile boolean supported;
	private static volatile boolean enabled;

	private TriggerRelease() {
	}

	/**
	 * Reads the switch and whether the server supports the use state, and logs which release mode is active. Called
	 * on every {@code settings.yml} load (startup and {@code /bartizan reload}).
	 */
	public static void configure(boolean exactReleaseDetection) {
		supported = serverSupportsUseState();
		enabled   = exactReleaseDetection;

		if (supported && enabled) {
			log.info("Trigger release: exact - gun items are usable while held (1.21.11+ item components), so fire "
			         + "stops on the first tick after right-click is released");
		} else {
			log.info("Trigger release: repeat-based fallback (" +
			         (supported ? "Weapons.Trigger.Exact_Release_Detection is false" : "needs a 1.21.11+ server") +
			         ") - AUTO fire stops at most 4 ticks after the last right-click repeat");
		}
	}

	/**
	 * {@code true} when {@code weapon}'s right-click trigger is tracked through the vanilla use state: the mode is
	 * active, and the weapon is a right-click-trigger gun whose right-click is not already some other vanilla use.
	 */
	public static boolean isExact(Weapon weapon) {
		return supported && enabled && eligible(weapon);
	}

	/**
	 * Adds the use-state components to {@code item} when {@code weapon} {@link #isExact is exact}, and strips them
	 * when it is not (the switch turned off). A no-op on a server without item components, and whenever the item is
	 * already in the wanted state.
	 */
	/**
	 * {@code true} when {@code item} already carries the use state - only then may its vanilla use go through: an item
	 * built before it had the components (they arrive with its next rebuild, usually the first shot) would otherwise
	 * run its material's own default use, e.g. an armor piece equipping itself.
	 */
	public static boolean carriesUseState(@Nullable ItemStack item) {
		if (!supported || item == null) return false;

		ItemMeta meta = item.getItemMeta();
		return meta != null && meta.getPersistentDataContainer().has(MARKER, PersistentDataType.BYTE);
	}

	@SuppressWarnings("deprecation")
	public static void applyItemState(Weapon weapon, @Nullable ItemStack item) {
		if (!supported || item == null) return;

		ItemMeta meta = item.getItemMeta();
		if (meta == null) return;

		boolean wanted = isExact(weapon);
		if (wanted == meta.getPersistentDataContainer().has(MARKER, PersistentDataType.BYTE)) return;

		Bukkit.getUnsafe().modifyItemStack(item, item.getType().getKey() + (wanted ? USE_STATE : NO_USE_STATE));

		meta = item.getItemMeta();
		if (meta == null) return;

		PersistentDataContainer data = meta.getPersistentDataContainer();
		if (wanted) data.set(MARKER, PersistentDataType.BYTE, (byte) 1);
		else data.remove(MARKER);
		item.setItemMeta(meta);
	}

	static boolean eligible(Weapon weapon) {
		if (!(weapon instanceof GunWeapon)) return false;

		// left_click: right-click is the scope input there, not the trigger
		HandlingData handling = weapon.getHandlingData();
		if (handling != null && handling.getTrigger() == HandlingData.Trigger.LEFT_CLICK) return false;

		// a spyglass scope is the spyglass's own vanilla use
		ScopeData scope = weapon.getScopeData();
		if (scope != null && scope.getType() == ScopeType.SPYGLASS) return false;

		Material material = weapon.getMaterial();
		String   name     = material.name();
		return !material.isBlock() && !material.isEdible() && !OWN_USE.contains(name) && !name.endsWith("BUCKET") &&
		       !name.endsWith("_BOAT") && !name.endsWith("_RAFT") && !name.endsWith("BUNDLE") &&
		       !name.endsWith("_SPAWN_EGG");
	}

	private static boolean serverSupportsUseState() {
		try {
			return NmsVersion.current().atLeast(21, 11);
		} catch (RuntimeException noServer) {
			// no server to ask (a unit test): nothing to support the components on
			return false;
		}
	}

}
