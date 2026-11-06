package com.ledgerline.repository;

import com.ledgerline.model.Account;
import com.ledgerline.model.AccountType;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
public class AccountRepository {

    private final JdbcTemplate jdbcTemplate;

    public AccountRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<Account> ACCOUNT_ROW_MAPPER = (rs, rowNum) -> new Account(
        rs.getLong("id"),
        rs.getString("name"),
        AccountType.valueOf(rs.getString("type")),
        rs.getString("currency"),
        rs.getLong("balance"),
        rs.getTimestamp("created_at").toInstant()
    );

    public Optional<Account> findById(Long id) {
        String sql = "SELECT id, name, type, currency, balance, created_at FROM accounts WHERE id = ?";
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(sql, ACCOUNT_ROW_MAPPER, id));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Acquires an exclusive write lock (SELECT ... FOR UPDATE) on the specified account.
     * MUST be called within an active transaction.
     */
    public Optional<Account> lockById(Long id) {
        String sql = "SELECT id, name, type, currency, balance, created_at FROM accounts WHERE id = ? FOR UPDATE";
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(sql, ACCOUNT_ROW_MAPPER, id));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Locks all involved accounts strictly in ASCENDING numerical order of ID.
     * Prevents cyclic lock ordering deadlocks across concurrent opposing transfers.
     */
    public Map<Long, Account> lockAccountsInOrder(Long... accountIds) {
        // Collect unique, sorted IDs in ascending order
        SortedSet<Long> sortedIds = new TreeSet<>();
        for (Long id : accountIds) {
            if (id != null) {
                sortedIds.add(id);
            }
        }

        Map<Long, Account> lockedAccounts = new LinkedHashMap<>();
        for (Long id : sortedIds) {
            Optional<Account> account = lockById(id);
            account.ifPresent(acc -> lockedAccounts.put(id, acc));
        }

        return lockedAccounts;
    }

    public Optional<Account> findExternalWorldAccount() {
        String sql = "SELECT id, name, type, currency, balance, created_at FROM accounts WHERE type = 'EXTERNAL' ORDER BY id ASC LIMIT 1";
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(sql, ACCOUNT_ROW_MAPPER));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public boolean isUserMemberOfAccount(Long accountId, Long userId) {
        String sql = "SELECT COUNT(*) FROM account_members WHERE account_id = ? AND user_id = ?";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, accountId, userId);
        return count != null && count > 0;
    }

    public void addMember(Long accountId, Long userId) {
        String sql = "INSERT INTO account_members (account_id, user_id, created_at) VALUES (?, ?, CURRENT_TIMESTAMP) ON CONFLICT DO NOTHING";
        jdbcTemplate.update(sql, accountId, userId);
    }

    public Account createAccount(String name, AccountType type, String currency, long initialBalance) {
        String sql = "INSERT INTO accounts (name, type, currency, balance, created_at) " +
                     "VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP) RETURNING id, name, type, currency, balance, created_at";
        return jdbcTemplate.queryForObject(sql, ACCOUNT_ROW_MAPPER, name, type.name(), currency, initialBalance);
    }

    public void updateBalance(Long accountId, long newBalance) {
        String sql = "UPDATE accounts SET balance = ? WHERE id = ?";
        jdbcTemplate.update(sql, newBalance, accountId);
    }
}

