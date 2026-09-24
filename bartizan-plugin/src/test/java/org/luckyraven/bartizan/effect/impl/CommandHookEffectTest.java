package org.luckyraven.bartizan.effect.impl;

import org.bukkit.Bukkit;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.weapon.dto.EffectSpec;
import org.luckyraven.bartizan.effect.EffectContext;
import org.mockito.MockedStatic;

import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * {@link CommandHookEffect} substitution (BZ-EF-04): a non-player entity's name is player-settable (a name tag), so
 * it must never reach the console command text — it is referenced by UUID instead, which can never expand to a
 * selector such as {@code @a} or smuggle in extra arguments.
 */
@DisplayName("CommandHookEffect")
class CommandHookEffectTest {

	@Test
	@DisplayName("a name-tagged mob victim is substituted by UUID, never by its custom name")
	void mobVictim_substitutedByUuid() {
		Player shooter = mock(Player.class);
		when(shooter.getName()).thenReturn("Alice");

		UUID   mobId = UUID.randomUUID();
		Zombie mob   = mock(Zombie.class);
		when(mob.getName()).thenReturn("@a");
		when(mob.getUniqueId()).thenReturn(mobId);

		EffectContext ctx  = EffectContext.builder().source(shooter).victim(mob).build();
		EffectSpec    spec = new EffectSpec("command", Map.of("Command", "give %victim% diamond 1 %player%",
		                                                      "Target", "victim"));

		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			ConsoleCommandSender console = mock(ConsoleCommandSender.class);
			bukkit.when(Bukkit::getConsoleSender).thenReturn(console);

			new CommandHookEffect().run(spec, ctx);

			bukkit.verify(() -> Bukkit.dispatchCommand(console, "give " + mobId + " diamond 1 Alice"));
		}
	}

	@Test
	@DisplayName("a player victim is still substituted by name")
	void playerVictim_substitutedByName() {
		Player victim = mock(Player.class);
		when(victim.getName()).thenReturn("Bob");

		EffectContext ctx  = EffectContext.builder().victim(victim).build();
		EffectSpec    spec = new EffectSpec("command", Map.of("Command", "effect give %victim% glowing 3",
		                                                      "Target", "victim"));

		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			ConsoleCommandSender console = mock(ConsoleCommandSender.class);
			bukkit.when(Bukkit::getConsoleSender).thenReturn(console);

			new CommandHookEffect().run(spec, ctx);

			bukkit.verify(() -> Bukkit.dispatchCommand(console, "effect give Bob glowing 3"));
		}
	}

}
