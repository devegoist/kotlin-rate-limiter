# kotlin-rate-limiter

처리율 제한 알고리즘 5가지를 구현해본 저장소. Kotlin + Spring Boot.

## 왜 만들었나

회사 서비스가 비로그인으로도 데이터를 볼 수 있는 구조라 스크래핑 대응을 고민하다가, 처리율 제한부터 정리해야겠다 싶어서 시작했다. 알고리즘마다 트레이드오프가 다른데 글로만 읽어서는 잘 안 와닿아서 직접 짜봤다.

## 구현한 알고리즘

| 알고리즘 | 버스트 | 메모리 | 정확도 |
|---|:-:|:-:|:-:|
| Token Bucket | 허용 | O(1) | 높음 |
| Leaky Bucket | 불허 | O(1) | 높음 |
| Fixed Window Counter | 경계에서 2배 | O(1) | 낮음 |
| Sliding Window Log | 불허 | O(limit) | 정확 |
| Sliding Window Counter | 약간 | O(1) | 근사 |

### Token Bucket

버킷에 토큰이 일정 속도로 채워지고 요청마다 하나씩 소비한다. 토큰이 쌓여 있으면 몰아서 쓸 수 있어서 버스트를 허용한다.

백그라운드 스레드로 채우지 않고, 요청이 올 때 "마지막 요청 이후 경과 시간 × 충전 속도"로 계산한다. 클라이언트 수와 무관하게 타이머가 필요 없다.

### Leaky Bucket

Token Bucket과 부호만 반대다. 요청이 큐에 쌓이고 일정 속도로 빠져나가며, 큐가 차면 버린다.

실제 큐와 워커 스레드 대신 경과 시간으로 큐 크기를 계산했다. `tryAcquire()`가 즉시 허용/거부를 반환하는 구조라 요청을 대기시킬 수 없어서인데, 그래서 트래픽 셰이핑은 안 된다. 비동기 처리가 전제된 곳이라면 실제 큐 방식이 맞다.

### Fixed Window Counter

시간을 고정 구간으로 나누고 구간마다 카운터를 센다. `now / windowSize`로 윈도우 번호를 구하고, 번호가 바뀌면 리셋한다. 구현이 단순하고 Redis `INCR` 하나로 분산 구현이 된다.

경계 문제가 있다. 12:00:59에 10개, 12:01:00에 또 10개가 통과하면 0.2초에 20개가 나간다. 테스트로 재현해뒀다.

### Sliding Window Log

모든 요청의 타임스탬프를 `ArrayDeque`에 저장하고, 윈도우 밖으로 나간 것을 제거하며 개수를 센다. 어느 구간을 잘라도 limit 이하라 경계 문제가 없다.

대신 요청 하나당 타임스탬프 하나라 메모리가 O(limit)이다. limit이 1000이면 클라이언트당 1000개를 들고 있어야 한다.

이것만 CAS 대신 `synchronized`를 썼다. Deque를 불변으로 만들어 CAS로 교체하려면 요청마다 컬렉션을 복사해야 하는데 O(limit) 비용이라 손해다.

### Sliding Window Counter

Fixed Window에 이전 윈도우 카운트를 가중치로 더한 방식.

    추정치 = 이전 카운트 × (1 - 현재 진행률) + 현재 카운트

이전 윈도우 요청이 균등 분포한다고 가정하는 근사치지만, 메모리는 O(1)이면서 경계 문제가 거의 없다. 특별한 이유가 없으면 이걸 쓰면 된다.

## 구조

    src/main/kotlin/com/example/ratelimiter/
    ├── core/
    │   └── RateLimiter.kt          # 공통 인터페이스
    ├── algorithm/
    │   ├── TokenBucketRateLimiter.kt
    │   ├── LeakyBucketRateLimiter.kt
    │   ├── FixedWindowRateLimiter.kt
    │   ├── SlidingWindowLogRateLimiter.kt
    │   └── SlidingWindowCounterRateLimiter.kt
    ├── filter/
    │   └── RateLimitInterceptor.kt # 429 응답 처리
    └── config/
        └── RateLimitConfig.kt      # 알고리즘 교체

전략 패턴이라 `RateLimitConfig`에서 구현체만 바꾸면 알고리즘이 바뀐다.

## 동시성

Sliding Window Log를 제외한 넷은 `ConcurrentHashMap` + `AtomicReference` + CAS 루프로 처리했다. 상태를 불변 data class로 만들고 통째로 교체하는 방식이다.

