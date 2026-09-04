package com.financeos.domain.instrument.price;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class YahooHostsTest {

    @Test
    void testGetCandidateHostsWithDefaultFallback() {
        PriceProperties.ProviderProperties props = new PriceProperties.ProviderProperties();
        assertEquals("https://query2.finance.yahoo.com", props.getBaseUrl());
        assertEquals("https://fc.yahoo.com", props.getCrumbBaseUrl());
        assertEquals("https://finance.yahoo.com", props.getCrumbFallbackUrl());
        assertTrue(props.isFallbackEnabled());

        List<String> hosts = YahooHosts.getCandidateHosts(props);
        assertEquals(2, hosts.size());
        assertEquals("https://query2.finance.yahoo.com", hosts.get(0));
        assertEquals("https://query1.finance.yahoo.com", hosts.get(1));
    }

    @Test
    void testGetCandidateHostsWithFallbackDisabled() {
        PriceProperties.ProviderProperties props = new PriceProperties.ProviderProperties();
        props.setBaseUrl("http://localhost:8089/yahoo");
        props.setFallbackEnabled(false);

        List<String> hosts = YahooHosts.getCandidateHosts(props);
        assertEquals(1, hosts.size());
        assertEquals("http://localhost:8089/yahoo", hosts.get(0));
    }

    @Test
    void testGetCandidateHostsWithQuery1Primary() {
        PriceProperties.ProviderProperties props = new PriceProperties.ProviderProperties();
        props.setBaseUrl("https://query1.finance.yahoo.com");
        props.setFallbackEnabled(true);

        List<String> hosts = YahooHosts.getCandidateHosts(props);
        assertEquals(2, hosts.size());
        assertEquals("https://query1.finance.yahoo.com", hosts.get(0));
        assertEquals("https://query2.finance.yahoo.com", hosts.get(1));
    }

    @Test
    void testGetCandidateHostsNullProps() {
        List<String> hosts = YahooHosts.getCandidateHosts(null);
        assertEquals(2, hosts.size());
        assertEquals("https://query2.finance.yahoo.com", hosts.get(0));
        assertEquals("https://query1.finance.yahoo.com", hosts.get(1));
    }
}
