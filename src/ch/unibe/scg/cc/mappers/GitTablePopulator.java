package ch.unibe.scg.cc.mappers;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.inject.Named;


import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.CommonConfigurationKeysPublic;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Counter;
import org.apache.hadoop.mapreduce.InputFormat;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.MRJobConfig;
import org.apache.hadoop.mapreduce.OutputFormat;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.NullOutputFormat;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.transport.PackParser;
import org.eclipse.jgit.treewalk.TreeWalk;

import ch.unibe.scg.cc.Frontend;
import ch.unibe.scg.cc.Java;
import ch.unibe.scg.cc.WrappedRuntimeException;
import ch.unibe.scg.cc.activerecord.CodeFile;
import ch.unibe.scg.cc.activerecord.Project;
import ch.unibe.scg.cc.activerecord.RealProjectFactory;
import ch.unibe.scg.cc.activerecord.RealVersionFactory;
import ch.unibe.scg.cc.activerecord.Version;
import ch.unibe.scg.cc.git.PackedRef;
import ch.unibe.scg.cc.git.PackedRefParser;
import ch.unibe.scg.cc.mappers.MakeHistogram.MakeHistogramReducer;
import ch.unibe.scg.cc.mappers.TablePopulator.CharsetDetector;
import ch.unibe.scg.cc.mappers.inputformats.GitPathInputFormat;

import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.Lists;
import com.google.inject.Inject;

public class GitTablePopulator implements Runnable {
	static Logger logger = Logger.getLogger(GitTablePopulator.class.getName());
	private static final String CORE_SITE_PATH = "/etc/hadoop/conf/core-site.xml";
	private static final String MAP_MEMORY = "4000";
	private static final String MAPRED_CHILD_JAVA_OPTS = "-Xmx4000m";
	// num_projects: projects.har 405 | testdata.har: 2 | dataset.har 2246
	/** needs to correspond with the path defined in DataFetchPipeline.sh */
	private static final String PROJECTS_HAR_PATH = "har://hdfs-haddock.unibe.ch/projects/dataset.har";
	private static final int MAX_PACK_FILESIZE_MB = 50;
	/** set LOCAL_FOLDER_NAME to the same value as in RepoCloner.rb */
	private static final String LOCAL_FOLDER_NAME = "repos";
	final MRWrapper mrWrapper;

	@Inject
	GitTablePopulator(MRWrapper mrWrapper) {
		this.mrWrapper = mrWrapper;
	}

	public void run() {
		try {
			Configuration config = new Configuration();
			config.set(MRJobConfig.NUM_REDUCES, "0");
			// we don't want multiple mappers on the same input folder as we
			// directly write to HBase in our map task, hence speculative
			// execution is disabled
			config.set(MRJobConfig.MAP_SPECULATIVE, "false");
			// set to 1 if unsure TODO: check max mem allocation if only 1 jvm
			config.set(MRJobConfig.JVM_NUMTASKS_TORUN, "-1");
			config.set(MRJobConfig.TASK_TIMEOUT, "432000000"); // 5 days
			config.set(MRJobConfig.MAP_MEMORY_MB, MAP_MEMORY);
			config.set(MRJobConfig.MAP_JAVA_OPTS, MAPRED_CHILD_JAVA_OPTS);
			// don't abort the whole job if a pack file is corrupt:
			config.set(MRJobConfig.MAP_FAILURES_MAX_PERCENT, "99");
			config.setClass(Job.INPUT_FORMAT_CLASS_ATTR, GitPathInputFormat.class, InputFormat.class);
			config.setClass(Job.OUTPUT_FORMAT_CLASS_ATTR, NullOutputFormat.class, OutputFormat.class);
			config.setClass(Job.COMBINE_CLASS_ATTR, MakeHistogramReducer.class, Reducer.class);
			String inputPaths = getInputPaths();
			config.set(FileInputFormat.INPUT_DIR, inputPaths);

			logger.info("Found: " + inputPaths);
			mrWrapper.launchMapReduceJob("gitPopulate", config, Optional.<String> absent(), Optional.<String> absent(),
					null, GitTablePopulatorMapper.class.getName(), Optional.<String> absent(), Text.class,
					IntWritable.class);
		} catch (IOException e) {
			throw new WrappedRuntimeException(e);
		} catch (InterruptedException e) {
			throw new WrappedRuntimeException(e);
		} catch (ClassNotFoundException e) {
			throw new WrappedRuntimeException(e);
		}
	}

