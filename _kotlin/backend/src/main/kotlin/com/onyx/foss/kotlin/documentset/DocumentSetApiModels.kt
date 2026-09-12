package com.onyx.foss.kotlin.documentset

import com.fasterxml.jackson.annotation.JsonAlias
import jakarta.validation.constraints.NotBlank

data class DocumentSetRequest(val id: Long? = null, @field:NotBlank val name: String, val description: String = "", @param:JsonAlias("cc_pair_ids") val ccPairIds: List<Long> = emptyList(), val isPublic: Boolean = true, val users: List<String> = emptyList(), val groups: List<Long> = emptyList())
