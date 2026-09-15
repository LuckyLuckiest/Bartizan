package org.luckyraven.bartizan.api.weapon.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.luckyraven.keystone.exception.PluginException;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * A weapon's interaction-handling rules (weapons-roadmap.md gate {@code HE}, part a): the {@code Information:}
 * keys {@code Equip_Delay}/{@code Deny_Use_In_Crafting}/{@code Cancel}/{@code Attributes}, plus the {@code Shoot:}
 * keys {@code Trigger}/{@code Circumstance}/{@code Destroy_When_Empty}/{@code Reset_Fall_Distance} — all parsed by
 * {@code WeaponAddon.registerWeapon} regardless of weapon category. {@code Trigger}/{@code Circumstance} only have
 * an effect for GUN weapons ({@code WeaponInteract}/{@code GunAction}); every other weapon category simply carries
 * unused defaults.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class HandlingData implements Cloneable {

	private int                     equipDelay        = 0;
	private boolean                 denyUseInCrafting = true;
	private Cancel                  cancel            = new Cancel(false, false, true, false);
	private List<AttributeEntry>    attributes        = List.of();
	private Trigger                 trigger           = Trigger.RIGHT_CLICK;
	private Map<Circumstance, Rule> circumstances     = new EnumMap<>(Circumstance.class);
	private boolean                 destroyWhenEmpty  = false;
	private boolean                 resetFallDistance = false;

	/**
	 * {@code Information.Cancel:} — block-interaction cancellation is {@code true} by default (today's behaviour
	 * before this gate); the other three default {@code false}.
	 */
	public record Cancel(boolean dropItem, boolean swapHands, boolean breakBlocks, boolean armSwing) {
	}

	/**
	 * One {@code Information.Attributes:} entry — {@code "<attribute> <operation> <amount>"}.
	 */
	public record AttributeEntry(Attribute attribute, AttributeModifier.Operation operation, double amount) {
	}

	/**
	 * {@code Shoot.Trigger} — GUN weapons only; every other WM trigger type (e.g. hold-to-charge triggers) is
	 * deliberately unsupported.
	 */
	public enum Trigger {

		RIGHT_CLICK,
		LEFT_CLICK;

		public static Optional<Trigger> fromKey(String key) {
			if (key == null || key.isBlank()) return Optional.empty();

			return switch (key.trim().toLowerCase(Locale.ROOT)) {
				case "right_click" -> Optional.of(RIGHT_CLICK);
				case "left_click" -> Optional.of(LEFT_CLICK);
				default -> Optional.empty();
			};
		}

	}

	/**
	 * {@code Shoot.Circumstance} keys. {@link #key()} is the {@code Capitalized_Underscore} YAML spelling, also
	 * used verbatim as the {@code EffectContext#getDenyReason()} value when the circumstance blocks a shot.
	 */
	public enum Circumstance {

		SNEAKING("Sneaking"),
		SPRINTING("Sprinting"),
		SWIMMING("Swimming"),
		IN_MIDAIR("In_Midair"),
		RELOADING("Reloading"),
		ZOOMING("Zooming"),
		AMMO_EMPTY("Ammo_Empty");

		private final String key;

		Circumstance(String key) {
			this.key = key;
		}

		public String key() {
			return key;
		}

	}

	/**
	 * {@code Shoot.Circumstance.<key>} value: {@code deny} blocks the shot while the circumstance is active,
	 * {@code required} blocks it while the circumstance is NOT active.
	 */
	public enum Rule {

		DENY,
		REQUIRED;

		public static Optional<Rule> fromKey(String key) {
			if (key == null || key.isBlank()) return Optional.empty();

			return switch (key.trim().toLowerCase(Locale.ROOT)) {
				case "deny" -> Optional.of(DENY);
				case "required" -> Optional.of(REQUIRED);
				default -> Optional.empty();
			};
		}

	}

	@Override
	public HandlingData clone() {
		try {
			HandlingData copy = (HandlingData) super.clone();
			copy.circumstances = new EnumMap<>(circumstances);
			copy.attributes    = List.copyOf(attributes);
			return copy;
		} catch (CloneNotSupportedException exception) {
			throw new PluginException(exception);
		}
	}

}
