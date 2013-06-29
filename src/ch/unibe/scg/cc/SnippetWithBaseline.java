package ch.unibe.scg.cc;

import com.google.common.collect.ComparisonChain;

public class SnippetWithBaseline implements Comparable<SnippetWithBaseline> {
	final int baseLine;
	final CharSequence snippet;

	public SnippetWithBaseline(int baseLine, CharSequence snippet) {
		this.baseLine = baseLine;
		this.snippet = snippet;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + baseLine;
		if (snippet != null) {
			result = prime * result +  snippet.hashCode();
		}
		return result;
	}

	@Override
	public boolean equals(Object that) {
		if (!(that instanceof SnippetWithBaseline)) {
			return false;
		}
		return compareTo((SnippetWithBaseline) that) == 0;
	}

	public int getBaseLine() {
		return baseLine;
	}

	public CharSequence getSnippet() {
		return snippet;
	}

	@Override
	public int compareTo(SnippetWithBaseline o) {
		return ComparisonChain.start()
				.compare(baseLine, o.baseLine)
				.compare(snippet.toString(), o.snippet.toString())
				.result();
	}
}