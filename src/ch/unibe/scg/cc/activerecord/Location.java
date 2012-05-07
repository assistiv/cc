package ch.unibe.scg.cc.activerecord;

import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.util.Bytes;


public class Location extends Column {

	private static final int MINIMUM_CLONE_LENGTH = 5;
	private final byte[] LOCATION_NAME = Bytes.toBytes("ln");
	final int length = MINIMUM_CLONE_LENGTH;
	int firstLine;

	public void save(Put put) {
        put.add(FAMILY_NAME, LOCATION_NAME, 0l, Bytes.toBytes(getFirstLine()));
	}

	public byte[] getHash() {
		return null;
	}
	
	public int getLength() {
		return length;
	}


	public int getFirstLine() {
		return firstLine;
	}


	public void setFirstLine(int firstLine) {
		this.firstLine = firstLine;
	}
}
