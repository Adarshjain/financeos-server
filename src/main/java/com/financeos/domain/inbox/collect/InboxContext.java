package com.financeos.domain.inbox.collect;

import com.financeos.domain.account.Account;
import java.util.List;
import java.util.function.Supplier;

/**
 * What one inbox build shares between its collectors, so a list every collector needs (the user's
 * credit cards) is read once per build instead of once per collector. Lives for a single build on a
 * single thread; never shared between requests or users.
 */
public final class InboxContext {

    private List<Account> creditCards;

    public List<Account> creditCards(Supplier<List<Account>> loader) {
        if (creditCards == null) {
            creditCards = loader.get();
        }
        return creditCards;
    }
}
