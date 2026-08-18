package com.devegoist.kotlinratelimiter

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import kotlin.time.Clock

@RequestMapping("/api")
@RestController
class ApiController {

    @GetMapping
    fun ping(): Map<String, Any> = mapOf(
        "message" to "pong",
        "timestamp" to Clock.System.now().toString()
    )

}