	private String getInputPaths() throws IOException, InterruptedException {
		Configuration conf = new Configuration();
		conf.addResource(new Path(CORE_SITE_PATH));
		conf.set(CommonConfigurationKeysPublic.FS_DEFAULT_NAME_KEY, PROJECTS_HAR_PATH);

		FileSystem fs = FileSystem.get(conf);
		// we read the pack files from the pre-generated index file because
		// executing `hadoop fs -ls /tmp/repos/` or recursively searching in
		// the HAR file is terribly slow
		Path indexFile = new Path(LOCAL_FOLDER_NAME + "/index");
		BufferedReader br = new BufferedReader(new InputStreamReader(fs.open(indexFile)));
		Collection<Path> packFilePaths = Lists.newArrayList();
		String line;
		while ((line = br.readLine()) != null) {
			// @formatter:off
			// sample line:
			// 5	repos/maven/objects/pack/pack-621f44a9430e5b6303c3580582160a3e53634553.pack
			// @formatter:on
			String[] record = line.split("\\t");
			int fileSize = Integer.parseInt(record[0]);
			String packPath = record[1];
			if (fileSize > MAX_PACK_FILESIZE_MB) {
				logger.warning(packPath + " exceeded MAX_PACK_FILESIZE_MB and won't be processed.");
				continue;
			}
			packFilePaths.add(new Path(packPath));
		}
		return Joiner.on(",").join(packFilePaths);
	}

	public static class GitTablePopulatorMapper extends GuiceMapper<Text, BytesWritable, Text, IntWritable> {
		private static final int MAX_TAGS_TO_PARSE = 15;

		// Optional because in MRMain, we have an injector that does not set
		// this property, and can't, because it doesn't have the counter
		// available.
		@Inject(optional = true)
		@Named(GuiceResource.COUNTER_PROCESSED_FILES)
		Counter processedFilesCounter;
		@Inject(optional = true)
		@Named(GuiceResource.COUNTER_IGNORED_FILES)
		Counter ignoredFilesCounter;

		@Inject
		GitTablePopulatorMapper(@Java Frontend javaFrontend, @Named("project2version") HTable project2version,
				@Named("version2file") HTable version2file, @Named("file2function") HTable file2function,
				@Named("function2snippet") HTable function2snippet, @Named("strings") HTable strings,
				RealProjectFactory projectFactory, RealVersionFactory versionFactory, CharsetDetector charsetDetector) {
			super();
			this.javaFrontend = javaFrontend;
			this.project2version = project2version;
			this.version2file = version2file;
			this.file2function = file2function;
			this.function2snippet = function2snippet;
			this.strings = strings;
			this.projectFactory = projectFactory;
			this.versionFactory = versionFactory;
			this.charsetDetector = charsetDetector;
		}

		final Frontend javaFrontend;
		final HTable project2version, version2file, file2function, function2snippet, strings;
		final RealProjectFactory projectFactory;
		final RealVersionFactory versionFactory;
		final CharsetDetector charsetDetector;
		final Pattern projectNameRegexNonBare = Pattern.compile(".+?/([^/]+)/.git/.*");
		final Pattern projectNameRegexBare = Pattern.compile(".+?/([^/]+)/objects/.*");

