package io.github.heun0180.portfolio.settlement;

import jakarta.persistence.*;

@Entity
@Table(name = "match_result", uniqueConstraints = @UniqueConstraint(
        name = "uk_result_competition_match", columnNames = {"competition_id", "match_id"}))
public class MatchResultEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "competition_id", nullable = false) private Competition competition;
    @Column(name = "match_id", nullable = false) private String matchId;
    @Column(nullable = false) private long points;
    protected MatchResultEntity() { }
    public MatchResultEntity(Competition competition, SettlementData.MatchPoints match) {
        this.competition = competition; matchId = match.matchId(); points = match.points();
    }
    public String getMatchId() { return matchId; }
    public long getPoints() { return points; }
}
