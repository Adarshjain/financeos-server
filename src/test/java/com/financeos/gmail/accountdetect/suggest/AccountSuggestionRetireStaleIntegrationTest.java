package com.financeos.gmail.accountdetect.suggest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.user.UserRepository;
import com.financeos.gmail.accountdetect.Vocab.Conf;
import com.financeos.gmail.accountdetect.Vocab.Kind;
import com.financeos.gmail.accountdetect.Vocab.Lifecycle;
import com.financeos.gmail.accountdetect.suggest.AccountSuggestion.State;
import com.financeos.support.ApiTestClient;
import com.financeos.support.UserDataCleanup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.financeos.gmail.accountdetect.DetectionFixtures.detection;
import static com.financeos.gmail.accountdetect.DetectionFixtures.entity;
import static org.assertj.core.api.Assertions.assertThat;

/** A run retires NEW rows it no longer reports; answered and matched rows are never touched. */
@SpringBootTest
@AutoConfigureMockMvc
class AccountSuggestionRetireStaleIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountSuggestionService service;
    @Autowired private AccountSuggestionRepository repository;

    private UUID userId;

    @BeforeEach
    void setUp() throws Exception {
        String email = "retire-" + UUID.randomUUID() + "@example.test";
        ApiTestClient.signUp(mockMvc, mapper, email);
        userId = userRepository.findByEmail(email).orElseThrow().getId();
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(userId));
    }

    private Optional<AccountSuggestion> find(String key) {
        return repository.findByUserId(userId).stream().filter(s -> s.getKey().equals(key)).findFirst();
    }

    private void setState(String key, State state) {
        AccountSuggestion s = find(key).orElseThrow();
        s.setState(state);
        repository.save(s);
    }

    @Test
    void anUnreportedNewRowIsDeletedAndAnsweredOrMatchedRowsStay() {
        service.apply(userId, detection(entity("hdfc", "1111", Kind.ACCOUNT, Conf.HIGH), entity("hdfc", "2222", Kind.CREDIT_CARD, Conf.HIGH),
                entity("icici", "3333", Kind.CREDIT_CARD, Conf.HIGH), entity("axis", "4444", Kind.CREDIT_CARD, Conf.HIGH),
                entity("sbi", "5555", Kind.CREDIT_CARD, Conf.HIGH), entity("yes", "6666", Kind.CREDIT_CARD, Conf.HIGH)));
        setState("hdfc:BANK_ACCOUNT:1111", State.ADDED);
        setState("hdfc:CREDIT_CARD:2222", State.NOT_MINE);
        setState("icici:CREDIT_CARD:3333", State.BELONGS);
        setState("axis:CREDIT_CARD:4444", State.MATCHED);

        service.apply(userId, detection(entity("yes", "6666", Kind.CREDIT_CARD, Conf.HIGH)));

        assertThat(find("hdfc:BANK_ACCOUNT:1111").orElseThrow().getState()).isEqualTo(State.ADDED);
        assertThat(find("hdfc:CREDIT_CARD:2222").orElseThrow().getState()).isEqualTo(State.NOT_MINE);
        assertThat(find("icici:CREDIT_CARD:3333").orElseThrow().getState()).isEqualTo(State.BELONGS);
        assertThat(find("axis:CREDIT_CARD:4444").orElseThrow().getState()).isEqualTo(State.MATCHED);
        assertThat(find("sbi:CREDIT_CARD:5555")).isEmpty();
        assertThat(find("yes:CREDIT_CARD:6666").orElseThrow().getState()).isEqualTo(State.NEW);
    }

    @Test
    void aChildOfADeletedParentLosesItsParentLink() {
        var primary = entity("hdfc", "1111", Kind.CREDIT_CARD, Conf.HIGH);
        var addOn = entity("hdfc", "2222", Kind.CREDIT_CARD, Conf.HIGH);
        addOn.lifecycle = Lifecycle.ADD_ON;
        addOn.lifecycleConf = Conf.HIGH;
        addOn.linkedTo = "hdfc:1111";
        service.apply(userId, detection(primary, addOn));
        AccountSuggestion child = find("hdfc:ADDON_CARD:2222").or(() -> find("hdfc:CREDIT_CARD:2222")).orElseThrow();
        assertThat(child.getParentSuggestionId()).isNotNull();
        // answered child (kept) whose NEW parent is no longer reported
        child.setState(State.NOT_MINE);
        repository.save(child);

        service.apply(userId, detection());

        assertThat(find("hdfc:CREDIT_CARD:1111")).isEmpty();
        AccountSuggestion kept = repository.findById(child.getId()).orElseThrow();
        assertThat(kept.getParentSuggestionId()).isNull();
    }

    @Test
    void aLiveAddOnWhosePrimaryIsRetiredFallsBackToItsBaseKind() {
        var primary = entity("hdfc", "1111", Kind.CREDIT_CARD, Conf.HIGH);
        var addOn = entity("hdfc", "2222", Kind.CREDIT_CARD, Conf.HIGH);
        addOn.lifecycle = Lifecycle.ADD_ON;
        addOn.lifecycleConf = Conf.HIGH;
        addOn.linkedTo = "hdfc:1111";
        service.apply(userId, detection(primary, addOn));
        assertThat(find("hdfc:CREDIT_CARD:2222").orElseThrow().getKind()).isEqualTo(AccountSuggestion.Kind.ADDON_CARD);

        service.apply(userId, detection(addOn));

        assertThat(find("hdfc:CREDIT_CARD:1111")).isEmpty();
        AccountSuggestion child = find("hdfc:CREDIT_CARD:2222").orElseThrow();
        assertThat(child.getKind()).isEqualTo(AccountSuggestion.Kind.CREDIT_CARD);
        assertThat(child.getParentSuggestionId()).isNull();
    }

    @Test
    void aDebitCardIsNotLinkedToAStaleBankRow() {
        var bank = entity("hdfc", "1111", Kind.ACCOUNT, Conf.HIGH);
        var debit = entity("hdfc", "2222", Kind.DEBIT_CARD, Conf.HIGH);
        service.apply(userId, detection(bank, debit));
        assertThat(find("hdfc:DEBIT_CARD:2222").orElseThrow().getParentSuggestionId()).isNotNull();

        service.apply(userId, detection(debit));

        assertThat(find("hdfc:BANK_ACCOUNT:1111")).isEmpty();
        assertThat(find("hdfc:DEBIT_CARD:2222").orElseThrow().getParentSuggestionId()).isNull();
    }
}
