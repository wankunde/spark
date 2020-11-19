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

package org.apache.spark.sql.plugin

import java.lang.management.ManagementFactory
import java.util

import scala.collection.JavaConverters._

import com.codahale.metrics.{Gauge, Metric, MetricSet}
import javax.management.ObjectName

import org.apache.spark.api.plugin._
import org.apache.spark.internal.Logging

class NativeMemoryExporterPlugin extends SparkPlugin with Logging{
  override def driverPlugin(): DriverPlugin = new DriverPlugin {}

  override def executorPlugin(): ExecutorPlugin = new ExecutorPlugin {
    var trackerOpt = Option.empty[NativeMemoryTracker]

    override def init(ctx: PluginContext, extraConf: util.Map[String, String]): Unit = {
      trackerOpt = startTracker(ctx)
    }

    override def shutdown(): Unit = stopTracker(trackerOpt)
  }

  val startTracker = (ctx: PluginContext) => {
    val tracker = new NativeMemoryTracker
    tracker.setDaemon(true)
    ctx.metricRegistry().registerAll(tracker)
    tracker.start()
    Some(tracker)
  }

  def stopTracker(trackerOpt: Option[NativeMemoryTracker]): Unit = trackerOpt match {
    case Some(tracker) =>
      tracker.interrupt()
      tracker.join()

    case _ =>
  }

  class NativeMemoryTracker(pollPeriod: Long = 5000) extends Thread with MetricSet {
    def getMetrics: util.Map[String, Metric] =
      Map[String, Metric](
        "total" -> new Gauge[Int] {
          override def getValue: Int = total
        },
        "javaHeap" -> new Gauge[Int] {
          override def getValue: Int = javaHeap
        },
        "clazz" -> new Gauge[Int] {
          override def getValue: Int = clazz
        },
        "thread" -> new Gauge[Int] {
          override def getValue: Int = thread
        },
        "code" -> new Gauge[Int] {
          override def getValue: Int = code
        },
        "gc" -> new Gauge[Int] {
          override def getValue: Int = gc
        },
        "compiler" -> new Gauge[Int] {
          override def getValue: Int = compiler
        },
        "internal" -> new Gauge[Int] {
          override def getValue: Int = internal
        },
        "symbol" -> new Gauge[Int] {
          override def getValue: Int = symbol
        },
        "nativeMemoryTracking" -> new Gauge[Int] {
          override def getValue: Int = nativeMemoryTracking
        },
        "arenaChunk" -> new Gauge[Int] {
          override def getValue: Int = arenaChunk
        }).asJava

    var total = 0
    var javaHeap = 0
    var clazz = 0
    var thread = 0
    var code = 0
    var gc = 0
    var compiler = 0
    var internal = 0
    var symbol = 0
    var nativeMemoryTracking = 0
    var arenaChunk = 0


    override def run(): Unit = {
      val objectName = new ObjectName("com.sun.management:type=DiagnosticCommand")
      val mbeanServer = ManagementFactory.getPlatformMBeanServer
      val signature = Array[String](classOf[Array[String]].getName)
      val params = new Array[AnyRef](1)

      val patterns = Seq(
        "^(Total):.*reserved=\\d+KB.*committed=(\\d+)KB" r,
        "^-\\s+(.*)\\s+\\(reserved=\\d+KB.*committed=(\\d+)KB\\)" r
      )

      try {
        while (true) {
          val resp = mbeanServer.invoke(objectName, "vmNativeMemory", params, signature)
          resp.asInstanceOf[String].split("\\r?\\n").foreach { line =>
            patterns.foreach { pattern =>
              line match {
                case pattern("Total", committed) => total = committed.toInt
                case pattern("Java Heap", committed) => javaHeap = committed.toInt
                case pattern("Class", committed) => clazz = committed.toInt
                case pattern("Thread", committed) => thread = committed.toInt
                case pattern("Code", committed) => code = committed.toInt
                case pattern("GC", committed) => gc = committed.toInt
                case pattern("Compiler", committed) => compiler = committed.toInt
                case pattern("Internal", committed) => internal = committed.toInt
                case pattern("Symbol", committed) => symbol = committed.toInt
                case pattern("Native Memory Tracking", committed) =>
                  nativeMemoryTracking = committed.toInt
                case pattern("Arena Chunk", committed) => arenaChunk = committed.toInt
                case _ =>
              }
            }
          }
          Thread.sleep(pollPeriod)
        }
      } catch {
        case _: InterruptedException =>
      }
    }
  }
}
