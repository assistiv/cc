package ch.unibe.scg.cc.javaFrontend;

import static org.junit.Assert.*;
import static org.hamcrest.Matchers.*;
import ch.unibe.jexample.*;
import org.junit.Test;
import org.junit.runner.RunWith;

import ch.unibe.scg.cc.Normalizer;
import ch.unibe.scg.cc.regex.Replace;

@RunWith(JExample.class)
public class JavaType1ReplacerFactoryTest {

	@Test
	public Replace[] checkWhiteSpaces() {
		JavaType1ReplacerFactory factory = new JavaType1ReplacerFactory();
		Replace[] replacers = factory.get();
		assertThat(replacers.length, greaterThan(2));
		Replace whiteSpaceA = replacers[0];
		Replace whiteSpaceB = replacers[1];
		assertThat(
				whiteSpaceB.allReplaced(whiteSpaceA.allReplaced(sampleClass())),
				startsWith("package fish.stink;\nimport java.util.*;\nimport static System.out;\npublic class Rod {\n"));
		return replacers;
	}

	@Given("checkWhiteSpaces")
	public Replace[] checkVisibility(Replace[] replaces) {
		StringBuilder sb = new StringBuilder(
				"\nprotected static void doIt() {\n");
		replaces[5].replaceAll(sb);
		assertThat(sb.toString(), is("\nstatic void doIt() {\n"));
		return replaces;
	}

	@Given("checkWhiteSpaces")
	public Normalizer checkAll(Replace[] replaces) {
		StringBuilder sb = new StringBuilder(sampleClass());
		assertThat(replaces, is(arrayWithSize(8)));
		Normalizer n = new Normalizer(replaces);
		n.normalize(sb);
		assertThat(
				sb.toString(),
				is("package fish.stink;\nimport java.util.*;\nimport static System.out;\nclass Rod {\nstatic void main(String[] args) {\nout.println(\"Hiya, wassup?\");\nfish.stinRod.doIt(new int[] { });\n}\nstatic void doIt() {\nif(System.timeInMillis() > 10000)\nout.println(1337);\nmain(null);\n}\n}\n"));

		return n;
	}

	String sampleClass() {
		return "package        fish.stink;\n" + "import java.util.*;\n"
				+ "import static System.out;\n\n" + "public class Rod {\n"
				+ "\tpublic static void main(String[] args) {\n"
				+ "\t\tout.println(\"Hiya, wassup?\");\n"
				+ "\t\tfish.stink.Rod.doIt(new int[] { 1, 2 ,3 });\n" + "	}\n"
				+ "\t\t\tprotected static void doIt() {\n"
				+ "\t\tif(System.timeInMillis() > 10000)\n"
				+ "\t\t\tout.println(1337);\n" + "\t\tmain(null);\n" + "\t}\n"
				+ "}\n";
	}
}
