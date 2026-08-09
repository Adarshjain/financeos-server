package com.financeos.domain.categorization;

import com.financeos.core.exception.ValidationException;
import lombok.extern.slf4j.Slf4j;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Evaluates a rule's pattern against a transaction's sourced description for every
 * {@link MatchType}. Also owns pattern canonicalization (what gets stored in
 * merchantKey for each type), so save-time validation and match-time behavior can
 * never drift apart.
 * <p>
 * Regex evaluation is guarded: patterns are length-capped and compile-checked at save
 * time, compiled lazily into a bounded cache, and evaluated against a deadline-checking
 * CharSequence so a catastrophically backtracking pattern aborts after
 * {@link #REGEX_TIMEOUT_MS} instead of hanging a categorization batch. A regex that
 * times out or fails simply doesn't match.
 */
@Slf4j
public final class RuleMatcher {

    public static final int MIN_PATTERN_LENGTH = 3;
    public static final int MAX_PATTERN_LENGTH = 255;
    public static final int MAX_REGEX_LENGTH = 200;
    static final long REGEX_TIMEOUT_MS = 50;

    private static final int MAX_REGEX_CACHE_SIZE = 1000;
    private static final ConcurrentHashMap<String, Pattern> REGEX_CACHE = new ConcurrentHashMap<>();

    private RuleMatcher() {
    }

    /**
     * Precomputed views of one description, so a batch run normalizes/uppercases each
     * transaction once instead of once per rule.
     */
    public static final class MatchContext {
        private final String raw;
        private final String upper;
        private String normalized; // lazy: only MERCHANT_KEY rules need it

        private MatchContext(String raw) {
            this.raw = raw;
            this.upper = raw.toUpperCase(Locale.ROOT);
        }

        public static MatchContext of(String description) {
            return new MatchContext(description == null ? "" : description.trim());
        }

        public boolean isBlank() {
            return raw.isBlank();
        }

        private String normalized() {
            if (normalized == null) {
                normalized = DescriptionNormalizer.normalize(raw);
            }
            return normalized;
        }
    }

    public static boolean matches(CategoryRule rule, MatchContext ctx) {
        return matches(rule.getMatchType(), rule.getMerchantKey(), ctx);
    }

    public static boolean matches(MatchType type, String pattern, MatchContext ctx) {
        if (pattern == null || pattern.isBlank() || ctx.isBlank()) {
            return false;
        }
        MatchType effectiveType = type == null ? MatchType.MERCHANT_KEY : type;
        return switch (effectiveType) {
            case MERCHANT_KEY -> pattern.length() >= MIN_PATTERN_LENGTH && ctx.normalized().contains(pattern);
            case CONTAINS -> ctx.upper.contains(pattern.toUpperCase(Locale.ROOT));
            case STARTS_WITH -> ctx.upper.startsWith(pattern.trim().toUpperCase(Locale.ROOT));
            case EXACT -> ctx.upper.equals(pattern.trim().toUpperCase(Locale.ROOT));
            case REGEX -> regexMatches(pattern, ctx.raw);
        };
    }

    /**
     * Validates a user-entered pattern for the given type and returns the canonical
     * form to store in merchantKey (normalized for MERCHANT_KEY, trimmed as-typed
     * otherwise). Throws ValidationException with a user-facing message when invalid.
     */
    public static String canonicalizePattern(MatchType type, String rawPattern) {
        if (rawPattern == null || rawPattern.isBlank()) {
            throw new ValidationException("Pattern must not be empty.");
        }
        String trimmed = rawPattern.trim();
        if (trimmed.length() > MAX_PATTERN_LENGTH) {
            throw new ValidationException("Pattern must be at most " + MAX_PATTERN_LENGTH + " characters.");
        }
        switch (type) {
            case MERCHANT_KEY -> {
                String normalized = DescriptionNormalizer.normalize(trimmed);
                if (normalized.length() < MIN_PATTERN_LENGTH) {
                    throw new ValidationException("Merchant key length must be at least "
                            + MIN_PATTERN_LENGTH + " characters after normalization.");
                }
                return normalized;
            }
            case CONTAINS, STARTS_WITH -> {
                if (trimmed.length() < MIN_PATTERN_LENGTH) {
                    throw new ValidationException("Pattern must be at least " + MIN_PATTERN_LENGTH + " characters.");
                }
                return trimmed;
            }
            case EXACT -> {
                return trimmed;
            }
            case REGEX -> {
                if (trimmed.length() > MAX_REGEX_LENGTH) {
                    throw new ValidationException("Regex pattern must be at most " + MAX_REGEX_LENGTH + " characters.");
                }
                try {
                    Pattern.compile(trimmed, Pattern.CASE_INSENSITIVE);
                } catch (PatternSyntaxException e) {
                    throw new ValidationException("Invalid regular expression: " + e.getDescription());
                }
                return trimmed;
            }
            default -> throw new ValidationException("Unsupported match type: " + type);
        }
    }

    private static boolean regexMatches(String pattern, String description) {
        Pattern compiled = compiledRegex(pattern);
        if (compiled == null) {
            return false;
        }
        long deadlineNanos = System.nanoTime() + REGEX_TIMEOUT_MS * 1_000_000;
        try {
            return compiled.matcher(new DeadlineCharSequence(description, deadlineNanos)).find();
        } catch (RegexTimeoutException e) {
            log.warn("Regex rule evaluation timed out after {}ms; treating as no match. Pattern: {}", REGEX_TIMEOUT_MS, pattern);
            return false;
        } catch (RuntimeException | StackOverflowError e) {
            log.warn("Regex rule evaluation failed; treating as no match. Pattern: {}", pattern, e);
            return false;
        }
    }

    private static Pattern compiledRegex(String pattern) {
        Pattern cached = REGEX_CACHE.get(pattern);
        if (cached != null) {
            return cached;
        }
        try {
            Pattern compiled = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
            if (REGEX_CACHE.size() >= MAX_REGEX_CACHE_SIZE) {
                REGEX_CACHE.clear();
            }
            REGEX_CACHE.put(pattern, compiled);
            return compiled;
        } catch (PatternSyntaxException e) {
            // Save-time validation should prevent this; a stored-but-invalid pattern just never matches.
            return null;
        }
    }

    private static final class RegexTimeoutException extends RuntimeException {
        RegexTimeoutException() {
            super(null, null, false, false);
        }
    }

    /**
     * CharSequence that aborts the regex engine (which reads input exclusively through
     * charAt) once the deadline passes.
     */
    private static final class DeadlineCharSequence implements CharSequence {
        private final CharSequence inner;
        private final long deadlineNanos;

        DeadlineCharSequence(CharSequence inner, long deadlineNanos) {
            this.inner = inner;
            this.deadlineNanos = deadlineNanos;
        }

        @Override
        public char charAt(int index) {
            if (System.nanoTime() > deadlineNanos) {
                throw new RegexTimeoutException();
            }
            return inner.charAt(index);
        }

        @Override
        public int length() {
            return inner.length();
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new DeadlineCharSequence(inner.subSequence(start, end), deadlineNanos);
        }

        @Override
        public String toString() {
            return inner.toString();
        }
    }
}
