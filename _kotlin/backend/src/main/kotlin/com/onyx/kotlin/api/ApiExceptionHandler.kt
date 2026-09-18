package com.onyx.kotlin.api

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

class ApiException(val status: HttpStatus, override val message: String) : RuntimeException(message)

@RestControllerAdvice
class ApiExceptionHandler {
    private val log = LoggerFactory.getLogger(ApiExceptionHandler::class.java)

    @ExceptionHandler(ApiException::class)
    fun apiError(error: ApiException): ResponseEntity<Map<String, String>> {
        if (error.status.is5xxServerError) {
            log.error("API error [{}]: {}", error.status, error.message, error)
        } else {
            log.warn("API error [{}]: {}", error.status, error.message)
        }
        return ResponseEntity.status(error.status).body(mapOf("detail" to error.message))
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun invalidInput(error: IllegalArgumentException): ResponseEntity<Map<String, String>> {
        log.warn("Invalid input: {}", error.message)
        return ResponseEntity.badRequest().body(mapOf("detail" to (error.message ?: "Invalid request")))
    }

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun conflictingData(error: DataIntegrityViolationException): ResponseEntity<Map<String, String>> {
        log.warn("Data conflict: {}", error.message)
        return ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("detail" to "Request conflicts with existing data"))
    }

    @ExceptionHandler(Exception::class)
    fun unhandledError(error: Exception): ResponseEntity<Map<String, String>> {
        log.error("Unhandled internal server error: {}", error.message, error)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(mapOf("detail" to (error.message ?: "Internal server error")))
    }
}
