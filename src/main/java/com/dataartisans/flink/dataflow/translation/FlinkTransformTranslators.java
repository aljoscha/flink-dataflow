package com.dataartisans.flink.dataflow.translation;

import com.dataartisans.flink.dataflow.io.ConsoleIO;
import com.dataartisans.flink.dataflow.translation.functions.FlinkCombineFunction;
import com.dataartisans.flink.dataflow.translation.functions.FlinkCreateFunction;
import com.dataartisans.flink.dataflow.translation.functions.FlinkDoFnFunction;
import com.dataartisans.flink.dataflow.translation.functions.FlinkKeyedListAggregationFunction;
import com.dataartisans.flink.dataflow.translation.functions.FlinkMultiOutputDoFnFunction;
import com.dataartisans.flink.dataflow.translation.functions.FlinkMultiOutputPruningFunction;
import com.dataartisans.flink.dataflow.translation.functions.RawUnionValue;
import com.dataartisans.flink.dataflow.translation.functions.UnionCoder;
import com.dataartisans.flink.dataflow.translation.types.CoderTypeInformation;
import com.dataartisans.flink.dataflow.translation.wrappers.SourceInputFormat;
import com.google.api.client.util.Maps;
import com.google.cloud.dataflow.sdk.coders.Coder;
import com.google.cloud.dataflow.sdk.io.AvroIO;
import com.google.cloud.dataflow.sdk.io.ReadSource;
import com.google.cloud.dataflow.sdk.io.Source;
import com.google.cloud.dataflow.sdk.io.TextIO;
import com.google.cloud.dataflow.sdk.transforms.Combine;
import com.google.cloud.dataflow.sdk.transforms.Create;
import com.google.cloud.dataflow.sdk.transforms.DoFn;
import com.google.cloud.dataflow.sdk.transforms.Flatten;
import com.google.cloud.dataflow.sdk.transforms.GroupByKey;
import com.google.cloud.dataflow.sdk.transforms.PTransform;
import com.google.cloud.dataflow.sdk.transforms.ParDo;
import com.google.cloud.dataflow.sdk.transforms.View;
import com.google.cloud.dataflow.sdk.values.KV;
import com.google.cloud.dataflow.sdk.values.PCollection;
import com.google.cloud.dataflow.sdk.values.PCollectionView;
import com.google.cloud.dataflow.sdk.values.TupleTag;
import com.google.common.collect.Lists;
import org.apache.avro.Schema;
import org.apache.flink.api.common.functions.GroupReduceFunction;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.DataSet;
import org.apache.flink.api.java.io.AvroInputFormat;
import org.apache.flink.api.java.io.AvroOutputFormat;
import org.apache.flink.api.java.io.TextInputFormat;
import org.apache.flink.api.java.operators.*;
import org.apache.flink.core.fs.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Translators for transforming
 * Dataflow {@link com.google.cloud.dataflow.sdk.transforms.PTransform}s to
 * Flink {@link org.apache.flink.api.java.DataSet}s
 */
public class FlinkTransformTranslators {

	// --------------------------------------------------------------------------------------------
	//  Transform Translator Registry
	// --------------------------------------------------------------------------------------------
	
	@SuppressWarnings("rawtypes")
	private static final Map<Class<? extends PTransform>, FlinkPipelineTranslator.TransformTranslator> TRANSLATORS = new HashMap<>();

