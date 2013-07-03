package ch.unibe.scg.cc;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import javax.inject.Inject;

import ch.unibe.scg.cc.Protos.CloneType;
import ch.unibe.scg.cc.Protos.CodeFile;
import ch.unibe.scg.cc.Protos.Function;
import ch.unibe.scg.cc.Protos.Project;
import ch.unibe.scg.cc.Protos.Version;
import ch.unibe.scg.cc.lines.StringOfLines;
import ch.unibe.scg.cc.lines.StringOfLinesFactory;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.Iterables;
import com.google.protobuf.ByteString;

public class Frontend implements Closeable {
	static final int MINIMUM_LINES = 5;
	static final int MINIMUM_FRAME_SIZE = MINIMUM_LINES;

	final private PhaseFrontend type1;
	final private PhaseFrontend type2;
	final private Tokenizer tokenizer;
	final private StandardHasher standardHasher;
	final private Hasher shingleHasher;
	final private StringOfLinesFactory stringOfLinesFactory;
	final private CellCodec codec;
	final private CellSink sink;

	private static final int CACHE_SIZE = 1000000;
	/** Functions that were successfully written to DB in this mapper */
	final Cache<ByteString, Boolean> writtenFunctions = CacheBuilder.newBuilder().maximumSize(CACHE_SIZE).build();
	/** Files that were successfully written to DB in this mapper */
	final Cache<ByteString, Boolean> writtenFiles = CacheBuilder.newBuilder().maximumSize(CACHE_SIZE).build();

	@Inject
	Frontend(StandardHasher standardHasher, ShingleHasher shingleHasher, @Type1 PhaseFrontend type1,
			@Type2 PhaseFrontend type2, Tokenizer tokenizer, StringOfLinesFactory stringOfLinesFactory,
			CellCodec codec, CellSink sink) {
		this.standardHasher = standardHasher;
		this.shingleHasher = shingleHasher;
		this.type1 = type1;
		this.type2 = type2;
		this.tokenizer = tokenizer;
		this.stringOfLinesFactory = stringOfLinesFactory;
		this.codec = codec;
		this.sink = sink;
	}

	public class ProjectRegistrar implements AutoCloseable {
		final private Project.Builder project;
		/** Separate from project, because we're keeping builders */
		final private Collection<Version.Builder> versions = new ArrayList<>();

		ProjectRegistrar(String projectName) {
			project = Project.newBuilder().setName(projectName);
		}

		@Override
		public void close() {
			if (versions.size() == 0) {
				return;
			}
			Set<ByteString> hs = new HashSet<>();
			for (Version.Builder v : versions) {
				hs.add(v.getHash());
			}
			project.setHash(xor(hs));
			sink.write(codec.encodeProject(project.build()));

			for (Version.Builder v : versions) {
				v.setProject(project.getHash());
				sink.write(codec.encodeVersion(v.build()));
			}
		}

		public VersionRegistrar makeVersionRegistrar(String versionName) {
			return new VersionRegistrar(this, versionName);
		}

		void register(Version.Builder v) {
			versions.add(v);
		}
	}

	public class VersionRegistrar implements AutoCloseable {
		final private ProjectRegistrar projectRegistrar;
		/** Separate from version because we're storing the builders. */
		final private Collection<CodeFile.Builder> files = new ArrayList<>();
		final private Version.Builder version;

		VersionRegistrar(ProjectRegistrar projectRegistrar, String versionName) {
			this.projectRegistrar = projectRegistrar;
			version = Version.newBuilder().setName(versionName);
		}

		@Override
		public void close() {
			if (files.size() == 0) {
				return;
			}

			Set<ByteString> hs = new HashSet<>();
			for (CodeFile.Builder fil : files) {
				hs.add(fil.getHash());
			}
			version.setHash(xor(hs));
			projectRegistrar.register(version);

			for (CodeFile.Builder fil : files) {
				fil.setVersion(version.getHash());
				sink.write(codec.encodeCodeFile(fil.build()));
			}

			sink.write(codec.encodeVersion(version.build()));
		}

		public FileRegistrar makeFileRegistrar() {
			return new FileRegistrar(this);
		}

		void register(CodeFile.Builder fil) {
			files.add(fil);
		}
	}

	public class FileRegistrar {
		final private VersionRegistrar versionRegistrar;

		FileRegistrar(VersionRegistrar versionRegistrar) {
			this.versionRegistrar = versionRegistrar;
		}

		public void register(String path, String contents) {
			CodeFile.Builder fil = CodeFile.newBuilder()
					.setPath(path)
					.setContents(contents)
					.setHash(ByteString.copyFrom(standardHasher.hash(contents)));

			versionRegistrar.register(fil);

			if (writtenFiles.getIfPresent(fil.getHash()) == null) {
				return;
			}

			for (Function fun : tokenizer.tokenize(contents)) {
				// type-1
				StringBuilder c = new StringBuilder(fun.getContents());
				type1.normalize(c);
				String normalized = c.toString();

				// TODO: Should this be part of the tokenizer?
				fun = Function.newBuilder(fun).setHash(ByteString.copyFrom(standardHasher.hash(fun.getContents()))).build();

				if (Utils.countLines(normalized) < MINIMUM_LINES) {
					continue;
				}

				sink.write(codec.encodeFunction(fun));

				if (writtenFunctions.getIfPresent(fun.getHash()) == null) {
					return;
				}

				registerSnippets(fun, normalized, CloneType.LITERAL);

				// type-2
				type2.normalize(c);
				normalized = c.toString();
				registerSnippets(fun, normalized, CloneType.RENAMED);

				// type-3
				registerSnippets(fun, normalized, CloneType.GAPPED);
			}
		}
	}

	public ProjectRegistrar makeProjectRegistrar(String projectName) {
		return new ProjectRegistrar(projectName);
	}

	@Override
	public void close() throws IOException {
		if (sink != null) {
			sink.close();
		}
	}

	private void registerSnippets(Protos.Function fun, String normalized, CloneType type) {
		StringOfLines s = stringOfLinesFactory.make(normalized);
		Hasher hasher = standardHasher;
		if (type.equals(CloneType.GAPPED)) {
			hasher = shingleHasher;
		}
		for (int frameStart = 0; frameStart + MINIMUM_LINES <= s.getNumberOfLines(); frameStart++) {
			String snippet = s.getLines(frameStart, MINIMUM_LINES);
			byte[] hash;
			try {
				hash = hasher.hash(snippet);
			} catch (CannotBeHashedException e) {
				// cannotBeHashedCounter.increment(1);
				continue;
			}

			sink.write(codec.encodeSnippet(
					Protos.Snippet.newBuilder()
					.setFunction(fun.getHash())
					.setLength(MINIMUM_LINES)
					.setPosition(frameStart)
					.setHash(ByteString.copyFrom(hash))
					.build()));
		}
	}

	static ByteString xor(Iterable<ByteString> hashes) {
		assert !Iterables.isEmpty(hashes) : "You asked me to xor an empty iterable.";

		byte[] ret = new byte[Iterables.getFirst(hashes, null).size()];

		for (ByteString h : hashes) {
			Utils.xor(ret, h.toByteArray());
		}

		return ByteString.copyFrom(ret);
	}
}
