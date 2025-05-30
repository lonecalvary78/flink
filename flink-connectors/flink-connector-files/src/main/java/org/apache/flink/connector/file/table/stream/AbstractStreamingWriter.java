/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.connector.file.table.stream;

import org.apache.flink.api.common.state.ListState;
import org.apache.flink.api.common.state.ListStateDescriptor;
import org.apache.flink.api.common.typeutils.base.BooleanSerializer;
import org.apache.flink.core.fs.Path;
import org.apache.flink.runtime.state.StateInitializationContext;
import org.apache.flink.runtime.state.StateSnapshotContext;
import org.apache.flink.streaming.api.functions.sink.filesystem.Bucket;
import org.apache.flink.streaming.api.functions.sink.filesystem.BucketLifeCycleListener;
import org.apache.flink.streaming.api.functions.sink.filesystem.Buckets;
import org.apache.flink.streaming.api.functions.sink.filesystem.StreamingFileSinkHelper;
import org.apache.flink.streaming.api.functions.sink.filesystem.legacy.StreamingFileSink;
import org.apache.flink.streaming.api.operators.AbstractStreamOperator;
import org.apache.flink.streaming.api.operators.BoundedOneInput;
import org.apache.flink.streaming.api.operators.OneInputStreamOperator;
import org.apache.flink.streaming.api.watermark.Watermark;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;

import org.apache.flink.shaded.guava33.com.google.common.collect.Lists;

import java.util.List;

import static org.apache.flink.util.Preconditions.checkState;

/**
 * Operator for file system sink. It is a operator version of {@link StreamingFileSink}. It can send
 * file and bucket information to downstream.
 */
public abstract class AbstractStreamingWriter<IN, OUT> extends AbstractStreamOperator<OUT>
        implements OneInputStreamOperator<IN, OUT>, BoundedOneInput {

    private static final long serialVersionUID = 1L;

    // ------------------------ configuration fields --------------------------

    private final long bucketCheckInterval;

    private final StreamingFileSink.BucketsBuilder<
                    IN, String, ? extends StreamingFileSink.BucketsBuilder<IN, String, ?>>
            bucketsBuilder;

    // --------------------------- runtime fields -----------------------------

    protected transient Buckets<IN, String> buckets;

    private transient StreamingFileSinkHelper<IN> helper;

    protected transient long currentWatermark;

    /**
     * Used to remember that EOI has already happened so that we don't emit the last committables of
     * the final checkpoints twice.
     */
    private static final ListStateDescriptor<Boolean> END_OF_INPUT_STATE_DESC =
            new ListStateDescriptor<>("end_of_input_state", BooleanSerializer.INSTANCE);

    private boolean endOfInput;
    private ListState<Boolean> endOfInputState;

    public AbstractStreamingWriter(
            long bucketCheckInterval,
            StreamingFileSink.BucketsBuilder<
                            IN, String, ? extends StreamingFileSink.BucketsBuilder<IN, String, ?>>
                    bucketsBuilder) {
        this.bucketCheckInterval = bucketCheckInterval;
        this.bucketsBuilder = bucketsBuilder;
    }

    /** Notifies a partition created. */
    protected abstract void partitionCreated(String partition);

    /**
     * Notifies a partition become inactive. A partition becomes inactive after all the records
     * received so far have been committed.
     */
    protected abstract void partitionInactive(String partition);

    /**
     * Notifies a new file has been opened.
     *
     * <p>Note that this does not mean that the file has been created in the file system. It is only
     * created logically and the actual file will be generated after it is committed.
     */
    protected abstract void onPartFileOpened(String partition, Path newPath);

    /** Commit up to this checkpoint id. */
    protected void commitUpToCheckpoint(long checkpointId) throws Exception {
        helper.commitUpToCheckpoint(checkpointId);
    }

    @Override
    public void initializeState(StateInitializationContext context) throws Exception {
        super.initializeState(context);
        buckets =
                bucketsBuilder.createBuckets(
                        getRuntimeContext().getTaskInfo().getIndexOfThisSubtask());

        // Set listener before the initialization of Buckets.
        buckets.setBucketLifeCycleListener(
                new BucketLifeCycleListener<IN, String>() {

                    @Override
                    public void bucketCreated(Bucket<IN, String> bucket) {
                        AbstractStreamingWriter.this.partitionCreated(bucket.getBucketId());
                    }

                    @Override
                    public void bucketInactive(Bucket<IN, String> bucket) {
                        AbstractStreamingWriter.this.partitionInactive(bucket.getBucketId());
                    }
                });

        buckets.setFileLifeCycleListener(AbstractStreamingWriter.this::onPartFileOpened);

        helper =
                new StreamingFileSinkHelper<>(
                        buckets,
                        context.isRestored(),
                        context.getOperatorStateStore(),
                        getRuntimeContext().getProcessingTimeService(),
                        bucketCheckInterval);

        currentWatermark = Long.MIN_VALUE;

        // Figure out if we have seen end of input before and if we should anything downstream. We
        // have the following
        // cases:
        // 1. state is empty:
        //   - First time initialization
        //   - Restoring from a previous version of Flink that didn't handle EOI
        //   - Upscaled from a final or regular checkpoint
        // In all cases, we regularly handle EOI, potentially resulting in unnecessary .
        // 2. state is not empty:
        //   - This implies Flink restores from a version that handles EOI.
        //   - If there is one entry, no rescaling happened (for this subtask), so if it's true,
        //     we recover from a final checkpoint (for this subtask) and can ignore another EOI
        //     else we have a regular checkpoint.
        //   - If there are multiple entries, Flink downscaled, and we need to check if all are
        //     true and do the same as above. As soon as one entry is false, we regularly start
        //     the writer and potentially emit duplicate summaries if we indeed recovered from a
        //     final checkpoint.
        endOfInputState = context.getOperatorStateStore().getListState(END_OF_INPUT_STATE_DESC);
        List<Boolean> previousState = Lists.newArrayList(endOfInputState.get());
        endOfInput = !previousState.isEmpty() && !previousState.contains(false);
    }

    @Override
    public void snapshotState(StateSnapshotContext context) throws Exception {
        super.snapshotState(context);
        helper.snapshotState(context.getCheckpointId());
    }

    @Override
    public void processWatermark(Watermark mark) throws Exception {
        super.processWatermark(mark);
        currentWatermark = mark.getTimestamp();
    }

    @Override
    public void processElement(StreamRecord<IN> element) throws Exception {
        checkState(!endOfInput, "Received element after endOfInput: %s", element);
        helper.onElement(
                element.getValue(),
                getProcessingTimeService().getCurrentProcessingTime(),
                element.hasTimestamp() ? element.getTimestamp() : null,
                currentWatermark);
    }

    @Override
    public void notifyCheckpointComplete(long checkpointId) throws Exception {
        super.notifyCheckpointComplete(checkpointId);
        if (!this.endOfInput) {
            commitUpToCheckpoint(checkpointId);
        }
    }

    @Override
    public void endInput() throws Exception {
        if (!this.endOfInput) {
            this.endOfInput = true;
            buckets.onProcessingTime(Long.MAX_VALUE);
            helper.snapshotState(Long.MAX_VALUE);
            output.emitWatermark(new Watermark(Long.MAX_VALUE));
            commitUpToCheckpoint(Long.MAX_VALUE);
        }
    }

    @Override
    public void close() throws Exception {
        super.close();
        if (helper != null) {
            helper.close();
        }
    }
}
