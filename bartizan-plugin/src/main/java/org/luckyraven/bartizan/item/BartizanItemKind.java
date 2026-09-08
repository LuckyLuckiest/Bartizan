package org.luckyraven.bartizan.item;

import org.luckyraven.keystone.item.ItemKind;

/**
 * bartizan.md §1.2: new enum replacing Gangland's {@code ItemKind.WEAPON} / {@code .AMMUNITION} / {@code .WEARABLE}
 * constants — {@code org.luckyraven.gangland.item.ItemKind} does not exist here, only Keystone's
 * {@link ItemKind} interface does.
 */
public enum BartizanItemKind implements ItemKind {

	WEAPON("weapon"),
	AMMUNITION("ammunition"),
	WEARABLE("wearable");

	private final String label;

	BartizanItemKind(String label) {
		this.label = label;
	}

	@Override
	public String label() {
		return label;
	}

}
