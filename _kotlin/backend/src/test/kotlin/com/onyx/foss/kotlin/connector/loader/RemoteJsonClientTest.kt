package com.onyx.foss.kotlin.connector.loader

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

class RemoteJsonClientTest {
    @Test
    fun connectorRequestTimeoutIsBounded() {
        assertThat(REMOTE_CONNECTOR_TIMEOUT).isLessThanOrEqualTo(Duration.ofSeconds(30))
    }
}
