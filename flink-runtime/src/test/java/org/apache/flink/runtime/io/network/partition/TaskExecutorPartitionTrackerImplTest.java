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

package org.apache.flink.runtime.io.network.partition;

import org.apache.flink.api.common.JobID;
import org.apache.flink.metrics.MetricGroup;
import org.apache.flink.runtime.clusterframework.types.ResourceID;
import org.apache.flink.runtime.deployment.InputGateDeploymentDescriptor;
import org.apache.flink.runtime.deployment.ResultPartitionDeploymentDescriptor;
import org.apache.flink.runtime.executiongraph.ExecutionAttemptID;
import org.apache.flink.runtime.executiongraph.PartitionInfo;
import org.apache.flink.runtime.io.network.NettyShuffleEnvironmentBuilder;
import org.apache.flink.runtime.io.network.partition.consumer.SingleInputGate;
import org.apache.flink.runtime.jobgraph.IntermediateDataSetID;
import org.apache.flink.runtime.shuffle.ShuffleDescriptor;
import org.apache.flink.runtime.shuffle.ShuffleEnvironment;
import org.apache.flink.runtime.shuffle.ShuffleIOOwnerContext;
import org.apache.flink.runtime.taskexecutor.partition.ClusterPartitionReport;

import org.apache.flink.shaded.guava33.com.google.common.collect.Iterables;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.apache.flink.core.testutils.FlinkAssertions.assertThatFuture;
import static org.assertj.core.api.Assertions.assertThat;

/** Tests for the {@link TaskExecutorPartitionTrackerImpl}. */
class TaskExecutorPartitionTrackerImplTest {

    @Test
    void createClusterPartitionReport() {
        final TaskExecutorPartitionTrackerImpl partitionTracker =
                new TaskExecutorPartitionTrackerImpl(new NettyShuffleEnvironmentBuilder().build());

        assertThat(partitionTracker.createClusterPartitionReport().getEntries()).isEmpty();

        final IntermediateDataSetID dataSetId = new IntermediateDataSetID();
        final JobID jobId = new JobID();
        final ResultPartitionID clusterPartitionId = new ResultPartitionID();
        final ResultPartitionID jobPartitionId = new ResultPartitionID();
        final int numberOfPartitions = 1;

        partitionTracker.startTrackingPartition(
                jobId,
                new TaskExecutorPartitionInfo(
                        new TestingShuffleDescriptor(clusterPartitionId),
                        dataSetId,
                        numberOfPartitions));
        partitionTracker.startTrackingPartition(
                jobId,
                new TaskExecutorPartitionInfo(
                        new TestingShuffleDescriptor(jobPartitionId),
                        dataSetId,
                        numberOfPartitions + 1));

        partitionTracker.promoteJobPartitions(Collections.singleton(clusterPartitionId));

        final ClusterPartitionReport clusterPartitionReport =
                partitionTracker.createClusterPartitionReport();

        final ClusterPartitionReport.ClusterPartitionReportEntry reportEntry =
                Iterables.getOnlyElement(clusterPartitionReport.getEntries());
        assertThat(reportEntry.getDataSetId()).isEqualTo(dataSetId);
        assertThat(reportEntry.getNumTotalPartitions()).isEqualTo(numberOfPartitions);
        assertThat(reportEntry.getHostedPartitions()).contains(clusterPartitionId);
    }

    @Test
    void testStopTrackingAndReleaseJobPartitions() throws Exception {
        final TestingShuffleEnvironment testingShuffleEnvironment = new TestingShuffleEnvironment();
        final CompletableFuture<Collection<ResultPartitionID>> shuffleReleaseFuture =
                new CompletableFuture<>();
        testingShuffleEnvironment.releasePartitionsLocallyFuture = shuffleReleaseFuture;

        final ResultPartitionID resultPartitionId1 = new ResultPartitionID();
        final ResultPartitionID resultPartitionId2 = new ResultPartitionID();

        final TaskExecutorPartitionTracker partitionTracker =
                new TaskExecutorPartitionTrackerImpl(testingShuffleEnvironment);
        partitionTracker.startTrackingPartition(
                new JobID(),
                new TaskExecutorPartitionInfo(
                        new TestingShuffleDescriptor(resultPartitionId1),
                        new IntermediateDataSetID(),
                        1));
        partitionTracker.startTrackingPartition(
                new JobID(),
                new TaskExecutorPartitionInfo(
                        new TestingShuffleDescriptor(resultPartitionId2),
                        new IntermediateDataSetID(),
                        1));
        partitionTracker.stopTrackingAndReleaseJobPartitions(
                Collections.singleton(resultPartitionId1));

        assertThatFuture(shuffleReleaseFuture)
                .eventuallySucceeds()
                .satisfies(actual -> assertThat(actual).containsExactly(resultPartitionId1));
    }

