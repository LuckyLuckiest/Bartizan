package org.luckyraven.bartizan.wearable;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * A wearables.yml carried over from Gangland 0.8.4 keeps the pre-split {@code Jetpack:} block. The loader must
 * translate it into the {@code Extra_Tags:} shape, or the built item never carries the {@code fuel} tag that the
 * gadget module's {@code isJetpack} check keys on and the jetpack silently never activates.
 */
class WearableAddonLegacyJetpackTest {

	private static final String LEGACY = """
			jetpack:
			   Material: IRON_CHESTPLATE
			   Jetpack:
			      Fuel_Key: "gasoline"
			      Fuel_Consumption_Rate: 1
			      Ascend_Power: 0.2
			      Glide_Descent_Rate: -0.05
			      Max_Speed_Y: 0.45
			      Sound:
			         Thrust:
			            Default_Sound:
			               Sound: ENTITY_BLAZE_SHOOT
			               Volume: 0.6
			               Pitch: 1.8
			""";

	@Test
	void legacyJetpackBlockBecomesExtraTags() throws Exception {
		YamlConfiguration yaml = new YamlConfiguration();
		yaml.loadFromString(LEGACY);
		ConfigurationSection jetpack = yaml.getConfigurationSection("jetpack.Jetpack");

		Map<String, Object> tags = WearableAddon.legacyJetpackToExtraTags(jetpack);

		assertEquals("gasoline", tags.get("fuel"));
		assertEquals(3600, tags.get("fuel_current"));
		assertEquals(3600, tags.get("fuel_max"));
		assertEquals(1, tags.get("jetpack_fuel_consumption_rate"));
		assertEquals(0.2, tags.get("jetpack_ascend_power"));
		assertEquals(0.45, tags.get("jetpack_max_speed_y"));
		assertFalse(tags.containsKey("jetpack_glide_descent_rate"));

		Map<?, ?> sounds = assertInstanceOf(Map.class, tags.get("Sounds"));
		Map<?, ?> thrust = assertInstanceOf(Map.class, sounds.get("Thrust"));
		Map<?, ?> sound  = assertInstanceOf(Map.class, thrust.get("Default_Sound"));
		assertEquals("ENTITY_BLAZE_SHOOT", sound.get("Sound"));
	}
}
