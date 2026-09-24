package org.luckyraven.bartizan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * BZ-NU-02: {@code dependencyHandler()}/the inner {@code Dependency} class always called {@code validate(null)},
 * so {@code Dependency.validate}'s {@code if (runnable != null) runnable.run();} branch was permanently dead, yet
 * it still logged "Found X, linking..." and "Linked X" for every soft dependency present on the server - a
 * misleading pair of log lines describing a link that never happened. Both were dead weight (NBTAPI is a hard
 * {@code plugin.yml} depend routed through this soft-dependency helper besides) and are deleted outright rather
 * than kept for a log line nothing needs.
 */
class BartizanSoftDependencyTest {

	@Test
	@DisplayName("the misleading dependencyHandler()/Dependency dead code is gone")
	void dependencyHandlerAndDependencyClassAreGone() {
		boolean hasDependencyHandler = Arrays.stream(Bartizan.class.getDeclaredMethods())
				.map(Method::getName)
				.anyMatch("dependencyHandler"::equals);
		assertFalse(hasDependencyHandler, "dependencyHandler() always logged a false 'linking'/'linked' pair - delete it");

		boolean hasDependencyClass = Arrays.stream(Bartizan.class.getDeclaredClasses())
				.map(Class::getSimpleName)
				.anyMatch("Dependency"::equals);
		assertFalse(hasDependencyClass, "the Dependency helper's Runnable branch was always dead - delete it");
	}

}