    @Test
    void testStopTrackingAndReleaseJobPartitionsFor() throws Exception {
        final TestingShuffleEnvironment testingShuffleEnvironment = new TestingShuffleEnvironment();
        final CompletableFuture<Collection<ResultPartitionID>> shuffleReleaseFuture =
                new CompletableFuture<>();
        testingShuffleEnvironment.releasePartitionsLocallyFuture = shuffleReleaseFuture;

        final JobID jobId1 = new JobID();
        final JobID jobId2 = new JobID();
        final ResultPartitionID resultPartitionId1 = new ResultPartitionID();
        final ResultPartitionID resultPartitionId2 = new ResultPartitionID();

        final TaskExecutorPartitionTracker partitionTracker =
                new TaskExecutorPartitionTrackerImpl(testingShuffleEnvironment);
        partitionTracker.startTrackingPartition(
                jobId1,
                new TaskExecutorPartitionInfo(
                        new TestingShuffleDescriptor(resultPartitionId1),
                        new IntermediateDataSetID(),
                        1));
        partitionTracker.startTrackingPartition(
                jobId2,
                new TaskExecutorPartitionInfo(
                        new TestingShuffleDescriptor(resultPartitionId2),
                        new IntermediateDataSetID(),
                        1));
        partitionTracker.stopTrackingAndReleaseJobPartitionsFor(jobId1);

        assertThatFuture(shuffleReleaseFuture)
                .eventuallySucceeds()
                .asList()
                .contains(resultPartitionId1);
    }

    @Test
    void testGetTrackedPartitionsFor() {
        final TestingShuffleEnvironment testingShuffleEnvironment = new TestingShuffleEnvironment();

        final JobID jobId = new JobID();
        final ResultPartitionID resultPartitionId = new ResultPartitionID();

        final TaskExecutorPartitionTracker partitionTracker =
                new TaskExecutorPartitionTrackerImpl(testingShuffleEnvironment);
        TaskExecutorPartitionInfo partitionInfo =
                new TaskExecutorPartitionInfo(
                        new TestingShuffleDescriptor(resultPartitionId),
                        new IntermediateDataSetID(),
                        1);

        partitionTracker.startTrackingPartition(jobId, partitionInfo);
        Collection<TaskExecutorPartitionInfo> partitions =
                partitionTracker.getTrackedPartitionsFor(jobId);

        assertThat(partitions).hasSize(1);
        assertThat(partitions.iterator().next()).isEqualTo(partitionInfo);
    }

    @Test
    void promoteJobPartitions() throws Exception {
        final TestingShuffleEnvironment testingShuffleEnvironment = new TestingShuffleEnvironment();
        final CompletableFuture<Collection<ResultPartitionID>> shuffleReleaseFuture =
                new CompletableFuture<>();
        testingShuffleEnvironment.releasePartitionsLocallyFuture = shuffleReleaseFuture;

        final JobID jobId = new JobID();
        final ResultPartitionID resultPartitionId1 = new ResultPartitionID();
        final ResultPartitionID resultPartitionId2 = new ResultPartitionID();

        final TaskExecutorPartitionTracker partitionTracker =
                new TaskExecutorPartitionTrackerImpl(testingShuffleEnvironment);
        partitionTracker.startTrackingPartition(
                jobId,
                new TaskExecutorPartitionInfo(
                        new TestingShuffleDescriptor(resultPartitionId1),
                        new IntermediateDataSetID(),
                        1));
        partitionTracker.startTrackingPartition(
                jobId,
                new TaskExecutorPartitionInfo(
                        new TestingShuffleDescriptor(resultPartitionId2),
                        new IntermediateDataSetID(),
                        1));
        partitionTracker.promoteJobPartitions(Collections.singleton(resultPartitionId1));

        partitionTracker.stopTrackingAndReleaseJobPartitionsFor(jobId);
        assertThatFuture(shuffleReleaseFuture)
                .eventuallySucceeds()
                .asList()
                .doesNotContain(resultPartitionId1);
    }

    @Test
    void stopTrackingAndReleaseAllClusterPartitions() throws Exception {
        final TestingShuffleEnvironment testingShuffleEnvironment = new TestingShuffleEnvironment();
        final CompletableFuture<Collection<ResultPartitionID>> shuffleReleaseFuture =
                new CompletableFuture<>();
        testingShuffleEnvironment.releasePartitionsLocallyFuture = shuffleReleaseFuture;

        final ResultPartitionID resultPartitionId1 = new ResultPartitionID();
        final ResultPartitionID resultPartitionId2 = new ResultPartitionID();

        final TaskExecutorPartitionTracker partitionTracker =
                new TaskExecutorPartitionTrackerImpl(testingShuffleEnvironment);
        partitionTracker.startTrackingPartition(
                new JobID(),
                new TaskExecutorPartitionInfo(
                        new TestingShuffleDescriptor(resultPartitionId1),
                        new IntermediateDataSetID(),
                        1));
        partitionTracker.startTrackingPartition(
                new JobID(),
                new TaskExecutorPartitionInfo(
                        new TestingShuffleDescriptor(resultPartitionId2),
                        new IntermediateDataSetID(),
                        1));
        partitionTracker.promoteJobPartitions(Collections.singleton(resultPartitionId1));

        partitionTracker.stopTrackingAndReleaseAllClusterPartitions();
        assertThatFuture(shuffleReleaseFuture)
                .eventuallySucceeds()
                .satisfies(actual -> assertThat(actual).contains(resultPartitionId1));
    }

