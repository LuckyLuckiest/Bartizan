package org.luckyraven.bartizan.api.weapon.dto;

import org.luckyraven.keystone.exception.PluginException;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * A weapon's (or {@code settings.yml}'s {@code Default_Effects:}) hook → effect-list table. A weapon's own list for
 * a hook replaces {@code Default_Effects}' list for that hook entirely — {@code EffectRunner} never merges the two
 * (weapons-roadmap.md gate {@code HA}, §1 "Global defaults").
 */
public class EffectsData implements Cloneable {

	private Map<EffectHook, List<EffectSpec>> byHook;

	private EffectsData() {
		this.byHook = new EnumMap<>(EffectHook.class);
	}

	public static EffectsData empty() {
		return new EffectsData();
	}

	public List<EffectSpec> forHook(EffectHook hook) {
		List<EffectSpec> specs = byHook.get(hook);
		return specs != null ? specs : List.of();
	}

	public boolean has(EffectHook hook) {
		List<EffectSpec> specs = byHook.get(hook);
		return specs != null && !specs.isEmpty();
	}

	public void put(EffectHook hook, List<EffectSpec> specs) {
		byHook.put(hook, List.copyOf(specs));
	}

	@Override
	public EffectsData clone() {
		try {
			EffectsData copy = (EffectsData) super.clone();
			copy.byHook = new EnumMap<>(byHook);
			return copy;
		} catch (CloneNotSupportedException exception) {
			throw new PluginException(exception);
		}
	}

}
