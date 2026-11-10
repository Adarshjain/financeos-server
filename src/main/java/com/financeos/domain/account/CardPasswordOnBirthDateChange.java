package com.financeos.domain.account;

import com.financeos.domain.user.BirthDateChangedEvent;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** After a birth date change, re-derives the statement password of cards that keep a last 6 (never replaces a hand-typed one). */
@Component
public class CardPasswordOnBirthDateChange {

    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final ApplicationEventPublisher events;

    public CardPasswordOnBirthDateChange(UserRepository userRepository, AccountRepository accountRepository,
                                         ApplicationEventPublisher events) {
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.events = events;
    }

    /** Runs inside the profile update's transaction. */
    @EventListener
    public void onBirthDateChanged(BirthDateChangedEvent event) {
        User user = userRepository.findById(event.userId()).orElse(null);
        if (user == null) return;
        boolean changed = false;
        for (Account a : accountRepository.findByUserIdAndType(event.userId(), AccountType.credit_card)) {
            AccountCreditCardDetails d = a.getCreditCardDetails();
            if (d == null || d.getLast6() == null || d.getLast6().isBlank()) continue;
            var fresh = CardStatementPassword.derive(a.getBank(), user.getDisplayName(), user.getBirthDate(), d.getLast6());
            if (fresh.isEmpty() || fresh.get().equals(d.getStatementPassword())) continue;
            String stored = d.getStatementPassword();
            boolean wasDerived = stored != null && stored.equals(CardStatementPassword
                    .derive(a.getBank(), user.getDisplayName(), event.oldBirthDate(), d.getLast6()).orElse(null));
            if (stored != null && !stored.isBlank() && !wasDerived) continue;
            d.setStatementPassword(fresh.get());
            accountRepository.save(a);
            changed = true;
        }
        if (changed) events.publishEvent(new StatementPasswordSavedEvent(event.userId()));
    }
}
