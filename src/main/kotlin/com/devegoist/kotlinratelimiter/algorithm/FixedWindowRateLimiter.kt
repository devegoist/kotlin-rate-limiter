package com.example.ratelimiter.algorithm

import com.devegoist.kotlinratelimiter.core.RateLimiter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Fixed Window Counter 알고리즘.
 *
 * ## 동작 원리
 * 시간을 고정 크기 윈도우로 나누고, 윈도우마다 요청 수를 센다.
 * 윈도우가 바뀌면 카운터를 0으로 리셋한다.
 *
 * ```
 * 윈도우 = 1초, 제한 = 10회
 *
 * 00:00 ┌───────────────┐ 카운터 리셋
 *       │ ●●●●●●●●●● ✕✕ │ 10개 통과, 이후 거부
 * 00:01 └───────────────┘
 *       ┌───────────────┐ 카운터 리셋
 *       │ ●●●●●         │ 다시 10개까지
 * 00:02 └───────────────┘
 * ```
 *
 * ## 장점
 * - 구현이 매우 단순하고 메모리 사용량이 적다 (클라이언트당 정수 2개)
 * - Redis의 INCR 하나로 분산 구현이 가능하다 (Lua 스크립트 불필요)
 * - "1분당 N회" 같은 정책을 사용자에게 설명하기 쉽다
 *
 * ## 약점: 경계 문제 (boundary spike)
 * 윈도우 경계를 걸치면 짧은 시간에 제한의 2배까지 통과할 수 있다.
 *
 * ```
 * 제한 = 1분당 10회
 *
 * 12:00:59.9 → 10개 통과 (12:00 윈도우)
 * 12:01:00.1 → 10개 통과 (12:01 윈도우, 리셋됨)
 * → 0.2초 만에 20개 통과
 * ```
 *
 * 이 문제를 해결한 것이 Sliding Window 계열 알고리즘이다.
 *
 * @param limit          윈도우당 허용 요청 수
 * @param windowSizeMillis 윈도우 크기 (밀리초)
 */
class FixedWindowRateLimiter(
    private val limit: Long,
    private val windowSizeMillis: Long
) : RateLimiter {

    init {
        require(limit > 0) { "limit은 양수여야 합니다: $limit" }
        require(windowSizeMillis > 0) { "windowSizeMillis는 양수여야 합니다: $windowSizeMillis" }
    }

    /**
     * 윈도우 상태 스냅샷.
     *
     * Token/Leaky Bucket과 달리 소수점 계산이 없다.
     * 윈도우 번호가 바뀌었는지만 판단하면 되기 때문.
     */
    private data class Window(
        val windowIndex: Long,   // 몇 번째 윈도우인지
        val count: Long          // 이 윈도우에서 처리한 요청 수
    )

    private val windows = ConcurrentHashMap<String, AtomicReference<Window>>()

    override fun tryAcquire(key: String): Boolean {
        val now = System.currentTimeMillis()
        val currentIndex = now / windowSizeMillis   // 정수 나눗셈으로 윈도우 번호 계산

        val windowRef = windows.computeIfAbsent(key) {
            AtomicReference(Window(windowIndex = currentIndex, count = 0))
        }

        while (true) {
            val current = windowRef.get()

            val updated = if (current.windowIndex != currentIndex) {
                // 새 윈도우 진입 → 카운터 리셋 후 1로 시작
                Window(windowIndex = currentIndex, count = 1)
            } else {
                // 같은 윈도우 → 한도 확인
                if (current.count >= limit) {
                    return false
                }
                Window(windowIndex = currentIndex, count = current.count + 1)
            }

            if (windowRef.compareAndSet(current, updated)) {
                return true
            }
        }
    }

    override fun algorithmName(): String =
        "FixedWindow(limit=$limit, window=${windowSizeMillis}ms)"
}