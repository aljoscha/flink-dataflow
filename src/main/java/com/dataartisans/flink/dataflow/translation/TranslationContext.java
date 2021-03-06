package com.dataartisans.flink.dataflow.translation;

import com.dataartisans.flink.dataflow.translation.types.CoderTypeInformation;
import com.google.cloud.dataflow.sdk.coders.Coder;
import com.google.cloud.dataflow.sdk.options.PipelineOptions;
import com.google.cloud.dataflow.sdk.values.PCollectionView;
import com.google.cloud.dataflow.sdk.values.PValue;
import com.google.cloud.dataflow.sdk.values.TypedPValue;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.DataSet;
import org.apache.flink.api.java.ExecutionEnvironment;
import org.apache.flink.api.java.typeutils.GenericTypeInfo;

import java.util.HashMap;
import java.util.Map;

public class TranslationContext {
	
	private final Map<PValue, DataSet<?>> dataSets;
	private final Map<PCollectionView<?, ?>, DataSet<?>> broadcastDataSets;
	
	private final ExecutionEnvironment env;
	private final PipelineOptions options;
	
	// ------------------------------------------------------------------------
	
	public TranslationContext(ExecutionEnvironment env, PipelineOptions options) {
		this.env = env;
		this.options = options;
		this.dataSets = new HashMap<>();
		this.broadcastDataSets = new HashMap<>();
	}
	
	// ------------------------------------------------------------------------
	
	public ExecutionEnvironment getExecutionEnvironment() {
		return env;
	}

	public PipelineOptions getPipelineOptions() {
		return options;
	}
	
	@SuppressWarnings("unchecked")
	public <T> DataSet<T> getInputDataSet(PValue value) {
		return (DataSet<T>) dataSets.get(value);
	}
	
	public void setOutputDataSet(PValue value, DataSet<?> set) {
		if (!dataSets.containsKey(value)) {
			dataSets.put(value, set);
		}
	}

	@SuppressWarnings("unchecked")
	public <T> DataSet<T> getSideInputDataSet(PCollectionView<?,?> value) {
		return (DataSet<T>) broadcastDataSets.get(value);
	}

	public void setSideInputDataSet(PCollectionView<?,?> value, DataSet<?> set) {
		if (!broadcastDataSets.containsKey(value)) {
			broadcastDataSets.put(value, set);
		}
	}
	
	@SuppressWarnings("unchecked")
	public <T> TypeInformation<T> getTypeInfo(PValue output) {
		if (output instanceof TypedPValue) {
			Coder<?> outputCoder = ((TypedPValue) output).getCoder();
			return new CoderTypeInformation(outputCoder);
		}
		return new GenericTypeInfo<T>((Class<T>)Object.class);
	}
}
