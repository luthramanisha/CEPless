package org.apache.flink.streaming.api.customoperators;

/**
 * Test.
 */
public interface CustomOperatorDeployed {
	/**
	 * Callback.
	 * @param requestIdentifier
	 * @param address
	 */
	void notifyOperatorDeployed(String requestIdentifier, CustomOperatorAddress address);
}
