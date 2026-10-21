package io.github.heun0180.portfolio.bingo;

import jakarta.persistence.*;

@Entity
@Table(name = "bingo_event")
public class BingoEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Version
    private long version;
    @Column(nullable = false)
    private int pointsPerKill;

    protected BingoEvent() { }

    public BingoEvent(int pointsPerKill) { changePolicy(pointsPerKill); }
    public Long getId() { return id; }
    public long getVersion() { return version; }
    public int getPointsPerKill() { return pointsPerKill; }

    public void changePolicy(int value) {
        if (value < 1) throw new IllegalArgumentException("pointsPerKill must be positive");
        pointsPerKill = value;
    }
}
