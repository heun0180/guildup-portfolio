package io.github.heun0180.portfolio.settlement;

import jakarta.persistence.*;

@Entity
@Table(name = "reward_account")
public class RewardAccount {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private long balance;
    public RewardAccount() { }
    public Long getId() { return id; }
    public long getBalance() { return balance; }
    void credit(long amount) { balance = Math.addExact(balance, amount); }
}
