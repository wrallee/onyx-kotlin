package com.onyx.kotlin.api

import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
private class TestExceptionController {
    @GetMapping("/test/api-exception")
    fun apiException(): Unit = throw ApiException(HttpStatus.NOT_FOUND, "Item not found")

    @GetMapping("/test/illegal-argument")
    fun illegalArgument(): Unit = throw IllegalArgumentException("Bad argument value")

    @GetMapping("/test/conflict")
    fun conflict(): Unit = throw DataIntegrityViolationException("Unique constraint violation")

    @GetMapping("/test/unhandled")
    fun unhandled(): Unit = throw RuntimeException("Unexpected database failure")
}

class ApiExceptionHandlerTest {
    private val mvc = MockMvcBuilders
        .standaloneSetup(TestExceptionController())
        .setControllerAdvice(ApiExceptionHandler())
        .build()

    @Test
    fun handlesApiException() {
        mvc.perform(get("/test/api-exception"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.detail").value("Item not found"))
    }

    @Test
    fun handlesIllegalArgumentException() {
        mvc.perform(get("/test/illegal-argument"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.detail").value("Bad argument value"))
    }

    @Test
    fun handlesDataIntegrityViolationException() {
        mvc.perform(get("/test/conflict"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.detail").value("Request conflicts with existing data"))
    }

    @Test
    fun handlesUnhandledExceptionAs500WithoutLeakingDetails() {
        mvc.perform(get("/test/unhandled"))
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.detail").value("Internal server error"))
    }

    @Test
    fun handlesSpringMvcClientExceptionWithAppropriateStatus() {
        // POST to a GET-only endpoint triggers HttpRequestMethodNotSupportedException
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/test/api-exception"))
            .andExpect(status().isMethodNotAllowed)
            .andExpect(jsonPath("$.detail").exists())
    }
}
