package ch.unibe.scg.cells;

import static com.google.common.base.Preconditions.checkNotNull;

import java.util.concurrent.atomic.AtomicLong;

import javax.inject.Inject;

import ch.unibe.scg.cells.CounterModule.CounterName;

/**
 * Represents a local {@link Counter}, that can be used inside {@link InMemoryPipeline}
 *
 * <p>
 * This class is {@link java.io.Serializable}, but there's a caveat.
 * Cells serializes this classes using {@link ShallowSerializingCopy}.
 * However, if serialization is attempted using a classical {@link java.io.ObjectOutputStream},
 * it will throw a {@link UnsupportedOperationException}.
 **/
class LocalCounter implements Counter {
	private static final long serialVersionUID = 1L;

	final private  String counterName;
	final private  AtomicLong count = new AtomicLong();

	@Inject
	LocalCounter(@CounterName String counterName) {
		this.counterName = checkNotNull(counterName);
	}

	@Override
	public void increment(long cnt) {
		//TODO: add pipeline stage awareness.
		count.addAndGet(cnt);
	}

	@Override
	public String toString() {
		//TODO: the full annotation name could be a little too much to display.
		return String.format("%s: %s", counterName, count);
	}

	private Object writeReplace() {
		return new ShallowSerializingCopy.SerializableLiveObject(this);
	}
}