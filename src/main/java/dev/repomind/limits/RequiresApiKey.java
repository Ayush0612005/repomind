package dev.repomind.limits;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an endpoint that anonymous callers may not use when limits are enabled: it consumes
 * shared resources (cloning and indexing repositories) or acts on GitHub on the service's behalf.
 * Read-only endpoints stay open to the anonymous policy so a public demo can be tried without a key.
 */
@Target({ ElementType.METHOD, ElementType.TYPE })
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresApiKey {
}
