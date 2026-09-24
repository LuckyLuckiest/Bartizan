package org.luckyraven.bartizan.wearable;

import lombok.CustomLog;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.Nullable;
import org.luckyraven.bartizan.api.weapon.dto.EffectHook;
import org.luckyraven.bartizan.api.weapon.dto.EffectsData;
import org.luckyraven.bartizan.api.wearable.Wearable;
import org.luckyraven.bartizan.effect.EffectContext;
import org.luckyraven.bartizan.effect.EffectRunner;
import org.luckyraven.bartizan.util.PotionEffectParser;
import org.luckyraven.keystone.bean.BeanLifecycle;
import org.luckyraven.keystone.timer.RepeatingTimer;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Worn-wearable effects ticker (weapons-roadmap.md gate {@code HL}, §5): one {@link RepeatingTimer} over online
 * players drives both {@code Effects_While_Worn} (piece + active set bonus) and the {@code On_Equip}/
 * {@code On_Unequip} hooks off the same per-player worn-key snapshot, diffed tick to tick. Mirrors
 * {@code HudService}/{@code StatusEffectService}'s shape — {@link #tick()} is package-visible so a test can drive
 * it directly.
 */
@CustomLog
public class WearableEffectsService implements BeanLifecycle {

	private static final EquipmentSlot[] ARMOR_SLOTS = {
			EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
	};

	/** {@code tick()} cadence — matches {@code StatusEffectService}/{@code HudService}. */
	private static final long TICK_INTERVAL = 10L;

	/** {@code Effects_While_Worn} re-applies every 4th service tick, i.e. every 40 ticks. */
	private static final int WORN_EFFECTS_EVERY_N_TICKS = 4;

	/**
	 * {@code Effects_While_Worn}'s configured duration is always ignored and replaced by this. Raised from 60 to
	 * 220 (gate {@code HL} review, §4) — 60 ticks (3s) is inside vanilla's ~200-tick "about to expire" blink window
	 * for effects like Night Vision, so a piece granting one visibly strobed between each 40-tick re-apply. Paired
	 * with {@link #removeDroppedWornEffects}, which now strips an effect the moment it's no longer granted, so the
	 * longer duration doesn't linger past an unequip either.
	 */
	private static final int WORN_EFFECT_DURATION_TICKS = 220;

	/**
	 * {@code EFFECT-duration-amplifier} with an optional sign on either numeric field — matched (not split) so a
	 * negative duration ({@code "permanent while worn"} sentinels some servers use) can't be confused with the
	 * amplifier field the roadmap actually asks this class to peek at.
	 */
	private static final Pattern WORN_EFFECT_TOKEN = Pattern.compile("^(\\w+)-(-?\\d+)-(-?\\d+)$");

	private final JavaPlugin       plugin;
	private final WearableService  wearableService;
	private final EffectRunner     effectRunner;

	private final Map<UUID, Set<String>> lastWornKeys = new HashMap<>();

	/**
	 * Last tick's granted {@code Effects_While_Worn} potion types per player, mapped to the highest Bukkit
	 * amplifier granted for that type (gate {@code HL} review, §4; amplifier tracking added BZ-WE-10) — diffed
	 * every tick (not just every {@link #WORN_EFFECTS_EVERY_N_TICKS}th one) in {@link #removeDroppedWornEffects} so
	 * a type that's no longer granted (its piece removed, or its Set tier lost/downgraded) is stripped immediately
	 * instead of lingering for up to {@link #WORN_EFFECT_DURATION_TICKS} ticks. The granted amplifier is kept so
	 * {@link #removeDroppedWornEffects} only strips the wearable's OWN instance of that effect, never an
	 * independently-acquired one from another source (BZ-WE-10) — {@code Player.removePotionEffect(type)} has no
	 * concept of "whose" effect it's clearing.
	 */
	private final Map<UUID, Map<PotionEffectType, Integer>> lastWornEffectGrants = new HashMap<>();

	private long tickCount = 0;

	@Nullable
	private RepeatingTimer timer;

	public WearableEffectsService(JavaPlugin plugin, WearableService wearableService, EffectRunner effectRunner) {
		this.plugin          = plugin;
		this.wearableService = wearableService;
		this.effectRunner    = effectRunner;
	}

	/**
	 * Starts the {@code tick()} timer. Called once, from {@code WiringConfig}'s bean construction. Idempotent.
	 */
	public void start() {
		if (timer != null) return;

		timer = new RepeatingTimer(plugin, TICK_INTERVAL, ignored -> tick());
		timer.start(false);
	}

	/**
	 * Drops {@code playerId}'s worn-key snapshot — called on quit ({@code WeaponQuitCleanupListener}). Without
	 * this a rejoining player's first tick diffs against stale gear from their last session, which can either
	 * miss a real {@code On_Equip} (the stale snapshot happens to already contain that key) or fire a spurious
	 * {@code On_Unequip} for gear they no longer have on.
	 */
	public void remove(UUID playerId) {
		lastWornKeys.remove(playerId);
		lastWornEffectGrants.remove(playerId);
	}

