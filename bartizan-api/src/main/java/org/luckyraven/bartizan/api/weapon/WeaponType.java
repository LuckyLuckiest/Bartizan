package org.luckyraven.bartizan.api.weapon;

public enum WeaponType {

	GUN,
	MELEE,
	THROWABLE,
	INCENDIARY,
	BIOLOGICAL,
	BEAM,
	OTHER;

	public static WeaponType getType(String type) {
		return switch (type.toLowerCase()) {
			case "gun" -> GUN;
			case "melee" -> MELEE;
			case "throwable", "throw", "grenade", "projectile", "proj" -> THROWABLE;
			case "incendiary", "fire" -> INCENDIARY;
			case "biological", "biology", "bio" -> BIOLOGICAL;
			case "beam", "laser" -> BEAM;
			default -> OTHER;
		};
	}

}
