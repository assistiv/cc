package ch.unibe.scg.cells.hadoop;

import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import org.apache.hadoop.conf.Configuration;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import ch.unibe.scg.cc.mappers.ConfigurationProvider;
import ch.unibe.scg.cells.Annotations.FamilyName;
import ch.unibe.scg.cells.Annotations.TableName;
import ch.unibe.scg.cells.Cell;
import ch.unibe.scg.cells.CellLookupTable;
import ch.unibe.scg.cells.CellSink;
import ch.unibe.scg.cells.CellSource;
import ch.unibe.scg.cells.hadoop.TableAdmin.TemporaryTable;

import com.google.common.base.Charsets;
import com.google.common.base.Stopwatch;
import com.google.common.collect.Iterables;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.TypeLiteral;
import com.google.inject.util.Providers;
import com.google.protobuf.ByteString;

/** Testing {@link HBaseCellSink} */
@SuppressWarnings("javadoc")
public final class HBaseCellSinkTest {
	private TemporaryTable testTable;
	private static final ByteString FAMILY = ByteString.copyFromUtf8("d");

	final private TableAdmin admin
			= Guice.createInjector(new HadoopModule(), new TestModule()).getInstance(TableAdmin.class);

	private class TestModule extends AbstractModule {
		@Override
		protected void configure() {
			bind(Configuration.class).toProvider(ConfigurationProvider.class);
			if (testTable != null) {
				bindConstant().annotatedWith(TableName.class)
					.to(new String(testTable.table.getTableName(), Charsets.UTF_8));
			} else {
				// HadoopModule requires it, and it's only a test, so ...
				bind(String.class).annotatedWith(TableName.class).toProvider(Providers.<String> of(null));
			}
			// TODO: Fix family globally?
			bind(ByteString.class).annotatedWith(FamilyName.class).toInstance(FAMILY);
		}
	}

	@Before
	public void createTable() throws IOException {
		testTable = admin.createTemporaryTable();
	}

	@After
	public void deleteTable() throws IOException {
		testTable.close();
	}

	/** Testing {@link HBaseCellSink#write(Cell)}. */
	@Test
	public void writeSmokeTest() throws IOException {
		Injector i = Guice.createInjector(new HadoopModule(), new TestModule());
		ByteString key = ByteString.copyFromUtf8("123");
		Cell<Void> cell = Cell.<Void> make(key, key, ByteString.EMPTY);

		try (HBaseCellLookupTable<Void> lookup = i.getInstance(HBaseCellLookupTable.class)) {
			Iterable<Cell<Void>> rowBeforeWrite = lookup.readRow(key);
			assertTrue(rowBeforeWrite.toString(), Iterables.isEmpty(rowBeforeWrite));

			try (HBaseCellSink<Void> cellSink = i.getInstance(HBaseCellSink.class)) {
				cellSink.write(cell);
			}

			Iterable<Cell<Void>> rowAfterWrite = lookup.readRow(key);
			assertFalse(rowAfterWrite.toString(), Iterables.isEmpty(rowAfterWrite));
		}

		try(CellSource<Void> src = i.getInstance(HBaseCellSource.class)) {
			Iterable<Cell<Void>> row = Iterables.getOnlyElement(src);
			Cell<Void> actual = Iterables.getOnlyElement(row);
			assertThat(actual, is(cell));
		}
	}

	@Test
	public void checkTimes() throws IOException, InterruptedException {
		final Injector injector = Guice.createInjector(new HadoopModule(), new TestModule());
		final ByteString key = ByteString.copyFromUtf8("123");
		final int rounds = 500;

		final Stopwatch writeStopWatch = new Stopwatch();
		try (CellSink<Void> sink = injector.getInstance(Key.get(new TypeLiteral<CellSink<Void>>() {}))) {
			writeStopWatch.start();
			for (int i = 0; i < rounds; i++) {
				sink.write(Cell.<Void> make(key, key, ByteString.EMPTY));
			}
		}
		writeStopWatch.stop();
		final long time2Write = writeStopWatch.elapsed(TimeUnit.MILLISECONDS);

		final Stopwatch readStopWatch = new Stopwatch();
		try (CellLookupTable<Void> lookup = injector.getInstance(HBaseCellLookupTable.class)) {
			readStopWatch.start();
			for (int i = 0; i < rounds; i++) {
				assertFalse(String.valueOf(i), Iterables.isEmpty(lookup.readRow(key)));
			}
		}
		readStopWatch.stop();
		final long time2Read = readStopWatch.elapsed(TimeUnit.MILLISECONDS);
		assertTrue(time2Write < time2Read);
	}
}
