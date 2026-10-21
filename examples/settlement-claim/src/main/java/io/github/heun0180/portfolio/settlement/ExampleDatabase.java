package io.github.heun0180.portfolio.settlement;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import static io.github.heun0180.portfolio.settlement.SettlementData.Status;

@Service
public class ExampleDatabase {
    private final CompetitionRepository competitions;
    private final RewardAccountRepository accounts;
    private final MatchResultRepository results;
    private final RewardEntryRepository entries;
    public ExampleDatabase(CompetitionRepository competitions, RewardAccountRepository accounts,
            MatchResultRepository results, RewardEntryRepository entries) {
        this.competitions = competitions; this.accounts = accounts; this.results = results; this.entries = entries;
    }
    @Transactional
    public long create(Instant endsAt) { return competitions.save(new Competition(endsAt, accounts.save(new RewardAccount()))).getId(); }
    @Transactional
    public void reset() { results.deleteAll(); entries.deleteAll(); competitions.deleteAll(); accounts.deleteAll(); }
    @Transactional(readOnly = true)
    public State read(long id) {
        Competition c = competitions.findById(id).orElseThrow();
        return new State(c.getStatus(),c.getResultRequestedAt(),c.getResultPublishAt(),c.getFinalizationStartedAt(),
                c.getFinalizationClaimToken(),c.getResultLastError(),c.getCompletedAt(),c.getTotalPoints(),
                accounts.findById(c.getRewardAccountId()).orElseThrow().getBalance(),entries.countByCompetitionId(id),
                results.findByCompetitionIdOrderByMatchId(id).stream().collect(Collectors.toMap(MatchResultEntity::getMatchId,MatchResultEntity::getPoints)),
                c.getRewardAccountId());
    }
    public record State(Status status, Instant requestedAt, Instant publishAt, Instant claimAt, UUID token,
            String error, Instant completedAt, long totalPoints, long rewardBalance, long rewardEntries,
            Map<String,Long> matches, long rewardAccountId) {
        public State { matches = Map.copyOf(matches); }
    }
}
