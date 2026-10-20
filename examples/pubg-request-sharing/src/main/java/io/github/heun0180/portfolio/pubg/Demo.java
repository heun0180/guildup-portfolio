package io.github.heun0180.portfolio.pubg;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 네트워크와 인증값을 사용하지 않는 실행 예제. */
public final class Demo {
    private Demo() { }

    public static void main(String[] args) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        MatchClient fakeClient = key -> {
            calls.incrementAndGet();
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("demo gate timed out");
            }
            return Optional.of(new Match(key.matchId(), "synthetic-squad"));
        };

        try (var service = new MatchRequestService(fakeClient, Clock.systemUTC(),
                Duration.ofMinutes(10), Duration.ofSeconds(30), 2)) {
            MatchKey key = new MatchKey("kakao", "example-match");
            try {
                var first = service.find(key);
                if (!entered.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("client did not start");
                }
                var second = service.find(key);
                release.countDown();
                System.out.println("요청 A: " + first.get(5, TimeUnit.SECONDS));
                System.out.println("요청 B: " + second.get(5, TimeUnit.SECONDS));
                System.out.println("동일 키 공유 후 Client 호출: " + calls.get());
                service.find(key).get(5, TimeUnit.SECONDS);
                System.out.println("캐시 조회 후 Client 호출: " + calls.get());
                service.find(new MatchKey("steam", key.matchId())).get(5, TimeUnit.SECONDS);
                System.out.println("다른 shard 조회 후 Client 호출: " + calls.get());
            } finally {
                release.countDown();
            }
        }
    }
}
