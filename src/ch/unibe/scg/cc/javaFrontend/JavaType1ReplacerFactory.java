package ch.unibe.scg.cc.javaFrontend;

import java.util.ArrayList;

import javax.inject.Singleton;

import jregex.Pattern;
import ch.unibe.scg.cc.ReplacerProvider;
import ch.unibe.scg.cc.regex.Replace;

import com.google.inject.Provider;

@Singleton
public class JavaType1ReplacerFactory extends ReplacerProvider implements Provider<Replace[]> {

	/**
	 * Hides deep function definitions
	 */
	// Replace makeHideDeepDefinitions() {
	// Pattern ifPattern = new
	// Pattern("(\\t{2,}|\\ {8,})([a-zA-Z \\t<>,]*\\([a-zA-Z \\t<>,]*\\)[a-zA-Z \\t<>,]*\\{|(\\n|[^\n]*[^.])class)");
	// return new Replace(ifPattern, "; $1");
	// }

	/**
	 * 0
	 */
	public Replace makeWhitespaceA() {
		Pattern whiteSpace = new Pattern("\\s*\\n\\s*");
		return new Replace(whiteSpace, "\n");
	}

	/**
	 * 1
	 */
	public Replace makeWhitespaceB() {
		Pattern whiteSpace = new Pattern("[ \f\r\t]+");
		return new Replace(whiteSpace, " ");
	}

	/**
	 * 2
	 */
	public Replace makeRj1() {
		Pattern packagesGo = new Pattern("(?:\\p{Ll}\\s*\\.)?(\\p{Lu}+)");
		String replaceWith = "$1";
		return new Replace(packagesGo, replaceWith);

	}

	/**
	 * 3
	 */
	public Replace makeRj3a() {
		Pattern initializationList = new Pattern("=\\s?\\{.*?\\}");
		return new Replace(initializationList, "= { }");
	}

	/**
	 * 4
	 */
	public Replace makeRj3b() {
		Pattern initializationList = new Pattern("\\]\\s?\\{.*?\\}");
		return new Replace(initializationList, "] { }");
	}

	/**
	 * 5
	 */
	public Replace makeRj5() {
		Pattern visibility = new Pattern("(\\s)(?:private\\s|public\\s|protected\\s)");
		return new Replace(visibility, "$1");
	}

	/**
	 * 6
	 */
	public Replace makeRj6() {
		Pattern ifPattern = new Pattern("\\sif\\s*\\((?:.*)\\)\\s*(\\n[^\\n\\{\\}]*)$");
		return new Replace(ifPattern, " {\\n$1\\n}\\n");
	}

	/**
	 * 7. This is necessary for tokenization. while statements look a lot like
	 * function definitions.
	 * 
	 * @return
	 */
	public Replace makeRenameWhile() {
		Pattern ifPattern = new Pattern("while");
		return new Replace(ifPattern, ";while");
	}
}
