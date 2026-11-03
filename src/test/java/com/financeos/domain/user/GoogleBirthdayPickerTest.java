package com.financeos.domain.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GoogleBirthdayPickerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private Optional<String> pick(String json) {
        return GoogleBirthdayPicker.pick(mapper, json);
    }

    @Test
    void prefersTheAccountEntryOverThePrimaryOne() {
        assertEquals(Optional.of("1990-04-05"), pick("""
                {"birthdays":[
                 {"metadata":{"primary":true,"source":{"type":"PROFILE"}},"date":{"year":1991,"month":1,"day":2}},
                 {"metadata":{"source":{"type":"ACCOUNT"}},"date":{"year":1990,"month":4,"day":5}}]}"""));
    }

    @Test
    void fallsBackToThePrimaryEntry() {
        assertEquals(Optional.of("1991-01-02"), pick("""
                {"birthdays":[
                 {"metadata":{"source":{"type":"CONTACT"}},"date":{"year":1985,"month":6,"day":7}},
                 {"metadata":{"primary":true,"source":{"type":"PROFILE"}},"date":{"year":1991,"month":1,"day":2}}]}"""));
    }

    @Test
    void aMissingYearGivesAPartialDate() {
        assertEquals(Optional.of("--03-09"), pick("""
                {"birthdays":[{"metadata":{"source":{"type":"ACCOUNT"}},"date":{"month":3,"day":9}}]}"""));
    }

    @Test
    void nothingUsableGivesEmpty() {
        assertEquals(Optional.empty(), pick("{}"));
        assertEquals(Optional.empty(), pick("{\"birthdays\":[{\"date\":{\"year\":1990}}]}"));
        assertEquals(Optional.empty(), pick("not json"));
    }
}
