package com.insur.insurcoresystem.common.ratelimit;

/**
 * 버킷 상태를 보관하는 곳. 기본은 서버 메모리({@link InMemoryRateLimitStore})라서 서버가 여러 대면 한도가 대수만큼 커진다.
 * 여러 대로 늘리면 Redis 같은 공용 저장소 구현을 빈으로 등록해 바꾼다 (같은 타입의 빈이 있으면 메모리 구현은 만들지 않는다).
 */
public interface RateLimitStore {

    /** 키의 버킷에서 하나를 꺼낸다. 비어 있으면 꺼내지 않고 거절한다. */
    Decision tryConsume(String key, RateLimitProperties.Limit limit);

    /**
     * @param allowed           호출을 허용했는지
     * @param remaining         허용 후 남은 수 (거절이면 0)
     * @param retryAfterSeconds 거절일 때 하나가 다시 찰 때까지 초 (올림, 최소 1)
     */
    record Decision(boolean allowed, long remaining, long retryAfterSeconds) {
    }
}
