package com.financeos.core.security;

import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class HibernateFilterAspect {

    private final UserFilterEnabler userFilterEnabler;

    public HibernateFilterAspect(UserFilterEnabler userFilterEnabler) {
        this.userFilterEnabler = userFilterEnabler;
    }

    // Advice that runs before any method annotated with @Transactional
    // We assume most data access happens within a transaction.
    // Adjust pointcut if necessary to cover all Service methods.
    // Advice that runs before any method or within a class annotated with
    // @Transactional
    @Before("@within(org.springframework.transaction.annotation.Transactional) || @annotation(org.springframework.transaction.annotation.Transactional)")
    public void enableUserFilter() {
        userFilterEnabler.enable();
    }
}
