/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.streaming.api.operators;

import org.apache.flink.annotation.Internal;
import org.apache.flink.api.common.functions.FilterFunction;
import org.apache.flink.streaming.api.customoperators.CustomOperatorAddress;
import org.apache.flink.streaming.api.customoperators.CustomOperatorDeployed;
import org.apache.flink.streaming.api.customoperators.UserDefinedOperatorInterface;
import org.apache.flink.streaming.api.customoperators.OperatorEventReceiver;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;

import java.io.IOException;

import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

/**
 * A {@link StreamOperator} for executing CEPless operators
 */
@Internal
public class StreamCEPlessOperator<IN> extends AbstractUdfStreamOperator<IN, FilterFunction<IN>> implements OneInputStreamOperator<IN, IN>, CustomOperatorDeployed, OperatorEventReceiver {

	private static final long serialVersionUID = 1L;
	private final String operatorName;
	private final UserDefinedOperatorInterface operatorInterface;
	private transient TimestampedCollector<IN> collector;

	private CustomOperatorAddress operatorAddress;

	Logger LOG = LoggerFactory.getLogger(StreamCEPlessOperator.class);

	public StreamCEPlessOperator(String operatorName) throws IOException {
		super(new FilterFunction<IN>() {
			@Override
			public boolean filter(IN value) throws Exception {
				return true;
			}
		});
		chainingStrategy = ChainingStrategy.ALWAYS;
		this.operatorName = operatorName;
		this.operatorInterface = new UserDefinedOperatorInterface();
		LOG.debug("Finished initialization");
	}

	/**
	 * Called when operator was deployed by the Flink engine and will soon start to receive events
	 * @throws Exception
	 */
	@Override
	public void open() throws Exception {
		super.open();
		System.out.println("StreamCEPlessOperator: OPEN");
		collector = new TimestampedCollector<>(output);
		System.out.println("StreamCEPlessOperator: Requesting operator");
		// Request operator using the UDO interface
		this.operatorInterface.requestOperator(operatorName, this);
	}

	/**
	 * Called when an event was received by the Flink engine for processing
	 * @param element Event received
	 * @throws Exception
	 */
	@Override
	public void processElement(StreamRecord<IN> element) throws Exception {
		try {
			String value = "" + element.getValue();
			if (this.operatorAddress == null) {
				System.out.println("StreamCEPlessOperator: not ready to receive events. dismissing...");
				return;
			}
			operatorInterface.sendEvent(value, operatorAddress);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Override
	public void notifyOperatorDeployed(String requestIdentifier, CustomOperatorAddress address) {
		System.out.println("StreamCEPlessOperator: Custom operator is deployed. Adding listener for events...");
		operatorInterface.addListener(address, this);
		this.operatorAddress = address;
	}

	/**
	 * Called when an event that was processed by a UD operator was received
	 * @param event Event received
	 */
	@Override
	public void receivedEvent(String event) {
		if (collector != null) {
			// Push event down the operator graph
			collector.collect((IN) event);
		} else {
			System.out.println("Event was not collected!");
		}
	}
}
