package io.guestgraph.connector.apaleo.apaleo.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One booking version: the booker, the clock, and its reservations' ids and dates. */
public final class Booking extends ApaleoObject {

  public static final Set<String> NEVER_TRAVELS =
      Set.of("paymentAccount", "registeredCard", "hasActivePaymentAccount");

  public static final Set<String> PERSONS = Set.of("booker");

  public Booking(Map<String, Object> raw) {
    super(raw, NEVER_TRAVELS);
  }

  public String id() {
    return text("id");
  }

  public String modified() {
    return text("modified");
  }

  public Person booker() {
    Map<String, Object> booker = map("booker");
    return booker == null ? null : new Person(booker);
  }

  /**
   * The reservations as the booking lists them, each stripped of what never travels; the mapping
   * reduces them further to what it nests.
   */
  public List<Map<String, Object>> reservations() {
    return maps("reservations").stream()
        .map(
            summary -> {
              Map<String, Object> kept = new LinkedHashMap<>(summary);
              kept.keySet().removeAll(Reservation.NEVER_TRAVELS);
              return Map.copyOf(kept);
            })
        .toList();
  }
}
