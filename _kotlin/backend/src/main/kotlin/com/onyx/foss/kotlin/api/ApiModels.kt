package com.onyx.foss.kotlin.api

data class StatusResponse(val success: Boolean, val message: String, val data: Long? = null)
data class ObjectCreationResponse(val id: Long, val credential: Map<String, Any?>? = null)
