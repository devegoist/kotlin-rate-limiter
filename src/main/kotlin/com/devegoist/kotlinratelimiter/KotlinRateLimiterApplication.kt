package com.devegoist.kotlinratelimiter

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class KotlinRateLimiterApplication

fun main(args: Array<String>) {
    runApplication<KotlinRateLimiterApplication>(*args)
}
