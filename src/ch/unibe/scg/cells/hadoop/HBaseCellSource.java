package ch.unibe.scg.cells.hadoop;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.NoSuchElementException;

import javax.inject.Inject;

import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;

import ch.unibe.scg.cells.Annotations.FamilyName;
import ch.unibe.scg.cells.Cell;
import ch.unibe.scg.cells.CellSource;

import com.google.common.collect.UnmodifiableIterator;
import com.google.protobuf.ByteString;

/** A cell source reading from a HTable. Don't forget to close it when you're done. Can be iterated only once! */
public class HBaseCellSource<T> implements CellSource<T> {
	final private ByteString family;
	/** May be null! */
	final private HTable hTable;
	final private ResultScanner scanner;
	boolean iterated = false;

	@Inject
	HBaseCellSource(@FamilyName ByteString family, HTable hTable) {
		// Doing openScanner in the object and not in the provider drastically
		// simplifies serialization.
		this(family, openScanner(hTable, family), hTable);
	}

	HBaseCellSource(@FamilyName ByteString family, ResultScanner scanner, HTable hTable) {
		this.family = family;
		this.scanner = scanner;
		this.hTable = hTable;
	}

	class ResultScannerIterator extends UnmodifiableIterator<Iterable<Cell<T>>> {
		/** The current row's column keys and cell contents. Null if the iterator is empty. */
		Iterable<Entry<byte[], byte[]>> curRow;
		/** The current row's key. */
		ByteString rowKey;

		/** {@code next} may be null. */
		ResultScannerIterator() {
			forward();
		}

		@Override public boolean hasNext() {
			return curRow != null;
		}

		@Override public Iterable<Cell<T>> next() {
			if (!hasNext()) { // Demanded by Iterator contract.
				throw new NoSuchElementException();
			}

			Iterable<Entry<byte[], byte[]>> oldRow = curRow;
			ByteString oldRowKey = rowKey;
			forward();

			List<Cell<T>> ret = new ArrayList<>();
			for (Entry<byte[], byte[]> e : oldRow) {
				ret.add(Cell.<T> make(oldRowKey, ByteString.copyFrom(e.getKey()), ByteString.copyFrom(e.getValue())));
			}
			return ret;
		}

		/** Advance curRow and rowKey to point to the row to be returned next. */
		private void forward() {
			Result r;
			try {
				r = scanner.next();
			} catch (IOException e1) {
				// TODO: Choose a better exception.
				throw new RuntimeException(e1);
			}
			if (r == null) {
				rowKey = null;
				curRow = null;
				scanner.close();
			} else {
				rowKey = ByteString.copyFrom(r.getRow());
				curRow = r.getFamilyMap(family.toByteArray()).entrySet();
			}
		}
	}

	/** Provides scans appropriate for reading tables sequentially, as in a Mapper. */
	static Scan makeScan() {
		Scan scan = new Scan();
		// HBase book says 500, see chapter 12.9.1. Hbase Book
		// This is 10 times faster than the default value.
		scan.setCaching(1000);

		scan.setCacheBlocks(false); // HBase book 12.9.5. Block Cache
		return scan;
	}

	private static ResultScanner openScanner(HTable tab, ByteString family) {
		Scan scan = makeScan();
		scan.addFamily(family.toByteArray());
		try {
			return tab.getScanner(scan);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public Iterator<Iterable<Cell<T>>> iterator() {
		if (iterated) {
			throw new IllegalStateException("This source has been iterated before.");
		}
		iterated = true;

		return new ResultScannerIterator();
	}

	@Override
	public void close() throws IOException {
		if (hTable != null) {
			hTable.close();
		}
		scanner.close();
	}
}
