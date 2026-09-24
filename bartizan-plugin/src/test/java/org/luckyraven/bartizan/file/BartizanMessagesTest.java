package org.luckyraven.bartizan.file;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * BZ-CM-04: {@code WEARABLE_INVALID}/{@code WEARABLE_NOT_REGISTERED}/{@code WEARABLE_NOT_WEARABLE} were declared
 * with {@code Type.PREFIX} (the generic plugin prefix) while the identical weapon/ammo failures
 * ({@code INVALID_WEAPON}, {@code INVALID_AMMO}) use {@code Type.ERROR} (the red {@code Errors.Prefix}) - a wrong
 * wearable name read as ordinary chat instead of an error. Reads the private {@code type} field directly (same
 * reflection technique {@code WearableAddonTest#setsField} already uses on a private field elsewhere in this
 * suite) rather than standing up a {@code MessageProvider}/{@code BartizanSettings} fixture just to observe an
 * enum constant.
 */
@DisplayName("BartizanMessages")
class BartizanMessagesTest {

	@Test
	@DisplayName("the three wearable error entries use Type.ERROR, matching INVALID_WEAPON/INVALID_AMMO")
	void wearableErrorEntries_useErrorType() throws ReflectiveOperationException {
		assertEquals(BartizanMessages.Type.ERROR, typeOf(BartizanMessages.WEARABLE_INVALID));
		assertEquals(BartizanMessages.Type.ERROR, typeOf(BartizanMessages.WEARABLE_NOT_REGISTERED));
		assertEquals(BartizanMessages.Type.ERROR, typeOf(BartizanMessages.WEARABLE_NOT_WEARABLE));
	}

	private static BartizanMessages.Type typeOf(BartizanMessages entry) throws ReflectiveOperationException {
		Field field = BartizanMessages.class.getDeclaredField("type");
		field.setAccessible(true);
		return (BartizanMessages.Type) field.get(entry);
	}

}
