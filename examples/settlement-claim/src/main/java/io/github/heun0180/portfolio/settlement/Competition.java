package io.github.heun0180.portfolio.settlement;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import static io.github.heun0180.portfolio.settlement.SettlementData.Status;

@Entity
@Table(name = "competition")
public class Competition {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Version private long version;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private Status status = Status.IN_PROGRESS;
    @Column(nullable = false) private Instant endsAt;
    private Instant resultRequestedAt;
    private Instant resultPublishAt;
    private Instant finalizationStartedAt;
    private UUID finalizationClaimToken;
    @Column(length = 500) private String resultLastError;
    private Instant completedAt;
    @Column(nullable = false) private long totalPoints;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reward_account_id", nullable = false) private RewardAccount rewardAccount;

    protected Competition() { }
    public Competition(Instant endsAt, RewardAccount recipient) { this.endsAt = endsAt; this.rewardAccount = recipient; }
    public Long getId() { return id; }
    public Status getStatus() { return status; }
    public Instant getEndsAt() { return endsAt; }
    public Instant getResultRequestedAt() { return resultRequestedAt; }
    public Instant getResultPublishAt() { return resultPublishAt; }
    public Instant getFinalizationStartedAt() { return finalizationStartedAt; }
    public UUID getFinalizationClaimToken() { return finalizationClaimToken; }
    public String getResultLastError() { return resultLastError; }
    public Instant getCompletedAt() { return completedAt; }
    public long getTotalPoints() { return totalPoints; }
    public Long getRewardAccountId() { return rewardAccount.getId(); }

    void request(Instant requested, Instant publish) {
        status = Status.RESULT_PENDING;
        resultRequestedAt = requested; resultPublishAt = publish;
        finalizationStartedAt = null; finalizationClaimToken = null; resultLastError = null;
    }
    void claim(Instant started, UUID token) {
        finalizationStartedAt = started; finalizationClaimToken = java.util.Objects.requireNonNull(token);
        resultLastError = null;
    }
    boolean owns(UUID token) { return token != null && token.equals(finalizationClaimToken); }
    void fail() {
        finalizationClaimToken = null;
        resultLastError = "Synthetic collection or persistence failed. Retry after the original claim window.";
        // 시작 시각을 보존한다. 재시도 기준은 실패 시각이 아니라 시작 시각이다.
    }
    void complete(long points, Instant now) {
        totalPoints = points; status = Status.COMPLETED; completedAt = now;
        finalizationStartedAt = null; finalizationClaimToken = null; resultLastError = null;
    }
}
