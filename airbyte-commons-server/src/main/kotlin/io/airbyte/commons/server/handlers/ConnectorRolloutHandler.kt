/*
 * Copyright (c) 2023 Airbyte, Inc., all rights reserved.
 */
package io.airbyte.commons.server.handlers

import com.google.common.annotations.VisibleForTesting
import io.airbyte.api.model.generated.ConnectorRolloutActorSelectionInfo
import io.airbyte.api.model.generated.ConnectorRolloutActorSyncInfo
import io.airbyte.api.model.generated.ConnectorRolloutFinalizeRequestBody
import io.airbyte.api.model.generated.ConnectorRolloutManualFinalizeRequestBody
import io.airbyte.api.model.generated.ConnectorRolloutManualFinalizeResponse
import io.airbyte.api.model.generated.ConnectorRolloutManualRolloutRequestBody
import io.airbyte.api.model.generated.ConnectorRolloutManualStartRequestBody
import io.airbyte.api.model.generated.ConnectorRolloutRead
import io.airbyte.api.model.generated.ConnectorRolloutRequestBody
import io.airbyte.api.model.generated.ConnectorRolloutStartRequestBody
import io.airbyte.api.model.generated.ConnectorRolloutState
import io.airbyte.api.model.generated.ConnectorRolloutStateTerminal
import io.airbyte.api.model.generated.ConnectorRolloutStrategy
import io.airbyte.api.model.generated.ConnectorRolloutUpdateStateRequestBody
import io.airbyte.api.problems.model.generated.ProblemMessageData
import io.airbyte.api.problems.throwable.generated.ConnectorRolloutInvalidRequestProblem
import io.airbyte.api.problems.throwable.generated.ConnectorRolloutNotEnoughActorsProblem
import io.airbyte.config.ConnectorEnumRolloutState
import io.airbyte.config.ConnectorEnumRolloutStrategy
import io.airbyte.config.ConnectorRollout
import io.airbyte.config.ConnectorRolloutFinalState
import io.airbyte.config.persistence.UserPersistence
import io.airbyte.connector.rollout.client.ConnectorRolloutClient
import io.airbyte.connector.rollout.shared.ActorSelectionInfo
import io.airbyte.connector.rollout.shared.Constants.AIRBYTE_API_CLIENT_EXCEPTION
import io.airbyte.connector.rollout.shared.RolloutActorFinder
import io.airbyte.connector.rollout.shared.models.ConnectorRolloutActivityInputFinalize
import io.airbyte.connector.rollout.shared.models.ConnectorRolloutActivityInputRollout
import io.airbyte.connector.rollout.shared.models.ConnectorRolloutActivityInputStart
import io.airbyte.data.exceptions.InvalidRequestException
import io.airbyte.data.helpers.ActorDefinitionVersionUpdater
import io.airbyte.data.services.ActorDefinitionService
import io.airbyte.data.services.ConnectorRolloutService
import io.micronaut.cache.annotation.Cacheable
import io.micronaut.transaction.annotation.Transactional
import io.temporal.client.WorkflowUpdateException
import io.temporal.failure.ApplicationFailure
import jakarta.inject.Inject
import jakarta.inject.Singleton
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * OperationsHandler. Javadocs suppressed because api docs should be used as source of truth.
 */
