package com.financeos.e2e;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@Profile("e2e")
public class CoverageRegistry {

    public record Key(String method, String pattern) implements Comparable<Key> {
        @Override
        public int compareTo(Key other) {
            int cmp = this.pattern.compareTo(other.pattern);
            return cmp != 0 ? cmp : this.method.compareTo(other.method);
        }
    }

    public static class Counters {
        private final AtomicInteger ok = new AtomicInteger();
        private final AtomicInteger clientError = new AtomicInteger();
        private final AtomicInteger serverError = new AtomicInteger();

        public int getOk() { return ok.get(); }
        public int getClientError() { return clientError.get(); }
        public int getServerError() { return serverError.get(); }
    }

    public record Snapshot(String method, String pattern, int ok, int clientError, int serverError) {}

    private final ConcurrentHashMap<Key, Counters> hits = new ConcurrentHashMap<>();

    public void record(String method, String pattern, int statusCode) {
        Key key = new Key(method, pattern);
        Counters counters = hits.computeIfAbsent(key, k -> new Counters());
        if (statusCode >= 500) {
            counters.serverError.incrementAndGet();
        } else if (statusCode >= 400) {
            counters.clientError.incrementAndGet();
        } else {
            counters.ok.incrementAndGet();
        }
    }

    public List<Snapshot> snapshot() {
        List<Snapshot> list = new ArrayList<>();
        hits.forEach((key, counters) -> list.add(new Snapshot(
                key.method(), key.pattern(),
                counters.getOk(), counters.getClientError(), counters.getServerError()
        )));
        list.sort(Comparator.comparing(Snapshot::pattern).thenComparing(Snapshot::method));
        return list;
    }

    public void reset() {
        hits.clear();
    }
}
