package com.financeos.domain.report.underlying;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.report.ReportFieldValuesService;
import com.financeos.domain.report.datasource.impl.TransactionsDatasource;
import com.financeos.domain.report.definition.FilterClause;
import com.financeos.domain.report.engine.DateRangeResolver;
import com.financeos.domain.report.engine.SqlPredicates;
import com.financeos.domain.user.User;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * An app-built transactions filter holds account ids; its chip reads the accounts' names (the
 * current user's only), and names stay as they are.
 */
class UnderlyingFilterChipsAccountIdTest {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private final UUID userId = UUID.randomUUID();
    private final UUID mine = UUID.randomUUID();
    private final UUID theirs = UUID.randomUUID();
    private AccountRepository accounts;
    private TransactionsDatasource datasource;
    private UnderlyingFilterChips chips;

    private static Account account(UUID id, String name, UUID owner) {
        User user = new User();
        user.setId(owner);
        Account a = new Account();
        a.setId(id);
        a.setName(name);
        a.setUser(user);
        return a;
    }

    @BeforeEach
    void setUp() {
        UserContext.setCurrentUserId(userId);
        DateRangeResolver dates = new DateRangeResolver(4);
        datasource = new TransactionsDatasource(new SqlPredicates(dates), dates);
        accounts = mock(AccountRepository.class);
        when(accounts.findAllById(any())).thenReturn(List.of(
                account(mine, "Main Bank", userId), account(theirs, "Not Yours", UUID.randomUUID())));
        datasource.setAccountRepository(accounts);
        chips = new UnderlyingFilterChips(mock(ReportFieldValuesService.class));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private String text(FilterClause filter) {
        return chips.describe(datasource, List.of(filter)).get(0).text();
    }

    @Test
    void accountIdsReadAsTheUsersAccountNames() {
        var ids = JSON.arrayNode().add(mine.toString()).add(theirs.toString()).add("Wallet");
        assertEquals("in Main Bank, " + theirs + ", Wallet", text(new FilterClause("account", "in", ids)));
    }

    @Test
    void aSingleIdReadsAsItsName() {
        assertEquals("is Main Bank", text(new FilterClause("account", "is", JSON.textNode(mine.toString()))));
    }

    @Test
    void namesAndOtherFieldsAreLeftAsTheyAre() {
        assertEquals("is Wallet", text(new FilterClause("account", "is", JSON.textNode("Wallet"))));
        assertEquals("is " + mine, text(new FilterClause("description", "exact", JSON.textNode(mine.toString()))));
    }

    @Test
    void withoutAUserOrRepositoryNothingIsLabelled() {
        UserContext.clear();
        assertEquals("is " + mine, text(new FilterClause("account", "is", JSON.textNode(mine.toString()))));
        UserContext.setCurrentUserId(userId);
        DateRangeResolver dates = new DateRangeResolver(4);
        TransactionsDatasource bare = new TransactionsDatasource(new SqlPredicates(dates), dates);
        assertEquals("is " + mine, chips.describe(bare,
                List.of(new FilterClause("account", "is", JSON.textNode(mine.toString())))).get(0).text());
    }
}
