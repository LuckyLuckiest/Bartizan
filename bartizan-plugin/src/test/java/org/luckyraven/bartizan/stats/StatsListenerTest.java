package org.luckyraven.bartizan.stats;

import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BZ-HU-04 review: a Citizens player-NPC raises {@code PlayerDeathEvent} too, so the unconditional death count wrote
 * a stats file for every dying NPC's random uuid. {@code isNpc} is stubbed: {@code NpcSupport} needs Citizens.
 */
@DisplayName("StatsListener - deaths")
class StatsListenerTest {

	@Test
	@DisplayName("a player-NPC death is not counted")
	void npcDeath_notRecorded() {
		StatsService  statsService = mock(StatsService.class);
		StatsListener listener     = spy(new StatsListener(statsService));
		Player        npc          = mock(Player.class);
		doReturn(true).when(listener).isNpc(npc);

		listener.onDeath(deathOf(npc));

		verify(statsService, never()).recordDeath(npc);
	}

	@Test
	@DisplayName("a real player's death is counted")
	void playerDeath_recorded() {
		StatsService  statsService = mock(StatsService.class);
		StatsListener listener     = spy(new StatsListener(statsService));
		Player        player       = mock(Player.class);
		doReturn(false).when(listener).isNpc(player);

		listener.onDeath(deathOf(player));

		verify(statsService).recordDeath(player);
	}

	private static PlayerDeathEvent deathOf(Player player) {
		PlayerDeathEvent event = mock(PlayerDeathEvent.class);
		when(event.getEntity()).thenReturn(player);
		return event;
	}

}
