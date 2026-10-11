package com.financeos.api.account.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The credit card request reads its optional shared limit off the wire, and older bodies still parse. */
class CreditCardRequestLimitPoolJsonTest {

    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();

    private CreateAccountRequest.CreditCardRequest read(String json) throws Exception {
        return assertInstanceOf(CreateAccountRequest.CreditCardRequest.class,
                mapper.readValue(json, CreateAccountRequest.class));
    }

    @Test
    void aBodyWithoutLimitPoolIsAnUnpooledCard() throws Exception {
        CreateAccountRequest.CreditCardRequest r = read("""
                {"type":"credit_card","name":"Regalia","last4":"1234","creditLimit":500000,"anniversaryDate":"2026-01-01"}""");

        assertNull(r.limitPool());
        assertEquals(0, new BigDecimal("500000").compareTo(r.creditLimit()));
    }

    @Test
    void aNewPoolIsReadWithoutAnId() throws Exception {
        CreateAccountRequest.CreditCardRequest r = read("""
                {"type":"credit_card","name":"Regalia","last4":"1234","anniversaryDate":"2026-01-01",
                 "limitPool":{"name":"HDFC shared","creditLimit":800000}}""");

        assertNull(r.creditLimit());
        assertNull(r.limitPool().id());
        assertEquals("HDFC shared", r.limitPool().name());
        assertEquals(0, new BigDecimal("800000").compareTo(r.limitPool().creditLimit()));
    }

    @Test
    void anExistingPoolIsReadByIdAlone() throws Exception {
        UUID id = UUID.randomUUID();
        CreateAccountRequest.CreditCardRequest r = read("""
                {"type":"credit_card","name":"Millennia","last4":"5678","anniversaryDate":"2026-01-01",
                 "limitPool":{"id":"%s"}}""".formatted(id));

        assertEquals(id, r.limitPool().id());
        assertNull(r.limitPool().name());
        assertNull(r.limitPool().creditLimit());
    }

    @Test
    void anExplicitNullLimitPoolIsUnpooled() throws Exception {
        assertNull(read("""
                {"type":"credit_card","name":"Regalia","last4":"1234","creditLimit":1,"anniversaryDate":"2026-01-01","limitPool":null}""")
                .limitPool());
    }
}
