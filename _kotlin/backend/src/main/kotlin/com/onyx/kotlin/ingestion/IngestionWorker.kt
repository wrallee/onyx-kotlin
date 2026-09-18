package com.onyx.kotlin.ingestion

import com.onyx.kotlin.config.OnyxProperties
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class IngestionWorker(
    private val properties: OnyxProperties,
    private val claims: JobClaimService,
    private val processor: IngestionProcessor,
) {
    private val log = LoggerFactory.getLogger(IngestionWorker::class.java)

    @Scheduled(fixedDelayString = "\${onyx.worker.poll-delay-ms:5000}")
    fun work() {
        if (!properties.worker.enabled) return
        try {
            claims.claimNext()?.let(processor::process)
        } catch (error: Exception) {
            log.error("Unhandled error in IngestionWorker: {}", error.message, error)
        }
    }
}
