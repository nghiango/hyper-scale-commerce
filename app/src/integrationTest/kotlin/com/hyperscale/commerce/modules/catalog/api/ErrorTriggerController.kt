package com.hyperscale.commerce.modules.catalog.api

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/catalog/test")
class ErrorTriggerController {
  @GetMapping("/error") fun trigger(): String = throw RuntimeException("boom")
}
