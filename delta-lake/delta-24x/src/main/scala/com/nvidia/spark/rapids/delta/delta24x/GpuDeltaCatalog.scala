/*
 * Copyright (c) 2023, NVIDIA CORPORATION.
 *
 * This file was derived from DeltaDataSource.scala in the
 * Delta Lake project at https://github.com/delta-io/delta.
 *
 * Copyright (2021) The Delta Lake Project Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nvidia.spark.rapids.delta.delta24x

import com.nvidia.spark.rapids.RapidsConf
import java.util

import org.apache.spark.sql.{DataFrame, SaveMode, SparkSession}
import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.catalyst.catalog.CatalogTable
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.connector.catalog.{Identifier, StagedTable, Table}
import org.apache.spark.sql.connector.expressions.Transform
import org.apache.spark.sql.delta.catalog.DeltaCatalog
import org.apache.spark.sql.delta.commands.TableCreationModes
import org.apache.spark.sql.delta.metering.DeltaLogging
import org.apache.spark.sql.delta.rapids.GpuDeltaCatalogBase
import org.apache.spark.sql.delta.rapids.delta24x.GpuCreateDeltaTableCommand
import org.apache.spark.sql.execution.command.LeafRunnableCommand
import org.apache.spark.sql.rapids.execution.ShimTrampolineUtil
import org.apache.spark.sql.types.StructType

class GpuDeltaCatalog(
    override val cpuCatalog: DeltaCatalog,
    override val rapidsConf: RapidsConf)
  extends GpuDeltaCatalogBase with DeltaLogging {

  override val spark: SparkSession = cpuCatalog.spark

  override protected def buildGpuCreateDeltaTableCommand(
      rapidsConf: RapidsConf,
      table: CatalogTable,
      existingTableOpt: Option[CatalogTable],
      mode: SaveMode,
      query: Option[LogicalPlan],
      operation: TableCreationModes.CreationMode,
      tableByPath: Boolean): LeafRunnableCommand = {
    GpuCreateDeltaTableCommand(
      table,
      existingTableOpt,
      mode,
      query,
      operation,
      tableByPath = tableByPath
    )(rapidsConf)
  }

  override protected def getExistingTableIfExists(table: TableIdentifier): Option[CatalogTable] = {
    cpuCatalog.getExistingTableIfExists(table)
  }

  override protected def verifyTableAndSolidify(
      tableDesc: CatalogTable,
      query: Option[LogicalPlan]): CatalogTable = {
    cpuCatalog.verifyTableAndSolidify(tableDesc, query)
  }

  override protected def createGpuStagedDeltaTableV2(
      ident: Identifier,
      schema: StructType,
      partitions: Array[Transform],
      properties: util.Map[String, String],
      operation: TableCreationModes.CreationMode): StagedTable = {
    new GpuStagedDeltaTableV2WithLogging(ident, schema, partitions, properties, operation)
  }

  override def loadTable(ident: Identifier, timestamp: Long): Table = {
    cpuCatalog.loadTable(ident, timestamp)
  }

  override def loadTable(ident: Identifier, version: String): Table = {
    cpuCatalog.loadTable(ident, version)
  }

  /**
   * Creates a Delta table using GPU for writing the data
   *
   * @param ident              The identifier of the table
   * @param schema             The schema of the table
   * @param partitions         The partition transforms for the table
   * @param allTableProperties The table properties that configure the behavior of the table or
   *                           provide information about the table
   * @param writeOptions       Options specific to the write during table creation or replacement
   * @param sourceQuery        A query if this CREATE request came from a CTAS or RTAS
   * @param operation          The specific table creation mode, whether this is a
   *                           Create/Replace/Create or Replace
   */
  override def createDeltaTable(
      ident: Identifier,
      schema: StructType,
      partitions: Array[Transform],
      allTableProperties: util.Map[String, String],
      writeOptions: Map[String, String],
      sourceQuery: Option[DataFrame],
      operation: TableCreationModes.CreationMode
  ): Table = recordFrameProfile(
    "DeltaCatalog", "createDeltaTable") {
    super.createDeltaTable(
      ident,
      schema,
      partitions,
      allTableProperties,
      writeOptions,
      sourceQuery,
      operation)
  }

  override def createTable(
      ident: Identifier,
      columns: Array[org.apache.spark.sql.connector.catalog.Column],
      partitions: Array[Transform],
      properties: util.Map[String, String]): Table = {
    createTable(
      ident,
      ShimTrampolineUtil.v2ColumnsToStructType(columns),
      partitions,
      properties)
  }

  override def createTable(
      ident: Identifier,
      schema: StructType,
      partitions: Array[Transform],
      properties: util.Map[String, String]): Table =
    recordFrameProfile("DeltaCatalog", "createTable") {
      super.createTable(ident, schema, partitions, properties)
    }

  override def stageReplace(
      ident: Identifier,
      schema: StructType,
      partitions: Array[Transform],
      properties: util.Map[String, String]): StagedTable =
    recordFrameProfile("DeltaCatalog", "stageReplace") {
      super.stageReplace(ident, schema, partitions, properties)
    }

  override def stageCreateOrReplace(
      ident: Identifier,
      schema: StructType,
      partitions: Array[Transform],
      properties: util.Map[String, String]): StagedTable =
    recordFrameProfile("DeltaCatalog", "stageCreateOrReplace") {
      super.stageCreateOrReplace(ident, schema, partitions, properties)
    }

  override def stageCreate(
      ident: Identifier,
      schema: StructType,
      partitions: Array[Transform],
      properties: util.Map[String, String]): StagedTable =
    recordFrameProfile("DeltaCatalog", "stageCreate") {
      super.stageCreate(ident, schema, partitions, properties)
    }

  /**
   * A staged Delta table, which creates a HiveMetaStore entry and appends data if this was a
   * CTAS/RTAS command. We have a ugly way of using this API right now, but it's the best way to
   * maintain old behavior compatibility between Databricks Runtime and OSS Delta Lake.
   */
  protected class GpuStagedDeltaTableV2WithLogging(
      ident: Identifier,
      schema: StructType,
      partitions: Array[Transform],
      properties: util.Map[String, String],
      operation: TableCreationModes.CreationMode)
    extends GpuStagedDeltaTableV2(ident, schema, partitions, properties, operation) {

    override def commitStagedChanges(): Unit = recordFrameProfile(
      "DeltaCatalog", "commitStagedChanges") {
      super.commitStagedChanges()
    }
  }
}
