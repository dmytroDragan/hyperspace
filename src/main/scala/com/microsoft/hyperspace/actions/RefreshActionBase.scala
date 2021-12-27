/*
 * Copyright (2020) The Hyperspace Project Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.microsoft.hyperspace.actions

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.types.StructType

import com.microsoft.hyperspace.{Hyperspace, HyperspaceException}
import com.microsoft.hyperspace.actions.Constants.States.{ACTIVE, REFRESHING}
import com.microsoft.hyperspace.index._
import com.microsoft.hyperspace.util.ResolverUtils.ResolvedColumn

/**
 * Base abstract class containing common code for different types of index refresh actions.
 *
 * @param spark SparkSession
 * @param logManager Index LogManager for index being refreshed
 * @param dataManager Index DataManager for index being refreshed
 */
// TODO: This class depends directly on LogEntry. This should be updated such that
//   it works with IndexLogEntry only. (for example, this class can take in
//   derivedDataset specific logic for refreshing).
private[actions] abstract class RefreshActionBase(
    spark: SparkSession,
    final override protected val logManager: IndexLogManager,
    dataManager: IndexDataManager)
    extends CreateActionBase(dataManager)
    with Action {
  private lazy val previousLogEntry: LogEntry = {
    logManager.getLog(baseId).getOrElse {
      throw HyperspaceException("LogEntry must exist for refresh operation")
    }
  }

  protected lazy val previousIndexLogEntry = previousLogEntry.asInstanceOf[IndexLogEntry]

  override val fileIdTracker = previousIndexLogEntry.fileIdTracker

  // Reconstruct a df from schema
  protected lazy val df = {
    val relations = previousIndexLogEntry.relations
    val relationMetadata = Hyperspace
      .getContext(spark)
      .sourceProviderManager
      .getRelationMetadata(relations.head)
    val latestRelation = relationMetadata.refresh()
    val dataSchema = latestRelation.dataSchema
    val df = {
      if (relationMetadata.canSupportUserSpecifiedSchema) spark.read.schema(dataSchema)
      else spark.read
    }.format(latestRelation.fileFormat)
      .options(latestRelation.options)
    // Due to the difference in how the "path" option is set: https://github.com/apache/spark/
    // blob/ef1441b56c5cab02335d8d2e4ff95cf7e9c9b9ca/sql/core/src/main/scala/org/apache/spark/
    // sql/DataFrameReader.scala#L197
    // load() with a single parameter needs to be handled differently.
    if (latestRelation.rootPaths.size == 1) {
      df.load(latestRelation.rootPaths.head)
    } else {
      df.load(latestRelation.rootPaths: _*)
    }
  }

  final override val transientState: String = REFRESHING

  final override val finalState: String = ACTIVE

  override def validate(): Unit = {
    if (!previousIndexLogEntry.state.equalsIgnoreCase(ACTIVE)) {
      throw HyperspaceException(
        s"Refresh is only supported in $ACTIVE state. " +
          s"Current index state is ${previousIndexLogEntry.state}")
    }
  }

  /**
   * Compare list of source data files from previous IndexLogEntry to list
   * of current source data files, validate fileInfo for existing files and
   * identify deleted source data files.
   * Finally, append the previously known deleted files to the result. These
   * are the files for which the index was never updated in the past.
   */
  protected lazy val deletedFiles: Seq[FileInfo] = {
    val relation = previousIndexLogEntry.relations.head
    val originalFiles = relation.data.properties.content.fileInfos

    (originalFiles -- currentFiles).toSeq
  }

  /**
   * Retrieve the source file list from reconstructed "df" for refresh.
   * Build Set[FileInfo] to compare the source file list with the previous index version.
   */
  protected lazy val currentFiles: Set[FileInfo] = {
    RelationUtils
      .getRelation(spark, df.queryExecution.optimizedPlan)
      .allFiles
      .map(f => FileInfo(f, fileIdTracker.addFile(f), asFullPath = true))
      .toSet
  }

  /**
   * Compare list of source data files from previous IndexLogEntry to list
   * of current source data files, validate fileInfo for existing files and
   * identify newly appended source data files.
   * Finally, append the previously known appended files to the result. These
   * are the files for which index was never updated in the past.
   */
  protected lazy val appendedFiles: Seq[FileInfo] = {
    val relation = previousIndexLogEntry.relations.head
    val originalFiles = relation.data.properties.content.fileInfos

    (currentFiles -- originalFiles).toSeq
  }
}
