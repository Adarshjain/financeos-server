package com.financeos.gmail.accountdetect.scan;

import com.financeos.gmail.accountdetect.MailFeature;
import com.financeos.gmail.engine.GmailEngine;
import com.financeos.gmail.internal.GmailEngineException;
import com.financeos.gmail.internal.GmailError;
import com.financeos.gmail.internal.GmailMessage;
import com.financeos.gmail.internal.MessageGoneException;
import com.google.api.client.googleapis.batch.BatchRequest;
import com.google.api.client.googleapis.batch.json.JsonBatchCallback;
import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpHeaders;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.ListMessagesResponse;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePartHeader;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gmail reads for the account-detect scan (moved unchanged from the v3 dev runner): per-token listing, metadata in
 * paced batches, full messages and attachments paced at {@code getsPerSecond} with backoff on 429/5xx. An auth
 * rejection is thrown as {@link GmailError#AUTH_ERROR} instead of being counted as a failed read.
 */
public class GmailMailSource implements MailSource, AccountDetectScan.Describable {

    private final GmailEngine engine;
    private final Gmail svc;
    private final int batchSize;
    private final double getsPerSecond;

    public GmailMailSource(GmailEngine engine, Gmail svc, int batchSize, double getsPerSecond) {
        this.engine = engine;
        this.svc = svc;
        this.batchSize = batchSize;
        this.getsPerSecond = getsPerSecond;
    }

    @Override
    public String describe() {
        return batchSize + " per batch, " + getsPerSecond + " gets/s";
    }

    @Override
    public Listing list(Collection<String> tokens, Instant after) throws Exception {
        AtomicInteger listCalls = new AtomicInteger();
        String suffix = after == null ? "" : " after:" + after.getEpochSecond();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<List<String>>> lf = new ArrayList<>();
            for (String t : tokens) lf.add(pool.submit(() -> listAll(svc, "from:" + t + suffix, listCalls)));
            Set<String> ids = new LinkedHashSet<>();
            for (Future<List<String>> f : lf) ids.addAll(await(f));
            return new Listing(new ArrayList<>(ids), listCalls.get());
        } finally {
            pool.shutdownNow();
        }
    }

    private static List<String> listAll(Gmail svc, String query, AtomicInteger calls) throws IOException {
        List<String> ids = new ArrayList<>();
        String page = null;
        try {
            do {
                calls.incrementAndGet();
                ListMessagesResponse r = svc.users().messages().list("me").setQ(query).setMaxResults(500L).setPageToken(page)
                        .setFields("nextPageToken,messages/id").execute();
                if (r == null) break;
                if (r.getMessages() != null) for (Message m : r.getMessages()) ids.add(m.getId());
                page = r.getNextPageToken();
            } while (page != null);
        } catch (IOException e) {
            throwIfAuth(e);
            throw e;
        }
        return ids;
    }

    @Override
    public Map<String, MailFeature> metadata(List<String> ids, Consumer<String> out) throws Exception {
        Map<String, MailFeature> result = new ConcurrentHashMap<>();
        List<String> pending = ids;
        AtomicLong slot = new AtomicLong(System.nanoTime());
        long gap = (long) (1_000_000_000L * batchSize / getsPerSecond);
        AtomicBoolean authFailed = new AtomicBoolean();
        int retries = 0;
        for (int attempt = 0; attempt < 6 && !pending.isEmpty(); attempt++) {
            List<String> retry = Collections.synchronizedList(new ArrayList<>());
            ExecutorService pool = Executors.newFixedThreadPool(4);
            try {
                List<Future<?>> fs = new ArrayList<>();
                for (int i = 0; i < pending.size(); i += batchSize) {
                    List<String> chunk = pending.subList(i, Math.min(pending.size(), i + batchSize));
                    fs.add(pool.submit(() -> {
                        if (authFailed.get()) return null;
                        pace(slot, gap);
                        try {
                            BatchRequest batch = svc.batch();
                            for (String id : chunk) {
                                svc.users().messages().get("me", id).setFormat("metadata").setMetadataHeaders(List.of("From", "Subject"))
                                        .setFields("id,snippet,internalDate,payload/headers")
                                        .queue(batch, new JsonBatchCallback<Message>() {
                                            @Override
                                            public void onSuccess(Message m, HttpHeaders h) {
                                                result.put(id, toFeature(m));
                                            }

                                            @Override
                                            public void onFailure(GoogleJsonError e, HttpHeaders h) {
                                                if (e.getCode() == 401) authFailed.set(true);
                                                else if (e.getCode() == 429 || e.getCode() >= 500 || e.getCode() == 403) retry.add(id);
                                            }
                                        });
                            }
                            batch.execute();
                        } catch (IOException e) {
                            if (GmailEngine.classify(e).getErrorType() == GmailError.AUTH_ERROR) authFailed.set(true);
                            else retry.addAll(chunk);
                        }
                        return null;
                    }));
                }
                for (Future<?> f : fs) await(f);
            } finally {
                pool.shutdownNow();
            }
            if (authFailed.get()) throw new GmailEngineException(GmailError.AUTH_ERROR, "Google rejected authentication (metadata)");
            if (retry.isEmpty()) break;
            retries += retry.size();
            backoff(slot, attempt);
            pending = new ArrayList<>(retry);
        }
        out.accept("  metadata retried ids=" + retries);
        Map<String, MailFeature> ordered = new LinkedHashMap<>();
        for (String id : ids) if (result.containsKey(id)) ordered.put(id, result.get(id));
        return ordered;
    }

    private static final Pattern DOMAIN = Pattern.compile("@([A-Za-z0-9.-]+)");

    static MailFeature toFeature(Message m) {
        String from = "", subject = "";
        if (m.getPayload() != null && m.getPayload().getHeaders() != null) {
            for (MessagePartHeader h : m.getPayload().getHeaders()) {
                if ("From".equalsIgnoreCase(h.getName())) from = h.getValue();
                else if ("Subject".equalsIgnoreCase(h.getName())) subject = h.getValue();
            }
        }
        String d = "";
        Matcher mm = DOMAIN.matcher(from == null ? "" : from);
        while (mm.find()) d = mm.group(1);
        Instant date = m.getInternalDate() == null ? null : Instant.ofEpochMilli(m.getInternalDate());
        return new MailFeature(d.toLowerCase(Locale.ROOT), date, subject == null ? "" : subject, m.getSnippet() == null ? "" : m.getSnippet(), null, List.of());
    }

    @Override
    public Map<String, GmailMessage> full(List<String> ids, Consumer<String> report) throws Exception {
        Map<String, GmailMessage> out = new ConcurrentHashMap<>();
        AtomicLong slot = new AtomicLong(System.nanoTime());
        long gap = (long) (1_000_000_000L / getsPerSecond);
        AtomicInteger retries = new AtomicInteger(), failed = new AtomicInteger(), gone = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(10);
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (String id : ids) {
                fs.add(pool.submit(() -> {
                    for (int attempt = 0; ; attempt++) {
                        pace(slot, gap);
                        try {
                            out.put(id, engine.fetchMessageDetails(svc, id));
                            return;
                        } catch (MessageGoneException e) {
                            gone.incrementAndGet();
                            return;
                        } catch (GmailEngineException e) {
                            if (e.getErrorType() == GmailError.AUTH_ERROR) throw e;
                            if (e.getErrorType() == GmailError.RATE_LIMIT && attempt < 5) {
                                retries.incrementAndGet();
                                backoff(slot, attempt);
                                continue;
                            }
                            failed.incrementAndGet();
                            return;
                        } catch (RuntimeException e) {
                            failed.incrementAndGet();
                            return;
                        }
                    }
                }));
            }
            for (Future<?> f : fs) await(f);
        } finally {
            pool.shutdownNow();
        }
        report.accept("  full retries=" + retries.get() + " failed=" + failed.get() + " gone=" + gone.get());
        return out;
    }

    @Override
    public Attachments attachments() {
        AtomicLong slot = new AtomicLong(System.nanoTime());
        long gap = (long) (1_000_000_000L / getsPerSecond);
        return (messageId, attachmentId) -> {
            byte[] bytes = null;
            for (int attempt = 0; attempt < 5 && bytes == null; attempt++) {
                pace(slot, gap);
                try {
                    bytes = svc.users().messages().attachments().get("me", messageId, attachmentId).execute().decodeData();
                } catch (GoogleJsonResponseException ex) {
                    throwIfAuth(ex);
                    if (ex.getStatusCode() == 429 || ex.getStatusCode() >= 500) backoff(slot, attempt);
                    else break;
                } catch (IOException ex) {
                    throwIfAuth(ex);
                    backoff(slot, attempt);
                }
            }
            return bytes;
        };
    }

    // ------------------------------------------------------------------------------------------------------------

    private static void throwIfAuth(IOException e) {
        GmailEngineException c = GmailEngine.classify(e);
        if (c.getErrorType() == GmailError.AUTH_ERROR) throw c;
    }

    /** Future.get with the task's own exception rethrown (an auth error stays a GmailEngineException). */
    static <T> T await(Future<T> f) throws Exception {
        try {
            return f.get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception ex) throw ex;
            if (e.getCause() instanceof Error err) throw err;
            throw e;
        }
    }

    static void pace(AtomicLong slot, long gapNanos) {
        long mine = slot.getAndAdd(gapNanos);
        long wait = mine - System.nanoTime();
        if (wait > 0) LockSupport.parkNanos(wait);
    }

    static void backoff(AtomicLong slot, int attempt) {
        long back = (1000L << attempt) + ThreadLocalRandom.current().nextLong(500);
        slot.accumulateAndGet(System.nanoTime() + back * 1_000_000L, Math::max);
        try {
            Thread.sleep(back);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
