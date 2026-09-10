package io.guestgraph.connector.apaleo.mapping;

import static io.guestgraph.connector.apaleo.mapping.ApaleoMapperTest.booking;
import static io.guestgraph.connector.apaleo.mapping.ApaleoMapperTest.reservation;
import static org.assertj.core.api.Assertions.assertThat;

import io.guestgraph.connector.apaleo.apaleo.model.Booking;
import io.guestgraph.connector.apaleo.apaleo.model.Reservation;
import io.guestgraph.connector.apaleo.testing.Recorded;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** Spec 005 task T014: data-model rule 3, the hash that decides whether a version is submitted. */
class RosterHashTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  @DisplayName("a room, rate or date change hashes equal; a person change hashes different")
  void onlyPersonsCount() {
    Map<String, Object> base = raw("reservation-three-persons.json");
    String before = RosterHash.of(new Reservation(base));

    Map<String, Object> roomChanged = new LinkedHashMap<>(base);
    roomChanged.put("unitGroup", Map.of("id", "BER-SGL", "code", "SGL"));
    roomChanged.put("arrival", "2026-08-02T15:00:00+02:00");
    roomChanged.put("modified", "2026-07-11T09:00:00Z");
    assertThat(RosterHash.of(new Reservation(roomChanged))).isEqualTo(before);

    Map<String, Object> emailChanged = copyWithPrimary(base, "email", "eva@other.example");
    assertThat(RosterHash.of(new Reservation(emailChanged))).isNotEqualTo(before);
  }

  @Test
  @DisplayName("an added or removed guest hashes different; an address-only change hashes equal")
  void rosterAndAddressChanges() {
    Map<String, Object> base = raw("reservation-three-persons.json");
    String before = RosterHash.of(new Reservation(base));

    Map<String, Object> removed = new LinkedHashMap<>(base);
    removed.put("additionalGuests", List.of(guests(base).getFirst()));
    assertThat(RosterHash.of(new Reservation(removed))).isNotEqualTo(before);

    Map<String, Object> added = new LinkedHashMap<>(base);
    List<Map<String, Object>> more = new ArrayList<>(guests(base));
    more.add(Map.of("firstName", "Hans", "lastName", "Keller"));
    added.put("additionalGuests", more);
    assertThat(RosterHash.of(new Reservation(added))).isNotEqualTo(before);

    Map<String, Object> addressOnly =
        copyWithPrimary(base, "address", Map.of("city", "Basel", "countryCode", "CH"));
    assertThat(RosterHash.of(new Reservation(addressOnly))).isEqualTo(before);
  }

  @Test
  @DisplayName("whitespace around a value does not count")
  void trimmingHashesEqual() {
    Map<String, Object> base = raw("reservation-example.json");
    Map<String, Object> padded = copyWithPrimary(base, "email", "  anna@example.com ");

    assertThat(RosterHash.of(new Reservation(padded)))
        .isEqualTo(RosterHash.of(new Reservation(base)));
  }

  @Test
  @DisplayName("on a booking the booker and the derived dates count, nothing else")
  void bookingHash() {
    Map<String, Object> base = raw("booking-distinct-booker.json");
    String before = RosterHash.of(new Booking(base));

    Map<String, Object> commentChanged = new LinkedHashMap<>(base);
    commentChanged.put("comment", "Different");
    commentChanged.put("modified", "2026-07-12T00:00:00Z");
    assertThat(RosterHash.of(new Booking(commentChanged))).isEqualTo(before);

    Map<String, Object> bookerChanged = new LinkedHashMap<>(base);
    Map<String, Object> booker = new LinkedHashMap<>((Map<String, Object>) base.get("booker"));
    booker.put("email", "daniel@other.example");
    bookerChanged.put("booker", booker);
    assertThat(RosterHash.of(new Booking(bookerChanged))).isNotEqualTo(before);

    Map<String, Object> longer = new LinkedHashMap<>(base);
    List<Map<String, Object>> reservations =
        new ArrayList<>((List<Map<String, Object>>) base.get("reservations"));
    Map<String, Object> late = new LinkedHashMap<>(reservations.getLast());
    late.put("id", "KLMNPQRS-3");
    late.put("departure", "2026-08-09T11:00:00+02:00");
    reservations.add(late);
    longer.put("reservations", reservations);
    assertThat(RosterHash.of(new Booking(longer)))
        .as("a new reservation widens the dates")
        .isNotEqualTo(before);
  }

  @Test
  @DisplayName("a reservation's hash and its booking's hash are independent")
  void hashesAreIndependent() {
    assertThat(RosterHash.of(reservation("reservation-example.json")))
        .isNotEqualTo(RosterHash.of(booking("booking-example.json")));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> raw(String name) {
    return JSON.readValue(Recorded.document(name), Map.class);
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> guests(Map<String, Object> reservation) {
    return (List<Map<String, Object>>) reservation.get("additionalGuests");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> copyWithPrimary(
      Map<String, Object> base, String key, Object value) {
    Map<String, Object> copy = new LinkedHashMap<>(base);
    Map<String, Object> primary =
        new LinkedHashMap<>((Map<String, Object>) base.get("primaryGuest"));
    primary.put(key, value);
    copy.put("primaryGuest", primary);
    return copy;
  }
}
