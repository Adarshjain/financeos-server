package com.financeos.api.account.dto;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Contract: the ingest watermark and last-statement date are bank/credit-card fields only.
 * Broker and generic DTOs must not expose them on the wire (requests ignore them, responses omit
 * them). The E2E shape checks in financeos-client/e2e/api/accounts.spec.ts mirror this.
 */
class AccountDtoIngestFieldsTest {

    private static Set<String> components(Class<? extends Record> record) {
        return Arrays.stream(record.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toSet());
    }

    @Test
    void bankAndCardResponses_carryIngestAndStatementDates() {
        for (Class<? extends Record> r : Set.of(
                AccountResponse.BankAccountResponse.class,
                AccountResponse.CreditCardAccountResponse.class)) {
            Set<String> names = components(r);
            assertTrue(names.contains("ingestFromDate"), r.getSimpleName());
            assertTrue(names.contains("lastStatementDate"), r.getSimpleName());
        }
    }

    @Test
    void brokerAndGenericResponses_omitIngestAndStatementDates() {
        for (Class<? extends Record> r : Set.of(
                AccountResponse.BrokerAccountResponse.class,
                AccountResponse.GenericAccountResponse.class)) {
            Set<String> names = components(r);
            assertFalse(names.contains("ingestFromDate"), r.getSimpleName());
            assertFalse(names.contains("lastStatementDate"), r.getSimpleName());
        }
    }

    @Test
    void brokerAndGenericRequests_haveNoIngestFromDateComponent_onCreateAndUpdate() {
        for (Class<? extends Record> r : Set.of(
                CreateAccountRequest.BrokerRequest.class,
                CreateAccountRequest.GenericAccountRequest.class,
                UpdateAccountRequest.BrokerRequest.class,
                UpdateAccountRequest.GenericAccountRequest.class)) {
            assertFalse(components(r).contains("ingestFromDate"), r.getSimpleName());
        }
    }

    @Test
    void bankAndCardRequests_keepIngestFromDateComponent_onCreateAndUpdate() {
        for (Class<? extends Record> r : Set.of(
                CreateAccountRequest.BankAccountRequest.class,
                CreateAccountRequest.CreditCardRequest.class,
                UpdateAccountRequest.BankAccountRequest.class,
                UpdateAccountRequest.CreditCardRequest.class)) {
            assertTrue(components(r).contains("ingestFromDate"), r.getSimpleName());
        }
    }
}
