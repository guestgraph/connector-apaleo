package io.guestgraph.connector.apaleo.apaleo.model;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** One reservation version: the guests, the dates, the clock, and the booking it belongs to. */
public final class Reservation extends ApaleoObject {

  /** Never anywhere in an observation (contracts/mapping.md). */
  public static final Set<String> NEVER_TRAVELS =
      Set.of(
          "paymentAccount", "registeredCard", "hasActivePaymentAccount", "timeSlices", "services");

  /** The person entries, which the mapping places, not nests; and the booker copy, unused. */
  public static final Set<String> PERSONS = Set.of("primaryGuest", "additionalGuests", "booker");

  public Reservation(Map<String, Object> raw) {
    super(raw, NEVER_TRAVELS);
  }

  public String id() {
    return text("id");
  }

  public String bookingId() {
    return text("bookingId");
  }

  public String status() {
    return text("status");
  }

  public String modified() {
    return text("modified");
  }

  public String arrival() {
    return text("arrival");
  }

  public String departure() {
    return text("departure");
  }

  public String propertyId() {
    Map<String, Object> property = map("property");
    return property == null ? null : String.valueOf(property.get("id"));
  }

  public Person primaryGuest() {
    Map<String, Object> guest = map("primaryGuest");
    return guest == null ? null : new Person(guest);
  }

  public List<Person> additionalGuests() {
    return maps("additionalGuests").stream().map(Person::new).toList();
  }
}
