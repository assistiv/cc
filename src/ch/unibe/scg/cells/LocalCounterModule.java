package ch.unibe.scg.cells;

import com.google.inject.AbstractModule;

/**
 * Counters that cannot be shared across machines,
 * and whose count is periodically shown on the command line of the user.
 **/
public final class LocalCounterModule extends AbstractModule implements CounterModule {
	@Override
	protected void configure() {
		bind(Counter.class).to(LocalCounter.class);
	}
}