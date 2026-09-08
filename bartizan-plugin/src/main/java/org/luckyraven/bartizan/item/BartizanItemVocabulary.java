package org.luckyraven.bartizan.item;

import org.luckyraven.bartizan.api.BartizanItemPredicates;
import org.luckyraven.keystone.item.spi.ItemVocabulary;
import org.luckyraven.keystone.item.spi.ItemVocabularyRegistrar;

/**
 * Bartizan's contribution to the shared item pipeline (bartizan.md §1.3 / §C.3, R6/R7): registers the
 * weapon/ammunition/wearable converters, serializers and refreshers under the {@code "bartizan"} namespace, so
 * {@code weapon:} / {@code ammo:} / {@code wearable:} item strings keep resolving inside Gangland's loot chests,
 * shops and signs after the split. Reproduces {@code WeaponModuleConfig.java:195-206}'s registration order exactly,
 * with the load-bearing priorities from §C.3: weapon and wearable refreshers at {@code 10} (ahead of Gangland's own
 * {@code uniqueItemRefresher} at {@code 0}), ammunition at the default priority {@code 0}.
 */
public final class BartizanItemVocabulary implements ItemVocabulary {

	private final WeaponConverter          weaponConverter;
	private final AmmunitionConverter      ammunitionConverter;
	private final WearableConverter        wearableConverter;
	private final WeaponItemSerializer     weaponItemSerializer;
	private final AmmunitionItemSerializer ammunitionItemSerializer;
	private final WearableItemSerializer   wearableItemSerializer;
	private final WeaponRefresher          weaponRefresher;
	private final WearableRefresher        wearableRefresher;
	private final AmmunitionItemRefresher  ammunitionItemRefresher;

	public BartizanItemVocabulary(WeaponConverter weaponConverter, AmmunitionConverter ammunitionConverter,
	                              WearableConverter wearableConverter, WeaponItemSerializer weaponItemSerializer,
	                              AmmunitionItemSerializer ammunitionItemSerializer,
	                              WearableItemSerializer wearableItemSerializer, WeaponRefresher weaponRefresher,
	                              WearableRefresher wearableRefresher,
	                              AmmunitionItemRefresher ammunitionItemRefresher) {
		this.weaponConverter          = weaponConverter;
		this.ammunitionConverter      = ammunitionConverter;
		this.wearableConverter        = wearableConverter;
		this.weaponItemSerializer     = weaponItemSerializer;
		this.ammunitionItemSerializer = ammunitionItemSerializer;
		this.wearableItemSerializer   = wearableItemSerializer;
		this.weaponRefresher          = weaponRefresher;
		this.wearableRefresher        = wearableRefresher;
		this.ammunitionItemRefresher  = ammunitionItemRefresher;
	}

	@Override
	public String namespace() {
		return "bartizan";
	}

	@Override
	public void contribute(ItemVocabularyRegistrar registrar) {
		registrar.converter("weapon", weaponConverter);
		registrar.converter(new String[] {"ammunition", "ammo"}, ammunitionConverter);
		registrar.converter("wearable", wearableConverter);

		registrar.serializer(WeaponItemPredicates.WEAPON, weaponItemSerializer, 0);
		registrar.serializer(WeaponItemPredicates.AMMUNITION, ammunitionItemSerializer, 0);
		registrar.serializer(BartizanItemPredicates.WEARABLE, wearableItemSerializer, 0);

		registrar.refresher(weaponRefresher, 10);
		registrar.refresher(wearableRefresher, 10);
		registrar.refresher(ammunitionItemRefresher, 0);
	}

}
