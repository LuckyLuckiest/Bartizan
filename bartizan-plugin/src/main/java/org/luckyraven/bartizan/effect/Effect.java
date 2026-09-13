package org.luckyraven.bartizan.effect;

import org.luckyraven.bartizan.api.weapon.dto.EffectSpec;

/**
 * One effect type's runtime behaviour (e.g. play a sound, spawn a particle). Registered by lowercase type key in
 * {@link EffectRunner}. Implementations resolve their own args off {@code spec} and their targets/locations off
 * {@code ctx} — the caller catches and logs any exception so one bad entry never aborts the rest of a hook's list.
 */
public interface Effect {

	void run(EffectSpec spec, EffectContext ctx);

}
