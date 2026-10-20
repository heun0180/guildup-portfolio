package io.github.heun0180.portfolio.pubg;

import java.util.Objects;

/** 예제에 필요한 최소 불변 데이터. PUBG HTTP 응답 모델이 아니다. */
public record Match(String id, String mode) {
    public Match {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(mode, "mode");
    }
}
