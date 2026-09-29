package org.luckyraven.bartizan.weapon;

import lombok.CustomLog;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
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

import java.io.File;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

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
 * <p>
 * The server version is only the floor: {@link #configure} also tries the components on a probe item, since
 * {@code modifyItemStack} logs a component its parser rejects and hands the item back unchanged. And the use state is
 * read by the client: with ViaVersion installed a client may join with an older protocol (ViaBackwards, ViaRewind),
 * so {@link #clientFollowsUseState} asks ViaVersion per player and only a 1.21.2+ client gets the exact mode - an
 * older one keeps the fallback. Bedrock clients (Geyser, Floodgate: {@link #BEDROCK_BRIDGES}) join at the server's
 * protocol, so ViaVersion can't tell them apart - with either installed the mode stays off for everyone. Such a
 * plugin on a proxy is out of sight: behind one the startup log warns, and {@code WeaponInteract} drops a player to
 * the fallback once their client sends a right-click through the use state (which a client following it never does).
 * <p>
 * The components stay on every gun item whoever holds it: ViaBackwards strips {@code use_effects} for a client below
 * 1.21.11 and {@code consumable} below 1.21.2 - so the gate is the {@code consumable}: a client that keeps it predicts
 * the use, and denied it would send no repeats either. // ponytail: a 1.21.2-1.21.10 client follows the use state
 * without {@code use_effects}, so it is slowed to 20% client-side while holding right-click; per-holder item state
 * (the finisher given the holder) if such players complain.
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
	 * Plugins that let Bedrock clients join (Geyser, Floodgate). A Bedrock client does not know the components at all
	 * and may never send the release the server's use state waits for, and it joins at the server's protocol, so
	 * {@link #clientFollowsUseState} can't single it out.
	 */
	private static final List<String> BEDROCK_BRIDGES = List.of("Geyser-Spigot", "floodgate");

	/**
	 * 1.21.2's protocol, the first with {@code minecraft:consumable}: from it on a client keeps the never-finishing
	 * use (ViaBackwards only strips {@code use_effects}, so up to 1.21.10 it is slowed to 20% while holding
	 * right-click) and sends the release; an older one loses the component and stays on its repeats.
	 */
	private static final int CONSUMABLE_PROTOCOL = 768;

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

	/**
	 * The server can add and strip the use-state components (1.21.11+, and its item parser takes them).
	 */
	private static volatile boolean supported;
	/**
	 * The switch is on and no {@link #BEDROCK_BRIDGES Bedrock plugin} is installed.
	 */
	private static volatile boolean enabled;
	/**
	 * ViaVersion is installed, so a client's protocol may differ from the server's - see {@link #clientFollowsUseState}.
	 */
	private static volatile boolean viaVersion;
	/**
	 * {@code Via.getAPI()} and {@code ViaAPI#getPlayerVersion(UUID)}, bound once per {@link #configure} (a
	 * server-global fact); {@code null} when ViaVersion is absent or its API could not be bound.
	 */
	@Nullable
	private static volatile Method viaApi, viaPlayerVersion;
	private static final AtomicBoolean viaFailureLogged = new AtomicBoolean();

	private TriggerRelease() {
	}

	/**
	 * Reads the switch and whether the server supports the use state, and logs which release mode is active. Called
	 * on every {@code settings.yml} load (startup and {@code /bartizan reload}).
	 */
	public static void configure(boolean exactReleaseDetection) {
		supported = serverSupportsUseState();

		String bridge = supported && exactReleaseDetection ? bedrockBridge() : null;
		enabled = exactReleaseDetection && bridge == null;
		bindViaVersion();

		if (supported && enabled) {
			if (viaVersion) {
				log.info("Trigger release: exact for 1.21.2+ clients, repeat-based fallback for older ones (asked "
				         + "per player through ViaVersion) - gun items are usable while held, so fire stops on the "
				         + "first tick after right-click is released");
			} else {
				log.info("Trigger release: exact - gun items are usable while held (1.21.11+ item components), so "
				         + "fire stops on the first tick after right-click is released");
			}
			if (behindProxy()) {
				log.warn("Trigger release: exact, behind a BungeeCord/Velocity proxy - if ViaBackwards, ViaRewind or "
				         + "Geyser runs on the proxy, set Weapons.Trigger.Exact_Release_Detection: false in "
				         + "settings.yml. Older 1.21.x clients are slowed while holding right-click, and a client "
				         + "that keeps sending right-click repeats through the use state is switched to the "
				         + "repeat-based fallback on its own");
			}
		} else if (bridge != null) {
			log.warn("Trigger release: repeat-based fallback - " + bridge + " is installed, and a Bedrock client "
			         + "might never release the gun's use state. AUTO fire stops at most 4 ticks after the last "
			         + "right-click repeat");
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
	 * {@code true} when {@code player}'s client follows the gun's use state: every client without ViaVersion (all
	 * join at the server's version), else a client ViaVersion reports at 1.21.2 or newer. An unknown version, or a
	 * ViaVersion that can't be asked (one warning), is {@code false} - the fallback, never a stuck trigger.
	 */
	public static boolean clientFollowsUseState(Player player) {
		if (!viaVersion) return true;

		Method api = viaApi, playerVersion = viaPlayerVersion;
		if (api == null || playerVersion == null) return false;

		try {
			return (int) playerVersion.invoke(api.invoke(null), player.getUniqueId()) >= CONSUMABLE_PROTOCOL;
		} catch (ReflectiveOperationException | RuntimeException failed) {
			viaFailed(failed);
			return false;
		}
	}

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

	/**
	 * Adds the use-state components to {@code item} when {@code weapon} {@link #isExact is exact}, and strips them
	 * when it is not (the switch off, or a Bedrock plugin installed). A no-op on a server without item
	 * components, and whenever the item is already in the wanted state.
	 */
	@SuppressWarnings("deprecation")
	public static void applyItemState(Weapon weapon, @Nullable ItemStack item) {
		if (!supported || item == null) return;

		ItemMeta meta = item.getItemMeta();
		if (meta == null) return;

		boolean wanted = isExact(weapon);
		if (wanted == meta.getPersistentDataContainer().has(MARKER, PersistentDataType.BYTE)) return;

		try {
			Bukkit.getUnsafe().modifyItemStack(item, item.getType().getKey() + (wanted ? USE_STATE : NO_USE_STATE));
		} catch (RuntimeException failed) {
			// a fork's own modifyItemStack: never let it break building or firing a gun - off until the next reload
			supported = false;
			log.warn("Trigger release: modifying a gun item's components failed - repeat-based fallback until the "
			         + "next reload", failed);
			return;
		}

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

	@SuppressWarnings("deprecation")
	private static boolean serverSupportsUseState() {
		try {
			if (!NmsVersion.current().atLeast(21, 11)) return false;
		} catch (RuntimeException noServer) {
			// no server to ask (a unit test): nothing to support the components on
			return false;
		}

		// the version is only the floor - a later rename of a component field fails the parse, which
		// modifyItemStack logs and swallows, leaving the probe as plain as it came (no meta)
		try {
			ItemStack probe = new ItemStack(Material.IRON_HOE);
			Bukkit.getUnsafe().modifyItemStack(probe, "minecraft:iron_hoe" + USE_STATE);
			if (probe.hasItemMeta()) return true;

			log.warn("Trigger release: this server's item parser rejected the use-state item components (see the "
			         + "error above) - repeat-based fallback");
		} catch (RuntimeException failed) {
			log.warn("Trigger release: modifying an item's components failed - repeat-based fallback", failed);
		}
		return false;
	}

	/**
	 * Spigot's {@code settings.bungeecord} or Paper's {@code proxies.velocity.enabled} - where an older-client plugin
	 * may run on the proxy, out of {@link #bedrockBridge}'s and ViaVersion's sight.
	 */
	private static boolean behindProxy() {
		try {
			if (Bukkit.spigot().getConfig().getBoolean("settings.bungeecord")) return true;
		} catch (RuntimeException noSpigotConfig) {
			// not a Spigot server (or a unit test): fall through to Paper's file
		}
		File paperGlobal = new File("config", "paper-global.yml");
		return paperGlobal.isFile() &&
		       YamlConfiguration.loadConfiguration(paperGlobal).getBoolean("proxies.velocity.enabled");
	}

	private static void bindViaVersion() {
		viaApi = viaPlayerVersion = null;
		viaFailureLogged.set(false);
		// only asked while the mode is on - off, every client is on the fallback anyway
		viaVersion = supported && enabled && Bukkit.getPluginManager().getPlugin("ViaVersion") != null;
		if (!viaVersion) return;

		try {
			// reflective, like the recoil: Bartizan names no ViaVersion type (plugin.yml softdepends on it)
			viaApi           = Class.forName("com.viaversion.viaversion.api.Via").getMethod("getAPI");
			viaPlayerVersion = Class.forName("com.viaversion.viaversion.api.ViaAPI")
			                        .getMethod("getPlayerVersion", UUID.class);
		} catch (ReflectiveOperationException | RuntimeException | LinkageError failed) {
			viaFailed(failed);
		}
	}

	private static void viaFailed(Throwable failed) {
		if (!viaFailureLogged.compareAndSet(false, true)) return;
		log.warn("Trigger release: can't ask ViaVersion for a client's version - repeat-based fallback for every "
		         + "player until the next reload", failed);
	}

	@Nullable
	private static String bedrockBridge() {
		for (String name : BEDROCK_BRIDGES) {
			if (Bukkit.getPluginManager().getPlugin(name) != null) return name;
		}
		return null;
	}

}
