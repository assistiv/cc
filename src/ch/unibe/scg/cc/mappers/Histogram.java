package ch.unibe.scg.cc.mappers;

import java.io.IOException;
import java.util.NavigableMap;

import javax.inject.Inject;

import junit.framework.Assert;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.OutputFormat;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.junit.Ignore;
import org.junit.Test;

import ch.unibe.scg.cc.util.WrappedRuntimeException;

import com.google.common.base.Optional;

public class Histogram implements Runnable {
	static final String OUT_DIR = "/tmp/histogram";
	static Logger logger = Logger.getLogger(Histogram.class);

	static {
		logger.setLevel(Level.ALL);
	}

	final MRWrapper hbaseWrapper;

	@Inject
	Histogram(MRWrapper hbaseWrapper) {
		this.hbaseWrapper = hbaseWrapper;
	}

	/**
	 * INPUT:<br>
	 * 
	 * <pre>
	 * FAC1 --> { [F1|2] , [F2|3] , [F3|8] }
	 * FAC2 --> { [F1|3] , [F3|9] }
	 * </pre>
	 * 
	 * OUTPUT:<br>
	 * 
	 * <pre>
	 * 3 --> 1
	 * 2 --> 1
	 * </pre>
	 */
	public static class HistogramMapper extends GuiceTableMapper<IntWritable, LongWritable> {
		/** receives rows from htable indexFacts2Functions */
		@SuppressWarnings("unchecked")
		@Override
		public void map(ImmutableBytesWritable uselessKey, Result value,
				@SuppressWarnings("rawtypes") org.apache.hadoop.mapreduce.Mapper.Context context) throws IOException,
				InterruptedException {
			NavigableMap<byte[], byte[]> familyMap = value.getFamilyMap(GuiceResource.FAMILY);
			context.write(new IntWritable(familyMap.size()), new LongWritable(1L));
		}
	}

	public static class HistogramReducer extends GuiceReducer<IntWritable, LongWritable, IntWritable, LongWritable> {
		@Override
		public void reduce(IntWritable columnCount, Iterable<LongWritable> values, Context context) throws IOException,
				InterruptedException {
			long sum = 0;
			for (LongWritable val : values) {
				sum += val.get();
			}
			context.write(columnCount, new LongWritable(sum));
		}
	}

	@Override
	public void run() {
		try {

			FileSystem.get(new Configuration()).delete(new Path(OUT_DIR), true);

			Scan scan = new Scan();
			scan.setCaching(100); // TODO play with this. (100 is default value)
			scan.addFamily(GuiceResource.FAMILY); // Gets all columns from the
													// specified family.

			Configuration config = new Configuration();
			config.set("mapreduce.job.reduces", "1");
			// TODO test that
			config.set("mapreduce.reduce.merge.inmem.threshold", "0");
			config.set("mapreduce.reduce.merge.memtomem.enabled", "true");
			// 256 works fine for IndexFacts2Functions
			config.set("mapreduce.task.io.sort.mb", "512");
			config.set("mapreduce.task.io.sort.factor", "100");
			config.set("mapreduce.job.ubertask.enable", "true");
			// set to 1 if unsure
			config.set("mapreduce.job.jvm.numtasks", "-1");
			config.set("mapreduce.task.timeout", "86400000");
			config.set("mapreduce.map.memory.mb", "1536");
			config.set("mapreduce.map.java.opts", "-Xmx1024M");
			config.set("mapreduce.reduce.memory.mb", "3072");
			config.set("mapreduce.reduce.java.opts", "-Xmx2560M");
			config.set(FileOutputFormat.OUTDIR, OUT_DIR);
			config.setClass(Job.OUTPUT_FORMAT_CLASS_ATTR, TextOutputFormat.class, OutputFormat.class);
			config.setClass(Job.COMBINE_CLASS_ATTR, HistogramReducer.class, Reducer.class);

			hbaseWrapper.launchMapReduceJob(Histogram.class.getName() + " Job", config,
					Optional.of("indexFacts2Functions"), Optional.<String> absent(), scan,
					HistogramMapper.class.getName(), Optional.of(HistogramReducer.class.getName()), IntWritable.class,
					LongWritable.class);
		} catch (IOException e) {
			throw new WrappedRuntimeException(e);
		} catch (ClassNotFoundException e) {
			throw new WrappedRuntimeException(e.getMessage(), e);
		} catch (InterruptedException e) {
			// exit thread
			return;
		}
	}

	public static class IndexFacts2FunctionsStep2Test {
		@Test
		@Ignore
		public void testIndex() {
			// TODO
			Assert.assertTrue(false);
		}
	}

}
