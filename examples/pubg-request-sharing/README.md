# PUBG Match API 중복 요청 공유

> Java 21의 `CompletableFuture`, Virtual Thread, `Semaphore`로 같은 경기의 진행 중 요청을 공유하는 독립 Maven 예제입니다.

[포트폴리오 README](../../README.md) · [PUBG API 설계 사례](../../docs/cases/pubg-request-reuse.md)

**GuildUp 운영 소스 전체를 공개한 프로젝트가 아닙니다.** Private 구현을 읽고 요청 공유에 필요한 책임을 재구성했습니다. HTTP·Spring·DB 없이 합성 `MatchClient`로 실행하며 API Key나 운영 설정이 필요하지 않습니다. 실행 코드의 외부 라이브러리는 없고, 테스트에만 JUnit Jupiter 5.13.4를 사용합니다.

## 1. 예제에서 다루는 문제

참가자 A와 B가 같은 경기 정보를 동시에 필요로 할 때 각각 Client를 호출하면 중복 수집이 발생합니다. 완료 결과를 캐시하는 것만으로는 **첫 요청이 아직 진행 중인 구간**의 중복을 막을 수 없습니다.

| 구간 | 사용한 장치 | 보장 범위 |
| --- | --- | --- |
| 같은 경기의 동시 요청 | 복합 키와 `putIfAbsent`로 소유자 선정, Future 공유 | 한 서비스 인스턴스의 같은 키 |
| 서로 다른 경기의 동시 수집 | 공정한 `Semaphore` | 해당 인스턴스에서 동시에 실행되는 Client 호출 상한 |
| 이미 완료된 성공·404 응답 | 서로 다른 TTL의 메모리 캐시 | 만료 전 같은 결과 재사용 |
| Client 예외 | 공유 Future의 예외 완료, in-flight 제거 | 대기자에게 실패 전달, 다음 호출에서 재시도 가능 |
| 오래된 cache miss를 가진 호출자 | 소유자 등록 직후 캐시 재확인 | 먼저 완료된 작업의 캐시가 아직 유효하면 불필요한 재수집 방지 |

404는 `Optional.empty()`로 **모델링**합니다. 실제 HTTP 404를 발생시키는 예제가 아닙니다. 통신 오류·429·5xx를 404처럼 캐시하면 장애를 정상적인 경기 부재로 숨기므로, `MatchClient` 계약은 이를 예외와 구분합니다.

## 2. 실제 GuildUp 설계와의 관계

분석한 원본은 `PubgMatchService`, `PubgApiClient.getMatch`, `PubgMatchServiceTests`입니다. 원본 경로는 Private 저장소의 `src/main/java/com/guildup/pubg/`와 `src/test/java/com/guildup/pubg/service/` 기준이며, 이 예제로 복사하지 않았습니다.

| 항목 | 확인한 운영 구현 | 재구성한 예제 |
| --- | --- | --- |
| 요청 단위 | 경기 목록 중복 제거 후 여러 기능이 공통 Match 조회 사용 | 한 번에 `find(MatchKey)` 한 경기 조회 |
| 진행 중 요청 | `(shard, matchId)`별 `CompletableFuture` 공유 | 같은 복합 키와 소유자 선정 원리 사용 |
| 실행 방식 | Virtual Thread 작업을 제출하고 호출자가 결과를 기다림 | Virtual Thread에서 Client를 호출하고 비동기 Future 반환 |
| 동시성 | 공통 서비스의 Semaphore | 서비스 인스턴스별 Semaphore |
| 캐시 | 성공 24시간·부재 1분 기본값, 크기 제한과 LRU | 두 TTL을 생성자로 주입, 조회한 키만 만료 검사, 크기 제한 없음 |
| 404 | HTTP Client의 `null`을 서비스가 `Optional.empty()`로 변환 | 가짜 Client가 명시적으로 `Optional.empty()` 반환 |
| fresh 조회 | 성공 재사용, 이전 부재 응답은 우회 가능 | fresh 우회는 생략, TTL 정책만 제공 |
| HTTP 복구 | Client에 429·일시 오류 등의 재시도 존재. Match 호출은 Governor의 간격 조절 대상으로 전달되지 않음 | HTTP·자동 retry·Rate Limit 헤더 처리는 생략 |
| 모니터링 | 작업 로그·컨텍스트 전파 | 합성 데모 출력과 테스트 assertion |
| 캐시 조회/등록 경쟁 | 읽은 원본의 `findOne`에는 소유자 등록 이후 캐시 재조회가 없음 | 이 경쟁을 검토하여 재확인 방어와 회귀 테스트를 추가 |

