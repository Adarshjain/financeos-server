package com.financeos.domain.account;

import com.financeos.api.account.dto.AccountResponse;
import com.financeos.api.account.dto.CardCycleHistoryItemResponse;
import com.financeos.api.account.dto.CardCycleSummaryResponse;
import com.financeos.api.account.dto.CreateAccountRequest;
import com.financeos.api.account.dto.CreditLimitPoolRequest;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.card.*;
import com.financeos.domain.holding.HoldingValuationService;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementCreditCardDetails;
import com.financeos.domain.statement.StatementRepository;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.gmail.domain.GmailBackfillDemand;
import com.financeos.gmail.domain.GmailBackfillDemandRepository;
import com.financeos.gmail.ingest.event.AccountIngestChangedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class AccountService {

    private final AccountRepository accountRepository;
    private final CardholderRepository cardholderRepository;
    private final CardRepository cardRepository;
    private final UserRepository userRepository;
    private final StatementRepository statementRepository;
    private final TransactionRepository transactionRepository;
    private final HoldingValuationService holdingValuationService;
    private final GmailBackfillDemandRepository backfillDemandRepository;
    private final ApplicationEventPublisher eventPublisher;

    public AccountService(AccountRepository accountRepository,
            CardholderRepository cardholderRepository,
            CardRepository cardRepository,
            UserRepository userRepository,
            StatementRepository statementRepository,
            TransactionRepository transactionRepository,
            HoldingValuationService holdingValuationService,
            GmailBackfillDemandRepository backfillDemandRepository,
            ApplicationEventPublisher eventPublisher) {
        this.accountRepository = accountRepository;
        this.cardholderRepository = cardholderRepository;
        this.cardRepository = cardRepository;
        this.userRepository = userRepository;
        this.statementRepository = statementRepository;
        this.transactionRepository = transactionRepository;
        this.holdingValuationService = holdingValuationService;
        this.backfillDemandRepository = backfillDemandRepository;
        this.eventPublisher = eventPublisher;
    }

    /** A bank is optional; when sent it must be a catalog id (brokers and generic accounts never carry one). */
    private static void validateBank(CreateAccountRequest request) {
        String bank = request.bank();
        if (bank == null) {
            return;
        }
        if (!BankCatalog.isKnown(bank)) {
            throw new ValidationException("Unknown bank: " + bank);
        }
    }

    public Account createAccount(CreateAccountRequest request) {
        validateBank(request);
        UUID userId = UserContext.getCurrentUserId();
        User user = userRepository.getReferenceById(userId);

        Account account = new Account(request.name(), request.type());
        account.setUser(user);
        account.setExcludeFromNetAsset(request.excludeFromNetAsset() != null ? request.excludeFromNetAsset() : false);
        
        FinancialPosition position = request.financialPosition();
        if (position == null && request.type() == AccountType.broker) {
            position = FinancialPosition.asset;
        }
        account.setFinancialPosition(position);
        account.setDescription(request.description());
        account.setBank(request.bank());
        account.setIngestFromDate(request.ingestFromDate());

        switch (request) {
            case CreateAccountRequest.BankAccountRequest bankReq -> {
                AccountBankDetails details = new AccountBankDetails(
                        account,
                        bankReq.openingBalance(),
                        bankReq.last4(),
                        bankReq.statementPassword());
                details.setUser(user);
                account.setBankDetails(details);
            }
            case CreateAccountRequest.CreditCardRequest ccReq -> {
                account = addCreditCardDetails(account, ccReq);
            }
            case CreateAccountRequest.BrokerRequest brokerReq -> {
                account = addBrokerDetails(account, brokerReq);
            }
            case CreateAccountRequest.GenericAccountRequest genericReq -> {
                // No extra details to update
            }
        }

        Account saved = accountRepository.save(account);
        populateBalanceInfo(saved);

        if (saved.getIngestFromDate() != null) {
            ratchetDemand(user, saved.getIngestFromDate());
        }
        if (saved.getCardholders() != null && !saved.getCardholders().isEmpty()) {
            for (Cardholder ch : saved.getCardholders()) {
                if (ch.getCards() != null) {
                    for (Card c : ch.getCards()) {
                        if (c.getLast4() != null) {
                            eventPublisher.publishEvent(new AccountIngestChangedEvent(userId, c.getLast4(), saved.getIngestFromDate()));
                        }
                    }
                }
            }
        }
        String last4 = extractLast4(saved);
        if (last4 != null) {
            eventPublisher.publishEvent(new AccountIngestChangedEvent(userId, last4, saved.getIngestFromDate()));
        }

        return saved;
    }

    /**
     * Initialises the LAZY {@code cardholders} and their {@code cards} collections while the service transaction is open.
     */
    private void initCardholders(Account account) {
        if (account.getCardholders() != null) {
            account.getCardholders().size();
            for (Cardholder ch : account.getCardholders()) {
                if (ch.getCards() != null) {
                    ch.getCards().size();
                }
            }
        }
    }

    @Transactional(readOnly = true)
    public List<Account> getAllAccounts() {
        List<Account> accounts = accountRepository.findAll();
        if (accounts.isEmpty()) {
            return accounts;
        }

        List<UUID> accountIds = accounts.stream().map(Account::getId).toList();

        // 1. Batch load cardholders with cards in 1 query for all accounts
        List<Cardholder> allCardholders = cardholderRepository.findByAccountIdInWithCards(accountIds);
        java.util.Map<UUID, List<Cardholder>> cardholdersByAccount = allCardholders.stream()
                .collect(java.util.stream.Collectors.groupingBy(ch -> ch.getAccount().getId()));

        accounts.forEach(account -> {
            List<Cardholder> chList = cardholdersByAccount.getOrDefault(account.getId(), List.of());
            account.setCardholders(new ArrayList<>(chList));
        });

        populateBalances(accounts);

        return accounts;
    }

    /**
     * Fills the balances, effective limits, utilisation and pool usage of {@code accounts} from one
     * batch balance query (plus the pools' own rows): the figures GET /accounts reports. A pooled card's
     * pool usage is only complete when {@code accounts} holds every member of the pool.
     */
    @Transactional(readOnly = true)
    public void populateBalances(List<Account> accounts) {
        // Batch compute balances in 1 query for all non-broker accounts
        List<UUID> nonBrokerIds = accounts.stream()
                .filter(a -> a.getType() != AccountType.broker)
                .map(Account::getId)
                .toList();

        List<UUID> brokerIds = accounts.stream()
                .filter(a -> a.getType() == AccountType.broker)
                .map(Account::getId)
                .toList();
        java.util.Map<UUID, BigDecimal> brokerValues = brokerIds.isEmpty()
                ? java.util.Map.of()
                : holdingValuationService.getBrokerMarketValues(brokerIds);

        java.util.Map<UUID, AccountRepositoryCustom.AccountBalanceBatch> balanceBatches = nonBrokerIds.isEmpty()
                ? java.util.Map.of()
                : accountRepository.findAccountBalanceBatches(nonBrokerIds);

        for (Account account : accounts) {
            if (account.getType() == AccountType.broker) {
                BigDecimal cash = account.getBrokerDetails() != null && account.getBrokerDetails().getCashBalance() != null
                        ? account.getBrokerDetails().getCashBalance()
                        : BigDecimal.ZERO;
                BigDecimal marketValue = brokerValues.getOrDefault(account.getId(), BigDecimal.ZERO);
                account.setCalculatedBalance(marketValue.add(cash));
                account.setBalanceAnchored(false);
                account.setAnchorDate(null);
                account.setReconciliationGap(null);
            } else {
                var batch = balanceBatches.get(account.getId());
                BalanceMath.apply(
                        account,
                        batch != null ? batch.anchorDate() : null,
                        batch != null ? batch.anchorCutoff() : null,
                        batch != null ? batch.anchorClosingBalance() : null,
                        batch != null ? batch.totalSum() : null,
                        batch != null ? batch.postAnchorSum() : null
                );
                // The statement-limit fallback rides on the same batch row: no per-card query.
                if (account.getType() == AccountType.credit_card) {
                    BigDecimal limit = CardUtilization.limit(CardUtilization.accountLimit(account),
                            batch != null ? batch.latestStatementCreditLimit() : null);
                    account.setEffectiveCreditLimit(limit);
                    account.setUtilizationPct(CardUtilization.pct(account.getCalculatedBalance(), limit, null));
                } else {
                    account.setEffectiveCreditLimit(null);
                    account.setUtilizationPct(null);
                }
            }
        }

        // Every member of a user's pool is in this list, so pool usage needs no further query
        // (beyond loading each pool's own row).
        java.util.Map<UUID, List<Account>> byPool = new java.util.LinkedHashMap<>();
        for (Account account : accounts) {
            CreditLimitPool pool = account.getType() == AccountType.credit_card ? CardUtilization.limitPool(account) : null;
            if (pool != null) {
                byPool.computeIfAbsent(pool.getId(), k -> new ArrayList<>()).add(account);
            }
        }
        for (List<Account> members : byPool.values()) {
            applyPoolUsage(CardUtilization.limitPool(members.get(0)), members);
        }
    }

    private static final int IN_LIST_CHUNK = 900;

    /**
     * Sets {@code lastTransactionDate} on each account (null for brokers and accounts with no transactions)
     * from one GROUP BY query per 900 ids. Only the account GET list and single GET call it: every other
     * reader of {@link #populateBalances} / {@link #getAccountById} skips the query.
     */
    @Transactional(readOnly = true)
    public void populateLastTransactionDates(List<Account> accounts) {
        List<UUID> nonBrokerIds = accounts.stream()
                .filter(a -> a.getType() != AccountType.broker)
                .map(Account::getId)
                .toList();
        java.util.Map<UUID, LocalDate> lastDates = lastTransactionDates(nonBrokerIds);
        for (Account account : accounts) {
            account.setLastTransactionDate(lastDates.get(account.getId()));
        }
    }

    /** The latest transaction date per account id (accounts without transactions are absent), in chunks of 900 ids. */
    private java.util.Map<UUID, LocalDate> lastTransactionDates(List<UUID> accountIds) {
        java.util.Map<UUID, LocalDate> result = new java.util.HashMap<>();
        for (int i = 0; i < accountIds.size(); i += IN_LIST_CHUNK) {
            List<UUID> chunk = accountIds.subList(i, Math.min(accountIds.size(), i + IN_LIST_CHUNK));
            for (TransactionRepository.AccountLastDateProjection row : transactionRepository.findLastTransactionDatesByAccountIds(chunk)) {
                result.put(row.getAccountId(), row.getLastDate());
            }
        }
        return result;
    }

    /** Sets the pool's usage on every member and measures each member's utilisation on the pool. */
    private static void applyPoolUsage(CreditLimitPool pool, List<Account> members) {
        CreditLimitPoolUsage usage = CardUtilization.poolUsage(pool, members);
        for (Account member : members) {
            member.setLimitPoolUsage(usage);
            member.setEffectiveCreditLimit(usage.creditLimit());
            member.setUtilizationPct(usage.utilizationPct());
        }
    }

    @Transactional(readOnly = true)
    public Account getAccountById(UUID id) {
        Account account = accountRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Account", id));
        UUID currentUserId = UserContext.getCurrentUserId();
        if (currentUserId != null && !account.getUser().getId().equals(currentUserId)) {
            throw new ValidationException("You do not have permission to access this account.");
        }
        populateBalanceInfo(account);
        initCardholders(account);
        return account;
    }

    /**
     * The current user's account with its balance populated exactly as {@link #getAccountById} does
     * (cardholders left lazy), or empty when no such account exists or it belongs to someone else.
     */
    @Transactional(readOnly = true)
    public Optional<Account> findOwnedAccount(UUID id) {
        UUID currentUserId = UserContext.getCurrentUserId();
        return accountRepository.findById(id)
                .filter(account -> account.getUser() != null && account.getUser().getId().equals(currentUserId))
                .map(account -> {
                    populateBalanceInfo(account);
                    return account;
                });
    }

    public Account updateAccount(UUID id, CreateAccountRequest request) {
        validateBank(request);
        Account account = getAccountById(id);

        if (account.getType() != request.type()) {
            throw new ValidationException("Changing account type is not supported");
        }

        LocalDate oldDate = account.getIngestFromDate();
        String oldLast4 = extractLast4(account);
        LocalDate newDate = request.ingestFromDate();

        account.setName(request.name());
        account.setExcludeFromNetAsset(request.excludeFromNetAsset() != null ? request.excludeFromNetAsset() : false);
        account.setFinancialPosition(request.financialPosition());
        account.setDescription(request.description());
        // The live form does not send a bank: an omitted bank keeps the stored one (it is never cleared via the API).
        if (request.bank() != null) {
            account.setBank(request.bank());
        }
        account.setIngestFromDate(newDate);

        switch (request) {
            case CreateAccountRequest.BankAccountRequest bankReq -> {
                if (account.getBankDetails() != null) {
                    AccountBankDetails details = account.getBankDetails();
                    details.setOpeningBalance(bankReq.openingBalance());
                    details.setLast4(bankReq.last4());
                    details.setStatementPassword(bankReq.statementPassword());
                } else {
                    AccountBankDetails details = new AccountBankDetails(
                            account,
                            bankReq.openingBalance(),
                            bankReq.last4(),
                            bankReq.statementPassword());
                    details.setUser(account.getUser());
                    account.setBankDetails(details);
                }
            }
            case CreateAccountRequest.CreditCardRequest ccReq -> account = addCreditCardDetails(account, ccReq);
            case CreateAccountRequest.BrokerRequest brokerReq -> account = addBrokerDetails(account, brokerReq);
            case CreateAccountRequest.GenericAccountRequest genericReq -> {
                // No extra details to update
            }
        }

        Account saved = accountRepository.save(account);
        // Leaving (or switching) a shared limit can empty the old one.
        accountRepository.deleteEmptyLimitPools(saved.getUser().getId());
        populateBalanceInfo(saved);

        boolean dateAdvanced = newDate != null && (oldDate == null || newDate.isBefore(oldDate));
        if (dateAdvanced) {
            ratchetDemand(saved.getUser(), newDate);
        }

        String newLast4 = extractLast4(saved);
        boolean last4Changed = !Objects.equals(oldLast4, newLast4);
        if ((dateAdvanced || last4Changed) && newLast4 != null) {
            eventPublisher.publishEvent(new AccountIngestChangedEvent(
                    saved.getUser().getId(),
                    newLast4,
                    saved.getIngestFromDate()
            ));
        }

        return saved;
    }

    public void deleteAccount(UUID id) {
        Account account = getAccountById(id);
        accountRepository.delete(account);
        // The deleted card may have been the last account on a shared limit.
        accountRepository.deleteEmptyLimitPools(account.getUser().getId());
    }

    public AccountResponse closeAccount(UUID id, LocalDate closedOn) {
        Account account = getAccountById(id);
        LocalDate targetClosedOn = closedOn != null ? closedOn : AppTime.today();

        // Validate close date is not before earliest transaction date
        LocalDate minTxnDate = transactionRepository.findMinDateByAccountId(id);
        if (minTxnDate != null && targetClosedOn.isBefore(minTxnDate)) {
            throw new ValidationException("Cannot close account on " + targetClosedOn + ": account has transactions dating back to " + minTxnDate);
        }

        account.setClosedOn(targetClosedOn);
        Account saved = accountRepository.save(account);
        populateBalanceInfo(saved);
        initCardholders(saved);

        List<String> warnings = new ArrayList<>();
        BigDecimal currentBalance = saved.getCalculatedBalance();
        if (currentBalance != null && currentBalance.compareTo(BigDecimal.ZERO) != 0) {
            warnings.add("Account closed with non-zero balance: " + currentBalance + ". Consider zeroing the balance before final reconciliation.");
        }

        String last4 = extractLast4(saved);
        return AccountResponse.from(saved, last4, null, warnings);
    }

    public AccountResponse reopenAccount(UUID id) {
        Account account = getAccountById(id);
        // On reopen: clear closedOn, then re-check open-card last4 uniqueness
        account.setClosedOn(null);
        if (account.getCardholders() != null) {
            for (Cardholder ch : account.getCardholders()) {
                if (ch.getClosedOn() == null) {
                    Card openCard = ch.openCard().orElse(null);
                    if (openCard != null) {
                        Optional<Card> collision = cardRepository.findOpenByAccountIdAndLast4(account.getId(), openCard.getLast4());
                        if (collision.isPresent() && !collision.get().getId().equals(openCard.getId())) {
                            throw new ValidationException("Cannot reopen account: card with last 4 digits " + openCard.getLast4() + " collides with an open card on this account");
                        }
                    }
                }
            }
        }

        Account saved = accountRepository.save(account);
        populateBalanceInfo(saved);
        initCardholders(saved);
        String last4 = extractLast4(saved);
        return AccountResponse.from(saved, last4, null);
    }

    private void ratchetDemand(User user, LocalDate newDate) {
        if (newDate == null) {
            return;
        }
        var demandOpt = backfillDemandRepository.findById(user.getId());
        if (demandOpt.isEmpty()) {
            GmailBackfillDemand demand = new GmailBackfillDemand(user, newDate);
            backfillDemandRepository.save(demand);
        } else {
            GmailBackfillDemand demand = demandOpt.get();
            if (demand.getFloorDate() == null || newDate.isBefore(demand.getFloorDate())) {
                demand.setFloorDate(newDate);
                backfillDemandRepository.save(demand);
            }
        }
    }

    public String extractLast4(Account account) {
        if (account.getBankDetails() != null && account.getBankDetails().getLast4() != null) {
            return account.getBankDetails().getLast4();
        }
        if (account.getType() == AccountType.credit_card) {
            return account.primaryLast4();
        }
        return null;
    }

    public Account addBrokerDetails(Account account, CreateAccountRequest.BrokerRequest request) {
        if (account.getType() != AccountType.broker) {
            throw new ValidationException("Broker details can only be added to broker accounts");
        }

        if (account.getBrokerDetails() != null) {
            AccountBrokerDetails details = account.getBrokerDetails();
            details.setProvider(request.provider());
            details.setClientId(request.clientId());
            if (request.cashBalance() != null) {
                details.setCashBalance(request.cashBalance());
            }
        } else {
            AccountBrokerDetails details = new AccountBrokerDetails(
                    account,
                    request.provider(),
                    request.clientId(),
                    request.cashBalance() != null ? request.cashBalance() : BigDecimal.ZERO);
            details.setUser(account.getUser());
            account.setBrokerDetails(details);
        }

        return accountRepository.save(account);
    }

    public Account addCreditCardDetails(Account account, CreateAccountRequest.CreditCardRequest request) {
        if (account.getType() != AccountType.credit_card) {
            throw new ValidationException("Credit card details can only be added to credit card accounts");
        }

        if (request.limitPool() == null && request.creditLimit() == null) {
            throw new ValidationException("Credit limit is required");
        }

        account.setRewardAnniversaryDate(request.anniversaryDate());

        if (request.replacesAccountId() != null) {
            // Only an account of the same owner can be replaced; another user's id reads as not found.
            Account replacesAccount = accountRepository.findByIdAndUser_Id(request.replacesAccountId(), account.getUser().getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Account", request.replacesAccountId()));
            account.setReplacesAccount(replacesAccount);
        }

        if (account.getCreditCardDetails() != null) {
            AccountCreditCardDetails details = account.getCreditCardDetails();
            details.setCreditLimit(request.creditLimit());
            details.setStatementPassword(request.statementPassword());
            details.setIssuer(request.issuer());
            details.setProductName(request.productName());
        } else {
            AccountCreditCardDetails details = new AccountCreditCardDetails(
                    account,
                    request.creditLimit(),
                    request.statementPassword(),
                    request.issuer(),
                    request.productName());
            details.setUser(account.getUser());
            account.setCreditCardDetails(details);
        }
        CreditLimitPool pool = resolveLimitPool(account, request);
        account.getCreditCardDetails().setLimitPool(pool);
        if (pool != null) {
            // A pooled card has no limit of its own: the pool's limit is the only one.
            account.getCreditCardDetails().setCreditLimit(null);
        }

        // Upsert primary cardholder and primary card
        Cardholder primaryCh = null;
        if (account.getCardholders() != null) {
            for (Cardholder ch : account.getCardholders()) {
                if (ch.isPrimary()) {
                    primaryCh = ch;
                    break;
                }
            }
        }
        if (primaryCh == null && account.getId() != null) {
            primaryCh = cardholderRepository.findPrimaryByAccountId(account.getId()).orElse(null);
        }
        if (primaryCh == null) {
            primaryCh = new Cardholder();
            primaryCh.setUser(account.getUser());
            primaryCh.setAccount(account);
            primaryCh.setRole(CardholderRole.PRIMARY);
            primaryCh.setRelationship(CardholderRelationship.SELF);
            primaryCh.setOpenedOn(request.anniversaryDate());
            // Persisted via Account.cardholders cascade — an explicit save here fails on the
            // create path, where the account itself is still transient.
            account.getCardholders().add(primaryCh);
        } else if (primaryCh.getOpenedOn() == null && request.anniversaryDate() != null) {
            primaryCh.setOpenedOn(request.anniversaryDate());
        }

        if (request.last4() != null && !request.last4().isBlank()) {
            Card openCard = primaryCh.openCard().orElse(null);
            if (openCard != null) {
                openCard.setLast4(request.last4());
                if (openCard.getIssuedOn() == null && request.anniversaryDate() != null) {
                    openCard.setIssuedOn(request.anniversaryDate());
                }
            } else {
                Card card = new Card();
                card.setUser(account.getUser());
                card.setAccount(account);
                card.setCardholder(primaryCh);
                card.setLast4(request.last4());
                card.setIssuedOn(request.anniversaryDate());
                primaryCh.getCards().add(card);
            }
        }

        return accountRepository.save(account);
    }



    /**
     * The shared limit the request puts the account in: null when it names none; the caller's
     * existing pool (renamed or re-limited when the request says so) when it carries an id; else a
     * new pool, persisted with the account. A pooled account's own limit is ignored (stored null).
     */
    private CreditLimitPool resolveLimitPool(Account account, CreateAccountRequest.CreditCardRequest request) {
        CreditLimitPoolRequest ref = request.limitPool();
        if (ref == null) {
            return null;
        }
        CreditLimitPool pool;
        if (ref.id() != null) {
            pool = accountRepository.findOwnedLimitPool(ref.id(), account.getUser().getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Shared credit limit", ref.id()));
            if (ref.name() != null) {
                pool.setName(validPoolName(ref.name()));
            }
            if (ref.creditLimit() != null) {
                pool.setCreditLimit(validPoolLimit(ref.creditLimit()));
            }
        } else {
            pool = new CreditLimitPool(account.getUser(), validPoolName(ref.name()), validPoolLimit(ref.creditLimit()));
        }
        return pool;
    }

    private static String validPoolName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new ValidationException("Shared limit name is required");
        }
        if (trimmed.length() > 150) {
            throw new ValidationException("Shared limit name must be at most 150 characters");
        }
        return trimmed;
    }

    private static BigDecimal validPoolLimit(BigDecimal limit) {
        if (limit == null || limit.signum() <= 0) {
            throw new ValidationException("Shared credit limit must be greater than zero");
        }
        return limit;
    }

    @Transactional(readOnly = true)
    public CardCycleSummaryResponse getCardCycleSummary(UUID accountId) {
        Account account = getAccountById(accountId);
        if (account.getType() != AccountType.credit_card) {
            throw new ValidationException("Cycle summary is only supported for credit card accounts");
        }

        List<Statement> statements = statementRepository.findByAccountIdOrderByPeriodEndAsc(accountId);
        if (statements.isEmpty()) {
            return null;
        }

        List<CardCycleHistoryItemResponse> history = statements.stream()
                .map(s -> {
                    StatementCreditCardDetails d = s.getCreditCardDetails();
                    return new CardCycleHistoryItemResponse(
                            s.getPeriodEnd(),
                            d != null ? d.getTotalPurchases() : null,
                            d != null ? d.getPaymentsReceived() : null,
                            d != null ? d.getFinanceCharges() : null,
                            d != null ? d.getFeesAndCharges() : null,
                            d != null ? d.getRewardPointsBalance() : null
                    );
                })
                .toList();

        Statement latest = statements.get(statements.size() - 1);
        StatementCreditCardDetails d = latest.getCreditCardDetails();

        Long daysUntilDue = null;
        if (d != null && d.getPaymentDueDate() != null) {
            daysUntilDue = java.time.temporal.ChronoUnit.DAYS.between(AppTime.today(), d.getPaymentDueDate());
        }

        // Live utilisation (balance now ÷ limit), the same figure GET /accounts reports; the limit
        // shown beside it is the one it was computed against.
        BigDecimal creditLimit = account.getEffectiveCreditLimit();
        BigDecimal utilizationPct = account.getUtilizationPct();

        return new CardCycleSummaryResponse(
                latest.getId(),
                latest.getPeriodStart(),
                latest.getPeriodEnd(),
                d != null ? d.getTotalAmountDue() : null,
                d != null ? d.getMinimumAmountDue() : null,
                d != null ? d.getPaymentDueDate() : null,
                daysUntilDue,
                creditLimit,
                d != null ? d.getAvailableCreditLimit() : null,
                utilizationPct,
                d != null ? d.getRewardPointsBalance() : null,
                history
        );
    }

    /**
     * Fills the account's transient balance fields (and, for a credit card, its live utilisation)
     * exactly as {@link #getAccountById} does, without the ownership check: for callers that already
     * hold an owned account entity.
     */
    @Transactional(readOnly = true)
    public Account populateLiveBalance(Account account) {
        populateBalanceInfo(account);
        return account;
    }

    /** Live utilisation for a card whose balance is populated; the statement limit is read only when needed. */
    private void applyUtilization(Account account) {
        if (account.getType() != AccountType.credit_card) {
            account.setEffectiveCreditLimit(null);
            account.setUtilizationPct(null);
            return;
        }
        CreditLimitPool pool = CardUtilization.limitPool(account);
        if (pool != null) {
            applyPoolUsage(pool, poolMembersWithBalances(account, pool));
            return;
        }
        account.setLimitPoolUsage(null);
        BigDecimal limit = CardUtilization.creditLimit(account, statementRepository);
        account.setEffectiveCreditLimit(limit);
        account.setUtilizationPct(CardUtilization.pct(account.getCalculatedBalance(), limit, null));
    }

    /**
     * {@code account} (balance already populated) plus every other account in its pool, their
     * balances filled from one batch query, the same figures GET /accounts computes.
     */
    private List<Account> poolMembersWithBalances(Account account, CreditLimitPool pool) {
        List<Account> members = new ArrayList<>();
        members.add(account);
        List<Account> others = accountRepository.findLimitPoolMembers(pool.getId(), account.getUser().getId()).stream()
                .filter(m -> !m.getId().equals(account.getId()))
                .toList();
        if (others.isEmpty()) {
            return members;
        }
        var batches = accountRepository.findAccountBalanceBatches(others.stream().map(Account::getId).toList());
        for (Account other : others) {
            var batch = batches.get(other.getId());
            BalanceMath.apply(
                    other,
                    batch != null ? batch.anchorDate() : null,
                    batch != null ? batch.anchorCutoff() : null,
                    batch != null ? batch.anchorClosingBalance() : null,
                    batch != null ? batch.totalSum() : null,
                    batch != null ? batch.postAnchorSum() : null);
            members.add(other);
        }
        return members;
    }

    private void populateBalanceInfo(Account account) {
        if (account.getType() == AccountType.broker) {
            BigDecimal cash = account.getBrokerDetails() != null && account.getBrokerDetails().getCashBalance() != null
                    ? account.getBrokerDetails().getCashBalance()
                    : BigDecimal.ZERO;
            BigDecimal marketValue = holdingValuationService.getBrokerMarketValues(List.of(account.getId()))
                    .getOrDefault(account.getId(), BigDecimal.ZERO);
            account.setCalculatedBalance(marketValue.add(cash));
            account.setBalanceAnchored(false);
            account.setAnchorDate(null);
            account.setReconciliationGap(null);
            return;
        }

        List<StatementRepository.AnchorStatementProjection> eligible = statementRepository.findEligibleAnchorStatements(account.getId(), org.springframework.data.domain.PageRequest.of(0, 1));
        if (!eligible.isEmpty()) {
            StatementRepository.AnchorStatementProjection anchor = eligible.get(0);
            BigDecimal anchorClosingBalance = anchor.getClosingBalance();
            LocalDate anchorDate = anchor.getPeriodEnd();
            // Everything dated after the statement's last billed day is unbilled (BalanceMath.cutoff).
            LocalDate cutoff = BalanceMath.cutoff(anchorDate, statementRepository.findLastLineDate(anchor.getId()));

            TransactionRepository.BalanceAggregatesProjection aggregates = transactionRepository.findBalanceAggregatesByAccountId(account.getId(), cutoff);
            BigDecimal totalSum = aggregates != null ? aggregates.getTotalSum() : null;
            BigDecimal postAnchorSum = aggregates != null ? aggregates.getPostAnchorSum() : null;

            BalanceMath.apply(account, anchorDate, cutoff, anchorClosingBalance, totalSum, postAnchorSum);
        } else {
            BigDecimal totalSum = transactionRepository.findTotalTransactionSumByAccountId(account.getId());
            BalanceMath.apply(account, null, null, totalSum, null);
        }
        applyUtilization(account);
    }

    /**
     * The balance made of the account's own transactions dated on or before {@code asOf}
     * (the statement-anchor-free balance of {@link #populateBalanceInfo}, cut at that day), in the
     * account's own sign: a credit card's is negative when money is owed.
     */
    @Transactional(readOnly = true)
    public BigDecimal transactionBalanceAsOf(Account account, LocalDate asOf) {
        TransactionRepository.BalanceAggregatesProjection aggregates =
                transactionRepository.findBalanceAggregatesByAccountId(account.getId(), asOf);
        BigDecimal total = aggregates != null && aggregates.getTotalSum() != null ? aggregates.getTotalSum() : BigDecimal.ZERO;
        BigDecimal after = aggregates != null && aggregates.getPostAnchorSum() != null ? aggregates.getPostAnchorSum() : BigDecimal.ZERO;
        return BalanceMath.pureTransactionBalance(account, total.subtract(after));
    }
}
