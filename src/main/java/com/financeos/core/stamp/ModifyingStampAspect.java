package com.financeos.core.stamp;

import java.util.UUID;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

/**
 * Bulk {@code @Modifying} repository queries skip the entity change listener. When one takes the owner as a
 * {@code userId} parameter, stamp that user (after the surrounding transaction commits).
 */
@Aspect
@Component
public class ModifyingStampAspect {

    private final UserChangeStamps stamps;

    public ModifyingStampAspect(UserChangeStamps stamps) {
        this.stamps = stamps;
    }

    @AfterReturning("@annotation(org.springframework.data.jpa.repository.Modifying)")
    public void stampUserParameter(JoinPoint joinPoint) {
        String[] names = ((MethodSignature) joinPoint.getSignature()).getParameterNames();
        Object[] args = joinPoint.getArgs();
        if (names == null) {
            return;
        }
        for (int i = 0; i < names.length && i < args.length; i++) {
            if ("userId".equals(names[i]) && args[i] instanceof UUID userId) {
                stamps.markChanged(userId);
            }
        }
    }
}