마지막 행은 **공개 예제에서 추가한 방어**입니다. 원본에서 이미 해결한 동작이나 실제 운영 장애 해결 이력으로 주장하지 않습니다.

## 3. 요청 공유 처리 흐름

핵심 코드는 [MatchRequestService](src/main/java/io/github/heun0180/portfolio/pubg/MatchRequestService.java)에 있습니다.

```text
find(shard, matchId)
  1. 유효한 캐시가 있으면 완료된 Future 반환
  2. inFlight.putIfAbsent로 현재 키의 소유자 선정
     - 기존 Future가 있으면 그 결과/실패를 공유
  3. 새 소유자는 캐시를 다시 조회
     - 이전 작업이 이미 캐시를 채웠다면 Client 호출 생략
  4. 캐시도 없으면 Virtual Thread 작업 제출
  5. Semaphore 획득 → Client 호출 → 성공/404 캐시 저장 → permit 반환
  6. 해당 in-flight 항목 제거 → 소유자 Future를 결과 또는 예외로 완료
  7. 각 호출자는 내부 Future의 copy를 통해 결과를 받음
```

### 완료 결과와 진행 중 작업을 따로 관리하는 이유

캐시는 **이미 완료된 값**을, in-flight Map은 **아직 완료되지 않은 작업**을 보관합니다. 같은 키의 대기자는 별도 작업을 만들거나 Semaphore permit을 추가로 소비하지 않습니다. 다른 키의 작업만 별도로 실행되어 permit을 기다립니다. Semaphore는 요청 수/분 같은 Rate Limit 정책이 아닙니다.

[MatchKey](src/main/java/io/github/heun0180/portfolio/pubg/MatchKey.java)는 두 필드의 동등성을 사용하는 Java record입니다. `kakao / synthetic-match`와 `steam / synthetic-match`는 다른 키입니다. 예제는 문자열의 대소문자를 자동 통합하지 않으므로 호출자가 `kakao`, `steam` 같은 정규화된 shard를 전달해야 합니다.

[TtlCache](src/main/java/io/github/heun0180/portfolio/pubg/TtlCache.java)의 값은 `Optional<Match>`이고, 캐시 조회 결과는 `Optional<Optional<Match>>`입니다. 바깥 Optional이 비면 **cache miss**, 바깥은 있고 안쪽만 비면 **캐시된 404**입니다. 성공·404의 만료 시각은 각각 캐시 저장 시각에 지정 TTL을 더한 값이며, 정확히 만료 시각부터 miss로 취급합니다.

### 첫 캐시 조회만으로는 부족한 경쟁

```text
A: 캐시에서 miss를 읽음 ── 잠시 멈춤
B: 같은 키 등록 → Client 호출 → 캐시 저장 → in-flight 제거 → 완료
A: 오래된 miss를 바탕으로 새 소유자가 됨
   ├─ 재확인 없음: 불필요한 두 번째 Client 호출
   └─ 재확인 있음: 아직 유효한 B의 성공/404 캐시 재사용
```

재확인은 Future 등록에 성공한 호출자만 수행합니다. 캐시가 실제로 만료되었거나 항목이 없다면 새 조회가 필요합니다. 모든 시점의 재조회를 금지하는 보장이 아닙니다.

### 실패와 호출자 취소

Client 실패는 404 캐시에 넣지 않습니다. in-flight를 제거한 **다음** 예외를 통지하므로, 실패를 받은 호출자의 다음 요청은 이전의 실패 Future에 다시 붙지 않고 재시도할 수 있습니다. 제거는 `remove(key, owner)`로 수행합니다.

