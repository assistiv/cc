package ch.unibe.scg.cc.activerecord;

import java.io.IOException;

import javax.inject.Inject;

import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.util.Bytes;

import ch.unibe.scg.cc.StandardHasher;

public class HashFact extends Column {

	private static final byte[] LOCATION_FIRST_LINE_NAME = Bytes.toBytes("fl");
	private static final byte[] LOCATION_LENGTH_NAME = Bytes.toBytes("ll");
	private static final byte[] SNIPPET_VALUE = Bytes.toBytes("sv");

	private byte[] hash;
	private String snippet;
	private RealProject project;
	private Function function;
	private Location location;
	private int type;
	
	StandardHasher standardHasher;

	@Inject
	public HashFact(StandardHasher standardHasher) {
		this.standardHasher = standardHasher;
	}

	public void save(Put put) throws IOException {
		assert location != null;
    	put.add(FAMILY_NAME, LOCATION_FIRST_LINE_NAME, 0l, Bytes.toBytes(location.getFirstLine()));
    	put.add(FAMILY_NAME, LOCATION_LENGTH_NAME, 0l, Bytes.toBytes(location.getLength()));
	}

	public byte[] getHash() {
		return Bytes.add(Bytes.toBytes(type), hash);
	}

	public void setHash(byte[] hash) {
		assert hash.length == 20;
		this.hash = hash;
	}

	public RealProject getProject() {
		return project;
	}

	public void setProject(RealProject project) {
		this.project = project;
	}

	public Location getLocation() {
		return location;
	}

	public void setLocation(Location location) {
		this.location = location;
	}
	
	public Function getFunction() {
		return function;
	}

	public void setFunction(Function function) {
		this.function = function;
	}

	public int getType() {
		return type;
	}

	public void setType(int type) {
		this.type = type;
	}

	public void saveSnippet(Put put) {
    	put.add(FAMILY_NAME, SNIPPET_VALUE, 0l, Bytes.toBytes(snippet));
	}
	
	public String getSnippet() {
		return this.snippet;
	}

	public void setSnippet(String snippet) {
		this.snippet = snippet;
	}


}
