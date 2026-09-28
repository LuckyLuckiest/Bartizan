package org.luckyraven.bartizan.api.npc;

import org.luckyraven.keystone.npc.spi.NpcRangedAttack;

/**
 * The single implementation of Keystone's {@link NpcRangedAttack} (architecture/PICK.md Amendments ruling (b)).
 * Bartizan owns NPC firing cadence; consumers never implement this - they obtain one from
 * {@link NpcWeaponFactory} and hand it to {@code AbstractNpc.setRangedAttack(...)}.
 * <p>
 * Since 0.6.0 (Keystone 1.13) the cadence counts server ticks through {@code tick(int)}, which Keystone calls with
 * the elapsed ticks divided by the NPC's {@code AbstractNpc#setFireRateScale} - that scale is the consumer's
 * per-tier fire-rate knob, so Bartizan needs no extra setter. Keystone's default scale 1.0 fires up to
 * {@code aiTickRate} times faster than 0.5.x; {@code setFireRateScale(aiTickRate)} reproduces the 0.5.x cadence
 * exactly (one tick off per AI tick), so a consumer must set it - start there and tune per tier - and ship with
 * 0.6.0 (documentation/migration.md §16). {@code isReloading()} mirrors the held weapon.
 */
public interface NpcWeaponController extends NpcRangedAttack {
	// inherited: isRanged(), isBusy(), isReloading(), tryFire(LivingEntity), triggerReload(),
	//            refreshHeldItem(), onDestroy(), tick(), tick(int elapsedServerTicks)
}
