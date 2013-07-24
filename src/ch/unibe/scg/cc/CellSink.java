package ch.unibe.scg.cc;

import java.io.Closeable;

/** All mappers write their outputs into {@link Sink}s, are ultimately owned by a CellSink */
public interface CellSink<T> extends Closeable {
	/** Whenever a {@link Sink} issues a write, it is encoded and converted to a cell write. */
	void write(Cell<T> cell);
}