	// register the known translators
	static {
		TRANSLATORS.put(View.CreatePCollectionView.class, new CreatePCollectionViewTranslator());
		TRANSLATORS.put(Combine.PerKey.class, new CombinePerKeyTranslator());
		// we don't need this because we translate the Combine.PerKey directly
		// TRANSLATORS.put(Combine.GroupedValues.class, new CombineGroupedValuesTranslator());

		TRANSLATORS.put(Create.class, new CreateTranslator());
		TRANSLATORS.put(Flatten.FlattenPCollectionList.class, new FlattenPCollectionTranslator());
		TRANSLATORS.put(GroupByKey.GroupByKeyOnly.class, new GroupByKeyOnlyTranslator());
		TRANSLATORS.put(ParDo.BoundMulti.class, new ParDoBoundMultiTranslator());
		TRANSLATORS.put(ParDo.Bound.class, new ParDoBoundTranslator());

		TRANSLATORS.put(AvroIO.Read.Bound.class, new AvroIOReadTranslator());
		TRANSLATORS.put(AvroIO.Write.Bound.class, new AvroIOWriteTranslator());

		//TRANSLATORS.put(BigQueryIO.Read.Bound.class, null);
		//TRANSLATORS.put(BigQueryIO.Write.Bound.class, null);

		//TRANSLATORS.put(DatastoreIO.Sink.class, null);

		//TRANSLATORS.put(PubsubIO.Read.Bound.class, null);
		//TRANSLATORS.put(PubsubIO.Write.Bound.class, null);

		TRANSLATORS.put(ReadSource.Bound.class, new ReadSourceTranslator());

		TRANSLATORS.put(TextIO.Read.Bound.class, new TextIOReadTranslator());
		TRANSLATORS.put(TextIO.Write.Bound.class, new TextIOWriteTranslator());
		// Flink-specific
		TRANSLATORS.put(ConsoleIO.Write.Bound.class, new ConsoleIOWriteTranslator());
		
		// Disabled because it depends on a pending pull request to the DataFlowSDK
		//TRANSLATORS.put(CoGroupByKey.class, new CoGroupByKeyTranslator());
	}


	public static FlinkPipelineTranslator.TransformTranslator<?> getTranslator(PTransform<?, ?> transform) {
		return TRANSLATORS.get(transform.getClass());
	}

	private static class ReadSourceTranslator<T> implements FlinkPipelineTranslator.TransformTranslator<ReadSource.Bound<T>> {

		@Override
		public void translateNode(ReadSource.Bound<T> transform, TranslationContext context) {
			String name = transform.getName();
			Source<T> source = transform.getSource();
			Coder<T> coder = transform.getOutput().getCoder();

			TypeInformation<T> typeInformation = context.getTypeInfo(transform.getOutput());

			DataSource<T> dataSource = new DataSource<>(context.getExecutionEnvironment(), new SourceInputFormat<>(source, context.getPipelineOptions(), coder), typeInformation, name);

			context.setOutputDataSet(transform.getOutput(), dataSource);
		}
	}

	private static class AvroIOReadTranslator<T> implements FlinkPipelineTranslator.TransformTranslator<AvroIO.Read.Bound<T>> {
		private static final Logger LOG = LoggerFactory.getLogger(AvroIOReadTranslator.class);

		@Override
		public void translateNode(AvroIO.Read.Bound<T> transform, TranslationContext context) {
			String path = transform.getFilepattern();
			String name = transform.getName();
			Schema schema = transform.getSchema();

			TypeInformation<T> typeInformation = context.getTypeInfo(transform.getOutput());

			// This is super hacky, but unfortunately we cannot get the type otherwise
			Class<T> avroType = null;
			try {
				Field typeField = transform.getClass().getDeclaredField("type");
				typeField.setAccessible(true);
				avroType = (Class<T>) typeField.get(transform);
			} catch (NoSuchFieldException | IllegalAccessException e) {
				// we know that the field is there and it is accessible
				System.out.println("Could not access type from AvroIO.Bound: " + e);
			}

			DataSource<T> source = new DataSource<>(context.getExecutionEnvironment(), new AvroInputFormat<>(new Path(path), avroType), typeInformation, name);

			context.setOutputDataSet(transform.getOutput(), source);
		}
	}

	private static class AvroIOWriteTranslator<T> implements FlinkPipelineTranslator.TransformTranslator<AvroIO.Write.Bound<T>> {
		private static final Logger LOG = LoggerFactory.getLogger(AvroIOWriteTranslator.class);

