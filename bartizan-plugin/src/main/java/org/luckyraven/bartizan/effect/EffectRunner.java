package org.luckyraven.bartizan.effect;

import lombok.CustomLog;
import org.bukkit.plugin.java.JavaPlugin;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.weapon.dto.EffectHook;
import org.luckyraven.bartizan.api.weapon.dto.EffectSpec;
import org.luckyraven.bartizan.api.weapon.dto.EffectsData;
import org.luckyraven.bartizan.effect.impl.*;
import org.luckyraven.bartizan.file.BartizanSettings;
import org.luckyraven.keystone.bean.BeanLifecycle;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The one feedback engine every hook fires through (weapons-roadmap.md gate {@code HA}, §1). Resolves the effect
 * list for a hook — the weapon's own list if it declared one, else {@code settings.yml}'s {@code Default_Effects}
 * (never merged) — and runs each spec's registered {@link Effect} in order, isolating one bad entry's exception from
 * the rest.
 */
@CustomLog
public class EffectRunner implements BeanLifecycle {

	/**
	 * Lowercase effect-type keys every {@code Effects:} entry's {@code Type} must resolve to (case-insensitively).
	 */
	public static final Set<String> TYPES = Set.of(
			"sound", "custom_sound", "particle", "potion", "action_bar", "title", "boss_bar", "message", "command",
			"push", "camera_shake", "ignite", "cooldown", "firework", "lightning");

	private final Map<String, Effect> registry;

	public EffectRunner(JavaPlugin plugin) {
		this(buildRegistry(plugin));
	}

	/**
	 * Test-only seam: run with a hand-built registry instead of the real 15 hook effects.
	 */
	EffectRunner(Map<String, Effect> registry) {
		this.registry = registry;
	}

	private static Map<String, Effect> buildRegistry(JavaPlugin plugin) {
		Map<String, Effect> map = new HashMap<>();
		map.put("sound", new SoundHookEffect());
		map.put("custom_sound", new CustomSoundHookEffect());
		map.put("particle", new ParticleHookEffect());
		map.put("potion", new PotionHookEffect());
		map.put("action_bar", new ActionBarHookEffect());
		map.put("title", new TitleHookEffect());
		map.put("boss_bar", new BossBarHookEffect(plugin));
		map.put("message", new MessageHookEffect());
		map.put("command", new CommandHookEffect());
		map.put("push", new PushHookEffect());
		map.put("camera_shake", new CameraShakeHookEffect());
		map.put("ignite", new IgniteHookEffect());
		map.put("cooldown", new CooldownHookEffect());
		map.put("firework", new FireworkHookEffect());
		map.put("lightning", new LightningHookEffect());
		return map;
	}

	/**
	 * Runs {@code hook}'s effect list for {@code weapon}: the weapon's own list if non-empty, else
	 * {@code settings}' {@code Default_Effects} list for the same hook — never both.
	 */
	public void run(Weapon weapon, EffectHook hook, EffectContext ctx) {
		List<EffectSpec> specs = weapon.getEffects().forHook(hook);
		if (specs.isEmpty()) specs = BartizanSettings.getDefaultEffects().forHook(hook);
		runSpecs(specs, hook, weapon.getName(), ctx);
	}

	/**
	 * {@code run(Weapon, ...)}'s twin for a non-weapon {@link EffectsData} owner — a wearable's own
	 * {@code Effects:} block (weapons-roadmap.md gate {@code HL}, §5: {@code ON_EQUIP}/{@code ON_UNEQUIP}/
	 * {@code ON_HIT_TAKEN}). Still falls back to {@code Default_Effects} when {@code effects} declares nothing for
	 * {@code hook} — {@code ownerName} is used only for the log line identifying which owner's effect threw.
	 */
	public void run(EffectsData effects, String ownerName, EffectHook hook, EffectContext ctx) {
		List<EffectSpec> specs = effects.forHook(hook);
		if (specs.isEmpty()) specs = BartizanSettings.getDefaultEffects().forHook(hook);
		runSpecs(specs, hook, ownerName, ctx);
	}

	/**
	 * The effects are built inline, not as beans, so this runner forwards disable to any that hold live state
	 * (e.g. {@link BossBarHookEffect}'s still-showing bars — BZ-EF-02).
	 */
	@Override
	public void onShutdown() {
		for (Effect effect : registry.values()) {
			if (effect instanceof BeanLifecycle lifecycle) lifecycle.onShutdown();
		}
	}

	private void runSpecs(List<EffectSpec> specs, EffectHook hook, String ownerName, EffectContext ctx) {
		if (specs.isEmpty()) return;

		for (EffectSpec spec : specs) {
			Effect effect = registry.get(spec.type().toLowerCase(Locale.ROOT));

			if (effect == null) {
				log.warn("Unknown effect type '{}' for hook {} on '{}'", spec.type(), hook.key(), ownerName);
				continue;
			}

			try {
				effect.run(spec, ctx);
			} catch (Exception exception) {
				log.warn("Effect '{}' for hook {} on '{}' threw: {}", spec.type(), hook.key(), ownerName,
				         exception.toString());
			}
		}
	}

}
