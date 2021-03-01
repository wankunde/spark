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

package org.apache.spark.sql.connector.catalog

import scala.collection.mutable
import scala.util.control.NonFatal

import org.apache.spark.internal.Logging
import org.apache.spark.sql.catalyst.analysis.NoSuchNamespaceException
import org.apache.spark.sql.catalyst.catalog.SessionCatalog
import org.apache.spark.sql.internal.SQLConf

/**
 * A thread-safe manager for [[CatalogPlugin]]s. It tracks all the registered catalogs, and allow
 * the caller to look up a catalog by name.
 *
 * There are still many commands (e.g. ANALYZE TABLE) that do not support v2 catalog API. They
 * ignore the current catalog and blindly go to the v1 `SessionCatalog`. To avoid tracking current
 * namespace in both `SessionCatalog` and `CatalogManger`, we let `CatalogManager` to set/get
 * current database of `SessionCatalog` when the current catalog is the session catalog.
 *
 * {{{
 * 背景:
 * 在spark2 中已经有了一套spark catalog的设计，通过参数 spark.sql.catalogImplementation 指定要使用的catalog
 * 默认有2种catalog: in-memory 和 hive。
 * SparkSession.enableHiveSupport() 判断如果spark运行环境中存在hive包，会自动设置catalog为hive用于访问hive metastore
 *
 * Spark3 catalog设计:
 * 1 BaseSessionStateBuilder 中初始化
 * val v2SessionCatalog = new V2SessionCatalog(catalog, conf)
 * val catalogManager = new CatalogManager(conf, v2SessionCatalog, catalog) // 该变量会传递给内部的 analyzer
 * 2 HiveSessionStateBuilder 中切换catalog为HiveSessionCatalog
 * 3. Analyzer 继承 LookupCatalog，LookupCatalog 中引用了 CatalogManager.currentCatalog 进行管理
 * 4. currentCatalog 表示当前正在使用的catalog, 默认为 spark_catalog, 通过setCurrentCatalog() 方法实现自定义
 * 5. 如果catalog名字为spark_catalog, 返回 v2SessionCatalog
 * 6. v2SessionCatalog 先判断 spark.sql.catalog.spark_catalog是否有被覆盖，默认为空，此时返回属性 defaultSessionCatalog
 * 7. 如果6中有自定义catalog，调用函数 loadV2SessionCatalog() 进行加载和初始化
 *
 * DeltaCatalog
 * 通过实现 CatalogExtension接口，可以轻松的对catalog的操作进行aop切面，例如createTable() 方法如果provider是delta会
 * 直接代理执行createDeltaTable 方法，该方法会执行 CreateDeltaTableCommand 命令内部实际上已经开始执行delta transaction了，
 * 也就是会产出对应的数据目录和日志
 * }}}
 *
 */
// TODO: all commands should look up table from the current catalog. The `SessionCatalog` doesn't
//       need to track current database at all.
private[sql]
class CatalogManager(
    conf: SQLConf,
    defaultSessionCatalog: CatalogPlugin,
    val v1SessionCatalog: SessionCatalog) extends Logging {
  import CatalogManager.SESSION_CATALOG_NAME

  private val catalogs = mutable.HashMap.empty[String, CatalogPlugin]

  def catalog(name: String): CatalogPlugin = synchronized {
    if (name.equalsIgnoreCase(SESSION_CATALOG_NAME)) {
      v2SessionCatalog
    } else {
      catalogs.getOrElseUpdate(name, Catalogs.load(name, conf))
    }
  }

  def isCatalogRegistered(name: String): Boolean = {
    try {
      catalog(name)
      true
    } catch {
      case _: CatalogNotFoundException => false
    }
  }

  private def loadV2SessionCatalog(): CatalogPlugin = {
    Catalogs.load(SESSION_CATALOG_NAME, conf) match {
      case extension: CatalogExtension =>
        extension.setDelegateCatalog(defaultSessionCatalog)
        extension
      case other => other
    }
  }

  /**
   * If the V2_SESSION_CATALOG config is specified, we try to instantiate the user-specified v2
   * session catalog. Otherwise, return the default session catalog.
   *
   * This catalog is a v2 catalog that delegates to the v1 session catalog. it is used when the
   * session catalog is responsible for an identifier, but the source requires the v2 catalog API.
   * This happens when the source implementation extends the v2 TableProvider API and is not listed
   * in the fallback configuration, spark.sql.sources.write.useV1SourceList
   */
  private[sql] def v2SessionCatalog: CatalogPlugin = {
    conf.getConf(SQLConf.V2_SESSION_CATALOG_IMPLEMENTATION).map { customV2SessionCatalog =>
      try {
        catalogs.getOrElseUpdate(SESSION_CATALOG_NAME, loadV2SessionCatalog())
      } catch {
        case NonFatal(_) =>
          logError(
            "Fail to instantiate the custom v2 session catalog: " + customV2SessionCatalog)
          defaultSessionCatalog
      }
    }.getOrElse(defaultSessionCatalog)
  }

  private var _currentNamespace: Option[Array[String]] = None

  def currentNamespace: Array[String] = synchronized {
    _currentNamespace.getOrElse {
      if (currentCatalog.name() == SESSION_CATALOG_NAME) {
        Array(v1SessionCatalog.getCurrentDatabase)
      } else {
        currentCatalog.defaultNamespace()
      }
    }
  }

  def setCurrentNamespace(namespace: Array[String]): Unit = synchronized {
    if (currentCatalog.name() == SESSION_CATALOG_NAME) {
      if (namespace.length != 1) {
        throw new NoSuchNamespaceException(namespace)
      }
      v1SessionCatalog.setCurrentDatabase(namespace.head)
    } else {
      _currentNamespace = Some(namespace)
    }
  }

  private var _currentCatalogName: Option[String] = None

  def currentCatalog: CatalogPlugin = synchronized {
    catalog(_currentCatalogName.getOrElse(conf.getConf(SQLConf.DEFAULT_CATALOG)))
  }

  def setCurrentCatalog(catalogName: String): Unit = synchronized {
    // `setCurrentCatalog` is noop if it doesn't switch to a different catalog.
    if (currentCatalog.name() != catalogName) {
      _currentCatalogName = Some(catalogName)
      _currentNamespace = None
      // Reset the current database of v1 `SessionCatalog` when switching current catalog, so that
      // when we switch back to session catalog, the current namespace definitely is ["default"].
      v1SessionCatalog.setCurrentDatabase(SessionCatalog.DEFAULT_DATABASE)
    }
  }

  // Clear all the registered catalogs. Only used in tests.
  private[sql] def reset(): Unit = synchronized {
    catalogs.clear()
    _currentNamespace = None
    _currentCatalogName = None
    v1SessionCatalog.setCurrentDatabase(SessionCatalog.DEFAULT_DATABASE)
  }
}

private[sql] object CatalogManager {
  val SESSION_CATALOG_NAME: String = "spark_catalog"
}
