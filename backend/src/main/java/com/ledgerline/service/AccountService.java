package com.ledgerline.service;

import com.ledgerline.dto.AccountDto;
import com.ledgerline.exception.ResourceNotFoundException;
import com.ledgerline.model.Account;
import com.ledgerline.model.AccountType;
import com.ledgerline.model.UserRole;
import com.ledgerline.repository.AccountRepository;
import com.ledgerline.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AccountService {

    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;

    public AccountService(
        AccountRepository accountRepository,
        UserRepository userRepository,
        AuditService auditService
    ) {
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
    }

    @Transactional
    public AccountDto createAccount(String name, Long adminId) {
        Account account = accountRepository.createAccount(name, AccountType.USER, "INR", 0L);
        String details = "{\"name\":\"" + name + "\",\"currency\":\"INR\",\"type\":\"USER\"}";
        auditService.logAdminAction(adminId, "CREATE_ACCOUNT", String.valueOf(account.id()), details);
        return toDto(account);
    }

    @Transactional
    public void addMember(Long accountId, Long userId, Long adminId) {
        accountRepository.findById(accountId)
            .orElseThrow(() -> new ResourceNotFoundException("Account not found"));

        userRepository.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        accountRepository.addMember(accountId, userId);
        String details = "{\"accountId\":" + accountId + ",\"userId\":" + userId + "}";
        auditService.logAdminAction(adminId, "ADD_MEMBER", String.valueOf(accountId), details);
    }

    @Transactional(readOnly = true)
    public List<AccountDto> getAccounts(Long callerId, UserRole callerRole) {
        List<Account> accounts;
        if (callerRole == UserRole.ADMIN || callerRole == UserRole.AUDITOR) {
            accounts = accountRepository.findAllAccounts();
        } else {
            accounts = accountRepository.findAccountsByMember(callerId);
        }
        return accounts.stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public AccountDto getAccountById(Long accountId, Long callerId, UserRole callerRole) {
        if (callerRole == UserRole.ADMIN || callerRole == UserRole.AUDITOR) {
            Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found"));
            return toDto(account);
        }

        // For HOLDER: execute a single query to eliminate any timing oracle
        var withMembership = accountRepository.findAccountWithMembership(accountId, callerId)
            .orElseThrow(() -> new ResourceNotFoundException("Account not found"));

        if (!withMembership.isMember()) {
            throw new ResourceNotFoundException("Account not found");
        }

        return toDto(withMembership.account());
    }

    private AccountDto toDto(Account account) {
        return new AccountDto(
            account.id(),
            account.name(),
            account.type().name(),
            account.currency(),
            account.balance(),
            account.createdAt()
        );
    }
}

