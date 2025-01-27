/*
 * Copyright (c) 2020-2025 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.workload.launcher.pipeline.consumer

import io.airbyte.commons.temporal.queue.MessageConsumer
import io.airbyte.config.messages.LauncherInputMessage
import io.airbyte.metrics.lib.MetricAttribute
import io.airbyte.workload.launcher.metrics.CustomMetricPublisher
import io.airbyte.workload.launcher.metrics.MeterFilterFactory
import io.airbyte.workload.launcher.metrics.WorkloadLauncherMetricMetadata
import io.airbyte.workload.launcher.pipeline.LaunchPipeline
import jakarta.inject.Singleton

@Singleton
class LauncherMessageConsumer(
  private val launchPipeline: LaunchPipeline,
  private val metricPublisher: CustomMetricPublisher,
) : MessageConsumer<LauncherInputMessage> {
  override fun consume(input: LauncherInputMessage) {
    if (input.startTimeMs != null) {
      val timeElapsed = System.currentTimeMillis() - input.startTimeMs!!
      metricPublisher.gauge(
        WorkloadLauncherMetricMetadata.PRODUCER_TO_CONSUMER_LATENCY_MS,
        timeElapsed,
        { it.toDouble() },
        MetricAttribute(MeterFilterFactory.WORKLOAD_TYPE_TAG, input.workloadType.toString()),
      )
    }

    launchPipeline.accept(
      LauncherInput(
        workloadId = input.workloadId,
        workloadInput = input.workloadInput,
        labels = input.labels,
        logPath = input.logPath,
        mutexKey = input.mutexKey,
        workloadType = input.workloadType,
        startTimeMs = input.startTimeMs,
        autoId = input.autoId,
      ),
    )
  }
}