`ConcurrentHashMap`은 서로 다른 키끼리 경합이 없고, 같은 키의 동시 요청만 `AtomicReference`가 담당한다.

테스트에서 50개 스레드로 500번 요청을 던져 정확히 limit만큼만 통과하는지 확인했다. CAS를 `set`으로 바꾸면 이 테스트가 깨진다.

## 값을 어떻게 정할까

로그를 보고 정하는 게 맞지만 출발점은 있어야 하니 일단 추측해봤다.

### 두 파라미터의 역할

- **rate** (`refillRate` / `leakRate`) → 장기 평균 처리량
- **capacity** (`limit`) → 순간에 몰릴 수 있는 양

capacity를 키워도 장기 처리량은 안 늘어난다. 물통을 키워도 구멍 크기는 그대로인 것과 같다. 버스트에 얼마나 관대할지만 정하는 값이다.

### rate

백엔드가 감당 가능한 처리량에서 역산한다. 부하 테스트로 초당 몇 개까지 안정적인지 재고 그 70~80% 수준으로 잡는다.

사용자 행동으로도 상한을 짐작할 수 있다. 검색은 사람이 초당 5회 이상 하기 어렵고, 목록 스크롤은 초당 2~3회 정도다.

### capacity

rate의 3~4배로 시작해봤다.

페이지 하나 열면 API가 5~10개 동시에 날아가는데, 버스트 여유가 없으면 정상 사용자가 첫 화면에서 막힌다. 반대로 10배쯤 주면 크롤러가 한 번에 많이 긁어갈 수 있다.

### 엔드포인트별 차등

전체에 하나의 값을 적용하면 의미가 없다. 정적 리소스 기준으로 맞추면 무한정 열리고, 비싼 API 기준으로 맞추면 페이지가 안 뜬다. 세 등급으로 나눠봤다.

| 등급 | 예시 | capacity | rate |
|---|---|:-:|:-:|
| 비싼 API | 검색, 목록 조회 | 20 | 5/s |
| 일반 API | 상세 조회 | 60 | 20/s |
| 가벼운 API | 설정값, 코드 테이블 | 200 | 100/s |

가벼운 API는 사실상 제한을 안 거는 셈이고 극단적인 남용만 막는 용도다.

### 주의할 점

**NAT.** 회사, 학교, 카페, 통신사 NAT는 여러 명이 IP 하나를 공유한다. IP로만 식별하면 정상 사용자가 같이 막힌다. IP + User-Agent 조합이 낫지만 완벽하진 않다.

**처음엔 느슨하게.** 위 숫자보다 2~3배 여유 있게 시작해서 로그를 보며 조여가는 게 안전하다. 타이트하게 잡으면 봇보다 정상 사용자 민원이 먼저 온다.

**shadow mode.** 차단하지 않고 "차단했다면"만 로그로 남겨 관찰하면 오탐을 미리 확인할 수 있다.

```kotlin
if (!limiter.tryAcquire(key)) {
    if (shadowMode) {
        log.warn("Would block: key={}, uri={}", key, request.requestURI)
        return true
    }
    response.status = 429
    return false
}
```

### 순서

1. 액세스 로그에서 IP별 요청 수, User-Agent 분포, 엔드포인트별 비율 확인
2. 정상 사용자 상위 95%가 초당 몇 회인지 파악
3. 그 2~3배로 설정
4. shadow mode로 1~2주 관찰
5. 실제 차단 시작
6. 데이터 보며 조정

## 실행

```bash
./gradlew bootRun
```

```bash
for i in $(seq 1 25); do
  curl -s -o /dev/null -w "%{http_code} " http://localhost:8080/api/ping
done
```

## 알려진 한계

- `ConcurrentHashMap`에 TTL이 없어서 키가 계속 쌓인다. Caffeine으로 교체 예정
- 서버가 여러 대면 각자 카운터를 가진다. 정확한 총량 제한이 필요하면 Redis + Lua로 가야 한다
- `X-Forwarded-For`를 검증 없이 신뢰해서 위조 가능하다. 신뢰 프록시 확인 필요

## 앞으로

- Caffeine 적용
- `@RateLimit` 어노테이션으로 엔드포인트별 차등 제한
- `Retry-After`, `X-RateLimit-*` 헤더
- shadow mode

## 스택

Kotlin 1.9 / Spring Boot 3.3 / JDK 21