		@Override
		public void translateNode(AvroIO.Write.Bound<T> transform, TranslationContext context) {
			DataSet<T> inputDataSet = context.getInputDataSet(transform.getInput());
			String filenamePrefix = transform.getFilenamePrefix();
			String filenameSuffix = transform.getFilenameSuffix();
			int numShards = transform.getNumShards();
			String shardNameTemplate = transform.getShardNameTemplate();

			// TODO: Implement these. We need Flink support for this.
			LOG.warn("Translation of TextIO.Write.filenameSuffix not yet supported. Is: {}.",
					filenameSuffix);
			LOG.warn("Translation of TextIO.Write.shardNameTemplate not yet supported. Is: {}.", shardNameTemplate);

			// This is super hacky, but unfortunately we cannot get the type otherwise
			Class<T> avroType = null;
			try {
				Field typeField = transform.getClass().getDeclaredField("type");
				typeField.setAccessible(true);
				avroType = (Class<T>) typeField.get(transform);
			} catch (NoSuchFieldException | IllegalAccessException e) {
				// we know that the field is there and it is accessible
				System.out.println("Could not access type from AvroIO.Bound: " + e);
			}

			DataSink<T> dataSink = inputDataSet.output(new AvroOutputFormat<T>(new Path
					(filenamePrefix), avroType));

			if (numShards > 0) {
				dataSink.setParallelism(numShards);
			}
		}
	}

	private static class TextIOReadTranslator implements FlinkPipelineTranslator.TransformTranslator<TextIO.Read.Bound<String>> {
		private static final Logger LOG = LoggerFactory.getLogger(TextIOReadTranslator.class);

		@Override
		public void translateNode(TextIO.Read.Bound<String> transform, TranslationContext context) {
			String path = transform.getFilepattern();
			String name = transform.getName();

			TextIO.CompressionType compressionType = transform.getCompressionType();
			boolean needsValidation = transform.needsValidation();

			// TODO: Implement these. We need Flink support for this.
			LOG.warn("Translation of TextIO.CompressionType not yet supported. Is: {}.", compressionType);
			LOG.warn("Translation of TextIO.Read.needsValidation not yet supported. Is: {}.", needsValidation);

			TypeInformation<String> typeInformation = context.getTypeInfo(transform.getOutput());

			DataSource<String> source = new DataSource<>(context.getExecutionEnvironment(), new TextInputFormat(new Path(path)), typeInformation, name);

			context.setOutputDataSet(transform.getOutput(), source);
		}
	}

	private static class TextIOWriteTranslator<T> implements FlinkPipelineTranslator.TransformTranslator<TextIO.Write.Bound<T>> {
		private static final Logger LOG = LoggerFactory.getLogger(TextIOWriteTranslator.class);

		@Override
		public void translateNode(TextIO.Write.Bound<T> transform, TranslationContext context) {
			DataSet<T> inputDataSet = context.getInputDataSet(transform.getInput());
			String filenamePrefix = transform.getFilenamePrefix();
			String filenameSuffix = transform.getFilenameSuffix();
			boolean needsValidation = transform.needsValidation();
			int numShards = transform.getNumShards();
			String shardNameTemplate = transform.getShardNameTemplate();

			// TODO: Implement these. We need Flink support for this.
			LOG.warn("Translation of TextIO.Write.needsValidation not yet supported. Is: {}.", needsValidation);
			LOG.warn("Translation of TextIO.Write.filenameSuffix not yet supported. Is: {}.", filenameSuffix);
			LOG.warn("Translation of TextIO.Write.shardNameTemplate not yet supported. Is: {}.", shardNameTemplate);

			//inputDataSet.print();
			DataSink<T> dataSink = inputDataSet.writeAsText(filenamePrefix);

			if (numShards > 0) {
				dataSink.setParallelism(numShards);
			}
		}
	}

	private static class ConsoleIOWriteTranslator implements FlinkPipelineTranslator.TransformTranslator<ConsoleIO.Write.Bound> {
		@Override
		public void translateNode(ConsoleIO.Write.Bound transform, TranslationContext context) {
			DataSet<?> inputDataSet = context.getInputDataSet(transform.getInput());
			inputDataSet.print().name(transform.getName());
		}
	}

	private static class GroupByKeyOnlyTranslator<K, V> implements FlinkPipelineTranslator.TransformTranslator<GroupByKey.GroupByKeyOnly<K, V>> {

		@Override
		public void translateNode(GroupByKey.GroupByKeyOnly<K, V> transform, TranslationContext context) {
			DataSet<KV<K, V>> inputDataSet = context.getInputDataSet(transform.getInput());
			GroupReduceFunction<KV<K, V>, KV<K, Iterable<V>>> groupReduceFunction = new FlinkKeyedListAggregationFunction<>();

			TypeInformation<KV<K, Iterable<V>>> typeInformation = context.getTypeInfo(transform.getOutput());

			Grouping<KV<K, V>> grouping = new UnsortedGrouping<>(inputDataSet, new Keys.ExpressionKeys<>(new String[]{""}, inputDataSet.getType()));

			GroupReduceOperator<KV<K, V>, KV<K, Iterable<V>>> outputDataSet =
					new GroupReduceOperator<>(grouping, typeInformation, groupReduceFunction, transform.getName());
			context.setOutputDataSet(transform.getOutput(), outputDataSet);
		}
	}

