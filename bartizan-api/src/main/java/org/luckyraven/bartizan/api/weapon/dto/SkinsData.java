package org.luckyraven.bartizan.api.weapon.dto;

import lombok.Getter;
import org.bukkit.NamespacedKey;
import org.jetbrains.annotations.Nullable;
import org.luckyraven.bartizan.api.weapon.SkinState;
import org.luckyraven.keystone.exception.PluginException;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A weapon's {@code Skins:} section (weapons-roadmap.md gate {@code HJ}): per-{@link SkinState} custom-model-data
 * overrides at the root level, plus a table of player-selectable {@code Named} skins that may each override any
 * state and/or {@code Item_Model}. {@code null} on {@code Weapon#getSkinsData()} means the weapon has no
 * {@code Skins:} block at all — it renders entirely through {@code Information.Custom_Model_Data}/
 * {@code Item_Model} (see {@code Weapon#resolveCustomModelData}/{@code Weapon#resolveItemModel}).
 */
@Getter
public class SkinsData implements Cloneable {

	private final Map<SkinState, Integer> states;
	private final Map<String, NamedSkin>  named;

	public SkinsData(Map<SkinState, Integer> states, Map<String, NamedSkin> named) {
		this.states = new EnumMap<>(SkinState.class);
		this.states.putAll(states);

		this.named = new LinkedHashMap<>(named);
	}

	/**
	 * @return the configured custom model data for {@code state} at the root level, or {@code null} when that
	 * 		state has no root-level override.
	 */
	@Nullable
	public Integer state(SkinState state) {
		return states.get(state);
	}

	/**
	 * @param name a {@code Skins.Named} key, matched case-insensitively.
	 *
	 * @return the named skin, or {@code null} when no {@code Named} entry has that name.
	 */
	@Nullable
	public NamedSkin named(String name) {
		return named.get(name.toLowerCase(Locale.ROOT));
	}

	/** @return every configured {@code Skins.Named} key, lowercased. */
	public Set<String> namedKeys() {
		return named.keySet();
	}

	/**
	 * One {@code Skins.Named.<name>} entry: its own per-{@link SkinState} overrides plus an optional
	 * {@code Item_Model} override. Every state falls back to this skin's own {@link SkinState#DEFAULT} before
	 * falling through to the root {@link SkinsData}.
	 */
	public record NamedSkin(Map<SkinState, Integer> states, @Nullable NamespacedKey itemModel) {

		public NamedSkin(Map<SkinState, Integer> states, @Nullable NamespacedKey itemModel) {
			Map<SkinState, Integer> copy = new EnumMap<>(SkinState.class);
			copy.putAll(states);

			this.states    = copy;
			this.itemModel = itemModel;
		}

		/** @return the configured custom model data for {@code state} on this named skin, or {@code null}. */
		@Nullable
		public Integer state(SkinState state) {
			return states.get(state);
		}

	}

	@Override
	public SkinsData clone() {
		try {
			// states/named hold immutable value types (Integer, NamedSkin) and are never mutated after this
			// object is constructed — a shallow copy sharing the same map instances across template copies is
			// safe, mirroring HudData#bossBar.
			return (SkinsData) super.clone();
		} catch (CloneNotSupportedException exception) {
			throw new PluginException(exception);
		}
	}

}