반환값은 내부 Future의 [`copy()`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/CompletableFuture.html#copy())입니다. 한 호출자가 반환값을 취소하거나 직접 완료해도 공유 작업과 다른 호출자의 결과를 변경하지 않습니다. 반대로 호출자 취소가 실제 Client 실행을 중단시키지는 않습니다.

## 4. 실행과 테스트

필요 환경은 **JDK 21, Maven 3.9 이상**입니다. Maven 실행에 사용하는 Java도 21인지 확인합니다. 최초 빌드에는 Maven 플러그인과 JUnit 다운로드가 필요하며, 예제 실행·테스트는 실제 PUBG나 운영 서비스에 접속하지 않습니다.

```bash
cd examples/pubg-request-sharing
java -version
mvn -version
mvn -B -ntp clean verify
java -jar target/pubg-request-sharing-1.0.0.jar
```

데모는 latch로 첫 Client 호출을 멈춘 뒤 두 번째 요청을 연결합니다. 출력의 호출 수는 합성 Client의 실행 횟수입니다.

```text
동일 키 공유 후 Client 호출: 1
캐시 조회 후 Client 호출: 1
다른 shard 조회 후 Client 호출: 2
```

전체 테스트 반복 실행 예시입니다. 아래 반복문은 macOS/Linux의 셸에서 실행합니다.

```bash
attempt=1
while [ "$attempt" -le 30 ]; do
  mvn -q test || exit 1
  attempt=$((attempt + 1))
done
```

테스트 결과는 `target/surefire-reports/`에서 확인합니다. `target/`은 이 예제의 `.gitignore`로 공개 대상에서 제외합니다. 종료 시에는 `try-with-resources`로 서비스를 닫아 실행 중인 작업을 기다립니다.

## 5. 테스트 시나리오와 검증 범위

테스트는 [MatchRequestServiceTest](src/test/java/io/github/heun0180/portfolio/pubg/MatchRequestServiceTest.java)에 있습니다. `CountDownLatch`로 호출 진입·재개 순서를 제어하고, [MutableClock](src/test/java/io/github/heun0180/portfolio/pubg/MutableClock.java)으로 TTL 시각을 움직입니다. `Thread.sleep`이나 실제 네트워크 응답 시간에 의존하지 않습니다. 각 latch/Future 대기에는 실패를 감지할 제한 시간을 둡니다.

| 시나리오 | 테스트 메서드 | 재현과 실제 assertion |
| --- | --- | --- |
| A. 두 호출 스레드의 같은 키 | `twoCallerThreadsShareOneInFlightClientCall` | 첫 Client가 멈춘 동안 두 번째 `find`의 반환을 확인. 두 Future는 미완료, Client 1회, 동일 Match 객체, 완료 후 in-flight 0 |
| B. 서로 다른 8경기 | `distinctMatchesRespectSemaphoreLimitAcrossAllRequests` | 상한 2개가 동시에 Client에 진입한 상태를 유지. 전체 완료 후 최대 동시 호출 2, 총 호출 8, 활성·in-flight 0 |
| C. 플랫폼 격리 | `sameMatchIdOnDifferentShardsUsesIndependentFlightsAndCacheEntries` | 두 shard의 Client 진입을 확인. 서로 다른 합성 응답과 두 키를 검증하고, 각각의 캐시 재조회 후에도 총 2회 |
| D. 성공 캐시 | `successfulCacheHitDoesNotCallClientAgain` | 연속 조회 결과 동일, Client 1회 |
| E. 성공·404 TTL | `successAndNotFoundExpireAtTheirOwnTtl` | 두 경우 각각 만료 1ns 전에는 호출 1회, 정확한 만료 경계에서는 2회 |
| F. 실패 전달과 재시도 | `failureReachesAllWaitersAndClearsFlightBeforeRetry` | checked/runtime 예외 각각 공유. 두 대기자의 원인 예외 동일, in-flight 0, 다음 호출 성공, 성공 캐시 재사용 |
| G. 조회/등록 경쟁 | `staleCacheMissBeforeFlightRegistrationDoesNotStartAnotherClientCall` | 캐시 어댑터가 A의 오래된 miss 반환을 지연. B가 완전히 끝난 후 A를 재개해 성공·404 모두 총 Client 1회 |
| 호출자 취소 격리 | `cancellingOneReturnedFutureDoesNotCancelSharedWork` | A의 반환 Future 취소 뒤 B와 성공 캐시는 정상, Client 1회 |
| 종료 후 요청 | `closedServiceRejectsNewRequestsWithoutLeavingAFlight` | 종료된 서비스는 실패 Future 반환, Client 호출·in-flight 모두 0 |

G의 캐시 어댑터는 테스트 코드 안에만 있습니다. 재현용 sleep이나 작업 중단 훅을 실제 서비스 경로에 추가하지 않았습니다. 파라미터별 실행을 포함하면 전체 **12개 테스트 실행**입니다.

### 실제 실행 기록

- 검증 날짜: **2026-10-20, Asia/Seoul**
- 환경: Eclipse Temurin **21.0.12.1**, Maven **3.9.16**, macOS aarch64. 검증 머신에서는 IntelliJ에 포함된 Maven 실행 파일을 사용했습니다.
- `mvn -B -ntp clean verify`: Java 21 컴파일·12개 테스트·실행 가능한 JAR 생성 성공. 실패/오류/건너뜀 0.
- JAR 데모: 동일 키 1회, 캐시 후 1회, 다른 shard 후 2회라는 합성 Client 호출 수 확인.
- 캐시 재확인을 임시로 생략한 대조 실험: G의 성공·404 두 경우 모두 `expected: 1, actual: 2`로 실패하여 경쟁을 탐지했습니다. 실험 직후 원본 예제 코드를 복원했습니다.
- 복원 후 전체 테스트를 **30회 반복 실행하여 360건 모두 통과**했습니다. 실패/오류/건너뜀 0. 이 반복 실행은 예제 제작 단계의 검증 기록입니다.
- 공개 전 재검증: **2026-10-20 19:50 KST**, 위와 같은 Java·Maven 환경에서 `mvn -B -ntp clean verify`를 다시 실행하여 컴파일·테스트 12개·JAR 생성에 성공했습니다. 실패/오류/건너뜀 0. 검토된 Java 구현과 테스트 코드는 변경하지 않았습니다.

이 기록은 해당 로컬 환경의 검증 결과입니다. Mock/가짜 Client 호출 횟수는 실제 운영 API 감소율·응답 시간·처리량 성과가 아닙니다. PostgreSQL 테스트와 운영 소스의 테스트는 실행하지 않았습니다.

## 6. 운영 구현과의 차이

작은 예제에 집중하기 위해 Spring 설정, HTTP 인증·직렬화, bulk 조회, fresh 부재 캐시 우회, 자동 retry, API 헤더 해석, Telemetry, 영속 Fact, DB, MDC·운영 모니터링을 포함하지 않았습니다. 운영용 캐시 라이브러리나 분산 락도 추가하지 않았습니다.

예제의 Future 반환 API, 내부 캐시 인터페이스, 호출자 취소 격리, 등록 후 캐시 재확인은 학습과 결정적인 테스트를 위해 재구성한 부분입니다. 특히 캐시 구현은 원본의 LRU·최대 크기 보장을 갖지 않습니다.

## 7. 기술적 한계와 다음 개선

| 확인 가능한 한계 | 운영 적용 전에 검토할 사항 |
| --- | --- |
| 캐시·in-flight·Semaphore가 인스턴스별 메모리에 있음 | 같은 JVM에서도 서비스 인스턴스를 여러 개 만들면 공유하지 않음. 다중 서버 제어는 별도 설계 필요 |
| 재시작하면 메모리 상태가 사라짐 | 지속적 재사용이 필요하면 별도의 영속 데이터 저장·정합성 정책 검토 |
| 캐시의 최대 크기와 주기적 청소 없음 | 조회하지 않는 만료 항목이 남음. 크기 제한, 정리 정책, 검증된 캐시 라이브러리 검토 |
| 서로 다른 키의 대기 작업 수 제한 없음 | Semaphore는 Client 동시 실행만 제한. Virtual Thread 수·대기 Future·메모리 사용을 제한하려면 작업 접수 상한 필요 |
| 공유 작업 자체의 deadline·강제 취소·자동 retry 없음 | 실제 Client의 연결/응답 timeout, retry budget과 backoff, 종료 정책 설계. `close()`는 작업이 끝나지 않으면 오래 기다릴 수 있음 |
| 404는 TTL 동안 재사용, 다른 실패는 캐시하지 않음 | 실제 데이터 가용 지연에 맞는 부재 TTL과 반복 실패 폭주 방어 검토 |
| TTL이 주입된 Clock의 절대 시각에 의존 | 시스템 시각 이동이 만료 판단에 미치는 영향과 단조 시간 기반 정책 검토 |
| 현재 테스트는 단일 JVM의 가짜 Client 사용 | 실제 HTTP timeout·429·서버 간 경쟁·부하·운영 성능은 검증 범위 밖 |

이 예제가 설명하는 것은 **진행 중인 같은 키의 요청 공유와 완료 결과의 한시적 재사용**입니다. 분산 환경의 exactly-once 실행이나 API Rate Limit 준수를 보장하는 시스템으로 확대해 해석하지 않습니다.
