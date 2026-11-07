package com.financeos.gmail.attention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.financeos.api.account.dto.PastCardRequest;
import com.financeos.api.gmail.dto.AssignNumberGroupResponse;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountBankDetails;
import com.financeos.domain.account.AccountIdentifierRepository;
import com.financeos.domain.account.AccountIdentifierService;
import com.financeos.domain.account.AccountIdentifierKind;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.account.card.Card;
import com.financeos.domain.account.card.CardRepository;
import com.financeos.domain.account.card.Cardholder;
import com.financeos.domain.account.card.CardholderRepository;
import com.financeos.domain.account.card.CardholderRole;
import com.financeos.domain.account.card.PastCardService;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.domain.GmailConnectionRepository;
import com.financeos.gmail.domain.GmailProcessedMessage;
import com.financeos.gmail.domain.GmailProcessedMessageRepository;
import com.financeos.gmail.domain.GmailProcessedStatus;
import com.financeos.support.UserDataCleanup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Saving a number as an old card instead of an identifier: from a Needs-attention group and from an account's identifier. */
@SpringBootTest
class UnresolvedNumberServicePastCardIntegrationTest {

    @Autowired private UnresolvedNumberService service;
    @Autowired private PastCardService pastCards;
    @Autowired private AccountIdentifierService identifiers;
    @Autowired private AccountIdentifierRepository identifierRepository;
    @Autowired private CardRepository cardRepository;
    @Autowired private CardholderRepository cardholderRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private GmailConnectionRepository connectionRepository;
    @Autowired private GmailProcessedMessageRepository messageRepository;
    @Autowired private JdbcTemplate jdbc;

    private User user;
    private User otherUser;
    private GmailConnection connection;
    private Account hdfc;

