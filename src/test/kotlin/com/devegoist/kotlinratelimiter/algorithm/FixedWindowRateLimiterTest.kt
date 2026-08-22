package com.example.ratelimiter.algorithm

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@DisplayName("FixedWindowRateLimiter")
class FixedWindowRateLimiterTest {

    @Nested
    @DisplayName("기본 동작")
    inner class BasicBehavior {

        @Test
        fun `윈도우당 limit만큼 요청이 허용된다`() {
            val limiter = FixedWindowRateLimiter(limit = 5, windowSizeMillis = 10_000)

            repeat(5) { i ->
                assertThat(limiter.tryAcquire("c"))
                    .describedAs("요청 ${i + 1}")
                    .isTrue()
            }
            assertThat(limiter.tryAcquire("c")).isFalse()
        }
    }

    @Nested
    @DisplayName("윈도우 전환")
    inner class WindowRotation {

        @Test
        fun `윈도우가 바뀌면 카운터가 리셋된다`() {
            val limiter = FixedWindowRateLimiter(limit = 3, windowSizeMillis = 200)

            repeat(3) { limiter.tryAcquire("c") }
            assertThat(limiter.tryAcquire("c")).isFalse()

            // 다음 윈도우로 확실히 넘어가도록 대기
            Thread.sleep(250)

            assertThat(limiter.tryAcquire("c")).isTrue()
        }
    }

    @Nested
    @DisplayName("경계 문제 (알려진 약점)")
    inner class BoundarySpike {

        @Test
        fun `윈도우 경계에서는 limit의 2배까지 통과할 수 있다`() {
            val windowSize = 500L
            val limiter = FixedWindowRateLimiter(limit = 5, windowSizeMillis = windowSize)

            // 현재 윈도우가 끝나기 직전까지 대기
            val now = System.currentTimeMillis()
            val untilBoundary = windowSize - (now % windowSize)
            Thread.sleep(untilBoundary - 50)   // 경계 50ms 전

            // 윈도우 끝자락에서 5개 소진
            val beforeBoundary = (1..5).count { limiter.tryAcquire("c") }
            assertThat(beforeBoundary).isEqualTo(5)

            // 경계를 넘김
            Thread.sleep(60)

            // 새 윈도우라 또 5개 통과
            val afterBoundary = (1..5).count { limiter.tryAcquire("c") }
            assertThat(afterBoundary).isEqualTo(5)

            // 결과: 약 110ms 사이에 10개 통과 (제한은 500ms당 5개)
            assertThat(beforeBoundary + afterBoundary).isEqualTo(10)
        }
    }

    @Nested
    @DisplayName("클라이언트 격리")
    inner class ClientIsolation {

        @Test
        fun `서로 다른 클라이언트는 독립적인 카운터를 가진다`() {
            val limiter = FixedWindowRateLimiter(limit = 2, windowSizeMillis = 10_000)

            repeat(2) { limiter.tryAcquire("alice") }
            assertThat(limiter.tryAcquire("alice")).isFalse()

            assertThat(limiter.tryAcquire("bob")).isTrue()
            assertThat(limiter.tryAcquire("bob")).isTrue()
            assertThat(limiter.tryAcquire("bob")).isFalse()
        }
    }

    @Nested
    @DisplayName("스레드 안전성")
    inner class ThreadSafety {

        @Test
        fun `동시 접근 시 정확히 limit만큼만 허용된다`() {
            val limit = 100L
            val limiter = FixedWindowRateLimiter(limit = limit, windowSizeMillis = 60_000)

            val threads = 50
            val executor = Executors.newFixedThreadPool(threads)
            val latch = CountDownLatch(threads)
            val allowed = AtomicInteger(0)

            repeat(threads) {
                executor.submit {
                    try {
                        repeat(10) {
                            if (limiter.tryAcquire("shared")) allowed.incrementAndGet()
                        }
                    } finally {
                        latch.countDown()
                    }
                }
            }

            latch.await()
            executor.shutdown()

            assertThat(allowed.get()).isEqualTo(limit.toInt())
        }
    }

    @Nested
    @DisplayName("입력 검증")
    inner class Validation {

        @Test
        fun `limit이 0 이하이면 예외를 던진다`() {
            assertThatThrownBy { FixedWindowRateLimiter(limit = 0, windowSizeMillis = 1000) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `windowSizeMillis가 0 이하이면 예외를 던진다`() {
            assertThatThrownBy { FixedWindowRateLimiter(limit = 10, windowSizeMillis = 0) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }
}