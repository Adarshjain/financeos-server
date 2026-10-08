package com.financeos.domain.inbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InboxKindsTest {

    private static final UUID ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 1);

    @Test
    void itemKeysAreStableAndKeyedBySubject() {
        assertEquals("bill:" + ID, InboxKinds.billKey(ID));
        assertEquals("bill-awaiting:" + ID, InboxKinds.billAwaitingKey(ID));
        assertEquals("emi:" + ID + ":7", InboxKinds.emiKey(ID, 7));
        assertEquals("lending:" + ID, InboxKinds.lendingKey(ID));
        assertEquals("statement-expected:" + ID + ":2026-10-01", InboxKinds.statementExpectedKey(ID, DAY));
        assertEquals("gmail-reconnect:" + ID, InboxKinds.gmailReconnectKey(ID));
        assertEquals("job:" + ID, InboxKinds.jobKey(ID));
    }

    @Test
    void rewardKeysCarryTheWindowStartWhenThereIsOne() {
        assertEquals("reward-milestone:" + ID + ":2026-10-01", InboxKinds.rewardMilestoneKey(ID, DAY));
        assertEquals("reward-cap:" + ID + ":2026-10-01", InboxKinds.rewardCapKey(ID, DAY));
    }

    @Test
    void rewardKeysWithoutAWindowStartAreJustTheSubject() {
        assertEquals("reward-milestone:" + ID, InboxKinds.rewardMilestoneKey(ID, null));
        assertEquals("reward-cap:" + ID, InboxKinds.rewardCapKey(ID, null));
    }

    @Test
    void theSameRewardInANewWindowGetsANewKey() {
        LocalDate nextWindow = DAY.plusMonths(1);
        assertFalse(InboxKinds.rewardMilestoneKey(ID, DAY).equals(InboxKinds.rewardMilestoneKey(ID, nextWindow)));
        assertFalse(InboxKinds.rewardCapKey(ID, DAY).equals(InboxKinds.rewardCapKey(ID, nextWindow)));
    }

    @Test
    void inboxHrefFocusesTheInboxOnTheKeyVerbatim() {
        String key = InboxKinds.emiKey(ID, 3);
        assertEquals("/inbox?item=emi:" + ID + ":3", InboxKinds.inboxHref(key));
        assertEquals("/inbox?item=review", InboxKinds.inboxHref(InboxKinds.KEY_REVIEW));
    }

    @Test
    void onlyTheTwoFixedSummaryKeysAreSummaryKeys() {
        assertTrue(InboxKinds.isSummaryKey("gmail-attention"));
        assertTrue(InboxKinds.isSummaryKey("review"));
        assertTrue(InboxKinds.isSummaryKey(InboxKinds.KEY_GMAIL_ATTENTION));
        assertTrue(InboxKinds.isSummaryKey(InboxKinds.KEY_REVIEW));

        assertFalse(InboxKinds.isSummaryKey(InboxKinds.billKey(ID)));
        assertFalse(InboxKinds.isSummaryKey(InboxKinds.gmailReconnectKey(ID)));
        assertFalse(InboxKinds.isSummaryKey("review:" + ID));
        assertFalse(InboxKinds.isSummaryKey("REVIEW"));
        assertFalse(InboxKinds.isSummaryKey(null));
    }

    @Test
    void dueSoonWindowIsSevenDays() {
        assertEquals(7, InboxKinds.DUE_SOON_DAYS);
    }

    @Test
    void allListsEveryKindInDisplayOrder() {
        assertEquals(List.of("bill", "emi", "lending", "statement_expected", "gmail_reconnect", "gmail_attention",
                "review", "job", "reward_milestone", "reward_cap"), InboxKinds.ALL);
    }

    @Test
    void everyKindHasAHumanLabel() {
        assertEquals("Card bills", InboxKinds.label(InboxKinds.BILL));
        assertEquals("EMIs", InboxKinds.label(InboxKinds.EMI));
        assertEquals("Lending returns", InboxKinds.label(InboxKinds.LENDING));
        assertEquals("Missing statements", InboxKinds.label(InboxKinds.STATEMENT_EXPECTED));
        assertEquals("Gmail reconnect", InboxKinds.label(InboxKinds.GMAIL_RECONNECT));
        assertEquals("Gmail attention", InboxKinds.label(InboxKinds.GMAIL_ATTENTION));
        assertEquals("Transactions to review", InboxKinds.label(InboxKinds.REVIEW));
        assertEquals("Finished jobs", InboxKinds.label(InboxKinds.JOB));
        assertEquals("Reward milestones", InboxKinds.label(InboxKinds.REWARD_MILESTONE));
        assertEquals("Reward caps", InboxKinds.label(InboxKinds.REWARD_CAP));
    }

    @Test
    void anUnknownKindIsItsOwnLabel() {
        assertEquals("something_new", InboxKinds.label("something_new"));
    }

    @Test
    void everyKindLandsOnItsPage() {
        assertEquals("/upcoming", InboxKinds.landingHref(InboxKinds.BILL));
        assertEquals("/loans", InboxKinds.landingHref(InboxKinds.EMI));
        assertEquals("/loans/lendings", InboxKinds.landingHref(InboxKinds.LENDING));
        assertEquals("/transactions/import", InboxKinds.landingHref(InboxKinds.STATEMENT_EXPECTED));
        assertEquals("/settings/gmail", InboxKinds.landingHref(InboxKinds.GMAIL_RECONNECT));
        assertEquals("/settings/gmail?focus=attention", InboxKinds.landingHref(InboxKinds.GMAIL_ATTENTION));
        assertEquals("/transactions/review", InboxKinds.landingHref(InboxKinds.REVIEW));
        assertEquals("/settings/activity", InboxKinds.landingHref(InboxKinds.JOB));
        assertEquals("/rewards", InboxKinds.landingHref(InboxKinds.REWARD_MILESTONE));
        assertEquals("/rewards", InboxKinds.landingHref(InboxKinds.REWARD_CAP));
    }

    @Test
    void anUnknownKindLandsOnTheInbox() {
        assertEquals("/inbox", InboxKinds.landingHref("something_new"));
    }
}
