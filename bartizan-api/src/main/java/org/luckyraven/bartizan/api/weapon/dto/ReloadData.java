package org.luckyraven.bartizan.api.weapon.dto;

import lombok.Builder;
import lombok.Getter;
import org.luckyraven.bartizan.api.weapon.reload.ReloadType;

@Getter
@Builder
public class ReloadData {

	private final int        cooldown;
	private final ReloadType type;

	@Override
	public String toString() {
		return String.format("ReloadData{cooldown=%d,type=%s}", cooldown, type);
	}

}
