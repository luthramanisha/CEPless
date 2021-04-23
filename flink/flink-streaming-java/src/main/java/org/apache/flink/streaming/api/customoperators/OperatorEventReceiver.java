package org.apache.flink.streaming.api.customoperators;

public interface OperatorEventReceiver {
	void receivedEvent(String event);
}

