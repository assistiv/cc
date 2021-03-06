package ch.unibe.scg.cells.benchmarks;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.NumberFormat;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import ch.unibe.scg.cells.Cell;
import ch.unibe.scg.cells.Cells;
import ch.unibe.scg.cells.Codec;
import ch.unibe.scg.cells.InMemoryPipeline;
import ch.unibe.scg.cells.LocalExecutionModule;
import ch.unibe.scg.cells.Mapper;
import ch.unibe.scg.cells.OneShotIterable;
import ch.unibe.scg.cells.Pipeline;
import ch.unibe.scg.cells.Sink;

import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.io.CharStreams;
import com.google.common.primitives.Ints;
import com.google.inject.Guice;
import com.google.protobuf.ByteString;

/**
 * Benchmarks cells performance on a local machine with wordcount problem.
 * Input folder can be specified via command line argument.
 */
public class CellsInMemoryWordCountBenchmark {
	private final static int TIMES = 50;

	final static class WordCount {
		final String word;
		final String fileName;
		int count;

		WordCount(String word, String fileName, int count) {
			this.count = count;
			this.word = word;
			this.fileName = fileName;
		}

		@Override
		public String toString() {
			return word + ": " + count;
		}
	}

	final static class WordCountCodec implements Codec<WordCount> {
		private static final long serialVersionUID = 1L;

		@Override
		public Cell<WordCount> encode(WordCount s) {
			return Cell.make(ByteString.copyFromUtf8(s.word),
					ByteString.copyFromUtf8(s.fileName),
					ByteString.copyFrom(Ints.toByteArray(s.count)));
		}

		@Override
		public WordCount decode(Cell<WordCount> encoded) throws IOException {
			return new WordCount(encoded.getRowKey().toStringUtf8(),
					encoded.getColumnKey().toStringUtf8(),
					Ints.fromByteArray(encoded.getCellContents().toByteArray()));
		}
	}

	final static class FileContent {
		final String fileName;
		final String content;

		FileContent(String fileName, String content) {
			this.fileName = fileName;
			this.content = content;
		}
	}

	final static class FileContentCodec implements Codec<FileContent> {
		private static final long serialVersionUID = 1L;
		private static ByteString colKey = ByteString.copyFromUtf8("c");

		@Override
		public Cell<FileContent> encode(FileContent b) {
			return Cell.make(ByteString.copyFromUtf8(b.fileName),
					colKey,
					ByteString.copyFromUtf8(b.content));
		}

		@Override
		public FileContent decode(Cell<FileContent> encoded) throws IOException {
			return new FileContent(encoded.getRowKey().toStringUtf8(),
					encoded.getCellContents().toStringUtf8());
		}
	}

	final static class WordParser implements Mapper<FileContent, WordCount> {
		private static final long serialVersionUID = 1L;

		@Override
		public void close() throws IOException { }

		@Override
		public void map(FileContent first, OneShotIterable<FileContent> row, Sink<WordCount> sink)
				throws IOException, InterruptedException {
			Map<String, WordCount> dictionary = new HashMap<>();
			for (FileContent file : row) {
				for (String word: file.content.split("\\s+")) {
					if (!word.isEmpty()) {
						if (!dictionary.containsKey(word)) {
							dictionary.put(word, new WordCount(word, file.fileName, 0));
						}
						dictionary.get(word).count++;
					}
				}
			}

			for (WordCount wc : dictionary.values()) {
				sink.write(wc);
			}
		}
	}

	final static class WordCounter implements Mapper<WordCount, WordCount> {
		private static final long serialVersionUID = 1L;

		@Override
		public void close() throws IOException { }

		@Override
		public void map(WordCount first, OneShotIterable<WordCount> row, Sink<WordCount> sink)
				throws IOException, InterruptedException {
			int count = 0;

			for (WordCount wc : row) {
				count += wc.count;
			}
			sink.write(new WordCount(first.word, first.fileName, count));
		}
	}

	/**
	 * Runs a wordcount benchmark. You can specify input folder with first argument.
	 * The default input folder is "benchmarks/data"
	 */
	public static void main(String[] args) throws IOException, InterruptedException {
		String input = "benchmarks/data";
		if (args.length > 0) {
			input = args[0];
		}

		double[] timings = new double[TIMES];
		NumberFormat f = NumberFormat.getInstance();
		f.setMaximumFractionDigits(2);

		for (int i = 0; i < TIMES; i++) {
			long startTime = System.nanoTime();

			try (InMemoryPipeline<FileContent, WordCount> pipe
						= Guice.createInjector(new LocalExecutionModule()).getInstance(InMemoryPipeline.Builder.class)
								.make(Cells.shard(Cells.encode(readFilesFromDisk(input), new FileContentCodec())))) {

				run(pipe);

				long total = 0;
				for (Iterable<WordCount> wcs : pipe.lastEfflux()) {
					total += Iterables.size(wcs);
				}

				timings[i] = (System.nanoTime() - startTime) / 1_000_000_000.0;
				System.out.println(f.format(timings[i]));

				System.out.println("Total words: " + total);
			}
		}

		System.out.println("--------------");
		System.out.println(String.format("median: %s", f.format(median(timings))));
		System.out.println(String.format("min: %s", f.format(min(timings))));

	}

	static void run(Pipeline<FileContent, WordCount> pipe) throws IOException, InterruptedException {
		pipe.influx(new FileContentCodec())
			.map(new WordParser())
			.shuffle(new WordCountCodec())
			.mapAndEfflux(new WordCounter(), new WordCountCodec());
	}

	static Iterable<FileContent> readFilesFromDisk(String path) {
		final ImmutableList.Builder<FileContent> ret = ImmutableList.builder();
		for (File f : new File(path).listFiles()) {
			try {
				ret.add(new FileContent(f.getName(),
						CharStreams.toString(new InputStreamReader(new FileInputStream(f), Charsets.UTF_8))));
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

		return ret.build();
	}

	private static double median(double[] d) {
		if (d == null || d.length == 0) {
			throw new IllegalArgumentException("Median of 0 elements is undefined.");
		}

		double[] copy = Arrays.copyOf(d, d.length);
		Arrays.sort(copy);
		return copy[copy.length / 2];
	}

	static double min(double[] d) {
		if (d == null || d.length == 0) {
			throw new IllegalArgumentException("Min of 0 elements is undefined.");
		}

		double min = d[0];
		for (int i = 1; i < d.length; i++) {
			if (d[i] < min) {
				min = d[i];
			}
		}

		return min;
	}
}