	private static class CombinePerKeyTranslator<K, VI, VO> implements FlinkPipelineTranslator.TransformTranslator<Combine.PerKey<K, VI, VO>> {

		@Override
		public void translateNode(Combine.PerKey<K, VI, VO> transform, TranslationContext context) {
			DataSet<KV<K, VI>> inputDataSet = context.getInputDataSet(transform.getInput());

			Combine.KeyedCombineFn<? super K, ? super VI, ?, VO> keyedCombineFn = null;
			// This is super hacky, but unfortunately we cannot get the fn otherwise
			try {
				Field fnField = transform.getClass().getDeclaredField("fn");
				fnField.setAccessible(true);
				keyedCombineFn = (Combine.KeyedCombineFn<? super K, ? super VI, ?, VO>) fnField.get(transform);
			} catch (NoSuchFieldException | IllegalAccessException e) {
				// we know that the field is there and it is accessible
				System.out.println("Could not access KeyedCombineFn: " + e);
			}

			GroupReduceFunction<KV<K, VI>, KV<K, VO>> groupReduceFunction = new FlinkCombineFunction<>(keyedCombineFn);

			TypeInformation<KV<K, VO>> typeInformation = context.getTypeInfo(transform.getOutput());

			Grouping<KV<K, VI>> grouping = new UnsortedGrouping<>(inputDataSet, new Keys.ExpressionKeys<>(new String[]{""}, inputDataSet.getType()));

			GroupReduceOperator<KV<K, VI>, KV<K, VO>> outputDataSet =
					new GroupReduceOperator<>(grouping, typeInformation, groupReduceFunction, transform.getName());
			context.setOutputDataSet(transform.getOutput(), outputDataSet);
		}
	}

//	private static class CombineGroupedValuesTranslator<K, VI, VO> implements FlinkPipelineTranslator.TransformTranslator<Combine.GroupedValues<K, VI, VO>> {
//
//		@Override
//		public void translateNode(Combine.GroupedValues<K, VI, VO> transform, TranslationContext context) {
//			DataSet<KV<K, VI>> inputDataSet = context.getInputDataSet(transform.getInput());
//
//			Combine.KeyedCombineFn<? super K, ? super VI, ?, VO> keyedCombineFn = transform.getFn();
//
//			GroupReduceFunction<KV<K, VI>, KV<K, VO>> groupReduceFunction = new FlinkCombineFunction<>(keyedCombineFn);
//
//			TypeInformation<KV<K, VO>> typeInformation = context.getTypeInfo(transform.getOutput());
//
//			Grouping<KV<K, VI>> grouping = new UnsortedGrouping<>(inputDataSet, new Keys.ExpressionKeys<>(new String[]{""}, inputDataSet.getType()));
//
//			GroupReduceOperator<KV<K, VI>, KV<K, VO>> outputDataSet =
//					new GroupReduceOperator<>(grouping, typeInformation, groupReduceFunction, transform.getName());
//			context.setOutputDataSet(transform.getOutput(), outputDataSet);
//		}
//	}
	
	private static class ParDoBoundTranslator<IN, OUT> implements FlinkPipelineTranslator.TransformTranslator<ParDo.Bound<IN, OUT>> {
		
		@Override
		public void translateNode(ParDo.Bound<IN, OUT> transform, TranslationContext context) {
			DataSet<IN> inputDataSet = context.getInputDataSet(transform.getInput());

			final DoFn<IN, OUT> doFn = transform.getFn();
			
			TypeInformation<OUT> typeInformation = context.getTypeInfo(transform.getOutput());

			FlinkDoFnFunction<IN, OUT> doFnWrapper = new FlinkDoFnFunction<>(doFn, context.getPipelineOptions());
			MapPartitionOperator<IN, OUT> outputDataSet = new MapPartitionOperator<>(inputDataSet, typeInformation, doFnWrapper, transform.getName());

			transformSideInputs(transform.getSideInputs(), outputDataSet, context);

			context.setOutputDataSet(transform.getOutput(), outputDataSet);
		}
	}

