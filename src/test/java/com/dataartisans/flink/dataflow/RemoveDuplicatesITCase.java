/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dataartisans.flink.dataflow;

import com.google.cloud.dataflow.sdk.Pipeline;
import com.google.cloud.dataflow.sdk.coders.StringUtf8Coder;
import com.google.cloud.dataflow.sdk.io.TextIO;
import com.google.cloud.dataflow.sdk.transforms.Create;
import com.google.cloud.dataflow.sdk.transforms.RemoveDuplicates;
import com.google.cloud.dataflow.sdk.values.PCollection;
import com.google.common.base.Joiner;
import org.apache.flink.test.util.JavaProgramTestBase;

import java.util.Arrays;
import java.util.List;


public class RemoveDuplicatesITCase extends JavaProgramTestBase {

	protected String resultPath;

	public RemoveDuplicatesITCase(){
	}

	static final String[] EXPECTED_RESULT = new String[] {
			"k1", "k5", "k2", "k3"};

	@Override
	protected void preSubmit() throws Exception {
		resultPath = getTempDirPath("result");
	}

	@Override
	protected void postSubmit() throws Exception {
		compareResultsByLinesInMemory(Joiner.on('\n').join(EXPECTED_RESULT), resultPath);
	}

	@Override
	protected void testProgram() throws Exception {

		List<String> strings = Arrays.asList("k1", "k5", "k5", "k2", "k1", "k2", "k3");

		Pipeline p = FlinkTestPipeline.create();

		PCollection<String> input =
				p.apply(Create.of(strings))
						.setCoder(StringUtf8Coder.of());

		PCollection<String> output =
				input.apply(RemoveDuplicates.<String>create());

		output.apply(TextIO.Write.to(resultPath));
		p.run();
	}
}

