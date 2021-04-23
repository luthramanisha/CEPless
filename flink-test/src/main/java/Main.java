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


import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.streaming.api.TimeCharacteristic;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaConsumer;;
import org.apache.flink.util.Collector;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.PrintWriter;
import java.io.IOException;
import java.io.FileNotFoundException;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.util.*;

/**
 * Flink example query using CEPless
 */
public class Main {

	// *************************************************************************
	// PROGRAM
	// *************************************************************************
	public static void main(String[] args) throws Exception {

		// Checking input parameters
		final ParameterTool params = ParameterTool.fromArgs(args);

		// set up the execution environment
		final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

		env.setStreamTimeCharacteristic(TimeCharacteristic.ProcessingTime);
		// make parameters available in the web interface
		env.getConfig().setGlobalJobParameters(params);

		// Load properties from CLI params
		System.out.println("Using input " + params.get("input"));
		boolean serverless = params.getBoolean("serverless");
		Properties props = new Properties();
		// props.setProperty("bootstrap.servers", "host.docker.internal:9092");
		props.setProperty("bootstrap.servers", params.get("kafka.server"));

		/**
		 * Topic op-test is used for fetching events from Kafka
		 */
		// CHANGE BY MATHEUS: SWITCH TOPIC FROM "op-test" TO THE PARAMETER "customOperator"
		DataStream<String> source = env.addSource(new FlinkKafkaConsumer<String>(params.get("customOperator"), new SimpleStringSchema(), props));

		/**
		 * Append timestamp (nanoseconds) to event when received from kafka for processing time measurement
		 */
		DataStream<String> query = source.map(new MapFunction<String, String>() {
			transient int i = 0;
			@Override
			public String map(String s) throws Exception {
				s = s.replaceAll("\\s+","");
				String c = new StringBuilder(s).toString();
				String d = "," + String.valueOf(System.nanoTime());
				String a = new StringBuilder(c).append(d).toString();
				return a;
			}
		});

		DataStream<String> result;
		if (serverless) {
			MapFunction <String, String> benchmarkBefore = benchmarkMap(params.getInt("sim.recordDelay"), "logs/benchmark-prev.csv");
			MapFunction <String, String> benchmark = benchmarkMap(params.getInt("sim.recordDelay"), "logs/benchmark.csv");
			// CHANGE BY MATHEUS: SWITCH OPERATOR FROM "op-test" TO THE PARAMETER "customOperator"
			result = query.map(benchmarkBefore).serverless(params.get("customOperator")).map(benchmark);
		} else {
			MapFunction <String, String> benchmark = benchmarkMap(params.getInt("sim.recordDelay"), "logs/benchmark.csv");
			result = query.map(benchmark);
		}

		// emit result
		if (params.has("output")) {
			// result.writeAsText(params.get("output"));
		} else {
			//System.out.println("Printing result to stdout. Use --output to specify output path.");
			//result.print();
		}

		// execute program
		env.execute("Streaming CEPLess query");
	}

	/**
	 * Creates a mapping function for recording benchmarks
	 * @param recordDelay A delay in milliseconds when collection of evaluations should start
	 * @param filename Name of the resulting benchmark file
	 * @return mapping function
	 */
	private static MapFunction<String, String> benchmarkMap(int recordDelay, String filename) {
		return new MapFunction<String, String>() {
			transient int time = 0;
			transient int k = -1;
			transient Timer scheduler;
			transient String lastMeasurement = null;
			transient long lastTimestamp = System.nanoTime();
			transient boolean firstWrite = true;

			@Override
			public String map(String value) throws Exception {
				if (k < 0) {
					k = 1;
				} else {
					k += 1;
				}
				if (scheduler == null) {
					scheduler = new Timer();
					scheduler.scheduleAtFixedRate(new TimerTask() {
						@Override
						public void run() {
							try {
								String[] values = lastMeasurement.split(",");
								long endToEndLatency = lastTimestamp - Long.parseLong(values[values.length - 2]);
								long processingLatency = lastTimestamp - Long.parseLong(values[values.length - 1]);
								writeThroughputToCSV(k, endToEndLatency, processingLatency);
								k = -1;
								time++;
							} catch (Exception e) {
								System.out.println("EXCEPTION!");
								e.printStackTrace();
							}

						}
					}, recordDelay, 1000);
				}
				if (value == null) {
					System.out.println("no value");
					return "";
				}
				lastMeasurement = value;
				lastTimestamp = System.nanoTime();
				return value;
			}

			private void writeThroughputToCSV(int i, long endToEndLatency, long processingLatency) {
				try (PrintWriter writer = new PrintWriter(new BufferedWriter(new FileWriter(filename, true)))) {
					if (firstWrite) {
						System.out.println("Clearing benchmark data...");
						firstWrite = false;
						PrintWriter pw = new PrintWriter(filename);
						pw.close();
					}
					writer.write("Flink \t" + time + "\t" + i + "\t" + endToEndLatency + "\t" + processingLatency + "\n");
				} catch (FileNotFoundException e) {
					System.out.println(e.getMessage());
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		};
	}

	// *************************************************************************
	// USER FUNCTIONS
	// *************************************************************************

	/**
	 * Implements the string tokenizer that splits sentences into words as a
	 * user-defined FlatMapFunction. The function takes a line (String) and
	 * splits it into multiple pairs in the form of "(word,1)" ({@code Tuple2<String,
	 * Integer>}).
	 */
	public static final class Tokenizer implements FlatMapFunction<String, Tuple2<String, Integer>> {

		@Override
		public void flatMap(String value, Collector<Tuple2<String, Integer>> out) {
			// normalize and split the line
			String[] tokens = value.toLowerCase().split("\\W+");

			// emit the pairs
			for (String token : tokens) {
				if (token.length() > 0) {
					out.collect(new Tuple2<>(token, 1));
				}
			}
		}
	}
}
