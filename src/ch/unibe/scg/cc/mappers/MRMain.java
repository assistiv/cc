package ch.unibe.scg.cc.mappers;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.logging.Logger;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.mapreduce.TableMapper;
import org.apache.hadoop.hbase.mapreduce.TableReducer;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;

import ch.unibe.scg.cc.CCModule;
import ch.unibe.scg.cc.javaFrontend.JavaModule;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.google.inject.util.Modules;

/**
 * Executes MR-jobs. All Mappers/Reducers use this class as the Main-Class in
 * the manifest.
 * <p>
 * {@code
 * <antcall target="jar">
 * 	<param name="mainClass" value="ch.unibe.scg.cc.mappers.MRMain" />
 * 	</antcall>
 * }
 * <p>
 * Finally, the command which gets called on the server looks somewhat like
 * this:
 * <p>
 * {@code
 * hadoop jar /tmp/cc.jar ch.unibe.scg.cc.mappers.MakeSnippet2Function
 * }
 * <p>
 * Provides generic Mappers and Reducers that will create the real
 * Mapper/Reducer by using dependency injection. Which Mapper/Reducer gets
 * injected is defined in the configuration with the attribute
 * "GuiceMapperAnnotation"/"GuiceReducerAnnotation" respectively.
 */
public class MRMain extends Configured implements Tool {
	static Logger logger = Logger.getLogger(MRMain.class.getName());

	// TODO: Doesn't seem to be used from within build.xml -- is this dead?
	public static void main(String[] args) throws Throwable {
		logger.finer(Arrays.toString(args));
		ToolRunner.run(new MRMain(), args);
	}

	@Override
	public int run(String[] args) {
		if (args.length == 0) {
			throw new IllegalArgumentException("You must specify the job as an argument to MRMain.");
		}
		logger.finer(Arrays.toString(args));
		assert args.length == 1;
		Class<?> c = classForNameOrPanic(args[0]);
		Injector injector = Guice.createInjector(new CCModule(), new JavaModule(), new HBaseModule());
		Object instance = injector.getInstance(c);
		((Runnable) instance).run();
		return 0;
	}

	/**
	 * We can only pass the Mapper as a class, but we want to configure our
	 * mapper using Guice. We'd prefer to set the mapper as an object (already
	 * Guice configured), but Hadoop won't let us. So, this class bridges
	 * between Guice and Hadoop. In setup, we Guice configure the real reducer,
	 * and this class acts as a proxy to the guice-configured reducer.
	 *
	 * <p>
	 * All methods except
	 * {@link #run(org.apache.hadoop.mapreduce.Mapper.Context)} are called on
	 * the guice-configured reducer.
	 */
	public static class MRMainMapper<KEYIN, VALUEIN, KEYOUT, VALUEOUT> extends Mapper<KEYIN, VALUEIN, KEYOUT, VALUEOUT> {
		GuiceMapper<KEYIN, VALUEIN, KEYOUT, VALUEOUT> guiceMapper;

		// Unavoidable as we're getting the object via reflection.
		@SuppressWarnings("unchecked")
		@Override
		protected void setup(final Context context) throws IOException, InterruptedException {
			guiceMapper = (GuiceMapper<KEYIN, VALUEIN, KEYOUT, VALUEOUT>) mapperOrReducer(context,
					Constants.GUICE_MAPPER_ANNOTATION_STRING);
			guiceMapper.setup(context);
		}

		@Override
		protected void map(KEYIN key, VALUEIN value, Context context) throws IOException, InterruptedException {
			guiceMapper.map(key, value, context);
		}

		@Override
		protected void cleanup(Context context) throws IOException, InterruptedException {
			guiceMapper.cleanup(context);
		}

		/**
		 * We need to call {@link #run(Mapper.Context)} on the super class here
		 * in order to keep control over the {@link #setup(Mapper.Context)},
		 * {@link #map(Object, Object, Mapper.Context)} and
		 * {@link #cleanup(Mapper.Context)} methods.
		 */
		@Override
		public void run(Context context) throws IOException, InterruptedException {
			super.run(context);
		}
	}

	/** see {@link MRMainMapper} */
	public static class MRMainTableMapper<KEYOUT, VALUEOUT> extends TableMapper<KEYOUT, VALUEOUT> {
		GuiceTableMapper<KEYOUT, VALUEOUT> guiceMapper;

		// Unavoidable as we're getting the object via reflection.
		@SuppressWarnings("unchecked")
		@Override
		protected void setup(final Context context) throws IOException, InterruptedException {
			guiceMapper = (GuiceTableMapper<KEYOUT, VALUEOUT>) mapperOrReducer(context,
					Constants.GUICE_MAPPER_ANNOTATION_STRING);
			guiceMapper.setup(context);
		}