		@Override
		public void map(Text key, BytesWritable value, Context context) throws IOException, InterruptedException {
			String packFilePath = key.toString();
			logger.info("Received: " + packFilePath);
			InputStream packFileStream = new ByteArrayInputStream(value.getBytes());
			DfsRepositoryDescription desc = new DfsRepositoryDescription(packFilePath);
			InMemoryRepository r = new InMemoryRepository(desc);

			Configuration conf = new Configuration();
			conf.addResource(new Path(CORE_SITE_PATH));
			conf.set(CommonConfigurationKeysPublic.FS_DEFAULT_NAME_KEY, PROJECTS_HAR_PATH);
			FileSystem fileSystem = FileSystem.get(conf);

			PackParser pp = r.newObjectInserter().newPackParser(packFileStream);
			// ProgressMonitor set to null, so NullProgressMonitor will be used.
			pp.parse(null);

			RevWalk revWalk = new RevWalk(r);

			PackedRefParser prp = new PackedRefParser();
			Pattern pattern = Pattern.compile("(.+)objects/pack/pack-[a-f0-9]{40}.pack");
			Matcher matcher = pattern.matcher(key.toString());
			if (!matcher.matches()) {
				throw new RuntimeException("Something seems to be wrong with this input path: " + key.toString());
			}
			String gitDirPath = matcher.group(1);
			String packedRefsPath = gitDirPath + Constants.PACKED_REFS;

			FSDataInputStream ins = fileSystem.open(new Path(packedRefsPath));
			List<PackedRef> pr = prp.parse(ins);

			String projectName = getProjName(packFilePath);
			logger.info("Processing " + projectName);
			int tagCount = pr.size();
			if (tagCount > MAX_TAGS_TO_PARSE) {
				int toIndex = tagCount - 1;
				int fromIndex = (tagCount - MAX_TAGS_TO_PARSE) < 0 ? 0 : (tagCount - MAX_TAGS_TO_PARSE);
				pr = pr.subList(fromIndex, toIndex);
			}
			pr = Lists.reverse(pr);
			Iterator<PackedRef> it = pr.iterator();
			int processedTagsCounter = 0;
			while (it.hasNext() && processedTagsCounter < MAX_TAGS_TO_PARSE) {
				PackedRef paref = it.next();
				String tag = paref.getName();
				logger.info("WALK TAG: " + tag);
				revWalk.dispose();
				RevCommit commit;
				try {
					commit = revWalk.parseCommit(paref.getKey());
				} catch (MissingObjectException e) {
					logger.warning("ERROR in file " + packFilePath + ": " + e.getMessage());
					continue;
				}
				try {
					RevTree tree = commit.getTree();
					TreeWalk treeWalk = new TreeWalk(r);
					treeWalk.addTree(tree);
					treeWalk.setRecursive(true);
					if (!treeWalk.next()) {
						return;
					}
					while (treeWalk.next()) {
						ObjectId objectId = treeWalk.getObjectId(0);
						String content = getContent(r, objectId);
						String filePath = treeWalk.getPathString();
						if (!filePath.endsWith(".java")) {
							ignoredFilesCounter.increment(1);
							continue;
						}
						String fileName = filePath.lastIndexOf('/') == -1 ? filePath : filePath.substring(filePath
								.lastIndexOf('/') + 1);
						CodeFile codeFile = register(content, fileName);
						Version version = register(filePath, codeFile);
						register(projectName, version, tag);
						processedFilesCounter.increment(1);
					}
					processedTagsCounter++;
				} catch (MissingObjectException moe) {
					logger.warning("MissingObjectException in " + projectName + " : " + moe);
				}
			}
			logger.info("Finished processing: " + projectName);
		}

		String getProjName(String packFilePath) {
			Matcher m = projectNameRegexNonBare.matcher(packFilePath);
			if (m.matches()) {
				return m.group(1);
			}

			m = projectNameRegexBare.matcher(packFilePath);
			if (m.matches()) {
				return m.group(1);
			}

			logger.warning("Could not simplify project name " + packFilePath);
			// Use URI as project name.
			return packFilePath;
		}

		private String getContent(Repository repository, ObjectId objectId) throws MissingObjectException, IOException {
			ObjectLoader loader = repository.open(objectId);
			InputStream inputStream = loader.openStream();
			BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream));
			StringBuilder stringBuilder = new StringBuilder();
			String line = null;
			while ((line = bufferedReader.readLine()) != null) {
				stringBuilder.append(line + "\n");
			}
			bufferedReader.close();
			return stringBuilder.toString();
		}

		private CodeFile register(String content, String fileName) throws IOException {
			CodeFile codeFile = javaFrontend.register(content, fileName);
			return codeFile;
		}

		private Version register(String filePath, CodeFile codeFile) throws IOException {
			Version version = versionFactory.create(filePath, codeFile);
			javaFrontend.register(version);
			return version;
		}

		private void register(String projectName, Version version, String tag) throws IOException {
			Project proj = projectFactory.create(projectName, version, tag);
			javaFrontend.register(proj);
		}

		@Override
		public void cleanup(Context context) throws IOException, InterruptedException {
			super.cleanup(context);
			javaFrontend.close();
			project2version.flushCommits();
			version2file.flushCommits();
			file2function.flushCommits();
			function2snippet.flushCommits();
			strings.flushCommits();
		}
	}
}
