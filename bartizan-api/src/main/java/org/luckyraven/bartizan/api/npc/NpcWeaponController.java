package org.luckyraven.bartizan.api.npc;

import org.luckyraven.keystone.npc.spi.NpcRangedAttack;

/**
 * The single implementation of Keystone's {@link NpcRangedAttack} (architecture/PICK.md Amendments ruling (b)).
 * Bartizan owns NPC firing cadence; consumers never implement this - they obtain one from
 * {@link NpcWeaponFactory} and hand it to {@code AbstractNpc.setRangedAttack(...)}.
 */
public interface NpcWeaponController extends NpcRangedAttack {
	// inherited: isRanged(), isBusy(), tryFire(LivingEntity), triggerReload(),
	//            refreshHeldItem(), onDestroy(), tick()
}
