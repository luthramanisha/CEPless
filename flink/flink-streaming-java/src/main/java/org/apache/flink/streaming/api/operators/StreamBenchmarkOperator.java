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
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;

import java.io.PrintWriter;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.FileNotFoundException;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.util.Arrays;
import java.util.Timer;
import java.util.TimerTask;

/**
 * A {@link StreamOperator} for executing {@link FilterFunction FilterFunctions}.
 */
@Internal
public class StreamBenchmarkOperator<IN> extends AbstractUdfStreamOperator<IN, FilterFunction<IN>> implements OneInputStreamOperator<IN, IN> {

	private static final long serialVersionUID = 1L;

	private int eventRate;
	private int k = 0;
	transient private Timer scheduler;

	public StreamBenchmarkOperator(int eventRate) throws IOException {
		super(new FilterFunction<IN>() {
			@Override
			public boolean filter(IN value) throws Exception {
				return false;
			}
		});
		k = 0;
		chainingStrategy = ChainingStrategy.ALWAYS;
		this.eventRate = eventRate;
		scheduler = new Timer();
		scheduler.scheduleAtFixedRate(new TimerTask() {
			@Override
			public void run() {
				writeThroughputToCSV(k);
				k = 0;
			}
		}, 0, 1000);
	}

	@Override
	public void processElement(StreamRecord<IN> element) throws Exception {
		k++;
		// System.out.println("Received event. Sending..." + k );
		String value = "" + element.getValue();
		String[] values = value.split(",");
		System.out.println(Arrays.toString(values));
		long timestamp = Long.parseLong(values[values.length - 1]);
		output.collect(element);
		writeToCSV(System.currentTimeMillis() - timestamp);
	}

	private void writeToCSV(Long time) {
		OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
		double load = bean.getSystemLoadAverage();
		try (PrintWriter writer = new PrintWriter(new BufferedWriter(new FileWriter("eval.csv", true)))) {
			writer.write("Flink \t" + "0\t" + "0\t" + time + "\t" + load + "\t0" + "\t0" + "\t0\t" + this.eventRate  + "\n");
		} catch (FileNotFoundException e) {
			System.out.println(e.getMessage());
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private void writeThroughputToCSV(int i) {
		try (PrintWriter writer = new PrintWriter(new BufferedWriter(new FileWriter("throughput.csv", true)))) {
			writer.write("Flink \t" + i + "\t" + eventRate + "\n");
		} catch (FileNotFoundException e) {
			System.out.println(e.getMessage());
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
}
