package io.github.heun0180.portfolio.bingo;

import jakarta.persistence.*;
import java.util.Objects;

@Entity
@Table(name = "bingo_participant", uniqueConstraints = @UniqueConstraint(
        name = "uk_participant_event_account", columnNames = {"event_id", "account_code"}))
public class BingoParticipant {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private BingoEvent event;
    @Column(name = "account_code", nullable = false)
    private String accountCode;
    @Column(nullable = false)
    private long progress;

    protected BingoParticipant() { }

    public BingoParticipant(BingoEvent event, String accountCode) {
        this.event = Objects.requireNonNull(event);
        changeAccount(accountCode);
    }

    public Long getId() { return id; }
    public String getAccountCode() { return accountCode; }
    public long getProgress() { return progress; }
    public void addProgress(long value) {
        if (value < 0) throw new IllegalArgumentException("negative contribution");
        progress = Math.addExact(progress, value);
    }
    public void changeAccount(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("blank account");
        accountCode = value;
    }
}
