package com.onyx.foss.kotlin.ingestion

import com.onyx.foss.kotlin.config.OnyxProperties
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class IngestionWorker(
    private val properties: OnyxProperties,
    private val claims: JobClaimService,
    private val processor: IngestionProcessor,
) {
    @Scheduled(fixedDelayString = "\${onyx.worker.poll-delay-ms:5000}")
    fun work() {
        if (!properties.worker.enabled) return
        claims.claimNext()?.let(processor::process)
    }
}