	/**
	 * One tick over every online player. Package-visible so a test can drive it directly without a real scheduler.
	 */
	void tick() {
		boolean applyWornEffects = shouldApplyWornEffects(tickCount);
		tickCount++;

		for (Player player : Bukkit.getOnlinePlayers()) {
			// A misconfigured wearable's Effects_While_Worn/hook must not take the whole tick down with it — same
			// guard StatusEffectService#tick/HudService#tick use.
			try {
				tickPlayer(player, applyWornEffects);
			} catch (Exception exception) {
				log.warn("wearable effects tick failed for " + player.getUniqueId() + ": " + exception.getMessage());
			}
		}
	}

	private void tickPlayer(Player player, boolean applyWornEffects) {
		Map<EquipmentSlot, Wearable> worn = wornRegisteredWearables(player);

		Set<String> currentKeys = new HashSet<>();
		for (Wearable wearable : worn.values()) currentKeys.add(wearable.getWearableKey());

		Set<String> previousKeys = lastWornKeys.getOrDefault(player.getUniqueId(), Set.of());

		for (String key : currentKeys) {
			if (!previousKeys.contains(key)) fireEquipHook(player, key, EffectHook.ON_EQUIP);
		}
		for (String key : previousKeys) {
			if (!currentKeys.contains(key)) fireEquipHook(player, key, EffectHook.ON_UNEQUIP);
		}
		lastWornKeys.put(player.getUniqueId(), currentKeys);

		removeDroppedWornEffects(player, worn);

		if (applyWornEffects) applyWornEffects(player, worn);
	}

	/**
	 * Strips a {@code PotionEffectType} the moment it's no longer granted by any currently worn piece or active
	 * Set tier (gate {@code HL} review, §4) — runs every tick (not gated by {@code applyWornEffects}) so a removed
	 * piece's, or a lost/downgraded Set tier's, worn effect doesn't linger for up to
	 * {@link #WORN_EFFECT_DURATION_TICKS} ticks after it's gone. Note: the HH scope's own Night Vision removal on
	 * scope-out can still briefly drop a worn Night Vision grant until the next {@link #WORN_EFFECTS_EVERY_N_TICKS}
	 * re-apply — that's an unrelated seam (scope-out removes ALL Night Vision, including this one) and isn't fixed
	 * here.
	 *
	 * <p>BZ-WE-10: {@code Player.removePotionEffect(type)} clears whatever effect of that type is currently active,
	 * regardless of source, so a type that drops out is only actually removed when the player's live effect still
	 * looks like the wearable's own grant ({@link #isStillTheWornGrant}) — otherwise it's a stronger/longer effect
	 * an unrelated source applied on top, and is left alone.
	 */
	private void removeDroppedWornEffects(Player player, Map<EquipmentSlot, Wearable> worn) {
		Map<PotionEffectType, Integer> currentGrants = new HashMap<>();
		for (String token : wornEffectTokens(player, worn)) {
			PotionEffect effect = parseWornEffectToken(token);
			if (effect != null) currentGrants.merge(effect.getType(), effect.getAmplifier(), Integer::max);
		}

		Map<PotionEffectType, Integer> previousGrants = lastWornEffectGrants.getOrDefault(player.getUniqueId(),
		                                                                                 Map.of());
		for (Map.Entry<PotionEffectType, Integer> entry : previousGrants.entrySet()) {
			PotionEffectType type = entry.getKey();
			if (currentGrants.containsKey(type)) continue;

			PotionEffect current = player.getPotionEffect(type);
			if (current != null && isStillTheWornGrant(current.getAmplifier(), current.getDuration(), entry.getValue())) {
				player.removePotionEffect(type);
			}
		}
		lastWornEffectGrants.put(player.getUniqueId(), currentGrants);
	}

	private void fireEquipHook(Player player, String wearableKey, EffectHook hook) {
		Wearable wearable = wearableService.getWearable(wearableKey);
		if (wearable == null) return;

		EffectsData effects = wearable.getEffects();
		if (!effects.has(hook)) return;

		EffectContext ctx = EffectContext.builder().source(player).ownerName(wearable.getName()).build();
		effectRunner.run(effects, wearable.getName(), hook, ctx);
	}

	private void applyWornEffects(Player player, Map<EquipmentSlot, Wearable> worn) {
		for (String token : wornEffectTokens(player, worn)) {
			PotionEffect effect = parseWornEffectToken(token);
			if (effect != null) player.addPotionEffect(effect);
		}
	}

