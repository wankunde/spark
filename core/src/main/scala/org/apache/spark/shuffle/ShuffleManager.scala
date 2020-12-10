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

package org.apache.spark.shuffle

import org.apache.spark.{ShuffleDependency, TaskContext}

/**
 * Pluggable interface for shuffle systems. A ShuffleManager is created in SparkEnv on the driver
 * and on each executor, based on the spark.shuffle.manager setting. The driver registers shuffles
 * with it, and executors (or tasks running locally in the driver) can ask to read and write data.
 *
 * NOTE: this will be instantiated by SparkEnv so its constructor can take a SparkConf and
 * boolean isDriver as parameters.
 *
 * {{{
 * 1. 在sparkEnv对象实例化的时候，根据参数 spark.shuffle.manager (sort / tungsten-sort) 来决定
 * 2. 现在只有一个实现类: SortShuffleManager
 * 3. 在 ShuffleWriteProcessor.write() 方法(MapTask)中，获取writer实例，负责具体的数据写入
 * }}}
 *
 */
private[spark] trait ShuffleManager {

  /**
   * Register a shuffle with the manager and obtain a handle for it to pass to tasks.
   * {{{
   *   BypassMergeSortShuffleHandle: map端没有combine，且reduce num<=200
   *   SerializedShuffleHandle
   *      1. map端没有combine
   *      2. serializer 支持relocation(KryoSerializer and Spark SQL's custom serializers)
   *      3. partition个数小于 1 << 24
   *   BaseShuffleHandle
   * }}}
   */
  def registerShuffle[K, V, C](
      shuffleId: Int,
      dependency: ShuffleDependency[K, V, C]): ShuffleHandle

  /**
   * Get a writer for a given partition. Called on executors by map tasks.
   * {{{
   *   核心还是根据handle确定使用哪种Writer
   *   SerializedShuffleHandle -> UnsafeShuffleWriter
   *   BypassMergeSortShuffleHandle -> BypassMergeSortShuffleWriter
   *   BaseShuffleHandle -> SortShuffleWriter
   * }}}
   * @param handle
   * @param mapId
   * @param context
   * @param metrics
   * @tparam K
   * @tparam V
   * @return
   */
  def getWriter[K, V](
      handle: ShuffleHandle,
      mapId: Long,
      context: TaskContext,
      metrics: ShuffleWriteMetricsReporter): ShuffleWriter[K, V]

  /**
   * Get a reader for a range of reduce partitions (startPartition to endPartition-1, inclusive).
   * Called on executors by reduce tasks.
   */
  def getReader[K, C](
      handle: ShuffleHandle,
      startPartition: Int,
      endPartition: Int,
      context: TaskContext,
      metrics: ShuffleReadMetricsReporter): ShuffleReader[K, C]

  /**
   * Get a reader for a range of reduce partitions (startPartition to endPartition-1, inclusive) to
   * read from map output (startMapIndex to endMapIndex - 1, inclusive).
   * Called on executors by reduce tasks.
   */
  def getReaderForRange[K, C](
      handle: ShuffleHandle,
      startMapIndex: Int,
      endMapIndex: Int,
      startPartition: Int,
      endPartition: Int,
      context: TaskContext,
      metrics: ShuffleReadMetricsReporter): ShuffleReader[K, C]

  /**
   * Remove a shuffle's metadata from the ShuffleManager.
   * @return true if the metadata removed successfully, otherwise false.
   */
  def unregisterShuffle(shuffleId: Int): Boolean

  /**
   * Return a resolver capable of retrieving shuffle block data based on block coordinates.
   */
  def shuffleBlockResolver: ShuffleBlockResolver

  /** Shut down this ShuffleManager. */
  def stop(): Unit
}
