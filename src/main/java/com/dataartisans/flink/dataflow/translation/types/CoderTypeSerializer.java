package com.dataartisans.flink.dataflow.translation.types;

import com.dataartisans.flink.dataflow.translation.wrappers.DataInputViewWrapper;
import com.dataartisans.flink.dataflow.translation.wrappers.DataOutputViewWrapper;
import com.google.cloud.dataflow.sdk.coders.Coder;
import org.apache.flink.api.common.typeutils.TypeSerializer;
import org.apache.flink.core.memory.DataInputView;
import org.apache.flink.core.memory.DataOutputView;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;

/**
 * Flink {@link org.apache.flink.api.common.typeutils.TypeSerializer} for
 * Dataflow {@link com.google.cloud.dataflow.sdk.coders.Coder}s
 */
public class CoderTypeSerializer<T> extends TypeSerializer<T> {
	
	private Coder<T> coder;
	private transient DataInputViewWrapper inputWrapper;
	private transient DataOutputViewWrapper outputWrapper;

	// We use this for internal encoding/decoding for creating copies using the Coder.
	private transient InspectableByteArrayOutputStream byteBuffer;

	public CoderTypeSerializer(Coder<T> coder) {
		this.coder = coder;
		this.inputWrapper = new DataInputViewWrapper(null);
		this.outputWrapper = new DataOutputViewWrapper(null);

		byteBuffer = new InspectableByteArrayOutputStream();
	}
	
	private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
		in.defaultReadObject();
		this.inputWrapper = new DataInputViewWrapper(null);
		this.outputWrapper = new DataOutputViewWrapper(null);

		byteBuffer = new InspectableByteArrayOutputStream();
	}
	
	@Override
	public boolean isImmutableType() {
		return false;
	}

	@Override
	public boolean isStateful() {
		return false;
	}

	@Override
	public T createInstance() {
		return null;
	}

	@Override
	public T copy(T t) {
		byteBuffer.reset();
		try {
			coder.encode(t, byteBuffer, Coder.Context.OUTER);
		} catch (IOException e) {
			throw new RuntimeException("Could not copy.", e);
		}
		try {
			return coder.decode(
					new ByteArrayInputStream(byteBuffer.getBuffer(), 0, byteBuffer.size()),
					Coder.Context.OUTER);
		} catch (IOException e) {
			throw new RuntimeException("Could not copy.", e);
		}
	}

	@Override
	public T copy(T t, T reuse) {
		return copy(t);
	}

	@Override
	public int getLength() {
		return 0;
	}

	@Override
	public void serialize(T t, DataOutputView dataOutputView) throws IOException {
		outputWrapper.setOutputView(dataOutputView);
		coder.encode(t, outputWrapper, Coder.Context.NESTED);
	}

	@Override
	public T deserialize(DataInputView dataInputView) throws IOException {
		inputWrapper.setInputView(dataInputView);
		return coder.decode(inputWrapper, Coder.Context.NESTED);
	}

	@Override
	public T deserialize(T t, DataInputView dataInputView) throws IOException {
		return deserialize(dataInputView);
	}

	@Override
	public void copy(DataInputView dataInputView, DataOutputView dataOutputView) throws IOException {
		serialize(deserialize(dataInputView), dataOutputView);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;

		CoderTypeSerializer that = (CoderTypeSerializer) o;

		if (!coder.equals(that.coder)) return false;

		return true;
	}

	@Override
	public int hashCode() {
		return coder.hashCode();
	}
}