	private static class ParDoBoundMultiTranslator<IN, OUT> implements FlinkPipelineTranslator.TransformTranslator<ParDo.BoundMulti<IN, OUT>> {

		@Override
		public void translateNode(ParDo.BoundMulti<IN, OUT> transform, TranslationContext context) {
			DataSet<IN> inputDataSet = context.getInputDataSet(transform.getInput());

			final DoFn<IN, OUT> doFn = transform.getFn();

			Map<TupleTag<?>, PCollection<?>> outputs = transform.getOutput().getAll();

			Map<TupleTag<?>, Integer> outputMap = Maps.newHashMap();
			// put the main output at index 0, FlinkMultiOutputDoFnFunction also expects this
			outputMap.put(transform.getMainOutputTag(), 0);
			int count = 1;
			for (TupleTag<?> tag: outputs.keySet()) {
				if (!outputMap.containsKey(tag)) {
					outputMap.put(tag, count++);
				}
			}

			// collect all output Coders and create a UnionCoder for our tagged outputs
			List<Coder<?>> outputCoders = Lists.newArrayList();
			for (PCollection<?> coll: outputs.values()) {
				outputCoders.add(coll.getCoder());
			}

			UnionCoder unionCoder = UnionCoder.of(outputCoders);

			@SuppressWarnings("unchecked")
			TypeInformation<RawUnionValue> typeInformation = new CoderTypeInformation<>(unionCoder);

			@SuppressWarnings("unchecked")
			FlinkMultiOutputDoFnFunction<IN, OUT> doFnWrapper = new FlinkMultiOutputDoFnFunction(doFn, context.getPipelineOptions(), outputMap);
			MapPartitionOperator<IN, RawUnionValue> outputDataSet = new MapPartitionOperator<>(inputDataSet, typeInformation, doFnWrapper, transform.getName());

			transformSideInputs(transform.getSideInputs(), outputDataSet, context);

			for (Map.Entry<TupleTag<?>, PCollection<?>> output: outputs.entrySet()) {
				TypeInformation<Object> outputType = context.getTypeInfo(output.getValue());
				int outputTag = outputMap.get(output.getKey());
				FlinkMultiOutputPruningFunction<Object> pruningFunction = new FlinkMultiOutputPruningFunction<>(outputTag);
				FlatMapOperator<RawUnionValue, Object> pruningOperator = new
						FlatMapOperator<>(outputDataSet, outputType,
						pruningFunction, output.getValue().getName());
				context.setOutputDataSet(output.getValue(), pruningOperator);

			}
		}
	}

	private static class FlattenPCollectionTranslator<T> implements FlinkPipelineTranslator.TransformTranslator<Flatten.FlattenPCollectionList<T>> {

		@Override
		public void translateNode(Flatten.FlattenPCollectionList<T> transform, TranslationContext context) {
			List<PCollection<T>> allInputs = transform.getInput().getAll();
			DataSet<T> result = null;
			for(PCollection<T> collection : allInputs) {
				DataSet<T> current = context.getInputDataSet(collection);
				if (result == null) {
					result = current;
				} else {
					result = result.union(current);
				}
			}
			context.setOutputDataSet(transform.getOutput(), result);
		}
	}

	private static class CreatePCollectionViewTranslator<R, T, WT> implements FlinkPipelineTranslator.TransformTranslator<View.CreatePCollectionView<R,T,WT>> {
		@Override
		public void translateNode(View.CreatePCollectionView<R, T, WT> transform, TranslationContext context) {
			DataSet<T> inputDataSet = context.getInputDataSet(transform.getInput());
			PCollectionView<T, WT> input = transform.apply(null);
			context.setSideInputDataSet(input, inputDataSet);
		}
	}

	private static class CreateTranslator<OUT> implements FlinkPipelineTranslator.TransformTranslator<Create<OUT>> {

