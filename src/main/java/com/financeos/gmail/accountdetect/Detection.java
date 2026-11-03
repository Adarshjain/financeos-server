package com.financeos.gmail.accountdetect;

import com.financeos.gmail.accountdetect.Vocab.Channel;
import com.financeos.gmail.accountdetect.Vocab.Conf;
import com.financeos.gmail.accountdetect.Vocab.Kind;
import com.financeos.gmail.accountdetect.Vocab.Lifecycle;
import com.financeos.gmail.accountdetect.Vocab.Status;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** The detector's output: reported entities with per-fact confidence, plus a dropped tally and diagnostics. */
public final class Detection {

    /** One own account or card. Facts with a null confidence are not reported. */
    public static final class Entity {
        public final String bank;
        public final String last4;
        public Conf existsConf;
        public Kind kind;
        public Conf kindConf;
        public String variant;
        public Conf variantConf;
        public int variantConflicts;
        /** Names this number was seen under when the variant could not be settled (reported LOW at most). */
        public final List<String> variantCandidates = new ArrayList<>();
        public Lifecycle lifecycle;
        public Conf lifecycleConf;
        public String linkedTo;
        public Status status;
        public LocalDate first;
        public LocalDate last;
        public final Set<Channel> channels = EnumSet.noneOf(Channel.class);
        public final TreeSet<YearMonth> billingMonths = new TreeSet<>();
        public final TreeSet<YearMonth> activityMonths = new TreeSet<>();
        public int counterpartyHits;
        public boolean localOnly;
        public String limitBucket;
        public Conf limitConf;
        /** The exact limit of the latest statement that printed one; kept only when {@link #limitConf} is HIGH or MEDIUM. */
        public java.math.BigDecimal limitAmount;
        /** At least two strongly linked statements name a holder and none of them is the user (a card or account that is not theirs). */
        public boolean notInYourName;
        /** The limit comes from a limit-change mail newer than any statement (never above LOW). */
        public boolean limitFromMail;
        /** A payment app, not coincident with a bank mail, confirmed the kind / the variant. */
        public boolean kindAppConfirmed;
        public boolean variantAppConfirmed;

        // ---- evidence the contract (Contract) reads; never mail content
        /** Bank-side channels only (payment apps excluded). */
        public final Set<Channel> bankChannels = EnumSet.noneOf(Channel.class);
        /** Distinct bank mails / independent documents naming this number as own. */
        public int evidenceMails;
        public long evidenceSpanDays;
        /** Seen only in folder/DB statement documents, never in a bank mail. */
        public boolean statementOnly;
        /** Distinct statement documents (by witness date) whose own header names this number, and their span. */
        public int headerStatements;
        public long headerSpanDays;
        public boolean kindUnanimous;
        public int kindChannels;
        public int kindMails;
        public long kindSpanDays;
        public int variantChannels;
        public int variantMails;
        public long variantSpanDays;
        /** Independent explicit witnesses of the lifecycle fact, by date (a mail and the PDF it carries are one witness). */
        public final TreeSet<LocalDate> lifecycleWitnesses = new TreeSet<>();
        /** The activity timeline corroborates the lifecycle fact (old number stops / new one starts in the window). */
        public boolean lifecycleTimeline;
        /** Only a "blocked" mail: a status, never more than LOW. */
        public boolean lifecycleFromBlock;
        /** The fact rests only on statements linked by period to a bank with several own numbers of that kind. */
        public boolean lifecycleWeakLink;
        public boolean variantWeakLink;
        /** The product name carries bank words after the dictionary name ("Airtel Axis Bank"): its exact form is unknown. */
        public boolean variantAmbiguous;
        public boolean limitWeakLink;
        /** Statements with a header credit limit: how many, their span, and whether all agree. */
        public int limitDocs;
        public long limitSpanDays;
        public boolean limitAgree;
        /** Own-slot evidence only in debit/transfer layouts, never in a balance or statement layout. */
        public boolean transferOnly;
        /** UPGRADED inferred from two different product names (no explicit mail). */
        public boolean lifecycleFromVariants;

        Entity(String bank, String last4) {
            this.bank = bank;
            this.last4 = last4;
        }

        /** An empty entity for a caller outside the detector (the e2e seed); the caller sets the facts it has. */
        public static Entity of(String bank, String last4) {
            return new Entity(bank, last4);
        }

        public String key() {
            return bank + ":" + last4;
        }
    }

    public final List<Entity> entities = new ArrayList<>();
    /** Reason → count of numbers, facts or mails not reported. */
    public final Map<String, Integer> dropped = new TreeMap<>();
    /** How each statement document was tied to an entity: outcome plus bank, last4 and period only. */
    public final List<StatementLink> statementLinks = new ArrayList<>();

    /** doc is the input document itself (identity only, for per-document reporting by the caller; never printed). */
    public record StatementLink(String source, String outcome, String bank, String last4, YearMonth period, StatementContent doc) {}
    /** Free-form counters for the report (clusters by role, slots by class, ...). */
    public final Map<String, Integer> stats = new TreeMap<>();
    /** Payment-app hits as bank:last4 → count. */
    public final Map<String, Integer> appHits = new TreeMap<>();
    /** Own numbers seen at least once in a counterparty slot. */
    public final Set<String> ownSeenAsCounterparty = new TreeSet<>();

    void drop(String reason) {
        dropped.merge(reason, 1, Integer::sum);
    }

    void stat(String key) {
        stats.merge(key, 1, Integer::sum);
    }

    public Entity find(String bank, String last4) {
        for (Entity e : entities) if (e.bank.equals(bank) && e.last4.equals(last4)) return e;
        return null;
    }

    /** Every reported fact as a stable key (for bootstrap stability and holdout comparisons). */
    public Map<String, Conf> facts() {
        Map<String, Conf> f = new TreeMap<>();
        for (Entity e : entities) {
            f.put("E|" + e.key(), e.existsConf);
            if (e.kindConf != null) f.put("K|" + e.key() + "|" + e.kind, e.kindConf);
            if (e.variantConf != null) f.put("V|" + e.key() + "|" + e.variant, e.variantConf);
            if (e.lifecycleConf != null) f.put("L|" + e.key() + "|" + e.lifecycle + "|" + e.linkedTo, e.lifecycleConf);
        }
        return f;
    }
}