    @BeforeEach
    void setUp() {
        user = saveUser("pastcard");
        otherUser = saveUser("pastcard-other");
        connection = new GmailConnection();
        connection.setUser(user);
        connection.setEmail(user.getEmail());
        connection.setEncryptedRefreshToken("refresh-token");
        connection = connectionRepository.save(connection);
        hdfc = saveAccount(user, "HDFC Savings", LocalDate.of(2026, 10, 7), null);
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(user.getId(), otherUser.getId()));
    }

    @Test
    void assignAsPastCard_createsAClosedCardOnTheNewPrimaryHolder_andSettlesMailLikeAnIdentifier() {
        GmailProcessedMessage old1 = mail("3003", "alerts@hdfcbank.net", "2021-05-10T00:00:00Z");
        GmailProcessedMessage old2 = mail("3003", "alerts@hdfcbank.net", "2025-04-10T00:00:00Z");
        GmailProcessedMessage inWindow = mail("3003", "alerts@hdfcbank.net", "2026-10-20T00:00:00Z");
        GmailProcessedMessage otherBank = mail("3003", "alerts@icicibank.com", "2021-05-10T00:00:00Z");
        GmailProcessedMessage otherNumber = mail("7358", "alerts@hdfcbank.net", "2021-05-10T00:00:00Z");

        AssignNumberGroupResponse response = service.assign(user, "hdfc", "3003", hdfc.getId(), null,
                new PastCardRequest(null, LocalDate.of(2021, 5, 1), LocalDate.of(2025, 4, 30)));

        assertThat(response.identifierId()).isNull();
        assertThat(response.cardId()).isNotNull();
        assertThat(response.reactivatedCount()).isEqualTo(1);
        assertThat(response.skippedCount()).isEqualTo(2);
        assertThat(response.remainingCount()).isZero();
        Card card = cardRepository.findById(response.cardId()).orElseThrow();
        assertThat(card.getLast4()).isEqualTo("3003");
        assertThat(card.getIssuedOn()).isEqualTo(LocalDate.of(2021, 5, 1));
        assertThat(card.getClosedOn()).isEqualTo(LocalDate.of(2025, 4, 30));
        assertThat(identifierRepository.findByAccountIdOrderByCreatedAtAsc(hdfc.getId())).isEmpty();
        assertThat(status(inWindow)).isEqualTo(GmailProcessedStatus.DISCOVERED);
        for (GmailProcessedMessage old : List.of(old1, old2)) {
            GmailProcessedMessage row = messageRepository.findById(old.getId()).orElseThrow();
            assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.SKIPPED_BEFORE_WATERMARK);
            assertThat(row.getAccount().getId()).isEqualTo(hdfc.getId());
        }
        assertThat(status(otherBank)).isEqualTo(GmailProcessedStatus.UNRESOLVED_ACCOUNT); // another bank's number stays parked
        assertThat(status(otherNumber)).isEqualTo(GmailProcessedStatus.UNRESOLVED_ACCOUNT);
    }

    @Test
    void assignAsPastCard_besideAnActiveCard_keepsItActive_andUsesTheNamedHolder() {
        Cardholder primary = holderWithActiveCard(hdfc, "1111", LocalDate.of(2025, 5, 1));
        mail("3003", "alerts@hdfcbank.net", "2021-05-10T00:00:00Z");

        AssignNumberGroupResponse response = service.assign(user, "hdfc", "3003", hdfc.getId(), null,
                new PastCardRequest(primary.getId(), LocalDate.of(2021, 5, 1), LocalDate.of(2025, 4, 30)));

        List<Card> cards = cardRepository.findByAccountId(hdfc.getId());
        assertThat(cards).hasSize(2);
        assertThat(cards).filteredOn(c -> c.getClosedOn() == null).extracting(Card::getLast4).containsExactly("1111");
        assertThat(cards).filteredOn(c -> c.getId().equals(response.cardId())).singleElement()
                .satisfies(c -> assertThat(c.getCardholder().getId()).isEqualTo(primary.getId()));
        assertThat(cardholderRepository.findByAccountId(hdfc.getId())).hasSize(1);
    }

    @Test
    void assignAsPastCard_validatesDates_andTheNumberNotBeingOnAnOpenCard() {
        holderWithActiveCard(hdfc, "1111", LocalDate.of(2025, 5, 1));
        GmailProcessedMessage parked = mail("3003", "alerts@hdfcbank.net", "2021-05-10T00:00:00Z");

        assertThatThrownBy(() -> service.assign(user, "hdfc", "3003", hdfc.getId(), null,
                new PastCardRequest(null, LocalDate.of(2025, 5, 1), LocalDate.of(2025, 4, 30))))
                .isInstanceOf(ValidationException.class).hasMessageContaining("after the closed date");
        assertThatThrownBy(() -> service.assign(user, "hdfc", "3003", hdfc.getId(), null,
                new PastCardRequest(null, LocalDate.of(2025, 5, 1), AppTime.today().plusDays(1))))
                .isInstanceOf(ValidationException.class).hasMessageContaining("future");
        assertThatThrownBy(() -> service.assign(user, "hdfc", "1111", hdfc.getId(), null,
                new PastCardRequest(null, LocalDate.of(2021, 5, 1), LocalDate.of(2025, 4, 30))))
                .isInstanceOf(ValidationException.class).hasMessageContaining("already active");
        assertThat(cardRepository.findByAccountId(hdfc.getId())).hasSize(1);
        assertThat(status(parked)).isEqualTo(GmailProcessedStatus.UNRESOLVED_ACCOUNT);
    }

    @Test
    void assignAsPastCard_isTheUsersOwn_accountAndHolder() {
        Account foreign = saveAccount(otherUser, "Other HDFC", LocalDate.of(2026, 10, 7), null);
        Cardholder foreignHolder = holderWithActiveCard(foreign, "9999", LocalDate.of(2025, 5, 1));
        PastCardRequest forForeignHolder = new PastCardRequest(foreignHolder.getId(), LocalDate.of(2021, 5, 1), LocalDate.of(2025, 4, 30));

        assertThatThrownBy(() -> service.assign(user, "hdfc", "3003", foreign.getId(), null,
                new PastCardRequest(null, LocalDate.of(2021, 5, 1), LocalDate.of(2025, 4, 30))))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.assign(user, "hdfc", "3003", hdfc.getId(), null, forForeignHolder))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(cardRepository.findByAccountId(foreign.getId())).hasSize(1);
        assertThat(cardRepository.findByAccountId(hdfc.getId())).isEmpty();
    }

    @Test
    void pastCard_onAClosedAccount_isAllowedUpToTheClosedDate() {
        Account closed = saveAccount(user, "Old HDFC", null, LocalDate.of(2026, 1, 1));

        AssignNumberGroupResponse ok = service.assign(user, null, "3003", closed.getId(), null,
                new PastCardRequest(null, LocalDate.of(2021, 5, 1), LocalDate.of(2025, 4, 30)));
        assertThat(ok.cardId()).isNotNull();

        assertThatThrownBy(() -> service.assign(user, null, "4004", closed.getId(), null,
                new PastCardRequest(null, LocalDate.of(2021, 5, 1), LocalDate.of(2026, 2, 1))))
                .isInstanceOf(ValidationException.class).hasMessageContaining("account closed");
    }

    @Test
    void convertIdentifier_removesTheIdentifierAndCreatesTheCard_andSettlesParkedMail() {
        Account account = hdfc;
        UUID identifierId = identifiers.createOrUpdateIdentifier(user, account, "3003", AccountIdentifierKind.OTHER).identifier().getId();
        GmailProcessedMessage old = mail("3003", "alerts@hdfcbank.net", "2021-05-10T00:00:00Z");

        PastCardService.Result result = pastCards.convertIdentifier(user, account, identifierId,
                new PastCardRequest(null, LocalDate.of(2021, 5, 1), LocalDate.of(2025, 4, 30)));

        assertThat(identifierRepository.findByAccountIdOrderByCreatedAtAsc(account.getId())).isEmpty();
        assertThat(result.card().getLast4()).isEqualTo("3003");
        assertThat(result.card().getClosedOn()).isEqualTo(LocalDate.of(2025, 4, 30));
        assertThat(cardRepository.findByAccountId(account.getId())).hasSize(1);
        assertThat(messageRepository.findById(old.getId()).orElseThrow().getStatus()).isEqualTo(GmailProcessedStatus.SKIPPED_BEFORE_WATERMARK);
    }

    @Test
    void convertIdentifier_keepsTheIdentifierWhenTheCardIsInvalid_refusesNonDigits_andOtherUsersIdentifiers() {
        UUID mine = identifiers.createOrUpdateIdentifier(user, hdfc, "3003", AccountIdentifierKind.OTHER).identifier().getId();
        UUID crn = identifiers.createOrUpdateIdentifier(user, hdfc, "CRN12345", AccountIdentifierKind.OTHER).identifier().getId();
        Account foreign = saveAccount(otherUser, "Other HDFC", null, null);
        UUID foreignId = identifiers.createOrUpdateIdentifier(otherUser, foreign, "5005", AccountIdentifierKind.OTHER).identifier().getId();
        PastCardRequest valid = new PastCardRequest(null, LocalDate.of(2021, 5, 1), LocalDate.of(2025, 4, 30));

        assertThatThrownBy(() -> pastCards.convertIdentifier(user, hdfc, mine,
                new PastCardRequest(null, LocalDate.of(2025, 5, 1), LocalDate.of(2025, 4, 30)))).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> pastCards.convertIdentifier(user, hdfc, crn, valid))
                .isInstanceOf(ValidationException.class).hasMessageContaining("4-digit");
        assertThatThrownBy(() -> pastCards.convertIdentifier(user, hdfc, foreignId, valid))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(identifierRepository.findByAccountIdOrderByCreatedAtAsc(hdfc.getId())).hasSize(2);
        assertThat(identifierRepository.findByAccountIdOrderByCreatedAtAsc(foreign.getId())).hasSize(1);
    }

    @Test
    void anOldCardIsRefused_whenAnotherAccountOfTheUserHoldsTheNumber() {
        Account b = saveAccount(user, "HDFC Other", LocalDate.of(2026, 10, 7), null);
        holderWithActiveCard(b, "1234", LocalDate.of(2025, 5, 1));
        GmailProcessedMessage parked = mail("1234", "alerts@hdfcbank.net", "2021-05-10T00:00:00Z");
        PastCardRequest request = new PastCardRequest(null, LocalDate.of(2021, 5, 1), LocalDate.of(2025, 4, 30));

        assertThatThrownBy(() -> service.assign(user, "hdfc", "1234", hdfc.getId(), null, request))
                .isInstanceOf(ValidationException.class).hasMessage("••1234 already belongs to HDFC Other");
        assertThat(cardRepository.findByAccountId(hdfc.getId())).isEmpty();
        assertThat(status(parked)).isEqualTo(GmailProcessedStatus.UNRESOLVED_ACCOUNT);
    }

    @Test
    void anOldCardIsRefused_whenAnotherAccountHoldsTheNumberAsAnIdentifierOrBankLast4_butNotAtAnotherBank() {
        Account b = saveAccount(user, "HDFC Other", LocalDate.of(2026, 10, 7), null);
        identifiers.createOrUpdateIdentifier(user, b, "2222", AccountIdentifierKind.OTHER);
        PastCardRequest request = new PastCardRequest(null, LocalDate.of(2021, 5, 1), LocalDate.of(2025, 4, 30));

        assertThatThrownBy(() -> service.assign(user, "hdfc", "2222", hdfc.getId(), null, request))
                .isInstanceOf(ValidationException.class).hasMessage("••2222 already belongs to HDFC Other");
        assertThatThrownBy(() -> service.assign(user, "hdfc", "0438", hdfc.getId(), null, request)) // hdfc itself holds 0438: fine
                .isNotInstanceOf(ResourceNotFoundException.class);

        Account icici = saveAccount(user, "ICICI Other", LocalDate.of(2026, 10, 7), null);
        icici.setBank("icici");
        accountRepository.save(icici);
        holderWithActiveCard(icici, "5656", LocalDate.of(2025, 5, 1));
        assertThat(service.assign(user, "hdfc", "5656", hdfc.getId(), null, request).cardId()).isNotNull();
    }

    @Test
    void convertIdentifier_isRefusedWhenAnotherAccountHoldsTheNumber_andSettlesOnlyTheAccountsBank() {
        UUID mine = identifiers.createOrUpdateIdentifier(user, hdfc, "3003", AccountIdentifierKind.OTHER).identifier().getId();
        Account b = saveAccount(user, "HDFC Other", LocalDate.of(2026, 10, 7), null);
        holderWithActiveCard(b, "3003", LocalDate.of(2025, 5, 1));
        PastCardRequest request = new PastCardRequest(null, LocalDate.of(2021, 5, 1), LocalDate.of(2025, 4, 30));

        assertThatThrownBy(() -> pastCards.convertIdentifier(user, hdfc, mine, request))
                .isInstanceOf(ValidationException.class).hasMessage("••3003 already belongs to HDFC Other");
        assertThat(identifierRepository.findByAccountIdOrderByCreatedAtAsc(hdfc.getId())).hasSize(1); // still there

        cardRepository.deleteAll(cardRepository.findByAccountId(b.getId()));
        cardholderRepository.deleteAll(cardholderRepository.findByAccountId(b.getId()));
        GmailProcessedMessage hdfcMail = mail("3003", "alerts@hdfcbank.net", "2021-05-10T00:00:00Z");
        GmailProcessedMessage iciciMail = mail("3003", "alerts@icicibank.com", "2021-05-10T00:00:00Z");
        pastCards.convertIdentifier(user, hdfc, mine, request);
        assertThat(status(hdfcMail)).isEqualTo(GmailProcessedStatus.SKIPPED_BEFORE_WATERMARK);
        assertThat(status(iciciMail)).isEqualTo(GmailProcessedStatus.UNRESOLVED_ACCOUNT);
    }

    private Cardholder holderWithActiveCard(Account account, String last4, LocalDate issuedOn) {
        User owner = account.getUser();
        Cardholder holder = new Cardholder();
        holder.setUser(owner);
        holder.setAccount(account);
        holder.setRole(CardholderRole.PRIMARY);
        holder.setOpenedOn(issuedOn);
        holder = cardholderRepository.save(holder);
        Card card = new Card();
        card.setUser(owner);
        card.setAccount(account);
        card.setCardholder(holder);
        card.setLast4(last4);
        card.setIssuedOn(issuedOn);
        cardRepository.save(card);
        return holder;
    }

    private GmailProcessedStatus status(GmailProcessedMessage m) {
        return messageRepository.findById(m.getId()).orElseThrow().getStatus();
    }

    private GmailProcessedMessage mail(String last4, String sender, String internalDate) {
        GmailProcessedMessage m = new GmailProcessedMessage();
        m.setConnection(connection);
        m.setUser(user);
        m.setGmailMessageId("msg-" + UUID.randomUUID());
        m.setStatus(GmailProcessedStatus.UNRESOLVED_ACCOUNT);
        m.setExtractedLast4(last4);
        m.setSenderAddress(sender);
        m.setPipelineKind("ALERT");
        m.setInternalDate(Instant.parse(internalDate));
        m.setError("No single account matches card/account ending " + last4);
        return messageRepository.save(m);
    }

    private Account saveAccount(User owner, String name, LocalDate ingestFrom, LocalDate closedOn) {
        Account account = new Account(name, AccountType.bank_account);
        account.setUser(owner);
        account.setBank("hdfc");
        account.setIngestFromDate(ingestFrom);
        account.setClosedOn(closedOn);
        AccountBankDetails details = new AccountBankDetails(account, BigDecimal.ZERO, "0438");
        details.setUser(owner);
        account.setBankDetails(details);
        return accountRepository.save(account);
    }

    private User saveUser(String prefix) {
        User u = new User();
        u.setDisplayName(prefix);
        u.setEmail(prefix + "-" + UUID.randomUUID() + "@example.test");
        u.setPasswordHash("hash");
        return userRepository.save(u);
    }
}
