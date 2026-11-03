package com.financeos.gmail.accountdetect;

import com.financeos.gmail.accountdetect.Vocab.Kind;
import com.financeos.gmail.accountdetect.Vocab.Lifecycle;
import com.financeos.gmail.extract.ExtractionTemplate;
import com.financeos.gmail.extract.ExtractionTemplateLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Made-up mailboxes with known truth. Alert layouts come from the repo's made-up extraction fixtures (numbers
 * swapped for the synthetic user's, all other digit runs randomised) plus generic hand-written layouts;
 * statements, reminders, service mails, offers, OTPs and payment-app mails are hand-written. No real mail.
 */
final class SyntheticMailbox {

    record Truth(String bank, String last4, Kind kind, String variant, Lifecycle lifecycle, String linkedTo) {
        String key() {
            return bank + ":" + last4;
        }
    }

    final List<MailFeature> mails = new ArrayList<>();
    final List<StatementContent> statements = new ArrayList<>();
    final Map<String, Truth> truth = new LinkedHashMap<>();
    /** Numbers that must never be reported (counterparties, payees, other people's cards). */
    final Set<String> traps = new HashSet<>();

    static final YearMonth START = YearMonth.of(2024, 11);
    static final int MONTHS = 24;
    static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd-MM-yy");
    static final DateTimeFormatter MON = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);
    static final String[] MERCHANTS = {"SWIGGY", "FLIPKART", "IRCTC", "AMAZON", "ZOMATO", "UBER", "BIGBASKET", "MYNTRA", "MAKEMYTRIP", "BOOKMYSHOW"};

    record BankSpec(String id, String domain, String name, boolean issuesCards) {}

    /**
     * The generator's own card products, written independently of the detector's dictionary: some names match it,
     * some are longer or shorter forms, some are unknown to it, some are generic tier words. A reported variant is
     * correct when the truth name starts with it (word by word).
     */
    static final Map<String, List<String>> PRODUCTS = Map.of(
            "hdfc", List.of("Regalia Gold", "Millennia", "Infinia Metal", "Platinum", "Tata Neu Infinity", "Swiggy"),
            "icici", List.of("Amazon Pay", "Sapphiro", "Coral", "Rubyx", "Emeralde Private"),
            "axis", List.of("Magnus", "Atlas", "Ace", "Flipkart", "Neo", "Select"),
            "yes", List.of("Marquee", "Elite+", "Reserv", "Prosperity Edge"),
            "federal", List.of("Scapia", "Signet", "Celesta"),
            "hsbc", List.of("Live+", "TravelOne", "Premier", "Visa Platinum"),
            "equitas", List.of("Platinum", "Classic"),
            "sbi", List.of("SimplyCLICK", "Cashback", "Elite", "Prime", "Aurum"),
            "au", List.of("LIT", "Altura Plus", "Zenith+", "Ixigo"),
            "indusind", List.of("Legend", "Pinnacle", "Tiger", "EazyDiner"));

    static List<String> products(String bank) {
        return PRODUCTS.getOrDefault(bank, List.of());
    }

    static final List<BankSpec> BANKS = List.of(
            new BankSpec("hdfc", "hdfcbank.net", "HDFC Bank", true),
            new BankSpec("icici", "icicibank.com", "ICICI Bank", true),
            new BankSpec("axis", "axisbank.com", "Axis Bank", true),
            new BankSpec("yes", "yesbank.in", "YES Bank", true),
            new BankSpec("federal", "federalbank.co.in", "Federal Bank", true),
            new BankSpec("hsbc", "mail.hsbc.co.in", "HSBC", true),
            new BankSpec("equitas", "equitasbank.com", "Equitas", true),
            new BankSpec("sbi", "sbicard.com", "SBI Card", true),
            new BankSpec("au", "aubank.in", "AU Bank", true),
            new BankSpec("indusind", "indusind.com", "IndusInd Bank", true));

    /** Per-bank mail style, randomised per mailbox. */
    static final class Style {
        int statementStyle;      // 0: variant + number in one sentence, 1: variant in subject only + number in body, 2: no variant
        boolean dueReminders;
        boolean pdfReadable;     // statement PDFs open without a password (statement content channel)
        boolean addOnWording;    // alerts say "add-on card"
        int wording;             // statement sentence form (0-3), so no single phrasing is the only one tested
    }

    final Random rnd;
    final Map<String, Style> styles = new HashMap<>();
    final Map<String, BankSpec> specs = new HashMap<>();
    final Set<String> usedLast4 = new HashSet<>();
    /** Every credit card with its real active window (for payment-app mails). */
    final List<Card> live = new ArrayList<>();

    // ------------------------------------------------------------------ fixture layouts

    record FixtureLayout(String bank, String from, String subject, String body, int start, int end, String oldLast4, Kind kind) {}

    static final List<FixtureLayout> FIXTURES = loadFixtures();

    @SuppressWarnings("unchecked")
    static List<FixtureLayout> loadFixtures() {
        List<FixtureLayout> out = new ArrayList<>();
        try {
            Map<String, ExtractionTemplate> byId = new HashMap<>();
            for (ExtractionTemplate t : ExtractionTemplateLoader.loadAll()) byId.put(t.id(), t);
            for (Resource r : new PathMatchingResourcePatternResolver().getResources("classpath*:gmail/extract-fixtures/*.yml")) {
                try (InputStream in = r.getInputStream()) {
                    Map<String, Object> root = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
                    for (Map<String, Object> f : (List<Map<String, Object>>) root.get("fixtures")) {
                        ExtractionTemplate t = byId.get((String) f.get("template"));
                        Map<String, Object> exp = (Map<String, Object>) f.get("expect");
                        if (t == null || exp == null || exp.get("last4") == null) continue;
                        String from = String.valueOf(f.get("from"));
                        String domain = from.replaceAll(".*@", "").replaceAll("[>'\\s]", "");
                        String bank = BankDirectory.bankForDomain(domain);
                        if (bank == null || BankDirectory.isApp(bank)) continue;
                        String body = String.valueOf(f.get("body"));
                        Matcher m = t.body().matcher(body);
                        if (!m.find() || m.start("last4") < 0) continue;
                        String before = body.substring(Math.max(0, m.start("last4") - 70), m.start("last4")).toLowerCase(Locale.ROOT);
                        Kind k = before.contains("credit card") ? Kind.CREDIT_CARD : before.contains("debit card") ? Kind.DEBIT_CARD
                                : before.matches("(?s).*(account|a/c|acct).*") && !before.contains("card") ? Kind.ACCOUNT : null;
                        if (k == null) continue;
                        out.add(new FixtureLayout(bank, domain, String.valueOf(f.get("subject")), body, m.start("last4"), m.end("last4"),
                                String.valueOf(exp.get("last4")), k));
                    }
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    SyntheticMailbox(long seed) {
        this.rnd = new Random(seed);
    }

    String newLast4() {
        while (true) {
            String s = String.format("%04d", rnd.nextInt(10000));
            if (usedLast4.add(s)) return s;
        }
    }

    String randomDigits(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append((char) ('0' + (i == 0 ? 1 + rnd.nextInt(9) : rnd.nextInt(10))));
        return sb.toString();
    }

    String amt() {
        return (50 + rnd.nextInt(20000)) + "." + String.format("%02d", rnd.nextInt(100));
    }

    String merchant() {
        return MERCHANTS[rnd.nextInt(MERCHANTS.length)];
    }

    LocalDate day(int month) {
        YearMonth ym = START.plusMonths(month);
        return ym.atDay(1 + rnd.nextInt(Math.min(27, ym.lengthOfMonth() - 1)));
    }

    void mail(String domain, LocalDate d, String subject, String body, boolean keepBody, String... attachments) {
        String snippet = body.replaceAll("\\s+", " ").trim();
        if (snippet.length() > 150) snippet = snippet.substring(0, 150);
        mails.add(new MailFeature(domain, d.atTime(9 + rnd.nextInt(10), rnd.nextInt(60)).toInstant(ZoneOffset.ofHoursMinutes(5, 30)),
                subject, snippet, keepBody ? body : null, List.of(attachments)));
    }

    /** Alerts are fetched as headers + snippet most of the time, like the runner's capped body sample. */
    void alert(String domain, LocalDate d, String subject, String body) {
        mail(domain, d, subject, body, rnd.nextDouble() < 0.3);
    }

    String fromFixture(FixtureLayout f, String last4) {
        String head = randomiseDigits(f.body().substring(0, f.start()), f.oldLast4(), last4);
        String span = f.body().substring(f.start(), f.end());
        String masked = span.replaceAll("\\d", "").isEmpty() ? last4 : span.replaceAll("\\d+$", "") + last4;
        if (span.replaceAll("\\D", "").length() < 4) masked = span.replaceAll("\\d+$", "") + last4;
        String tail = randomiseDigits(f.body().substring(f.end()), f.oldLast4(), last4);
        return head + masked + tail;
    }

    String fixSubject(FixtureLayout f, String last4) {
        return randomiseDigits(f.subject(), f.oldLast4(), last4);
    }

    private String randomiseDigits(String s, String oldLast4, String last4) {
        Matcher m = Pattern.compile("\\d{4,}").matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String g = m.group();
            String rep = g.endsWith(oldLast4) ? g.substring(0, g.length() - 4).replaceAll("\\d", "0") + last4 : randomDigits(g.length());
            m.appendReplacement(sb, rep);
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Registers a bank with a fixed mail style (for the trap tests). */
    BankSpec bank(String id, int statementStyle, boolean due, boolean pdf, boolean addOnWording) {
        BankSpec b = BANKS.stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow();
        Style s = new Style();
        s.statementStyle = statementStyle;
        s.dueReminders = due;
        s.pdfReadable = pdf;
        s.addOnWording = addOnWording;
        styles.put(id, s);
        specs.put(id, b);
        return b;
    }

    Detection detectRaw() {
        return Detector.detect(mails.stream().map(FeatureExtractor::derive).toList(), statements);
    }

    Detection detect(long seed) {
        return Stability.detectStable(mails.stream().map(FeatureExtractor::derive).toList(), statements, seed).detection();
    }

    // ------------------------------------------------------------------ generation

    record Card(BankSpec bank, String last4, Kind kind, String variant, int from, int to) {}

    static SyntheticMailbox generate(long seed) {
        SyntheticMailbox mb = new SyntheticMailbox(seed);
        mb.build(seed);
        return mb;
    }

    private void build(long seed) {
        List<BankSpec> pool = new ArrayList<>(BANKS);
        java.util.Collections.shuffle(pool, rnd);
        int nBanks = 3 + rnd.nextInt(4);
        List<BankSpec> banks = pool.subList(0, nBanks);
        for (BankSpec b : banks) {
            Style s = new Style();
            s.statementStyle = rnd.nextInt(3);
            s.dueReminders = rnd.nextBoolean();
            s.pdfReadable = rnd.nextDouble() < 0.4;
            s.addOnWording = rnd.nextBoolean();
            s.wording = rnd.nextInt(4);
            styles.put(b.id(), s);
            specs.put(b.id(), b);
        }
        BankSpec home = banks.get(0);
        boolean oneCardUser = seed % 5 == 1;   // a mailbox with a single credit card and nothing else

        // savings account + debit card at the home bank
        String acct = newLast4();
        if (!oneCardUser) {
            String debit = newLast4();
            truth.put(home.id() + ":" + acct, new Truth(home.id(), acct, Kind.ACCOUNT, null, null, null));
            truth.put(home.id() + ":" + debit, new Truth(home.id(), debit, Kind.DEBIT_CARD, null, null, null));
            String rent = newLast4();
            traps.add(home.id() + ":" + rent);
            accountMails(home, acct, rent, seed % 2 == 0);
            debitMails(home, debit);
            List<String> family = new ArrayList<>();
            for (int i = 0, n = rnd.nextInt(3); i < n; i++) family.add(newLast4());
            for (String f : family) traps.add(home.id() + ":" + f);
            familyTransfers(home, acct, family);
        }
        if (!oneCardUser && rnd.nextBoolean()) {
            BankSpec second = banks.get(1);
            String acct2 = newLast4();
            truth.put(second.id() + ":" + acct2, new Truth(second.id(), acct2, Kind.ACCOUNT, null, null, null));
            accountMails(second, acct2, null, false);
        }

        // 2-4 credit cards
        int nCards = oneCardUser ? 1 : 2 + rnd.nextInt(3);
        List<Card> cards = new ArrayList<>();
        for (int i = 0; i < nCards; i++) {
            BankSpec b = banks.get(rnd.nextInt(banks.size()));
            List<String> vs = products(b.id());
            String v = vs.isEmpty() ? null : vs.get(rnd.nextInt(vs.size()));
            String l4 = newLast4();
            if (!oneCardUser && i == 0 && seed % 4 == 0 && !b.id().equals(home.id())) {
                usedLast4.remove(l4);
                l4 = acct; // same last4 as the savings account, at a different bank
            }
            cards.add(new Card(b, l4, Kind.CREDIT_CARD, v, 0, MONTHS - 1));
        }
        Map<String, Integer> perBank = new HashMap<>();
        for (Card c : cards) perBank.merge(c.bank().id(), 1, Integer::sum);
        for (Card c : cards) {
            double p = rnd.nextDouble();
            String scenario = p < 0.35 ? "none" : p < 0.48 ? "replaced" : p < 0.61 ? "upgraded" : p < 0.74 ? "addon"
                    : p < 0.87 ? "closed" : "stopped";
            if ((scenario.equals("replaced") || scenario.equals("upgraded")) && perBank.get(c.bank().id()) > 1) scenario = "none";
            if (scenario.equals("upgraded") && products(c.bank().id()).size() < 2) scenario = "replaced";
            creditCardScenario(c, scenario, perBank.get(c.bank().id()) == 1);
        }

        // marketing, OTPs, KYC, payment apps
        for (BankSpec b : banks) marketing(b, cards);
        String other = newLast4();
        traps.add(cards.get(0).bank().id() + ":" + other);
        for (int m = 0; m < MONTHS; m++) {
            if (rnd.nextDouble() < 0.7) {
                Card c = live.get(rnd.nextInt(live.size()));
                if (m >= c.from() && m <= c.to()) {
                    mail("cred.club", day(m), "Payment successful", "Payment of INR " + amt() + " to " + c.bank().name() + " credit card XX" + c.last4()
                            + " was successful. Your CRED coins have been credited.", true);
                }
            }
            if (rnd.nextDouble() < 0.4) {
                // a family member's card paid through the app: never a detected entity
                mail("cred.club", day(m), "Payment successful", "Payment of INR " + amt() + " to " + cards.get(0).bank().name() + " credit card XX" + other
                        + " was successful.", true);
            }
        }
        mail(home.domain(), day(rnd.nextInt(MONTHS)), "Aadhaar seeding confirmation",
                "Dear Customer, your Aadhaar number XXXX XXXX " + randomDigits(4) + " has been successfully linked to your account XX" + acct + ". Thank you.", true);
    }

    void accountMails(BankSpec b, String acct, String rent, boolean rentTrap) {
        List<FixtureLayout> fx = FIXTURES.stream().filter(f -> f.bank().equals(b.id()) && f.kind() == Kind.ACCOUNT).toList();
        for (int m = 0; m < MONTHS; m++) {
            for (int i = 0; i < 5; i++) {
                LocalDate d = day(m);
                int pick = rnd.nextInt(fx.isEmpty() ? 4 : 6);
                if (pick >= 4) {
                    FixtureLayout f = fx.get(rnd.nextInt(fx.size()));
                    alert(b.domain(), d, fixSubject(f, acct), fromFixture(f, acct));
                } else if (pick == 0) {
                    alert(b.domain(), d, "You have done a UPI txn", "Dear Customer, INR " + amt() + " has been debited from your " + b.name() + " A/c XX" + acct
                            + " to VPA " + randomDigits(6).toLowerCase() + "@okbank on " + d.format(DMY) + ". UPI Ref No " + randomDigits(12) + ".");
                } else if (pick == 1) {
                    alert(b.domain(), d, "Funds transfer alert", "INR " + amt() + " debited from your A/c XX" + acct + " and credited to A/c XX" + randomDigits(4)
                            + " of beneficiary SAMPLE PAYEE via NEFT. UTR " + randomDigits(16) + ".");
                } else if (pick == 2) {
                    alert(b.domain(), d, "Credit alert for your account", "INR " + amt() + " has been credited to your A/c XX" + acct + " by IMPS from A/c XX"
                            + randomDigits(4) + ". Ref " + randomDigits(12) + ".");
                } else {
                    alert(b.domain(), d, "Account balance update", "The available balance in your A/c XX" + acct + " is INR " + amt() + " as on " + d.format(DMY) + ".");
                }
            }
            if (rentTrap && rent != null) {
                LocalDate d = day(m);
                alert(b.domain(), d, "Standing instruction executed", "Standing instruction for INR 25000.00 executed on " + d.format(DMY)
                        + ". Amount transferred to beneficiary A/c XX" + rent + ".");
                if (rnd.nextBoolean())
                    alert(b.domain(), d, "Funds transfer alert", "INR 25000.00 debited from your A/c XX" + acct + " and credited to A/c XX" + rent
                            + " of beneficiary SAMPLE LANDLORD via NEFT. UTR " + randomDigits(16) + ".");
            }
            if (rnd.nextDouble() < 0.9) {
                YearMonth ym = START.plusMonths(m);
                mail(b.domain(), ym.atEndOfMonth().minusDays(1), b.name() + " Savings Account statement for " + ym.format(MON),
                        "Dear Customer, your account statement for A/c XX" + acct + " for " + ym.format(MON) + " is attached.", true, "AcctStatement_" + ym + ".pdf");
            }
        }
    }

    /** Monthly transfers to family accounts, in two layouts: the other party, never an own account. */
    void familyTransfers(BankSpec b, String acct, List<String> family) {
        for (int m = 0; m < MONTHS; m++) {
            for (String f : family) {
                if (rnd.nextDouble() < 0.2) continue;
                LocalDate d = day(m);
                if (rnd.nextBoolean()) {
                    alert(b.domain(), d, "IMPS transfer successful", "IMPS transfer of INR " + amt() + " to A/c XX" + f + " from your A/c XX" + acct
                            + " is successful. Ref " + randomDigits(12) + ".");
                } else {
                    alert(b.domain(), d, "Funds transfer alert", "INR " + amt() + " debited from your A/c XX" + acct + " and credited to A/c XX" + f
                            + " of beneficiary SAMPLE FAMILY via IMPS. UTR " + randomDigits(16) + ".");
                }
            }
        }
    }

    void debitMails(BankSpec b, String debit) {
        List<FixtureLayout> fx = FIXTURES.stream().filter(f -> f.bank().equals(b.id()) && f.kind() == Kind.DEBIT_CARD).toList();
        for (int m = 0; m < MONTHS; m++) {
            for (int i = 0; i < 2; i++) {
                LocalDate d = day(m);
                int pick = rnd.nextInt(fx.isEmpty() ? 2 : 3);
                if (pick == 2) {
                    FixtureLayout f = fx.get(rnd.nextInt(fx.size()));
                    alert(b.domain(), d, fixSubject(f, debit), fromFixture(f, debit));
                } else if (pick == 0) {
                    // a generic tier word on a debit card ("Platinum Debit Card") is never a product
                    alert(b.domain(), d, b.name() + " Debit Card transaction alert", "Your " + b.name() + " Platinum Debit Card XX" + debit + " was used for INR " + amt()
                            + " at " + merchant() + " on " + d.format(DMY) + ".");
                } else {
                    alert(b.domain(), d, "ATM cash withdrawal alert", "INR " + amt() + " withdrawn at ATM using your Debit Card XX" + debit + " on " + d.format(DMY) + ".");
                }
            }
            if (m % 9 == 3) {
                mail(b.domain(), day(m), "Your " + b.name() + " Debit Card has been dispatched",
                        "Your " + b.name() + " Platinum Debit Card ending " + debit + " has been dispatched and will reach you soon.", true);
            }
        }
    }

    void cardAlerts(Card c, int from, int to, boolean addOn) {
        List<FixtureLayout> fx = FIXTURES.stream().filter(f -> f.bank().equals(c.bank().id()) && f.kind() == Kind.CREDIT_CARD).toList();
        Style st = styles.get(c.bank().id());
        String b = c.bank().name();
        for (int m = from; m <= to; m++) {
            for (int i = 0; i < 3; i++) {
                LocalDate d = day(m);
                int pick = rnd.nextInt(fx.isEmpty() ? 2 : 3);
                String mer = merchant();
                if (addOn && st.addOnWording) {
                    alert(c.bank().domain(), d, "Transaction alert on your " + b + " Credit Card", "Thank you for using your " + b + " Credit Card (add-on card) ending "
                            + c.last4() + " for INR " + amt() + " at " + mer + " on " + d.format(DMY) + ".");
                } else if (pick == 2) {
                    FixtureLayout f = fx.get(rnd.nextInt(fx.size()));
                    alert(c.bank().domain(), d, fixSubject(f, c.last4()), fromFixture(f, c.last4()));
                } else if (pick == 0) {
                    alert(c.bank().domain(), d, "Transaction alert on your " + b + " Credit Card", "Dear Customer, Thank you for using your " + b
                            + " Credit Card ending " + c.last4() + " for INR " + amt() + " at " + mer + " on " + d.format(DMY) + ". If not done by you, call us.");
                } else {
                    alert(c.bank().domain(), d, "INR " + amt() + " spent on your " + b + " Credit Card at " + mer, "INR " + amt() + " spent on " + b
                            + " Credit Card XX" + c.last4() + " at " + mer + " on " + d.format(DMY) + ". Avl limit: INR " + amt() + ".");
                }
                if (rnd.nextDouble() < 0.15) {
                    mail(c.bank().domain(), d, "OTP for your transaction", randomDigits(6) + " is the OTP for transaction of INR " + amt() + " at " + mer
                            + " on your " + b + " Credit Card XX" + c.last4() + ". Do not share it with anyone.", true);
                }
            }
        }
    }

    /** The credit-limit bucket the generator prints on a card's statements (truth for the limit field). */
    static String limitOf(String last4) {
        return new String[]{"<1L", "1-3L", "3-5L", "5-10L"}[Math.abs(last4.hashCode()) % 4];
    }

    void cardBilling(Card c, int from, int to, List<String> addOns) {
        Style st = styles.get(c.bank().id());
        String b = c.bank().name();
        String v = c.variant();
        String limit = limitOf(c.last4());
        for (int m = from; m <= to; m++) {
            YearMonth ym = START.plusMonths(m);
            if (rnd.nextDouble() < 0.08) continue; // a missing month
            LocalDate d = ym.atDay(12);
            int style = v == null ? 2 : st.statementStyle;
            if (style == 0) {
                String body = switch (st.wording) {
                    case 0 -> "Dear Customer, your " + b + " " + v + " Credit Card statement for card ending " + c.last4() + " is attached.";
                    case 1 -> "The statement of your " + v + " Credit Card ending " + c.last4() + " (" + b + ") is ready.";
                    case 2 -> "Card variant: " + v + ". Card number XXXX" + c.last4() + ". Your statement is attached.";
                    default -> "Your " + b + " Credit Card (" + v + ") XX" + c.last4() + " statement is attached.";
                };
                mail(c.bank().domain(), d, "Your " + b + " Credit Card Statement for " + ym.format(MON), body + " Total amount due INR " + amt() + ".", true);
            } else if (style == 1) {
                mail(c.bank().domain(), d, v + " Credit Card Statement - " + ym.format(MON),
                        "Please find attached the statement for your card XX" + c.last4() + ". Total amount due INR " + amt() + ".", true,
                        "XXXXXXXXXXXX" + c.last4() + "_" + ym + ".pdf");
            } else {
                mail(c.bank().domain(), d, b + " Credit Card e-Statement",
                        "Your statement is ready. Statement for Credit Card number XXXX XXXX XXXX " + c.last4() + ". Total amount due INR " + amt() + ".", true);
            }
            if (st.pdfReadable) {
                statements.add(new StatementContent("PDF_MAIL", c.bank().id(), c.last4(), Kind.CREDIT_CARD, ym, v, limit,
                        addOns.isEmpty() ? List.of() : List.copyOf(addOns), false));
            }
            if (st.dueReminders) {
                mail(c.bank().domain(), ym.atDay(22), "Payment due reminder for your " + b + " Credit Card",
                        "Your payment for " + b + " Credit Card XX" + c.last4() + " is due on " + ym.plusMonths(1).atDay(2).format(DMY)
                                + ". Minimum amount due INR " + amt() + ".", true);
            }
        }
    }

    void dispatch(Card c, int month) {
        String b = c.bank().name();
        String v = c.variant() == null ? "" : c.variant() + " ";
        mail(c.bank().domain(), day(month), "Your " + b + " Credit Card has been dispatched",
                "Your " + v + b + " Credit Card ending " + c.last4() + " has been dispatched and will reach you soon.", true);
    }

    void creditCardScenario(Card c, String scenario, boolean onlyCardAtBank) {
        String key = c.bank().id() + ":" + c.last4();
        switch (scenario) {
            case "replaced", "upgraded" -> {
                int k = 6 + rnd.nextInt(11);
                String v2 = c.variant();
                if (scenario.equals("upgraded")) {
                    List<String> vs = new ArrayList<>(products(c.bank().id()));
                    vs.remove(c.variant());
                    v2 = vs.get(rnd.nextInt(vs.size()));
                }
                Card a = new Card(c.bank(), c.last4(), Kind.CREDIT_CARD, c.variant(), 0, k);
                Card nb = new Card(c.bank(), newLast4(), Kind.CREDIT_CARD, v2, k + 1, MONTHS - 1);
                Lifecycle l = scenario.equals("upgraded") ? Lifecycle.UPGRADED : Lifecycle.REPLACED;
                truth.put(key, new Truth(c.bank().id(), c.last4(), Kind.CREDIT_CARD, c.variant(), l, nb.bank().id() + ":" + nb.last4()));
                truth.put(nb.bank().id() + ":" + nb.last4(), new Truth(c.bank().id(), nb.last4(), Kind.CREDIT_CARD, v2, Lifecycle.REPLACEMENT, key));
                live.add(a);
                live.add(nb);
                cardAlerts(a, 0, k, false);
                cardBilling(a, 0, k, List.of());
                cardAlerts(nb, k + 1, MONTHS - 1, false);
                cardBilling(nb, k + 1, MONTHS - 1, List.of());
                if (rnd.nextBoolean()) {
                    String b = c.bank().name();
                    // several phrasings each, some the detector does not know: those must end blank or LOW, never wrong
                    String o = a.last4(), n = nb.last4();
                    if (l == Lifecycle.UPGRADED) {
                        String[] w = {
                                "Dear Customer, your " + b + " Credit Card XX" + o + " has been upgraded to " + v2 + " Credit Card. Your new " + v2 + " "
                                        + b + " Credit Card ending " + n + " has been dispatched.",
                                "Congratulations! Your " + b + " Credit Card XX" + o + " is upgraded to " + v2 + " Credit Card ending " + n + ".",
                                "Your " + b + " Credit Card XX" + o + " now moves to the " + v2 + " Credit Card, card XX" + n + "."};
                        mail(c.bank().domain(), day(k), "About your " + b + " Credit Card", w[rnd.nextInt(w.length)], true);
                    } else {
                        String[] w = {
                                "Dear Customer, your " + b + " Credit Card XX" + o + " has been replaced. Your new card ending " + n + " has been dispatched.",
                                "As your " + b + " Credit Card XX" + o + " was reported lost, it has been blocked and a replacement card XX" + n + " is on its way.",
                                "Your " + b + " Credit Card XX" + o + " has been closed and a new card XX" + n + " has been issued to you.",
                                "We have swapped your " + b + " Credit Card XX" + o + " for a fresh card XX" + n + ".",
                                "Your card XX" + o + " is retired; please start using card XX" + n + "."};
                        mail(c.bank().domain(), day(k), "Your replacement card is on its way", w[rnd.nextInt(w.length)], true);
                    }
                } else {
                    dispatch(nb, k + 1);
                }
            }
            case "addon" -> {
                truth.put(key, new Truth(c.bank().id(), c.last4(), Kind.CREDIT_CARD, c.variant(), null, null));
                int a0 = 2 + rnd.nextInt(10);
                String x = newLast4();
                Card addon = new Card(c.bank(), x, Kind.CREDIT_CARD, c.variant(), a0, MONTHS - 1);
                truth.put(c.bank().id() + ":" + x, new Truth(c.bank().id(), x, Kind.CREDIT_CARD, c.variant(), Lifecycle.ADD_ON, key));
                live.add(c);
                cardAlerts(c, 0, MONTHS - 1, false);
                cardBilling(c, 0, a0 - 1, List.of());
                cardBilling(c, a0, MONTHS - 1, List.of(x));
                cardAlerts(addon, a0, MONTHS - 1, true);
                if (rnd.nextBoolean()) {
                    String b = c.bank().name();
                    String[] w = {
                            "Dear Customer, an add-on card ending " + x + " has been issued on your " + b + " Credit Card XX" + c.last4() + ".",
                            "A supplementary card XX" + x + " linked to your " + b + " Credit Card XX" + c.last4() + " is now active.",
                            "Your family member's card XX" + x + " on " + b + " Credit Card XX" + c.last4() + " is ready to use."};
                    mail(c.bank().domain(), day(a0), "About your " + b + " Credit Card", w[rnd.nextInt(w.length)], true);
                }
            }
            case "closed" -> {
                int k = 6 + rnd.nextInt(13);
                truth.put(key, new Truth(c.bank().id(), c.last4(), Kind.CREDIT_CARD, c.variant(), Lifecycle.CLOSED, null));
                live.add(new Card(c.bank(), c.last4(), c.kind(), c.variant(), 0, k - 1));
                cardAlerts(c, 0, k - 1, false);
                cardBilling(c, 0, k - 1, List.of());
                String b = c.bank().name();
                String[] w = {
                        "As requested, your " + b + " Credit Card ending " + c.last4() + " has been closed. Thank you for banking with us.",
                        "Closure of your " + b + " Credit Card XX" + c.last4() + " has been processed successfully.",
                        "Your " + b + " Credit Card account ending " + c.last4() + " is now closed.",
                        "We have shut down your " + b + " Credit Card XX" + c.last4() + " as you asked.",
                        "Your " + b + " Credit Card XX" + c.last4() + " has been permanently blocked."};
                mail(c.bank().domain(), START.plusMonths(k).atDay(20), "Your " + b + " Credit Card request", w[rnd.nextInt(w.length)], true);
            }
            case "stopped" -> {
                int k = 4 + rnd.nextInt(12);
                truth.put(key, new Truth(c.bank().id(), c.last4(), Kind.CREDIT_CARD, c.variant(), null, null));
                live.add(new Card(c.bank(), c.last4(), c.kind(), c.variant(), 0, k));
                cardAlerts(c, 0, k, false);
                cardBilling(c, 0, k, List.of());
            }
            default -> {
                live.add(c);
                truth.put(key, new Truth(c.bank().id(), c.last4(), Kind.CREDIT_CARD, c.variant(), null, null));
                cardAlerts(c, 0, MONTHS - 1, false);
                cardBilling(c, 0, MONTHS - 1, List.of());
                if (rnd.nextDouble() < 0.3) {
                    String b = c.bank().name();
                    mail(c.bank().domain(), day(rnd.nextInt(MONTHS)), "Important information about your " + b + " Credit Card",
                            "Your " + b + " Credit Card XX" + c.last4() + " will be closed if not used for 12 months.", true);
                }
            }
        }
    }

    void marketing(BankSpec b, List<Card> cards) {
        List<String> vs = products(b.id());
        Card own = cards.stream().filter(c -> c.bank().equals(b)).findFirst().orElse(null);
        for (int m = 0; m < MONTHS; m++) {
            for (int i = 0; i < 2; i++) {
                String v = vs.isEmpty() ? "Platinum" : vs.get(rnd.nextInt(vs.size()));
                int pick = rnd.nextInt(3);
                if (pick == 0 && own != null) {
                    mail(b.domain(), day(m), "Upgrade to " + v + " Credit Card - exclusive offer",
                            "Dear customer, as a valued holder of your " + b.name() + " Credit Card XX" + own.last4() + ", you are eligible to upgrade to the "
                                    + v + " " + b.name() + " Credit Card.", true);
                } else if (pick == 1) {
                    String fake = randomDigits(4);
                    mail(b.domain(), day(m), "Pre-approved " + v + " Credit Card for you",
                            "Get the " + v + " " + b.name() + " Credit Card XX" + fake + " with lifetime free benefits. Apply now.", true);
                } else {
                    mail(b.domain(), day(m), "Exclusive offers on " + merchant(), "Get 10% off with your " + b.name() + " card. Limited period offer.", true);
                }
            }
        }
    }
}