    @Test
    void stopTrackingAndReleaseClusterPartitions() throws Exception {
        final TestingShuffleEnvironment testingShuffleEnvironment = new TestingShuffleEnvironment();
        final CompletableFuture<Collection<ResultPartitionID>> shuffleReleaseFuture =
                new CompletableFuture<>();
        testingShuffleEnvironment.releasePartitionsLocallyFuture = shuffleReleaseFuture;

        final ResultPartitionID resultPartitionId1 = new ResultPartitionID();
        final ResultPartitionID resultPartitionId2 = new ResultPartitionID();

        final IntermediateDataSetID dataSetId1 = new IntermediateDataSetID();
        final IntermediateDataSetID dataSetId2 = new IntermediateDataSetID();

        final TaskExecutorPartitionTracker partitionTracker =
                new TaskExecutorPartitionTrackerImpl(testingShuffleEnvironment);
        partitionTracker.startTrackingPartition(
                new JobID(),
                new TaskExecutorPartitionInfo(
                        new TestingShuffleDescriptor(resultPartitionId1), dataSetId1, 1));
        partitionTracker.startTrackingPartition(
                new JobID(),
                new TaskExecutorPartitionInfo(
                        new TestingShuffleDescriptor(resultPartitionId2), dataSetId2, 1));
        partitionTracker.promoteJobPartitions(Collections.singleton(resultPartitionId1));

        partitionTracker.stopTrackingAndReleaseClusterPartitions(Collections.singleton(dataSetId1));
        assertThatFuture(shuffleReleaseFuture)
                .eventuallySucceeds()
                .satisfies(actual -> assertThat(actual).contains(resultPartitionId1));
    }

    private static class TestingShuffleEnvironment
            implements ShuffleEnvironment<ResultPartition, SingleInputGate> {

        private final ShuffleEnvironment<ResultPartition, SingleInputGate>
                backingShuffleEnvironment = new NettyShuffleEnvironmentBuilder().build();

        CompletableFuture<Collection<ResultPartitionID>> releasePartitionsLocallyFuture = null;

        @Override
        public int start() throws IOException {
            return backingShuffleEnvironment.start();
        }

        @Override
        public ShuffleIOOwnerContext createShuffleIOOwnerContext(
                String ownerName, ExecutionAttemptID executionAttemptID, MetricGroup parentGroup) {
            return backingShuffleEnvironment.createShuffleIOOwnerContext(
                    ownerName, executionAttemptID, parentGroup);
        }

        @Override
        public List<ResultPartition> createResultPartitionWriters(
                ShuffleIOOwnerContext ownerContext,
                List<ResultPartitionDeploymentDescriptor> resultPartitionDeploymentDescriptors) {
            return backingShuffleEnvironment.createResultPartitionWriters(
                    ownerContext, resultPartitionDeploymentDescriptors);
        }

        @Override
        public void releasePartitionsLocally(Collection<ResultPartitionID> partitionIds) {
            backingShuffleEnvironment.releasePartitionsLocally(partitionIds);
            if (releasePartitionsLocallyFuture != null) {
                releasePartitionsLocallyFuture.complete(partitionIds);
            }
        }

        @Override
        public Collection<ResultPartitionID> getPartitionsOccupyingLocalResources() {
            return backingShuffleEnvironment.getPartitionsOccupyingLocalResources();
        }

        @Override
        public List<SingleInputGate> createInputGates(
                ShuffleIOOwnerContext ownerContext,
                PartitionProducerStateProvider partitionProducerStateProvider,
                List<InputGateDeploymentDescriptor> inputGateDeploymentDescriptors) {
            return backingShuffleEnvironment.createInputGates(
                    ownerContext, partitionProducerStateProvider, inputGateDeploymentDescriptors);
        }

        @Override
        public boolean updatePartitionInfo(
                ExecutionAttemptID consumerID, PartitionInfo partitionInfo)
                throws IOException, InterruptedException {
            return backingShuffleEnvironment.updatePartitionInfo(consumerID, partitionInfo);
        }

        @Override
        public void close() throws Exception {
            backingShuffleEnvironment.close();
        }
    }

    private static class TestingShuffleDescriptor implements ShuffleDescriptor {
        private final ResultPartitionID resultPartitionID;

        private TestingShuffleDescriptor(ResultPartitionID resultPartitionID) {

            this.resultPartitionID = resultPartitionID;
        }

        @Override
        public ResultPartitionID getResultPartitionID() {
            return resultPartitionID;
        }

        @Override
        public Optional<ResourceID> storesLocalResourcesOn() {
            return Optional.empty();
        }
    }
}
