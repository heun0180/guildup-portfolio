package io.github.heun0180.portfolio.settlement;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RewardService {
    private final RewardAccountRepository accounts;
    private final RewardEntryRepository entries;
    public RewardService(RewardAccountRepository accounts, RewardEntryRepository entries) {
        this.accounts = accounts; this.entries = entries;
    }
    @Transactional
    public boolean credit(Competition competition) {
        RewardAccount account = accounts.lockById(competition.getRewardAccountId()).orElseThrow();
        if (entries.existsByCompetitionId(competition.getId())) return false;
        entries.save(new RewardEntry(competition, account, 3));
        account.credit(3);
        return true;
    }
}
