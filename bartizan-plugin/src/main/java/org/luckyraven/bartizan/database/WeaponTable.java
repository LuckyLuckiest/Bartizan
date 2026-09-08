package org.luckyraven.bartizan.database;

import org.luckyraven.keystone.persistence.database.component.Attribute;
import org.luckyraven.keystone.persistence.database.component.Table;
import org.luckyraven.bartizan.api.weapon.Weapon;

import java.sql.Types;
import java.util.Map;
import java.util.UUID;

public class WeaponTable extends Table<Weapon> {

	public WeaponTable() {
		super("weapon");

		Attribute<UUID>   uuid = new Attribute<>("uuid", true, UUID.class);
		Attribute<String> type = new Attribute<>("type", false, String.class);

		this.addAttribute(uuid);
		this.addAttribute(type);
	}

	@Override
	public Object[] getData(Weapon data) {
		return new Object[]{data.getUuid().toString(), data.getName()};
	}

	@Override
	public Map<String, Object> searchCriteria(Weapon data) {
		return createSearchCriteria("uuid = ?", new Object[]{data.getUuid().toString()}, new int[]{Types.CHAR},
		                            new int[]{0});
	}
}
