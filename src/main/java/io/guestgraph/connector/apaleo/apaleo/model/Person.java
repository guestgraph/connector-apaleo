package io.guestgraph.connector.apaleo.apaleo.model;

import java.util.Map;
import java.util.Set;

/**
 * A guest or a booker: the six fields the engine extracts, and everything else kept for nesting.
 */
public final class Person extends ApaleoObject {

  /** The person fields the engine's extractor reads, under the names it reads them. */
  public static final Set<String> EXTRACTED =
      Set.of(
          "firstName",
          "lastName",
          "email",
          "phone",
          "birthDate",
          "identificationType",
          "identificationNumber");

  public Person(Map<String, Object> raw) {
    super(raw, Set.of());
  }

  public String firstName() {
    return text("firstName");
  }

  public String lastName() {
    return text("lastName");
  }

  public String email() {
    return text("email");
  }

  public String phone() {
    return text("phone");
  }

  public String birthDate() {
    return text("birthDate");
  }

  public String identificationType() {
    return text("identificationType");
  }

  public String identificationNumber() {
    return text("identificationNumber");
  }
}