		@Override
		protected void map(ImmutableBytesWritable key, Result value, Context context) throws IOException,
				InterruptedException {
			guiceMapper.map(key, value, context);
		}

		@Override
		protected void cleanup(Context context) throws IOException, InterruptedException {
			guiceMapper.cleanup(context);
		}

		@Override
		public void run(Context context) throws IOException, InterruptedException {
			super.run(context);
		}
	}

	/** @see MRMainMapper */
	public static class MRMainReducer<KEYIN, VALUEIN, KEYOUT, VALUEOUT> extends
			Reducer<KEYIN, VALUEIN, KEYOUT, VALUEOUT> {
		GuiceReducer<KEYIN, VALUEIN, KEYOUT, VALUEOUT> reducer;

		// Unavoidable as we're getting the object via reflection.
		@SuppressWarnings("unchecked")
		@Override
		protected void setup(Context context) throws IOException, InterruptedException {
			reducer = (GuiceReducer<KEYIN, VALUEIN, KEYOUT, VALUEOUT>) mapperOrReducer(context,
					Constants.GUICE_REDUCER_ANNOTATION_STRING);
			reducer.setup(context);
		}

		@Override
		protected void cleanup(Context context) throws IOException, InterruptedException {
			reducer.cleanup(context);
		}

		@Override
		public void run(Context context) throws IOException, InterruptedException {
			super.run(context);
		}

		@Override
		protected void reduce(KEYIN key, Iterable<VALUEIN> values, Context context) throws IOException,
				InterruptedException {
			reducer.reduce(key, values, context);
		}
	}

	/** @see MRMainMapper */
	public static class MRMainTableReducer extends
			TableReducer<ImmutableBytesWritable, ImmutableBytesWritable, ImmutableBytesWritable> {
		GuiceTableReducer<ImmutableBytesWritable, ImmutableBytesWritable, ImmutableBytesWritable> reducer;

		// Unavoidable as we're getting the object via reflection.
		@SuppressWarnings("unchecked")
		@Override
		protected void setup(Context context) throws IOException, InterruptedException {
			reducer = (GuiceTableReducer<ImmutableBytesWritable, ImmutableBytesWritable, ImmutableBytesWritable>) mapperOrReducer(
					context, Constants.GUICE_REDUCER_ANNOTATION_STRING);
			reducer.setup(context);
		}

		@Override
		protected void cleanup(Context context) throws IOException, InterruptedException {
			reducer.cleanup(context);
		}

		@Override
		public void run(Context context) throws IOException, InterruptedException {
			super.run(context);
		}

		@Override
		protected void reduce(ImmutableBytesWritable key, Iterable<ImmutableBytesWritable> values, Context context)
				throws IOException, InterruptedException {
			reducer.reduce(key, values, context);
		}
	}

	/**
	 * Throws a RuntimeException if the class is not found.
	 *
	 * @param qualifiedClassName
	 *            the fully qualified class name
	 * @return returns the class Object
	 */
	public static Class<?> classForNameOrPanic(String qualifiedClassName) {
		try {
			return Class.forName(qualifiedClassName);
		} catch (ClassNotFoundException e) {
			throw new WrappedRuntimeException("Class " + qualifiedClassName + " not found!", e);
		}
	}

	static Object mapperOrReducer(TaskAttemptContext context, String annotationString) {
		Object ret = Guice.createInjector(
				Modules.override(new CCModule(), new JavaModule(), new MapperModule(), new CounterModule(context))
						.with(configurableModules(context.getConfiguration())))
						.getInstance(classForNameOrPanic(context.getConfiguration().get(annotationString)));
		if (!(ret instanceof Mapper || ret instanceof Reducer)) {
			throw new IllegalStateException("I tried to get a mapper/reducer from the context, but instead found a "
					+ ret.getClass());
		}
		return ret;
	}

	static Collection<Module> configurableModules(Configuration configuration) {
		Collection<Module> configurableModules = new ArrayList<>();
		String customModuleClassNames = configuration.get(Constants.GUICE_CUSTOM_MODULES_ANNOTATION_STRING);
		if (customModuleClassNames != null) {
			try {
				for (String customModuleClassName : customModuleClassNames.
						split(String.valueOf(Constants.GUICE_CUSTOM_MODULES_ANNOTATION_STRING_SPLITTER))) {
					configurableModules.add((Module) classForNameOrPanic(customModuleClassName).newInstance());
				}
			} catch (InstantiationException | IllegalAccessException | IllegalArgumentException | SecurityException e) {
				throw new WrappedRuntimeException(e);
			}
		}
		return configurableModules;
	}
}
