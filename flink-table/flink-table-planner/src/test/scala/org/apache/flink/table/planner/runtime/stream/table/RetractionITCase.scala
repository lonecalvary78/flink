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
package org.apache.flink.table.planner.runtime.stream.table

import org.apache.flink.table.api._
import org.apache.flink.table.api.bridge.scala.{dataStreamConversions, tableConversions}
import org.apache.flink.table.planner.runtime.utils.{StreamingEnvUtil, StreamingWithStateTestBase, TestingRetractSink}
import org.apache.flink.table.planner.runtime.utils.StreamingEnvUtil.fromCollection
import org.apache.flink.table.planner.runtime.utils.StreamingWithStateTestBase.StateBackendMode
import org.apache.flink.table.planner.utils.TableFunc0
import org.apache.flink.testutils.junit.extensions.parameterized.ParameterizedTestExtension
import org.apache.flink.types.Row

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.ExtendWith

/** tests for retraction */
@ExtendWith(Array(classOf[ParameterizedTestExtension]))
class RetractionITCase(mode: StateBackendMode) extends StreamingWithStateTestBase(mode) {
  // input data
  val data = List(
    ("Hello", 1),
    ("word", 1),
    ("Hello", 1),
    ("bark", 1),
    ("bark", 1),
    ("bark", 1),
    ("bark", 1),
    ("bark", 1),
    ("bark", 1),
    ("flink", 1)
  )

  // keyed groupby + keyed groupby
  @TestTemplate
  def testWordCount(): Unit = {
    val stream = StreamingEnvUtil.fromCollection(env, data)
    val table = stream.toTable(tEnv, 'word, 'num)
    val resultTable = table
      .groupBy('word)
      .select('num.sum.as('count))
      .groupBy('count)
      .select('count, 'count.count.as('frequency))

    val sink = new TestingRetractSink
    resultTable.toRetractStream[Row].addSink(sink)
    env.execute()

    val expected = Seq("1,2", "2,1", "6,1")
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected.sorted)
  }

  // keyed groupby + non-keyed groupby
  @TestTemplate
  def testGroupByAndNonKeyedGroupBy(): Unit = {
    val stream = StreamingEnvUtil.fromCollection(env, data)
    val table = stream.toTable(tEnv, 'word, 'num)
    val resultTable = table
      .groupBy('word)
      .select('word.as('word), 'num.sum.as('cnt))
      .select('cnt.sum)

    val sink = new TestingRetractSink
    resultTable.toRetractStream[Row].addSink(sink).setParallelism(1)
    env.execute()

    val expected = Seq("10")
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected.sorted)
  }

  // non-keyed groupby + keyed groupby
  @TestTemplate
  def testNonKeyedGroupByAndGroupBy(): Unit = {
    val stream = StreamingEnvUtil.fromCollection(env, data)
    val table = stream.toTable(tEnv, 'word, 'num)
    val resultTable = table
      .select('num.sum.as('count))
      .groupBy('count)
      .select('count, 'count.count)

    val sink = new TestingRetractSink
    resultTable.toRetractStream[Row].addSink(sink)
    env.execute()

    val expected = Seq("10,1")
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected.sorted)
  }

  // test unique process, if the current output message of unbounded groupby equals the
  // previous message, unbounded groupby will ignore the current one.
  @TestTemplate
  def testUniqueProcess(): Unit = {
    // data input
    val data = List(
      (1, 1L),
      (2, 2L),
      (3, 3L),
      (3, 3L),
      (4, 1L),
      (4, 0L),
      (4, 0L),
      (4, 0L),
      (5, 1L),
      (6, 6L),
      (6, 6L),
      (6, 6L),
      (7, 8L)
    )

    val stream = StreamingEnvUtil.fromCollection(env, data)
    val table = stream.toTable(tEnv, 'pk, 'value)
    val resultTable = table
      .groupBy('pk)
      .select('pk.as('pk), 'value.sum.as('sum))
      .groupBy('sum)
      .select('sum, 'pk.count.as('count))

    val sink = new TestingRetractSink
    resultTable.toRetractStream[Row].addSink(sink)
    env.execute()

    val expected = Seq(
      "(true,1,1)",
      "(true,2,1)",
      "(true,3,1)",
      "(false,3,1)",
      "(true,6,1)",
      "(false,1,1)",
      "(true,1,2)",
      "(false,1,2)",
      "(true,1,3)",
      "(false,6,1)",
      "(true,6,2)",
      "(false,6,2)",
      "(true,6,1)",
      "(true,12,1)",
      "(false,12,1)",
      "(true,18,1)",
      "(true,8,1)"
    )
    assertThat(sink.getRawResults.sorted).isEqualTo(expected.sorted)
  }

  // correlate should handle retraction messages correctly
  @TestTemplate
  def testCorrelate(): Unit = {
    val func0 = new TableFunc0

    val stream = fromCollection(env, data)
    val table = stream.toTable(tEnv, 'word, 'num)
    val resultTable = table
      .groupBy('word)
      .select('word.as('word), 'num.sum.as('cnt))
      .leftOuterJoinLateral(func0('word))
      .groupBy('cnt)
      .select('cnt, 'word.count.as('frequency))

    val sink = new TestingRetractSink
    resultTable.toRetractStream[Row].addSink(sink)
    env.execute()

    val expected = Seq("1,2", "2,1", "6,1")
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected.sorted)
  }
}
