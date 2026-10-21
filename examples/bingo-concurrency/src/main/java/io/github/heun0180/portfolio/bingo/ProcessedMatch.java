package io.github.heun0180.portfolio.bingo;

import jakarta.persistence.*;

@Entity
@Table(name = "processed_match", uniqueConstraints = @UniqueConstraint(
        name = "uk_processed_event_participant_match",
        columnNames = {"event_id", "participant_id", "match_id"}))
public class ProcessedMatch {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private BingoEvent event;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "participant_id", nullable = false)
    private BingoParticipant participant;
    @Column(name = "match_id", nullable = false)
    private String matchId;

    protected ProcessedMatch() { }
    public ProcessedMatch(BingoEvent event, BingoParticipant participant, String matchId) {
        this.event = event;
        this.participant = participant;
        this.matchId = matchId;
    }
    public String getMatchId() { return matchId; }
    public Long getParticipantId() { return participant.getId(); }
}
