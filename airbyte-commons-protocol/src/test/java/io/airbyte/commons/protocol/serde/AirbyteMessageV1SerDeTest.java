/*
 * Copyright (c) 2020-2025 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.commons.protocol.serde;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.airbyte.protocol.models.AirbyteMessage;
import io.airbyte.protocol.models.AirbyteMessage.Type;
import io.airbyte.protocol.models.ConnectorSpecification;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AirbyteMessageV1SerDeTest {

  @Test
  void v1SerDeRoundTripTest() throws URISyntaxException {
    final AirbyteMessageV1Deserializer deser = new AirbyteMessageV1Deserializer();
    final AirbyteMessageV1Serializer ser = new AirbyteMessageV1Serializer();

    final AirbyteMessage message = new AirbyteMessage()
        .withType(Type.SPEC)
        .withSpec(
            new ConnectorSpecification()
                .withProtocolVersion("1.0.0")
                .withDocumentationUrl(new URI("file:///tmp/doc")));

    final String serializedMessage = ser.serialize(message);
    final Optional<AirbyteMessage> deserializedMessage = deser.deserializeExact(serializedMessage);

    assertEquals(message, deserializedMessage.get());
  }

}
