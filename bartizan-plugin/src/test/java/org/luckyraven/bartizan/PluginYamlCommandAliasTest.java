package org.luckyraven.bartizan;

import org.bukkit.plugin.InvalidDescriptionException;
import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BZ-CM-02: {@code plugin.yml} aliased {@code /bartizan} to {@code weapon}, shadowing {@code WeaponCommand}'s own
 * {@code weapon} subcommand label — Bukkit consumed {@code weapon} as the invoking alias and handed the dispatcher
 * {@code args[0] = "give"}, which matches no registered subcommand, so {@code /weapon give ...} never reached
 * {@code WeaponGiveCommand}.
 */
class PluginYamlCommandAliasTest {

	@Test
	@SuppressWarnings("unchecked")
	void bartizanCommandDoesNotAliasWeapon() throws IOException, InvalidDescriptionException {
		try (InputStream in = getClass().getClassLoader().getResourceAsStream("plugin.yml")) {
			assertNotNull(in, "plugin.yml must be on the test classpath");

			PluginDescriptionFile description = new PluginDescriptionFile(in);

			Map<String, Object> bartizanCommand = description.getCommands().get("bartizan");
			assertNotNull(bartizanCommand, "plugin.yml must still register the bartizan command");

			List<String> aliases = (List<String>) bartizanCommand.get("aliases");

			assertFalse(aliases.contains("weapon"),
			           "the 'weapon' alias shadows WeaponCommand's own 'weapon' subcommand label");
			assertTrue(aliases.contains("btz"));
		}
	}

}
