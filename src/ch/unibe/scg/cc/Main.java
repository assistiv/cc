package ch.unibe.scg.cc;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;

import org.apache.commons.lang3.time.StopWatch;
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.VFS;

import ch.unibe.scg.cc.modules.CCModule;
import ch.unibe.scg.cc.modules.JavaModule;

import com.google.inject.Guice;
import com.google.inject.Injector;

public class Main {
	public static final int TYPE_1_CLONE = 1;
	public static final int TYPE_2_CLONE = 2;
	public static final int TYPE_3_CLONE = 3;


	public static void main(String[] args) throws  IOException {
		Injector injector = Guice.createInjector(
				new JavaModule(), 
				new CCModule()
		);
		ProjectWalker walker = injector.getInstance(ProjectWalker.class);
		stoppedWalk(walker);
	}

	static void stoppedWalk(ProjectWalker walker) throws  IOException {
		StopWatch stopWatch = new StopWatch();
		stopWatch.start();
		walk(walker);
		stopWatch.stop();
		System.out.format("Time needed: %,8d msec%n", stopWatch.getTime());
	}

	public static void walk(ProjectWalker walker) throws  IOException {
		crawl(walker, "eclipse-ant", "projects/eclipse-ant.zip");
//		crawl(walker, "eclipse-jdtcore", "projects/eclipse-jdtcore.zip");
//		crawl(walker, "j2sdk1.4.0-javax-swing", "projects/j2sdk1.4.0-javax-swing.zip");
//		crawl(walker, "netbeans-javadoc", "projects/netbeans-javadoc.zip");
	}
	 
	private static void crawl(ProjectWalker walker, String projectName, String filePath) throws IOException {
		FileObject file = VFS.getManager().resolveFile(new File("."), filePath);
		file = VFS.getManager().resolveFile("zip://" + file.getURL().getPath());
		// FileObject file = VFS.getManager().resolveFile(new File("."),
		// "projects/onefile/");
		walker.crawl(file, projectName);
	} 

}
