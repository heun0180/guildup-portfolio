package io.github.heun0180.portfolio.bingo;

import org.springframework.stereotype.Component;

@Component
public class KillCountPolicy implements ScoringPolicy {
    @Override
    public long contribution(String accountCode, int kills, int pointsPerKill) {
        return Math.multiplyExact((long) kills, pointsPerKill);
    }
}
