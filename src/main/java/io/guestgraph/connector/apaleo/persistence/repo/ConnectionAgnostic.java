package io.guestgraph.connector.apaleo.persistence.repo;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The explicit allowlist for the ArchUnit connection-scoping rule: every repository method must
 * take a {@code connectionId} parameter unless it carries this annotation with a justification. One
 * instance serves many connections, and a query without one reads across them.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ConnectionAgnostic {

  /** Why this query may run without a connection predicate. */
  String value();
}