@Singleton
open class ConnectorRolloutHandler
  @Inject
  constructor(
    private val connectorRolloutService: ConnectorRolloutService,
    private val actorDefinitionService: ActorDefinitionService,
    private val actorDefinitionVersionUpdater: ActorDefinitionVersionUpdater,
    private val connectorRolloutClient: ConnectorRolloutClient,
    private val userPersistence: UserPersistence,
    private val rolloutActorFinder: RolloutActorFinder,
  ) {
    @VisibleForTesting
    open fun buildConnectorRolloutRead(
      connectorRollout: ConnectorRollout,
      withActorSyncAndSelectionInfo: Boolean,
    ): ConnectorRolloutRead {
      val rolloutStrategy = connectorRollout.rolloutStrategy?.let { ConnectorRolloutStrategy.fromValue(it.toString()) }

      val actorDefinitionVersion = actorDefinitionService.getActorDefinitionVersion(connectorRollout.releaseCandidateVersionId)
      var rollout =
        ConnectorRolloutRead()
          .id(connectorRollout.id)
          .dockerRepository(actorDefinitionVersion.dockerRepository)
          .dockerImageTag(actorDefinitionVersion.dockerImageTag)
          .workflowRunId(connectorRollout.workflowRunId)
          .actorDefinitionId(connectorRollout.actorDefinitionId)
          .releaseCandidateVersionId(connectorRollout.releaseCandidateVersionId)
          .initialVersionId(connectorRollout.initialVersionId)
          .state(ConnectorRolloutState.fromString(connectorRollout.state.toString()))
          .initialRolloutPct(connectorRollout.initialRolloutPct?.toInt())
          .currentTargetRolloutPct(connectorRollout.currentTargetRolloutPct?.toInt())
          .finalTargetRolloutPct(connectorRollout.finalTargetRolloutPct?.toInt())
          .hasBreakingChanges(connectorRollout.hasBreakingChanges)
          .rolloutStrategy(rolloutStrategy)
          .maxStepWaitTimeMins(connectorRollout.maxStepWaitTimeMins?.toInt())
          .updatedAt(connectorRollout.updatedAt?.let { unixTimestampToOffsetDateTime(it) })
          .createdAt(connectorRollout.createdAt?.let { unixTimestampToOffsetDateTime(it) })
          .expiresAt(connectorRollout.expiresAt?.let { unixTimestampToOffsetDateTime(it) })
          .errorMsg(connectorRollout.errorMsg)
          .failedReason(connectorRollout.failedReason)
          .updatedBy(
            connectorRollout.rolloutStrategy?.let { strategy ->
              connectorRollout.updatedBy?.let { updatedBy ->
                getUpdatedBy(strategy, updatedBy)
              }
            },
          ).completedAt(connectorRollout.completedAt?.let { unixTimestampToOffsetDateTime(it) })
          .expiresAt(connectorRollout.expiresAt?.let { unixTimestampToOffsetDateTime(it) })
          .errorMsg(connectorRollout.errorMsg)
          .failedReason(connectorRollout.failedReason)

      if (withActorSyncAndSelectionInfo) {
        rollout = rollout.actorSelectionInfo(getPinnedActorInfo(connectorRollout.id)).actorSyncs(getActorSyncInfo(connectorRollout.id))
      }
      return rollout
    }

    @VisibleForTesting
    open fun validateRolloutActorDefinitionId(
      dockerRepository: String,
      dockerImageTag: String,
      actorDefinitionId: UUID,
    ) {
      val actorDefinitionVersion =
        actorDefinitionService
          .getActorDefinitionVersion(actorDefinitionId, dockerImageTag)
          .orElseThrow {
            throw ConnectorRolloutInvalidRequestProblem(
              ProblemMessageData().message(
                "Actor definition version not found for actor definition id: $actorDefinitionId and docker image tag: $dockerImageTag",
              ),
            )
          }

      if (actorDefinitionVersion.dockerRepository != dockerRepository || actorDefinitionVersion.dockerImageTag != dockerImageTag) {
        throw ConnectorRolloutInvalidRequestProblem(
          ProblemMessageData().message(
            "Actor definition ID does not match docker repository: $dockerRepository and docker image tag: $dockerImageTag",
          ),
        )
      }
    }

    open fun getOrCreateAndValidateManualStartInput(
      dockerRepository: String,
      actorDefinitionId: UUID,
      dockerImageTag: String,
      updatedBy: UUID,
      rolloutStrategy: ConnectorRolloutStrategy,
      initialRolloutPct: Int?,
      finalTargetRolloutPct: Int?,
    ): ConnectorRollout {
      val actorDefinitionVersion =
        actorDefinitionService.getActorDefinitionVersion(
          actorDefinitionId,
          dockerImageTag,
        )
      if (actorDefinitionVersion.isEmpty) {
        throw ConnectorRolloutInvalidRequestProblem(
          ProblemMessageData().message(
            "Actor definition version not found for actor definition id: $actorDefinitionId " +
              "and docker image tag: $dockerImageTag",
          ),
        )
      }
      if (actorDefinitionVersion.get().dockerRepository != dockerRepository) {
        throw ConnectorRolloutInvalidRequestProblem(
          ProblemMessageData().message(
            "Actor definition version does not match docker repository: $dockerRepository ",
          ),
        )
      }
      val connectorRollouts: List<ConnectorRollout> =
        connectorRolloutService.listConnectorRollouts(
          actorDefinitionId,
          actorDefinitionVersion.get().versionId,
        )

      val initializedRollouts = connectorRollouts.filter { it.state == ConnectorEnumRolloutState.INITIALIZED }
      val initialVersion =
        actorDefinitionService.getDefaultVersionForActorDefinitionIdOptional(actorDefinitionId)
          ?: throw ConnectorRolloutInvalidRequestProblem(
            ProblemMessageData().message("Could not find initial version for actor definition id: $actorDefinitionId"),
          )

      if (initializedRollouts.isEmpty()) {
        val connectorRollout =
          ConnectorRollout()
            .withId(UUID.randomUUID())
            .withActorDefinitionId(actorDefinitionId)
            .withReleaseCandidateVersionId(actorDefinitionVersion.get().versionId)
            .withInitialVersionId(initialVersion.get().versionId)
            .withUpdatedBy(updatedBy)
            .withState(ConnectorEnumRolloutState.INITIALIZED)
            .withHasBreakingChanges(false)
            .withRolloutStrategy(ConnectorEnumRolloutStrategy.fromValue(rolloutStrategy.toString()))
            .withInitialRolloutPct(initialRolloutPct?.toLong())
            .withFinalTargetRolloutPct(finalTargetRolloutPct?.toLong())
        connectorRolloutService.writeConnectorRollout(connectorRollout)
        return connectorRollout
      }

      if (initializedRollouts.size > 1) {
        throw ConnectorRolloutInvalidRequestProblem(
          ProblemMessageData().message("Expected at most 1 rollout in the INITIALIZED state, found ${initializedRollouts.size}."),
        )
      }
      val finalEnumStates =
        ConnectorEnumRolloutState.entries.filter { rollout ->
          ConnectorRolloutFinalState.entries.map { it.toString() }.contains(
            rollout.toString(),
          )
        }
      val rolloutsInInvalidState =
        connectorRollouts.filter { rollout: ConnectorRollout ->
          finalEnumStates.contains(rollout.state) &&
            (rollout.state != ConnectorEnumRolloutState.INITIALIZED)
        }

      if (rolloutsInInvalidState.isNotEmpty()) {
        throw ConnectorRolloutInvalidRequestProblem(
          ProblemMessageData().message("Found rollouts in invalid states: ${rolloutsInInvalidState.map { it.id }}."),
        )
      }
      return initializedRollouts.first()
    }

    @VisibleForTesting
    open fun getAndValidateStartRequest(connectorRolloutStart: ConnectorRolloutStartRequestBody): ConnectorRollout {
      // We expect to hit this code path under 2 different circumstances:
      // 1. When a rollout is being started for the first time
      // 2. When a rollout's Temporal workflow is being reset, e.g. for a bug fix.
      // In case 1, the rollout will be in INITIALIZED state, and we'll change the state to WORKFLOW_STARTED.
      // In case 2, the rollout may be in any state, and we only want to change it to WORKFLOW_STARTED if it was INITIALIZED.
      // However, in case 2 the workflow will have a new run ID, so we still want to update that.
      var connectorRollout = connectorRolloutService.getConnectorRollout(connectorRolloutStart.id)
      if (connectorRollout.state == ConnectorEnumRolloutState.INITIALIZED) {
        connectorRollout =
          connectorRollout
            .withState(ConnectorEnumRolloutState.WORKFLOW_STARTED)
            .withRolloutStrategy(ConnectorEnumRolloutStrategy.fromValue(connectorRolloutStart.rolloutStrategy.toString()))
      }
      // Always update the workflow run ID if provided; if the workflow was restarted it will have changed
      connectorRollout =
        connectorRollout
          .withWorkflowRunId(connectorRolloutStart.workflowRunId)
          // Also include the version ID, for cases where the rollout wasn't automatically added to the rollouts table (i.e. for testing)
          .withInitialVersionId(connectorRollout.initialVersionId)
      return connectorRollout
    }

    @VisibleForTesting
    open fun getAndRollOutConnectorRollout(connectorRolloutRequest: ConnectorRolloutRequestBody): ConnectorRollout {
      var connectorRollout = connectorRolloutService.getConnectorRollout(connectorRolloutRequest.id)
      if (connectorRollout.state !in
        setOf(
          ConnectorEnumRolloutState.WORKFLOW_STARTED,
          ConnectorEnumRolloutState.IN_PROGRESS,
          ConnectorEnumRolloutState.PAUSED,
        )
      ) {
        throw ConnectorRolloutInvalidRequestProblem(
          ProblemMessageData().message(
            "Connector rollout must be in WORKFLOW_STARTED, IN_PROGRESS, or PAUSED state to update the rollout, but was in state " +
              connectorRollout.state.toString(),
          ),
        )
      }
      if (connectorRolloutRequest.actorIds == null && connectorRolloutRequest.targetPercentage == null) {
        throw ConnectorRolloutInvalidRequestProblem(
          ProblemMessageData().message(
            "ActorIds or targetPercentage must be provided, but neither were found.",
          ),
        )
      }
      if (connectorRolloutRequest.actorIds != null) {
        try {
          actorDefinitionVersionUpdater.createReleaseCandidatePinsForActors(
            connectorRolloutRequest.actorIds.toSet(),
            connectorRollout.actorDefinitionId,
            connectorRollout.releaseCandidateVersionId,
            connectorRollout.id,
          )
        } catch (e: InvalidRequestException) {
          throw ConnectorRolloutInvalidRequestProblem(
            ProblemMessageData().message("Failed to create release candidate pins for actors: ${e.message}"),
          )
        }
        connectorRollout =
          connectorRollout
            .withState(ConnectorEnumRolloutState.IN_PROGRESS)
            .withRolloutStrategy(ConnectorEnumRolloutStrategy.fromValue(connectorRolloutRequest.rolloutStrategy.toString()))
            .withUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC).toEpochSecond())
      }
      if (connectorRolloutRequest.targetPercentage != null) {
        connectorRollout = pinByPercentage(connectorRollout, connectorRolloutRequest.targetPercentage!!, connectorRolloutRequest.rolloutStrategy!!)
      }

      return connectorRollout
    }

    open fun pinByPercentage(
      connectorRollout: ConnectorRollout,
      targetPercentage: Int,
      rolloutStrategy: ConnectorRolloutStrategy,
    ): ConnectorRollout {
      val actorSelectionInfo = getActorSelectionInfo(connectorRollout, targetPercentage)
      if (actorSelectionInfo.actorIdsToPin.isEmpty()) {
        throw ConnectorRolloutNotEnoughActorsProblem(
          ProblemMessageData().message(
            "No additional actors are eligible to be pinned for the progressive rollout.",
          ),
        )
      }

      try {
        actorDefinitionVersionUpdater.createReleaseCandidatePinsForActors(
          actorSelectionInfo.actorIdsToPin.toSet(),
          connectorRollout.actorDefinitionId,
          connectorRollout.releaseCandidateVersionId,
          connectorRollout.id,
        )
      } catch (e: InvalidRequestException) {
        throw ConnectorRolloutInvalidRequestProblem(ProblemMessageData().message("Failed to create release candidate pins for actors: ${e.message}"))
      }

      return connectorRollout
        .withState(ConnectorEnumRolloutState.IN_PROGRESS)
        .withRolloutStrategy(ConnectorEnumRolloutStrategy.fromValue(rolloutStrategy.toString()))
        .withUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC).toEpochSecond())
        .withCurrentTargetRolloutPct(actorSelectionInfo.percentagePinned.toLong())
    }

    @VisibleForTesting
    open fun getAndValidateFinalizeRequest(connectorRolloutFinalize: ConnectorRolloutFinalizeRequestBody): ConnectorRollout {
      val connectorRollout = connectorRolloutService.getConnectorRollout(connectorRolloutFinalize.id)
      val invalidFinalizeStates = ConnectorRolloutFinalState.entries.map { ConnectorEnumRolloutState.fromValue(it.toString()) }
      if (connectorRollout.state in invalidFinalizeStates
      ) {
        throw ConnectorRolloutInvalidRequestProblem(
          ProblemMessageData().message(
            "Connector rollout may not be in a terminal state when finalizing the rollout, but was in state " + connectorRollout.state.toString(),
          ),
        )
      }

      // Unpin all actors that are pinned to the release candidate, unless the state is CANCELED and the user opted to retain pins on cancellation
      // so that the same actors will be pinned to the next release candidate.
      if (!(connectorRolloutFinalize.state == ConnectorRolloutStateTerminal.CANCELED && connectorRolloutFinalize.retainPinsOnCancellation)) {
        actorDefinitionVersionUpdater.removeReleaseCandidatePinsForVersion(
          connectorRollout.actorDefinitionId,
          connectorRollout.releaseCandidateVersionId,
        )
      }

      val currentTime = OffsetDateTime.now(ZoneOffset.UTC).toEpochSecond()
      return connectorRollout
        .withState(ConnectorEnumRolloutState.fromValue(connectorRolloutFinalize.state.toString()))
        .withRolloutStrategy(ConnectorEnumRolloutStrategy.fromValue(connectorRolloutFinalize.rolloutStrategy.toString()))
        .withUpdatedAt(currentTime)
        .withCompletedAt(currentTime)
        .withErrorMsg(connectorRolloutFinalize.errorMsg)
        .withFailedReason(connectorRolloutFinalize.failedReason)
    }

    @VisibleForTesting
    open fun getAndValidateUpdateStateRequest(
      id: UUID,
      state: ConnectorEnumRolloutState,
      errorMsg: String?,
      failedReason: String?,
    ): ConnectorRollout {
      val connectorRollout = connectorRolloutService.getConnectorRollout(id)
      val invalidUpdateStates = ConnectorRolloutFinalState.entries.map { ConnectorEnumRolloutState.fromValue(it.toString()) }
      if (connectorRollout.state in invalidUpdateStates
      ) {
        throw ConnectorRolloutInvalidRequestProblem(
          ProblemMessageData().message(
            "Connector rollout may not be in a terminal state when updating the rollout state, but was in state " + connectorRollout.state.toString(),
          ),
        )
      }
      return connectorRollout
        .withState(state)
        .withErrorMsg(errorMsg)
        .withFailedReason(failedReason)
    }

    private fun unixTimestampToOffsetDateTime(unixTimestamp: Long): OffsetDateTime = Instant.ofEpochSecond(unixTimestamp).atOffset(ZoneOffset.UTC)

    open fun listConnectorRollouts(): List<ConnectorRolloutRead> {
      val connectorRollouts: List<ConnectorRollout> = connectorRolloutService.listConnectorRollouts()
      return connectorRollouts.map { connectorRollout ->
        buildConnectorRolloutRead(connectorRollout, false)
      }
    }

    open fun listConnectorRollouts(actorDefinitionId: UUID): List<ConnectorRolloutRead> {
      val connectorRollouts: List<ConnectorRollout> =
        connectorRolloutService.listConnectorRollouts(actorDefinitionId)
      return connectorRollouts.map { connectorRollout ->
        buildConnectorRolloutRead(connectorRollout, false)
      }
    }

    open fun listConnectorRollouts(
      actorDefinitionId: UUID,
      dockerImageTag: String,
    ): List<ConnectorRolloutRead> {
      val actorDefinitionVersion = actorDefinitionService.getActorDefinitionVersion(actorDefinitionId, dockerImageTag)
      if (actorDefinitionVersion.isEmpty) {
        return emptyList()
      }
      val connectorRollouts: List<ConnectorRollout> =
        connectorRolloutService.listConnectorRollouts(
          actorDefinitionId,
          actorDefinitionVersion.get().versionId,
        )
      return connectorRollouts.map { connectorRollout ->
        buildConnectorRolloutRead(connectorRollout, false)
      }
    }

    @Transactional("config")
    open fun startConnectorRollout(connectorRolloutStart: ConnectorRolloutStartRequestBody): ConnectorRolloutRead {
      val connectorRollout = getAndValidateStartRequest(connectorRolloutStart)

      // If actors are still pinned to a previous rollout's release candidate, we migrate them to the new release candidate
      if (connectorRolloutStart.migratePins) {
        actorDefinitionVersionUpdater.migrateReleaseCandidatePins(
          connectorRollout.actorDefinitionId,
          connectorRolloutService.listConnectorRollouts(connectorRollout.actorDefinitionId).map { it.id.toString() },
          connectorRollout.id.toString(),
          connectorRollout.releaseCandidateVersionId,
        )
        connectorRollout.initialRolloutPct = getActorSelectionInfo(connectorRollout, 0).percentagePinned.toLong()
      }

      val updatedConnectorRollout = connectorRolloutService.writeConnectorRollout(connectorRollout)
      return buildConnectorRolloutRead(updatedConnectorRollout, true)
    }

    @Transactional("config")
    open fun doConnectorRollout(connectorRolloutUpdate: ConnectorRolloutRequestBody): ConnectorRolloutRead {
      val connectorRollout = getAndRollOutConnectorRollout(connectorRolloutUpdate)
      val updatedConnectorRollout = connectorRolloutService.writeConnectorRollout(connectorRollout)
      return buildConnectorRolloutRead(updatedConnectorRollout, true)
    }

    @Transactional("config")
    open fun finalizeConnectorRollout(connectorRolloutFinalize: ConnectorRolloutFinalizeRequestBody): ConnectorRolloutRead {
      val connectorRollout = getAndValidateFinalizeRequest(connectorRolloutFinalize)
      val updatedConnectorRollout = connectorRolloutService.writeConnectorRollout(connectorRollout)
      return buildConnectorRolloutRead(updatedConnectorRollout, true)
    }

    open fun getConnectorRollout(id: UUID): ConnectorRolloutRead {
      val connectorRollout = connectorRolloutService.getConnectorRollout(id)
      return buildConnectorRolloutRead(connectorRollout, true)
    }

    open fun updateState(connectorRolloutUpdateStateRequestBody: ConnectorRolloutUpdateStateRequestBody): ConnectorRolloutRead {
      val rollout: ConnectorRollout =
        if (connectorRolloutUpdateStateRequestBody.id == null) {
          getRolloutByActorDefinitionIdAndDockerImageTag(
            connectorRolloutUpdateStateRequestBody.actorDefinitionId,
            connectorRolloutUpdateStateRequestBody.dockerImageTag,
          )
        } else {
          connectorRolloutService.getConnectorRollout(connectorRolloutUpdateStateRequestBody.id)
        }
      val connectorRollout =
        getAndValidateUpdateStateRequest(
          rollout.id,
          ConnectorEnumRolloutState.fromValue(connectorRolloutUpdateStateRequestBody.state.toString()),
          connectorRolloutUpdateStateRequestBody.errorMsg,
          connectorRolloutUpdateStateRequestBody.failedReason,
        )
      val updatedConnectorRollout = connectorRolloutService.writeConnectorRollout(connectorRollout)
      return buildConnectorRolloutRead(updatedConnectorRollout, true)
    }

    fun getActorSyncInfo(id: UUID): List<ConnectorRolloutActorSyncInfo> {
      val rollout = connectorRolloutService.getConnectorRollout(id)
      val actorSyncInfoMap = rolloutActorFinder.getSyncInfoForPinnedActors(rollout)
      return actorSyncInfoMap.map { (actorId, syncInfo) ->
        ConnectorRolloutActorSyncInfo()
          .actorId(actorId)
          .nConnections(syncInfo.nConnections)
          .nSucceeded(syncInfo.nSucceeded)
          .nFailed(syncInfo.nFailed)
      }
    }

    fun getPinnedActorInfo(id: UUID): ConnectorRolloutActorSelectionInfo {
      val rollout = connectorRolloutService.getConnectorRollout(id)
      val actorSelectionInfo = rolloutActorFinder.getActorSelectionInfo(rollout, null)
      return ConnectorRolloutActorSelectionInfo()
        .nActors(actorSelectionInfo.nActors)
        .nPinnedToConnectorRollout(actorSelectionInfo.nPreviouslyPinned)
        .nActorsEligibleOrAlreadyPinned(actorSelectionInfo.nActorsEligibleOrAlreadyPinned)
    }

    open fun manualStartConnectorRollout(connectorRolloutManualStart: ConnectorRolloutManualStartRequestBody): ConnectorRolloutRead {
      val rollout =
        getOrCreateAndValidateManualStartInput(
          connectorRolloutManualStart.dockerRepository,
          connectorRolloutManualStart.actorDefinitionId,
          connectorRolloutManualStart.dockerImageTag,
          connectorRolloutManualStart.updatedBy,
          connectorRolloutManualStart.rolloutStrategy,
          connectorRolloutManualStart.initialRolloutPct,
          connectorRolloutManualStart.finalTargetRolloutPct,
        )

      try {
        connectorRolloutClient.startRollout(
          ConnectorRolloutActivityInputStart(
            connectorRolloutManualStart.dockerRepository,
            connectorRolloutManualStart.dockerImageTag,
            connectorRolloutManualStart.actorDefinitionId,
            rollout.id,
            connectorRolloutManualStart.updatedBy,
            rollout.rolloutStrategy,
            actorDefinitionService.getActorDefinitionVersion(rollout.initialVersionId).dockerImageTag,
            rollout,
            getPinnedActorInfo(rollout.id),
            getActorSyncInfo(rollout.id),
            rollout.initialRolloutPct?.toInt(),
            rollout.finalTargetRolloutPct?.toInt(),
            connectorRolloutManualStart.migratePins,
          ),
        )
      } catch (e: WorkflowUpdateException) {
        rollout.state = ConnectorEnumRolloutState.CANCELED
        connectorRolloutService.writeConnectorRollout(rollout)
        throw throwAirbyteApiClientExceptionIfExists("startWorkflow", e)
      }

      return buildConnectorRolloutRead(connectorRolloutService.getConnectorRollout(rollout.id), false)
    }

    open fun manualDoConnectorRolloutUpdate(connectorRolloutUpdate: ConnectorRolloutManualRolloutRequestBody): ConnectorRolloutRead {
      val connectorRollout = connectorRolloutService.getConnectorRollout(connectorRolloutUpdate.id)
      if (connectorRollout.state == ConnectorEnumRolloutState.INITIALIZED) {
        try {
          connectorRolloutClient.startRollout(
            ConnectorRolloutActivityInputStart(
              connectorRolloutUpdate.dockerRepository,
              connectorRolloutUpdate.dockerImageTag,
              connectorRolloutUpdate.actorDefinitionId,
              connectorRolloutUpdate.id,
              connectorRolloutUpdate.updatedBy,
              getRolloutStrategyForManualUpdate(connectorRollout.rolloutStrategy),
              actorDefinitionService.getActorDefinitionVersion(connectorRollout.initialVersionId).dockerImageTag,
              connectorRollout,
              getPinnedActorInfo(connectorRollout.id),
              getActorSyncInfo(connectorRollout.id),
              connectorRollout.initialRolloutPct.toInt(),
              connectorRollout.finalTargetRolloutPct.toInt(),
              connectorRolloutUpdate.migratePins,
            ),
          )
        } catch (e: WorkflowUpdateException) {
          throw throwAirbyteApiClientExceptionIfExists("startWorkflow", e)
        }
      }
      try {
        connectorRolloutClient.doRollout(
          ConnectorRolloutActivityInputRollout(
            connectorRolloutUpdate.dockerRepository,
            connectorRolloutUpdate.dockerImageTag,
            connectorRolloutUpdate.actorDefinitionId,
            connectorRolloutUpdate.id,
            connectorRolloutUpdate.actorIds,
            connectorRolloutUpdate.targetPercentage,
            connectorRolloutUpdate.updatedBy,
            getRolloutStrategyForManualUpdate(connectorRollout.rolloutStrategy),
          ),
        )
      } catch (e: WorkflowUpdateException) {
        throw throwAirbyteApiClientExceptionIfExists("doRollout", e)
      }
      return buildConnectorRolloutRead(connectorRolloutService.getConnectorRollout(connectorRolloutUpdate.id), false)
    }

    open fun manualFinalizeConnectorRollout(
      connectorRolloutFinalize: ConnectorRolloutManualFinalizeRequestBody,
    ): ConnectorRolloutManualFinalizeResponse {
      // Start a workflow if one doesn't exist
      val connectorRollout = connectorRolloutService.getConnectorRollout(connectorRolloutFinalize.id)

      if (connectorRollout.state == ConnectorEnumRolloutState.INITIALIZED) {
        try {
          connectorRolloutClient.startRollout(
            ConnectorRolloutActivityInputStart(
              connectorRolloutFinalize.dockerRepository,
              connectorRolloutFinalize.dockerImageTag,
              connectorRolloutFinalize.actorDefinitionId,
              connectorRolloutFinalize.id,
              connectorRolloutFinalize.updatedBy,
              getRolloutStrategyForManualUpdate(connectorRollout.rolloutStrategy),
              actorDefinitionService.getActorDefinitionVersion(connectorRollout.initialVersionId).dockerImageTag,
              connectorRollout,
              getPinnedActorInfo(connectorRollout.id),
              getActorSyncInfo(connectorRollout.id),
            ),
          )
        } catch (e: WorkflowUpdateException) {
          throw throwAirbyteApiClientExceptionIfExists("startWorkflow", e)
        }
      }
      logger.info {
        "Finalizing rollout for ${connectorRolloutFinalize.id}; " +
          "dockerRepository=${connectorRolloutFinalize.dockerRepository}" +
          "dockerImageTag=${connectorRolloutFinalize.dockerImageTag}" +
          "actorDefinitionId=${connectorRolloutFinalize.actorDefinitionId}"
      }
      try {
        connectorRolloutClient.finalizeRollout(
          ConnectorRolloutActivityInputFinalize(
            connectorRolloutFinalize.dockerRepository,
            connectorRolloutFinalize.dockerImageTag,
            connectorRolloutFinalize.actorDefinitionId,
            connectorRolloutFinalize.id,
            actorDefinitionService.getActorDefinitionVersion(connectorRollout.initialVersionId).dockerImageTag,
            ConnectorRolloutFinalState.fromValue(connectorRolloutFinalize.state.toString()),
            connectorRolloutFinalize.errorMsg,
            connectorRolloutFinalize.failedReason,
            connectorRolloutFinalize.updatedBy,
            getRolloutStrategyForManualUpdate(connectorRollout.rolloutStrategy),
            connectorRolloutFinalize.retainPinsOnCancellation,
          ),
        )
      } catch (e: WorkflowUpdateException) {
        throw throwAirbyteApiClientExceptionIfExists("finalizeRollout", e)
      }
      val response = ConnectorRolloutManualFinalizeResponse()
      response.status("ok")
      return response
    }

    private fun getRolloutStrategyForManualUpdate(currentRolloutStrategy: ConnectorEnumRolloutStrategy?): ConnectorEnumRolloutStrategy {
      return if (currentRolloutStrategy == null || currentRolloutStrategy == ConnectorEnumRolloutStrategy.MANUAL) {
        ConnectorEnumRolloutStrategy.MANUAL
      } else {
        ConnectorEnumRolloutStrategy.OVERRIDDEN
      }
    }

    @Transactional("config")
    open fun getActorSelectionInfo(
      connectorRollout: ConnectorRollout,
      targetPercent: Int,
    ): ActorSelectionInfo {
      val actorSelectionInfo = rolloutActorFinder.getActorSelectionInfo(connectorRollout, targetPercent)
      if (targetPercent > 0 && actorSelectionInfo.actorIdsToPin.isEmpty()) {
        throw ConnectorRolloutNotEnoughActorsProblem(
          ProblemMessageData().message(
            "No actors are eligible to be pinned for a progressive rollout.",
          ),
        )
      }
      return actorSelectionInfo
    }

    @Cacheable("rollout-updated-by")
    open fun getUpdatedBy(
      rolloutStrategy: ConnectorEnumRolloutStrategy,
      updatedById: UUID,
    ): String =
      when (rolloutStrategy) {
        ConnectorEnumRolloutStrategy.MANUAL -> userPersistence.getUser(updatedById).get().email
        else -> ""
      }

    private fun isTerminalState(state: ConnectorEnumRolloutState): Boolean =
      ConnectorRolloutFinalState.entries
        .map {
          ConnectorEnumRolloutState.fromValue(it.toString())
        }.contains(state)

    private fun getRolloutByActorDefinitionIdAndDockerImageTag(
      actorDefinitionId: UUID,
      dockerImageTag: String,
    ): ConnectorRollout {
      val actorDefinitionVersion =
        actorDefinitionService
          .getActorDefinitionVersion(actorDefinitionId, dockerImageTag)
          .orElseThrow {
            throw ConnectorRolloutInvalidRequestProblem(
              ProblemMessageData().message(
                "Actor definition version not found for actor definition id: $actorDefinitionId and docker image tag: $dockerImageTag",
              ),
            )
          }
      val rollouts =
        connectorRolloutService.listConnectorRollouts(actorDefinitionId, actorDefinitionVersion.versionId).filter {
          !isTerminalState(it.state)
        }
      if (rollouts.size != 1) {
        throw ConnectorRolloutInvalidRequestProblem(
          ProblemMessageData().message(
            "Expected 1 rollout in a non-terminal state, found ${rollouts.size}.",
          ),
        )
      }
      return rollouts.first()
    }

    private fun throwAirbyteApiClientExceptionIfExists(
      handlerName: String,
      e: WorkflowUpdateException,
    ): Throwable {
      if (isAirbyteApiClientException(e)) {
        throw ConnectorRolloutInvalidRequestProblem(
          extractAirbyteApiClientException(e),
          ProblemMessageData().message("An error occurred in the `$handlerName` update handler."),
        )
      } else {
        throw e
      }
    }

    @VisibleForTesting
    fun extractAirbyteApiClientException(e: WorkflowUpdateException): String {
      val exc = getAirbyteApiClientException(e) as ApplicationFailure
      logger.error { "AirbyteApiClientException: $exc" }
      return exc.originalMessage
    }

    private fun isAirbyteApiClientException(e: WorkflowUpdateException): Boolean {
      val cause = e.cause?.cause
      if (cause is ApplicationFailure) {
        return cause.type == AIRBYTE_API_CLIENT_EXCEPTION
      }
      return false
    }

    private fun getAirbyteApiClientException(e: WorkflowUpdateException): Throwable = e.cause?.cause ?: e.cause ?: e
  }
