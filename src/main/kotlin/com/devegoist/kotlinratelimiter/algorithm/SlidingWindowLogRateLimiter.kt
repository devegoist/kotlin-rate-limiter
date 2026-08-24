package com.devegoist.kotlinratelimiter.algorithm

import com.devegoist.kotlinratelimiter.core.RateLimiter
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * Sliding Window Log 알고리즘.
 *
 * ## 동작 원리
 * 모든 요청의 타임스탬프를 기록하고, 현재 시각 기준 윈도우 안에 있는
 * 요청 수를 세어 제한한다. 윈도우는 시계에 고정되지 않고 현재 시각을
 * 따라 계속 이동한다.
 *
 * ```
 * 제한 = 1초당 3회
 *
 *      삭제됨
 *       ↓
 * ──────●─────●─────●──────────────
 *    00.100 00.500 00.900      01.200(현재)
 *       ┃                          ┃
 *       ┗━━━━━ 윈도우(1초) ━━━━━━━━┛
 *              (오른쪽으로 계속 이동)
 * ```
 *
 * ## 장점: 완벽한 정확도
 * 어느 시점에서 윈도우를 잘라도 요청 수가 정확히 limit 이하다.
 * Fixed Window의 경계 문제가 발생하지 않는다.
 *
 * ## 단점: 메모리
 * 요청 하나당 타임스탬프 하나를 저장하므로 O(limit) 메모리가 필요하다.
 * limit이 1000이면 클라이언트당 타임스탬프 1000개를 들고 있어야 한다.
 *
 * ## 동시성 전략
 * 다른 알고리즘과 달리 CAS 대신 synchronized를 사용한다.
 * Deque 전체를 불변 스냅샷으로 만들어 CAS로 교체하려면 요청마다
 * 컬렉션을 복사해야 하는데, 이는 O(limit) 비용이라 오히려 비싸다.
 * 짧은 임계 구역에서는 락이 더 효율적이다.
 *
 * @param limit            윈도우당 허용 요청 수
 * @param windowSizeMillis 윈도우 크기 (밀리초)
 */
class SlidingWindowLogRateLimiter(
    private val limit: Int,
    private val windowSizeMillis: Long
) : RateLimiter {

    init {
        require(limit > 0) { "limit은 양수여야 합니다: $limit" }
        require(windowSizeMillis > 0) { "windowSizeMillis는 양수여야 합니다: $windowSizeMillis" }
    }

    /**
     * 클라이언트별 타임스탬프 로그.
     *
     * ArrayDeque를 쓰는 이유:
     * - 앞에서 제거(오래된 것), 뒤에 추가(새 요청) 모두 O(1)
     * - 타임스탬프는 시간순으로 들어오므로 정렬 상태가 자동 유지된다
     */
    private val logs = ConcurrentHashMap<String, ArrayDeque<Long>>()

    override fun tryAcquire(key: String): Boolean {
        val log = logs.computeIfAbsent(key) { ArrayDeque() }

        // Deque는 스레드 안전하지 않으므로 락으로 보호
        synchronized(log) {
            val now = System.currentTimeMillis()
            val windowStart = now - windowSizeMillis

            // ── 윈도우 밖으로 나간 오래된 타임스탬프 제거 ──
            // 앞쪽부터 순서대로 확인 (시간순 정렬이 보장됨)
            while (log.isNotEmpty() && log.peekFirst() <= windowStart) {
                log.pollFirst()
            }

            // 윈도우 안의 요청 수가 한도에 도달했는가
            if (log.size >= limit) {
                return false
            }

            // 이번 요청을 기록
            log.addLast(now)
            return true
        }
    }

    override fun algorithmName(): String =
        "SlidingWindowLog(limit=$limit, window=${windowSizeMillis}ms)"
}