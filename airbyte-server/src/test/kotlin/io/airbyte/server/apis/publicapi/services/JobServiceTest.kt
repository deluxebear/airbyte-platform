/*
 * Copyright (c) 2020-2025 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.server.apis.publicapi.services

import io.airbyte.api.problems.throwable.generated.StateConflictProblem
import io.airbyte.api.problems.throwable.generated.TryAgainLaterConflictProblem
import io.airbyte.commons.server.errors.ValueConflictKnownException
import io.airbyte.commons.server.handlers.SchedulerHandler
import io.airbyte.data.services.ApplicationService
import io.airbyte.server.apis.publicapi.errorHandlers.JOB_NOT_RUNNING_MESSAGE
import io.micronaut.test.annotation.MockBean
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.mockk.every
import io.mockk.mockk
import jakarta.inject.Inject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

@MicronautTest
class JobServiceTest {
  @Inject
  private lateinit var jobService: JobServiceImpl

  private val connectionId = UUID.randomUUID()

  @Inject
  lateinit var schedulerHandler: SchedulerHandler

  @MockBean(SchedulerHandler::class)
  fun schedulerHandler(): SchedulerHandler = mockk()

  @MockBean(ApplicationService::class)
  fun applicationService(): ApplicationService = mockk<ApplicationService>()

  @Test
  fun `test sync already running value conflict known exception`() {
    val failureReason = "A sync is already running for: $connectionId"
    every { schedulerHandler.syncConnection(any()) } throws
      ValueConflictKnownException(failureReason)

    assertThrows<TryAgainLaterConflictProblem>(failureReason) { jobService.sync(connectionId) }
  }

  @Test
  fun `test sync already running illegal state exception`() {
    val failureReason = "A sync is already running for: $connectionId"
    every { schedulerHandler.syncConnection(any()) } throws
      IllegalStateException(failureReason)

    assertThrows<StateConflictProblem>(failureReason) { jobService.sync(connectionId) }
  }

  @Test
  fun `test cancel non-running sync`() {
    // This is a real error message that we can get.
    // Happens because after canceling a job we go to the job persistence to fetch it but have no ID

    val couldNotFindJobMessage = "Could not find job with id: -1"
    every { schedulerHandler.syncConnection(any()) } throws RuntimeException(couldNotFindJobMessage)
    assertThrows<StateConflictProblem>(JOB_NOT_RUNNING_MESSAGE) { jobService.sync(connectionId) }

    val failureReason = "Failed to cancel job with id: -1"
    every { schedulerHandler.syncConnection(any()) } throws IllegalStateException(failureReason)
    assertThrows<StateConflictProblem>(JOB_NOT_RUNNING_MESSAGE) { jobService.sync(connectionId) }
  }
}
