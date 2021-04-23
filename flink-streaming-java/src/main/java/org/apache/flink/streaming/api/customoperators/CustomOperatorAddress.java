package org.apache.flink.streaming.api.customoperators;

import java.io.Serializable;

/**
 * Test.
 */
public class CustomOperatorAddress implements Serializable {
	String addrIn;
	String addrOut;

	/**
	 * Test.
	 * @param addrIn
	 * @param addrOut
	 */
	CustomOperatorAddress(String addrIn, String addrOut) {
		this.addrIn = addrIn;
		this.addrOut = addrOut;
	}
}
