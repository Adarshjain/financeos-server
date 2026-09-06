package com.financeos.e2e;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CoverageRegistryTest {

    private CoverageRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new CoverageRegistry();
    }

    @Test
    void countersAreSplitByStatusClass() {
        registry.record("GET", "/api/v1/accounts", 200);
        registry.record("GET", "/api/v1/accounts", 301);
        registry.record("GET", "/api/v1/accounts", 404);
        registry.record("GET", "/api/v1/accounts", 500);

        List<CoverageRegistry.Snapshot> snap = registry.snapshot();
        assertEquals(1, snap.size());
        CoverageRegistry.Snapshot s = snap.get(0);
        assertEquals(2, s.ok());          // 200 + 301
        assertEquals(1, s.clientError());  // 404
        assertEquals(1, s.serverError()); // 500
    }

    @Test
    void snapshotIsSortedByPatternThenMethod() {
        registry.record("POST", "/api/v1/transactions", 200);
        registry.record("GET", "/api/v1/transactions", 200);
        registry.record("GET", "/api/v1/accounts", 200);

        List<CoverageRegistry.Snapshot> snap = registry.snapshot();
        assertEquals(3, snap.size());
        assertEquals("/api/v1/accounts", snap.get(0).pattern());
        assertEquals("GET", snap.get(0).method());
        assertEquals("/api/v1/transactions", snap.get(1).pattern());
        assertEquals("GET", snap.get(1).method());
        assertEquals("/api/v1/transactions", snap.get(2).pattern());
        assertEquals("POST", snap.get(2).method());
    }

    @Test
    void resetClearsAllData() {
        registry.record("GET", "/api/v1/accounts", 200);
        assertFalse(registry.snapshot().isEmpty());

        registry.reset();
        assertTrue(registry.snapshot().isEmpty());
    }

    @Test
    void distinctMethodsAreTrackedSeparately() {
        registry.record("GET", "/api/v1/accounts", 200);
        registry.record("POST", "/api/v1/accounts", 201);

        List<CoverageRegistry.Snapshot> snap = registry.snapshot();
        assertEquals(2, snap.size());
    }
}