		@Override
		public void translateNode(Create<OUT> transform, TranslationContext context) {
			TypeInformation<OUT> typeInformation = context.getTypeInfo(transform.getOutput());
			Iterable<OUT> elements = transform.getElements();

			// we need to serializer the elements to byte arrays, since they might contain
			// elements that are not serializable by Java serialization. We deserialize them
			// in the FlatMap function using the Coder.

			List<byte[]> serializedElements = Lists.newArrayList();
			Coder<OUT> coder = transform.getOutput().getCoder();
			for (OUT element: elements) {
				ByteArrayOutputStream bao = new ByteArrayOutputStream();
				try {
					coder.encode(element, bao, Coder.Context.OUTER);
					serializedElements.add(bao.toByteArray());
				} catch (IOException e) {
					throw new RuntimeException("Could not serialize Create elements using Coder: " + e);
				}
			}

			DataSet<Integer> initDataSet = context.getExecutionEnvironment().fromElements(1);
			FlinkCreateFunction<Integer, OUT> flatMapFunction = new FlinkCreateFunction<>(serializedElements, coder);
			FlatMapOperator<Integer, OUT> outputDataSet = new FlatMapOperator<>(initDataSet, typeInformation, flatMapFunction, transform.getName());

			context.setOutputDataSet(transform.getOutput(), outputDataSet);
		}
	}

	private static void transformSideInputs(List<PCollectionView<?, ?>> sideInputs,
	                                        MapPartitionOperator<?, ?> outputDataSet,
	                                        TranslationContext context) {
		// get corresponding Flink broadcast DataSets
		for(PCollectionView<?, ?> input : sideInputs) {
			DataSet<?> broadcastSet = context.getSideInputDataSet(input);
			outputDataSet.withBroadcastSet(broadcastSet, input.getTagInternal().getId());
		}
	}

// Disabled because it depends on a pending pull request to the DataFlowSDK
//	/**
//	 * Special composite transform translator. Only called if the CoGroup is two dimensional.
//	 * @param <K>
//	 */
//	private static class CoGroupByKeyTranslator<K, V1, V2> implements FlinkPipelineTranslator.TransformTranslator<CoGroupByKey<K>> {
//
//		@Override
//		public void translateNode(CoGroupByKey<K> transform, TranslationContext context) {
//			KeyedPCollectionTuple<K> input = transform.getInput();
//
//			CoGbkResultSchema schema = input.getCoGbkResultSchema();
//			List<KeyedPCollectionTuple.TaggedKeyedPCollection<K, ?>> keyedCollections = input.getKeyedCollections();
//
//			KeyedPCollectionTuple.TaggedKeyedPCollection<K, ?> taggedCollection1 = keyedCollections.get(0);
//			KeyedPCollectionTuple.TaggedKeyedPCollection<K, ?> taggedCollection2 = keyedCollections.get(1);
//
//			TupleTag<?> tupleTag1 = taggedCollection1.getTupleTag();
//			TupleTag<?> tupleTag2 = taggedCollection2.getTupleTag();
//
//			PCollection<? extends KV<K, ?>> collection1 = taggedCollection1.getCollection();
//			PCollection<? extends KV<K, ?>> collection2 = taggedCollection2.getCollection();
//
//			DataSet<KV<K,V1>> inputDataSet1 = context.getInputDataSet(collection1);
//			DataSet<KV<K,V2>> inputDataSet2 = context.getInputDataSet(collection2);
//
//			TypeInformation<KV<K,CoGbkResult>> typeInfo = context.getTypeInfo(transform.getOutput());
//
//			CoGroupKeyedListAggregator<K,V1,V2> aggregator = new CoGroupKeyedListAggregator<>(schema, tupleTag1, tupleTag2);
//
//			ExpressionKeys<KV<K,V1>> keySelector1 = new ExpressionKeys<>(new String[]{""}, inputDataSet1.getType());
//			ExpressionKeys<KV<K,V2>> keySelector2 = new ExpressionKeys<>(new String[]{""}, inputDataSet2.getType());
//
//			DataSet<KV<K, CoGbkResult>> out = new CoGroupOperator<>(inputDataSet1, inputDataSet2,
//																	keySelector1, keySelector2, 
//					                                                aggregator, typeInfo, null, transform.getName());
//			context.setOutputDataSet(transform.getOutput(), out);
//		}
//	}

	// --------------------------------------------------------------------------------------------
	//  Miscellaneous
	// --------------------------------------------------------------------------------------------
	
	private FlinkTransformTranslators() {}
}
