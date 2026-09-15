package org.luckyraven.bartizan.api.weapon;

import org.bukkit.entity.Player;

/**
 * A weapon's skin state (weapons-roadmap.md gate {@code HJ}) — which {@code Skins:} entry (the root section, or a
 * selected {@code Skins.Named} skin) supplies the custom model data / item model rendered right now.
 * {@link Weapon#currentSkinState(Player)} resolves the active state in a fixed priority order: {@link #RELOAD} >
 * {@link #SCOPE} > {@link #NO_AMMO} > {@link #SPRINT} > {@link #DEFAULT}.
 */
public enum SkinState {

	DEFAULT("Default"),
	SCOPE("Scope"),
	RELOAD("Reload"),
	SPRINT("Sprint"),
	NO_AMMO("No_Ammo");

	private final String key;

	SkinState(String key) {
		this.key = key;
	}

	/**
	 * @return the {@code Capitalized_Underscore} YAML spelling of this state under {@code Skins:}/
	 * 		{@code Skins.Named.<name>}.
	 */
	public String key() {
		return key;
	}

}
