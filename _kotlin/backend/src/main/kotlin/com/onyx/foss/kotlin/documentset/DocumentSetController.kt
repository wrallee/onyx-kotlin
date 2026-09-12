package com.onyx.foss.kotlin.documentset

import jakarta.validation.Valid
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/manage")
class DocumentSetController(private val sets: DocumentSetService) {
    @PostMapping("/admin/document-set")
    fun createSet(@Valid @RequestBody request: DocumentSetRequest): Long = sets.createSet(request)

    @PatchMapping("/admin/document-set")
    fun updateSet(@Valid @RequestBody request: DocumentSetRequest) = sets.updateSet(request)

    @DeleteMapping("/admin/document-set/{setId}")
    fun deleteSet(@PathVariable setId: Long) = sets.deleteSet(setId)

    @GetMapping("/admin/document-set/{setId}")
    fun documentSet(@PathVariable setId: Long): Map<String, Any?> =
        sets.documentSet(setId)

    @GetMapping("/document-set")
    fun documentSets(): List<Map<String, Any?>> = sets.listSets()

    @GetMapping("/document-set-public")
    fun documentSetPublic(): Map<String, Boolean> = mapOf("is_public" to true)
}
