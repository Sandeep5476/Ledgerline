package com.ledgerline.service;

import com.ledgerline.dto.StatementEntry;
import com.ledgerline.dto.StatementResponse;
import com.ledgerline.exception.ResourceNotFoundException;
import com.ledgerline.model.Account;
import com.ledgerline.model.UserRole;
import com.ledgerline.repository.AccountRepository;
import com.ledgerline.repository.StatementRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class StatementService {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    private final StatementRepository statementRepository;
    private final AccountRepository accountRepository;

    public StatementService(StatementRepository statementRepository, AccountRepository accountRepository) {
        this.statementRepository = statementRepository;
        this.accountRepository = accountRepository;
    }

    @Transactional(readOnly = true)
    public StatementResponse getStatement(
        Long accountId,
        Instant from,
        Instant to,
        Integer page,
        Integer size,
        Long callerId,
        UserRole callerRole
    ) {
        // 1. Verify existence of account
        Optional<Account> accountOpt = accountRepository.findById(accountId);
        if (accountOpt.isEmpty()) {
            throw new ResourceNotFoundException("Account not found");
        }

        // 2. Authorisation: Account member, ADMIN, or AUDITOR
        boolean isAuthorized = callerRole == UserRole.ADMIN || callerRole == UserRole.AUDITOR;
        if (!isAuthorized) {
            isAuthorized = accountRepository.isUserMemberOfAccount(accountId, callerId);
        }

        if (!isAuthorized) {
            // Return 404 to avoid leaking the account's existence to unauthorized callers
            throw new ResourceNotFoundException("Account not found");
        }

        // 3. Normalize pagination
        int effectivePage = (page == null || page < 0) ? 0 : page;
        int effectiveSize = (size == null || size <= 0) ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        int offset = effectivePage * effectiveSize;

        // 4. Fetch unified postings & refusals in a single query (no N+1)
        List<StatementEntry> entries = statementRepository.findStatementEntries(
            accountId,
            from,
            to,
            effectiveSize,
            offset
        );

        long totalElements = statementRepository.countStatementEntries(accountId, from, to);

        return new StatementResponse(accountId, effectivePage, effectiveSize, totalElements, entries);
    }
}

