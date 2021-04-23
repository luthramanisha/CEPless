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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * A {@link StreamOperator} for executing {@link FilterFunction FilterFunctions}.
 */
@Internal
public class StreamKMeansOperator<IN> extends AbstractUdfStreamOperator<IN, FilterFunction<IN>> implements OneInputStreamOperator<IN, IN> {

	private static final long serialVersionUID = 1L;

	public StreamKMeansOperator() throws IOException {
		super(new FilterFunction<IN>() {
			@Override
			public boolean filter(IN value) throws Exception {
				return false;
			}
		});
		chainingStrategy = ChainingStrategy.ALWAYS;
	}

	@Override
	public void processElement(StreamRecord<IN> element) throws Exception {
		KRequest request = (KRequest)element.getValue();
		List<Mean> predicted = predict(request.k, request.inputData);
		output.collect(new StreamRecord<IN>((IN)predicted));
	}

	static private class KRequest {
		private int k;
		private float[][] inputData;
	}

	private final Random mRandomState = new Random();
	private final int mMaxIterations = 30;
	private float mSqConvergenceEpsilon = 0.005f;


	/**
	 * Runs k-means on the input data (X) trying to find k means.
	 *
	 * K-Means is known for getting stuck into local optima, so you might
	 * want to run it multiple time and argmax on
	 *
	 * @param k The number of points to return.
	 * @param inputData Input data.
	 * @return An array of k Means, each representing a centroid and data points that belong to it.
	 */
	public List<Mean> predict(final int k, final float[][] inputData) {
		int dimension = inputData[0].length;
		final ArrayList<Mean> means = new ArrayList<>();
		for (int i = 0; i < k; i++) {
			Mean m = new Mean(dimension);
			for (int j = 0; j < dimension; j++) {
				m.mCentroid[j] = mRandomState.nextFloat();
			}
			means.add(m);
		}
		// Iterate until we converge or run out of iterationsß
		boolean converged = false;
		for (int i = 0; i < mMaxIterations; i++) {
			converged = step(means, inputData);
			if (converged) {
				break;
			}
		}
		return means;
	}
	/**
	 * K-Means iteration.
	 *
	 * @param means Current means
	 * @param inputData Input data
	 * @return True if data set converged
	 */
	private boolean step(final ArrayList<Mean> means, final float[][] inputData) {
		// Clean up the previous state because we need to compute
		// which point belongs to each mean again.
		for (int i = means.size() - 1; i >= 0; i--) {
			final Mean mean = means.get(i);
			mean.mClosestItems.clear();
		}
		for (int i = inputData.length - 1; i >= 0; i--) {
			final float[] current = inputData[i];
			final Mean nearest = nearestMean(current, means);
			nearest.mClosestItems.add(current);
		}
		boolean converged = true;
		// Move each mean towards the nearest data set points
		for (int i = means.size() - 1; i >= 0; i--) {
			final Mean mean = means.get(i);
			if (mean.mClosestItems.size() == 0) {
				continue;
			}
			// Compute the new mean centroid:
			//   1. Sum all all points
			//   2. Average them
			final float[] oldCentroid = mean.mCentroid;
			mean.mCentroid = new float[oldCentroid.length];
			for (int j = 0; j < mean.mClosestItems.size(); j++) {
				// Update each centroid component
				for (int p = 0; p < mean.mCentroid.length; p++) {
					mean.mCentroid[p] += mean.mClosestItems.get(j)[p];
				}
			}
			for (int j = 0; j < mean.mCentroid.length; j++) {
				mean.mCentroid[j] /= mean.mClosestItems.size();
			}
			// We converged if the centroid didn't move for any of the means.
			if (sqDistance(oldCentroid, mean.mCentroid) > mSqConvergenceEpsilon) {
				converged = false;
			}
		}
		return converged;
	}

	private static Mean nearestMean(float[] point, List<Mean> means) {
		Mean nearest = null;
		float nearestDistance = Float.MAX_VALUE;
		final int meanCount = means.size();
		for (int i = 0; i < meanCount; i++) {
			Mean next = means.get(i);
			// We don't need the sqrt when comparing distances in euclidean space
			// because they exist on both sides of the equation and cancel each other out.
			float nextDistance = sqDistance(point, next.mCentroid);
			if (nextDistance < nearestDistance) {
				nearest = next;
				nearestDistance = nextDistance;
			}
		}
		return nearest;
	}

	private static float sqDistance(float[] a, float[] b) {
		float dist = 0;
		final int length = a.length;
		for (int i = 0; i < length; i++) {
			dist += (a[i] - b[i]) * (a[i] - b[i]);
		}
		return dist;
	}

	/**
	 * Definition of a mean, contains a centroid and points on its cluster.
	 */
	public static class Mean {
		float[] mCentroid;
		final ArrayList<float[]> mClosestItems = new ArrayList<>();
		public Mean(int dimension) {
			mCentroid = new float[dimension];
		}
		public Mean(float ...centroid) {
			mCentroid = centroid;
		}
		public float[] getCentroid() {
			return mCentroid;
		}
		public List<float[]> getItems() {
			return mClosestItems;
		}
		@Override
		public String toString() {
			return "Mean(centroid: " + Arrays.toString(mCentroid) + ", size: "
				+ mClosestItems.size() + ")";
		}
	}
}
