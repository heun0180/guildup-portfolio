package io.github.heun0180.portfolio.settlement;

import jakarta.persistence.*;

@Entity
@Table(name = "reward_entry", uniqueConstraints = @UniqueConstraint(
        name = "uk_reward_competition", columnNames = "competition_id"))
public class RewardEntry {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "competition_id", nullable = false) private Competition competition;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false) private RewardAccount account;
    @Column(nullable = false) private long amount;
    protected RewardEntry() { }
    public RewardEntry(Competition competition, RewardAccount account, long amount) {
        this.competition = competition; this.account = account; this.amount = amount;
    }
}
