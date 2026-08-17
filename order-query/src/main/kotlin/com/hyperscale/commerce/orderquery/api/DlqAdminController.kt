package com.hyperscale.commerce.orderquery.api

import com.hyperscale.commerce.orderquery.application.DlqReplayResult
import com.hyperscale.commerce.orderquery.application.DlqReplayService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/admin/dlq")
class DlqAdminController(
    private val dlqReplayService: DlqReplayService,
) {

  @PostMapping("/replay")
  fun replay(@RequestBody request: DlqReplayRequest): ResponseEntity<DlqReplayResult> {
    require(request.dlqTopic.isNotBlank()) { "dlqTopic must not be blank" }
    require(request.targetTopic.isNotBlank()) { "targetTopic must not be blank" }
    require(request.maxRecords > 0) { "maxRecords must be greater than 0" }

    val result =
        dlqReplayService.replay(
            dlqTopic = request.dlqTopic,
            targetTopic = request.targetTopic,
            maxRecords = request.maxRecords,
        )
    return ResponseEntity.ok(result)
  }
}

data class DlqReplayRequest(
    val dlqTopic: String,
    val targetTopic: String,
    val maxRecords: Int = 100,
)
