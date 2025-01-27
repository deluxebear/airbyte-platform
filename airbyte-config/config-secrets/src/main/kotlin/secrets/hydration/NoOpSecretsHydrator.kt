/*
 * Copyright (c) 2020-2025 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.config.secrets.hydration

import com.fasterxml.jackson.databind.JsonNode
import io.airbyte.config.secrets.persistence.RuntimeSecretPersistence
import io.airbyte.config.secrets.persistence.SecretPersistence
import io.micronaut.context.annotation.Requires
import jakarta.inject.Singleton

/**
 * No-op hydrator. Used if there is no secrets persistence configured for this Airbyte instance.
 */
@Requires(missingBeans = [SecretPersistence::class])
@Singleton
class NoOpSecretsHydrator : SecretsHydrator {
  override fun hydrateFromDefaultSecretPersistence(partialConfig: JsonNode): JsonNode = partialConfig

  override fun hydrateFromRuntimeSecretPersistence(
    partialConfig: JsonNode,
    runtimeSecretPersistence: RuntimeSecretPersistence,
  ): JsonNode = partialConfig

  override fun hydrateSecretCoordinateFromDefaultSecretPersistence(secretCoordinate: JsonNode): JsonNode = secretCoordinate

  override fun hydrateSecretCoordinateFromRuntimeSecretPersistence(
    secretCoordinate: JsonNode,
    runtimeSecretPersistence: RuntimeSecretPersistence,
  ): JsonNode = secretCoordinate
}
