package com.financeos.e2e;

import com.financeos.llm.FailoverLlmClient;
import com.financeos.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class E2eProfileAbsentTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void noE2eBeansExist() {
        assertThrows(NoSuchBeanDefinitionException.class, () -> context.getBean(ScriptedLlmClient.class));
        assertThrows(NoSuchBeanDefinitionException.class, () -> context.getBean(E2eControlController.class));
        assertThrows(NoSuchBeanDefinitionException.class, () -> context.getBean(E2eCoverageFilter.class));
        assertThrows(NoSuchBeanDefinitionException.class, () -> context.getBean(CoverageRegistry.class));
        assertThrows(NoSuchBeanDefinitionException.class, () -> context.getBean(E2eSecurityConfiguration.class));
    }

    @Test
    void llmClientIsFailoverLlmClient() {
        LlmClient client = context.getBean(LlmClient.class);
        assertInstanceOf(FailoverLlmClient.class, client);
    }
}
