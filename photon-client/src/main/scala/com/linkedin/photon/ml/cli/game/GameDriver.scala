/*
 * Copyright 2017 LinkedIn Corp. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linkedin.photon.ml.cli.game

import org.apache.hadoop.conf.Configuration
import org.apache.spark.SparkContext
import org.slf4j.Logger

import com.linkedin.photon.ml.data.avro.NameAndTermFeatureSetContainer
import com.linkedin.photon.ml.util.Implicits._
import com.linkedin.photon.ml.util._

/**
 * Contains common functions for GAME training and scoring drivers.
 *
 * @param sc The Spark context for the application
 * @param params The input parameters
 * @param logger The logger instance for recording log output
 */
abstract class GameDriver(sc: SparkContext, params: FeatureParams with PalDBIndexMapParams, logger: Logger) {

  protected val hadoopConfiguration: Configuration = sc.hadoopConfiguration

  protected val parallelism: Int = sc.getConf.get("spark.default.parallelism",
    s"${sc.getExecutorStorageStatus.length * 3}").toInt

  /**
   * Builds feature key to index map loaders according to configuration.
   * Also, sets intercept terms ON by default.
   *
   * @return A map of shard id to feature map loader
   * @deprecated This function will be removed in the next major version.
   */
  protected[game] def prepareFeatureMapsDefault(): Map[String, IndexMapLoader] = {

    val allFeatureSectionKeys = params.featureShardIdToFeatureSectionKeysMap.values.reduce(_ ++ _)
    val nameAndTermFeatureSetContainer = NameAndTermFeatureSetContainer.readNameAndTermFeatureSetContainerFromTextFiles(
      params.featureNameAndTermSetInputPath, allFeatureSectionKeys, hadoopConfiguration)

    val featureShardIdToFeatureMapLoader =
      params.featureShardIdToFeatureSectionKeysMap.map { case (shardId, featureSectionKeys) =>
        val featureMap = nameAndTermFeatureSetContainer
          .getFeatureNameAndTermToIndexMap(
            featureSectionKeys,
            params.featureShardIdToInterceptMap.getOrElse(shardId, true))
          .map { case (k, v) => Utils.getFeatureKey(k.name, k.term) -> v }
          .toMap

        val indexMapLoader = new DefaultIndexMapLoader(sc, featureMap)

        (shardId, indexMapLoader)
      }

    featureShardIdToFeatureMapLoader.tap { case (shardId, featureMapLoader) =>
      logger.debug(s"Feature shard ID: $shardId, number of features: ${featureMapLoader.indexMapForDriver().size}")
    }
  }

  /**
   * Builds PalDB off-heap feature name-and-term to index map loaders according to configuration.
   *
   * @return A map of shard id to feature map
   */
  protected[game] def prepareFeatureMapsPalDB(): Map[String, IndexMapLoader] =
    params.featureShardIdToFeatureSectionKeysMap
      .map { case (shardId, _) =>
        (shardId, PalDBIndexMapLoader(sc, params.offHeapIndexMapDir.get, params.offHeapIndexMapNumPartitions, shardId))
      }

  /**
   * Builds feature name-and-term to index maps according to configuration.
   *
   * @return A map of shard id to feature map
   */
  protected[game] def prepareFeatureMaps(): Map[String, IndexMapLoader] =
    params.offHeapIndexMapDir match {
      // If an off-heap map path is specified, use the paldb loader
      case Some(_) => prepareFeatureMapsPalDB()
      // Otherwise, fall back to the default loader
      case _ => prepareFeatureMapsDefault()
    }

  /**
   * Resolves paths for specified date ranges to physical file paths.
   *
   * @param baseDirs The base dirs to which date-specific relative paths will be appended
   * @param dateRangeOpt Optional date range
   * @param daysAgoOpt Optional days-ago specification for date range
   * @return All resolved paths
   */
  protected[game] def pathsForDateRange(
      baseDirs: Seq[String],
      dateRangeOpt: Option[String],
      daysAgoOpt: Option[String]): Seq[String] = (dateRangeOpt, daysAgoOpt) match {

    // Specified as date range
    case (Some(dateRangeSpec), None) =>
      val dateRange = DateRange.fromDates(dateRangeSpec)
      IOUtils.getInputPathsWithinDateRange(baseDirs, dateRange, hadoopConfiguration, errorOnMissing = false)

    // Specified as a range of start days ago - end days ago
    case (None, Some(daysAgoSpec)) =>
      val dateRange = DateRange.fromDaysAgo(daysAgoSpec)
      IOUtils.getInputPathsWithinDateRange(baseDirs, dateRange, hadoopConfiguration, errorOnMissing = false)

    // Both types specified: illegal
    case (Some(_), Some(_)) =>
      throw new IllegalArgumentException(
        "Both date range and days ago given. You must specify date ranges using only one format.")

    // No range specified, just use the train dir
    case (None, None) => baseDirs
  }
}
