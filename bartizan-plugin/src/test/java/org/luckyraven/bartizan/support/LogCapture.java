package org.luckyraven.bartizan.support;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Attaches a capturing log4j2 appender to a class's Keystone {@code @CustomLog} logger for the duration of a
 * try-with-resources block, so a test can assert the level a production code path actually logged at instead of
 * only its side effects. {@code @CustomLog} (lombok.config) backs every {@code log} field with
 * {@code org.luckyraven.keystone.logging.Logger.getLogger(TYPE)}, which is a plain, cached log4j2
 * {@link org.apache.logging.log4j.core.Logger} - the exact instance this attaches to.
 */
public final class LogCapture implements AutoCloseable {

	private final Logger            logger;
	private final Level             originalLevel;
	private final CapturingAppender appender;

	private LogCapture(Logger logger) {
		this.logger        = logger;
		this.originalLevel = logger.getLevel();
		this.appender      = new CapturingAppender();
		appender.start();
		logger.addAppender(appender);
		logger.setLevel(Level.ALL);
	}

	public static LogCapture attach(Class<?> loggedClass) {
		org.apache.logging.log4j.Logger raw = org.luckyraven.keystone.logging.Logger.getLogger(loggedClass);
		if (!(raw instanceof Logger core)) {
			throw new IllegalStateException("expected a log4j-core Logger, got " + raw.getClass());
		}
		return new LogCapture(core);
	}

	public boolean any(Level level, String messageFragment) {
		return appender.events.stream().anyMatch(
				event -> event.getLevel().equals(level) &&
				         event.getMessage().getFormattedMessage().contains(messageFragment));
	}

	@Override
	public void close() {
		logger.removeAppender(appender);
		logger.setLevel(originalLevel);
		appender.stop();
	}

	private static final class CapturingAppender extends AbstractAppender {

		private final List<LogEvent> events = new CopyOnWriteArrayList<>();

		private CapturingAppender() {
			super("LogCapture-" + System.nanoTime(), null, null);
		}

		@Override
		public void append(LogEvent event) {
			events.add(event.toImmutable());
		}
	}

}
