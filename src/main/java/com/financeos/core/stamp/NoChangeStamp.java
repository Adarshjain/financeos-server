package com.financeos.core.stamp;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an entity field that changes constantly without changing anything a user sees (progress ticks, "last used"
 * times). An update that touches only such fields does not move the user's change stamp.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface NoChangeStamp {
}
