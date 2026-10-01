package com.tribule.ledger.ledger;

import com.tribule.ledger.money.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/** Reads balances, and rebuilds them from the journal when asked to prove them. */
@Service
public class BalanceService {

    private final AccountRepository accounts;
    private final BalanceRepository balances;

    public BalanceService(AccountRepository accounts, BalanceRepository balances) {
        this.accounts = accounts;
        this.balances = balances;
    }

    /** An account with no entries yet has a zero balance rather than no balance. */
    @Transactional(readOnly = true)
    public AccountBalance balanceOf(String accountCode) {
        Account account = accounts.require(accountCode);
        return balances.findByCode(accountCode).orElseGet(() -> new AccountBalance(
                account.id(), account.code(), account.currency().code(), 0L, 0L, 0L, null, 0L, null));
    }

    @Transactional(readOnly = true)
    public Money normalBalance(String accountCode) {
        Account account = accounts.require(accountCode);
        return Money.ofMinor(balanceOf(accountCode).normalBalanceMinor(), account.currency());
    }

    @Transactional(readOnly = true)
    public List<AccountBalance> balancesForCurrency(String currencyCode) {
        return balances.findByCurrency(currencyCode);
    }

    @Transactional(readOnly = true)
    public List<AccountBalance> allBalances() {
        return balances.findAll();
    }

    @Transactional(readOnly = true)
    public List<TrialBalanceLine> trialBalance() {
        return balances.trialBalance();
    }

    /** Replays the journal for one account and reports what it finds. */
    @Transactional(readOnly = true)
    public BalanceRepository.RebuiltBalance rebuild(String accountCode) {
        return balances.rebuild(accounts.require(accountCode).id());
    }

    @Transactional
    public int snapshot(String accountCode) {
        return balances.snapshot(accounts.require(accountCode).id());
    }

    @Transactional
    public int snapshotAll() {
        return balances.snapshotAll();
    }

    @Transactional(readOnly = true)
    public Optional<AccountBalance> findBalance(String accountCode) {
        return balances.findByCode(accountCode);
    }
}
