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

package org.apache.flink.datastream.api.context;

import org.apache.flink.annotation.Experimental;
import org.apache.flink.api.common.state.AggregatingStateDeclaration;
import org.apache.flink.api.common.state.BroadcastState;
import org.apache.flink.api.common.state.BroadcastStateDeclaration;
import org.apache.flink.api.common.state.ListStateDeclaration;
import org.apache.flink.api.common.state.MapStateDeclaration;
import org.apache.flink.api.common.state.ReducingStateDeclaration;
import org.apache.flink.api.common.state.ValueStateDeclaration;
import org.apache.flink.api.common.state.v2.AggregatingState;
import org.apache.flink.api.common.state.v2.ListState;
import org.apache.flink.api.common.state.v2.MapState;
import org.apache.flink.api.common.state.v2.ReducingState;
import org.apache.flink.api.common.state.v2.ValueState;

import java.util.Optional;

/** This is responsibility for managing runtime information related to state of process function. */
@Experimental
public interface StateManager {
    /**
     * Get the key of current record.
     *
     * @return The key of current processed record.
     * @throws UnsupportedOperationException if the key can not be extracted for this function, for
     *     instance, get the key from a non-keyed partition stream.
     */
    <K> K getCurrentKey() throws UnsupportedOperationException;

    /**
     * Get the optional of the specific list state.
     *
     * @param stateDeclaration of this state.
     * @return the list state corresponds to the state declaration, this may be empty.
     */
    <T> Optional<ListState<T>> getStateOptional(ListStateDeclaration<T> stateDeclaration)
            throws Exception;

    /**
     * Get the specific list state.
     *
     * @param stateDeclaration of this state.
     * @return the list state corresponds to the state declaration
     * @throws RuntimeException if the state is not available.
     */
    <T> ListState<T> getState(ListStateDeclaration<T> stateDeclaration) throws Exception;

    /**
     * Get the optional of the specific value state.
     *
     * @param stateDeclaration of this state.
     * @return the value state corresponds to the state declaration, this may be empty.
     */
    <T> Optional<ValueState<T>> getStateOptional(ValueStateDeclaration<T> stateDeclaration)
            throws Exception;

    /**
     * Get the specific value state.
     *
     * @param stateDeclaration of this state.
     * @return the value state corresponds to the state declaration.
     * @throws RuntimeException if the state is not available.
     */
    <T> ValueState<T> getState(ValueStateDeclaration<T> stateDeclaration) throws Exception;

    /**
     * Get the optional of the specific map state.
     *
     * @param stateDeclaration of this state.
     * @return the map state corresponds to the state declaration, this may be empty.
     */
    <K, V> Optional<MapState<K, V>> getStateOptional(MapStateDeclaration<K, V> stateDeclaration)
            throws Exception;

    /**
     * Get the specific map state.
     *
     * @param stateDeclaration of this state.
     * @return the map state corresponds to the state declaration.
     * @throws RuntimeException if the state is not available.
     */
    <K, V> MapState<K, V> getState(MapStateDeclaration<K, V> stateDeclaration) throws Exception;

    /**
     * Get the optional of the specific reducing state.
     *
     * @param stateDeclaration of this state.
     * @return the reducing state corresponds to the state declaration, this may be empty.
     */
    <T> Optional<ReducingState<T>> getStateOptional(ReducingStateDeclaration<T> stateDeclaration)
            throws Exception;

    /**
     * Get the specific reducing state.
     *
     * @param stateDeclaration of this state.
     * @return the reducing state corresponds to the state declaration.
     * @throws RuntimeException if the state is not available.
     */
    <T> ReducingState<T> getState(ReducingStateDeclaration<T> stateDeclaration) throws Exception;

    /**
     * Get the optional of the specific aggregating state.
     *
     * @param stateDeclaration of this state.
     * @return the aggregating state corresponds to the state declaration, this may be empty.
     */
    <IN, ACC, OUT> Optional<AggregatingState<IN, OUT>> getStateOptional(
            AggregatingStateDeclaration<IN, ACC, OUT> stateDeclaration) throws Exception;

    /**
     * Get the specific aggregating state.
     *
     * @param stateDeclaration of this state.
     * @return the aggregating state corresponds to the state declaration.
     * @throws RuntimeException if the state is not available.
     */
    <IN, ACC, OUT> AggregatingState<IN, OUT> getState(
            AggregatingStateDeclaration<IN, ACC, OUT> stateDeclaration) throws Exception;

    /**
     * Get the optional of the specific broadcast state.
     *
     * @param stateDeclaration of this state.
     * @return the broadcast state corresponds to the state declaration, this may be empty.
     */
    <K, V> Optional<BroadcastState<K, V>> getStateOptional(
            BroadcastStateDeclaration<K, V> stateDeclaration) throws Exception;

    /**
     * Get the specific broadcast state.
     *
     * @param stateDeclaration of this state.
     * @return the broadcast state corresponds to the state declaration.
     * @throws RuntimeException if the state is not available.
     */
    <K, V> BroadcastState<K, V> getState(BroadcastStateDeclaration<K, V> stateDeclaration)
            throws Exception;
}