	/**
	 * Every raw {@code Effects_While_Worn} token currently granted: each worn piece's own list, plus every active
	 * Set tier's list (see {@link WearableService#activeSetTiers}) — shared by {@link #applyWornEffects} (which
	 * applies them) and {@link #removeDroppedWornEffects} (which diffs their parsed types tick to tick).
	 */
	private List<String> wornEffectTokens(Player player, Map<EquipmentSlot, Wearable> worn) {
		List<String> tokens = new ArrayList<>();
		for (Wearable wearable : worn.values()) tokens.addAll(wearable.getEffectsWhileWorn());
		for (WearableService.SetTier tier : wearableService.activeSetTiers(player).values()) {
			tokens.addAll(tier.effectsWhileWorn());
		}
		return tokens;
	}

	/**
	 * Every worn armour piece, resolved through {@link WearableService#resolveWearable} (registered or a
	 * temporary vanilla fallback) — a temporary wearable's {@code Effects:}/{@code Effects_While_Worn:} are always
	 * empty ({@link Wearable} field defaults), so including it here is a harmless no-op rather than dead weight:
	 * it still needs to flow through the equip/unequip diff so swapping vanilla armour for a registered wearable
	 * (or back) is seen as a real change.
	 */
	private Map<EquipmentSlot, Wearable> wornRegisteredWearables(Player player) {
		EntityEquipment equipment = player.getEquipment();
		if (equipment == null) return Map.of();

		Map<EquipmentSlot, Wearable> worn = new EnumMap<>(EquipmentSlot.class);
		for (EquipmentSlot slot : ARMOR_SLOTS) {
			ItemStack item = equipment.getItem(slot);
			if (item.getType().isAir()) continue;

			Wearable wearable = wearableService.resolveWearable(item);
			if (wearable != null) worn.put(slot, wearable);
		}
		return worn;
	}

	/**
	 * {@code tickCount % WORN_EFFECTS_EVERY_N_TICKS == 0} — extracted so a test can pin the cadence arithmetic
	 * (0, 4, 8, … apply; everything else doesn't) without needing a live {@link Player}/scheduler.
	 */
	static boolean shouldApplyWornEffects(long tickCount) {
		return tickCount % WORN_EFFECTS_EVERY_N_TICKS == 0;
	}

	/**
	 * Whether {@code token}'s raw (pre-{@link PotionEffectParser}) amplifier field is the {@code -1} skip
	 * sentinel — extracted so a test can pin this against plain strings, without needing a live
	 * {@code PotionEffectType} registry to also exercise the non-skip path through {@link #parseWornEffectToken}.
	 */
	static boolean isSkipSentinel(String token) {
		Matcher matcher = WORN_EFFECT_TOKEN.matcher(token.trim());
		return matcher.matches() && matcher.group(3).equals("-1");
	}

	/**
	 * BZ-WE-10: whether a player's currently active potion effect of a dropped-grant type is still, itself, the
	 * wearable's own instance (so it's safe to strip) rather than an independently-acquired effect from another
	 * source sitting on top. Extracted as pure arithmetic — same reason as {@link #isSkipSentinel} — because
	 * exercising this through a live {@link PotionEffect} needs a real {@code PotionEffectType} registry this test
	 * environment doesn't have (see {@code WearableEffectsServiceTest}'s class javadoc).
	 *
	 * <p>Matches on amplifier and on the effect's remaining duration still being inside the worn re-apply window
	 * ({@link #WORN_EFFECT_DURATION_TICKS}): a genuinely infinite or just much longer effect from another source
	 * already fails that duration bound, so no separate infinite-duration check is needed (and Spigot's 1.16.5
	 * compile floor has no {@code PotionEffect.isInfinite()} to make one with).
	 *
	 * @return {@code true} only when {@code currentAmplifier == grantedAmplifier} and
	 *         {@code currentDuration <= WORN_EFFECT_DURATION_TICKS}
	 */
	static boolean isStillTheWornGrant(int currentAmplifier, int currentDuration, int grantedAmplifier) {
		return currentAmplifier == grantedAmplifier && currentDuration <= WORN_EFFECT_DURATION_TICKS;
	}

	/**
	 * Parses one {@code Effects_While_Worn} token: the configured duration is always replaced by
	 * {@link #WORN_EFFECT_DURATION_TICKS}; a raw amplifier of {@code -1} skips the token entirely (checked via
	 * {@link #isSkipSentinel}, against the token's raw text, before {@link PotionEffectParser} 1-indexes/clamps it
	 * into a Bukkit amplifier that can no longer be told apart from a configured tier I).
	 */
	@Nullable
	private static PotionEffect parseWornEffectToken(String token) {
		if (token == null || token.isBlank() || isSkipSentinel(token)) return null;

		PotionEffect parsed = PotionEffectParser.parseSingle(token);
		return parsed != null ? new PotionEffect(parsed.getType(), WORN_EFFECT_DURATION_TICKS, parsed.getAmplifier())
		                     : null;
	}

	@Override
	public void onShutdown() {
		lastWornKeys.clear();
		lastWornEffectGrants.clear();

		if (timer != null) {
			timer.stop();
			timer = null;
		}
	}

}
