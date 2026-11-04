package com.financeos.api.gmail;

import com.financeos.api.gmail.dto.BankDomainsResponse;
import com.financeos.gmail.classify.BankSenderDomains;
import com.financeos.gmail.ingest.SenderAllowlistService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GmailControllerBankDomainsTest {

    @Test
    void bankDomainsEndpointReturnsTheServiceList() {
        SenderAllowlistService service = mock(SenderAllowlistService.class);
        List<BankDomainsResponse> list = List.of(new BankDomainsResponse("kotak", List.of("kotak.com", "kotak.bank.in")));
        when(service.bankDomains()).thenReturn(list);
        GmailController controller = new GmailController(null, null, null, service, null, null, null, null, null, null, null, null, null);

        assertThat(controller.getBankDomains().getBody()).isEqualTo(list);
    }

    @Test
    void realServiceListsKotakFirstClass() {
        SenderAllowlistService service = new SenderAllowlistService(null, null, null, null, null, BankSenderDomains.defaults());
        assertThat(service.bankDomains()).filteredOn(b -> b.bankId().equals("kotak")).singleElement()
                .satisfies(b -> assertThat(b.domains()).containsExactly("kotak.com", "kotak.bank.in"));
    }
}
