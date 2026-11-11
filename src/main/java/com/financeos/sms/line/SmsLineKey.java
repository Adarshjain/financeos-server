package com.financeos.sms.line;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The line key of one message: the same six steps the phone runs (see the SMS sync API contract). Pure: it reads only
 * the catalogue data it is built with.
 */
@Component
public class SmsLineKey {

    /** Why a message belongs to no line. */
    public enum Skip { PERSONAL, OTP, PROMO, UNKNOWN_SENDER }

    /** Either a key or the reason there is none. */
    public record Result(String sender, String ending, String family, Skip skip) {
        public boolean hasLine() {
            return skip == null;
        }

        static Result key(String sender, String ending, String family) {
            return new Result(sender, ending, family, null);
        }

        static Result skipped(String sender, Skip skip) {
            return new Result(sender, null, null, skip);
        }
    }

    private static final Pattern SENDER = Pattern.compile("^[A-Z0-9]{3,20}$");
    private static final Pattern HAS_LETTER = Pattern.compile("[A-Z]");
    private static final Pattern AMOUNT = Pattern.compile("(?i)(?:inr|rs\\.?|₹)\\s*[\\d,]+(?:\\.\\d{1,2})?");
    private static final Pattern MONEY_WORD = Pattern.compile(
            "(?i)\\b(debited|credited|spent|withdrawn|received|paid|deposited|refund(ed)?|deducted|purchase|sent)\\b");

    private final SenderCatalogue catalogue;
    private final List<Pattern> otp;
    private final List<String> promoSuffixes;
    private final List<Pattern> endings;
    private final java.util.Map<String, List<Pattern>> families = new java.util.HashMap<>();

    public SmsLineKey(SenderCatalogue catalogue) {
        this.catalogue = catalogue;
        SenderCatalogue.Data data = catalogue.data();
        this.otp = data.drop().otpPatterns().stream().map(Pattern::compile).toList();
        this.promoSuffixes = data.drop().promoHeaderSuffixes();
        this.endings = data.endingPatterns().stream().map(e -> Pattern.compile(e.pattern())).toList();
        for (SenderCatalogue.Sender s : data.senders()) {
            families.put(s.header(), s.families().stream().map(f -> Pattern.compile("(?i)" + f.pattern())).toList());
        }
    }

    /** A sender must be 3-20 upper-case letters or digits and hold at least one letter (all digits is a phone number or short code). */
    public static boolean isValidSender(String normalised) {
        return normalised != null && SENDER.matcher(normalised).matches() && HAS_LETTER.matcher(normalised).find();
    }

    /** True when the body has an amount and a money word (the txnCount rule, step 6). */
    public static boolean hasAmountAndMoneyWord(String body) {
        return body != null && AMOUNT.matcher(body).find() && MONEY_WORD.matcher(body).find();
    }

    public Result resolve(String rawSender, String body) {
        String raw = rawSender == null ? "" : rawSender.trim().toUpperCase(Locale.ROOT);
        String sender = SenderCatalogue.normaliseSender(rawSender);
        String text = body == null ? "" : body;
        // 1
        if (!isValidSender(sender)) {
            return Result.skipped(sender, Skip.PERSONAL);
        }
        // 2
        for (String suffix : promoSuffixes) {
            if (raw.endsWith("-" + suffix)) {
                return Result.skipped(sender, Skip.PROMO);
            }
        }
        for (Pattern p : otp) {
            if (p.matcher(text).find()) {
                return Result.skipped(sender, Skip.OTP);
            }
        }
        Optional<SenderCatalogue.Sender> known = catalogue.find(sender);
        // 3
        if (known.isPresent() && known.get().numberless()) {
            List<Pattern> fams = families.get(sender);
            if (fams.isEmpty()) {
                return Result.key(sender, null, null);
            }
            List<SenderCatalogue.Family> defs = known.get().families();
            for (int i = 0; i < fams.size(); i++) {
                if (fams.get(i).matcher(text).find()) {
                    return Result.key(sender, null, defs.get(i).key());
                }
            }
            return Result.skipped(sender, Skip.UNKNOWN_SENDER);
        }
        // 4
        String ending = ending(text);
        // 5
        if (known.isEmpty() && !(ending != null && hasAmountAndMoneyWord(text))) {
            return Result.skipped(sender, Skip.UNKNOWN_SENDER);
        }
        return Result.key(sender, ending, null);
    }

    /** Capture group 1 of whichever ending pattern matches starts earliest in the body. */
    public String ending(String body) {
        int best = Integer.MAX_VALUE;
        String ending = null;
        for (Pattern p : endings) {
            Matcher m = p.matcher(body);
            if (m.find() && m.start() < best) {
                best = m.start();
                ending = m.group(1);
            }
        }
        return ending;
    }
}
