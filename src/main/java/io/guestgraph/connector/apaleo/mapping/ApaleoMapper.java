package io.guestgraph.connector.apaleo.mapping;

import io.guestgraph.connector.apaleo.apaleo.model.Booking;
import io.guestgraph.connector.apaleo.apaleo.model.Person;
import io.guestgraph.connector.apaleo.apaleo.model.Reservation;
import io.guestgraph.connector.apaleo.engine.model.IngestRecord;
import io.guestgraph.connector.apaleo.engine.model.SourceObject;
import io.guestgraph.connector.apaleo.persistence.ObjectType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One Apaleo object version to the engine's records, exactly as contracts/mapping.md states: one
 * record per person, the six extracted fields at the top of the payload, everything else nested
 * where the engine extracts nothing from it. Pure: no Spring, no state.
 */
public final class ApaleoMapper {

  /** The reservation's own fields the payload nests under {@code reservation}. */
  static final Set<String> RESERVATION_BLOCK =
      Set.of(
          "bookingId",
          "status",
          "channelCode",
          "source",
          "company",
          "externalReferences",
          "externalCode",
          "comment",
          "guestComment",
          "travelPurpose",
          "property",
          "unitGroup",
          "ratePlan",
          "adults",
          "childrenAges",
          "created",
          "modified",
          "arrival",
          "departure");

  /** The booking's own fields the payload nests under {@code booking}. */
  static final Set<String> BOOKING_BLOCK =
      Set.of("groupId", "comment", "bookerComment", "created", "modified");

  /** What a booking's nested reservation summary keeps. */
  static final Set<String> RESERVATION_SUMMARY =
      Set.of("id", "status", "property", "arrival", "departure", "channelCode");

  private final String sourceSystem;

  public ApaleoMapper(String sourceSystem) {
    this.sourceSystem = sourceSystem;
  }

  public List<IngestRecord> map(Reservation reservation) {
    List<IngestRecord> records = new ArrayList<>();
    Map<String, Object> block = pick(reservation.fields(), RESERVATION_BLOCK);
    Person primary = reservation.primaryGuest();
    if (primary != null) {
      records.add(record(reservation, primary, "primaryGuest", "PRIMARY_GUEST", null, block));
    }
    List<Person> additional = reservation.additionalGuests();
    for (int i = 0; i < additional.size(); i++) {
      records.add(
          record(
              reservation,
              additional.get(i),
              "additionalGuests[" + i + "]",
              "ADDITIONAL_GUEST",
              i,
              block));
    }
    return records;
  }

  public List<IngestRecord> map(Booking booking) {
    Person booker = booking.booker();
    if (booker == null) {
      return List.of();
    }
    Map<String, Object> block = pick(booking.fields(), BOOKING_BLOCK);
    List<Map<String, Object>> summaries = new ArrayList<>();
    for (Map<String, Object> reservation : booking.reservations()) {
      summaries.add(pick(reservation, RESERVATION_SUMMARY));
    }
    block.put("reservations", summaries);
    BookingDates dates = BookingDates.of(booking);
    Map<String, Object> payload = personPayload(booker);
    payload.put("booking", block);
    return List.of(
        new IngestRecord(
            sourceSystem,
            booking.id() + ":booker:" + booking.modified(),
            booking.modified(),
            payload,
            new SourceObject(
                ObjectType.BOOKING.code(),
                booking.id(),
                "BOOKER",
                null,
                booking.modified(),
                dates.start(),
                dates.end())));
  }

  private IngestRecord record(
      Reservation reservation,
      Person person,
      String slot,
      String role,
      Integer position,
      Map<String, Object> block) {
    Map<String, Object> payload = personPayload(person);
    payload.put("reservation", block);
    return new IngestRecord(
        sourceSystem,
        reservation.id() + ":" + slot + ":" + reservation.modified(),
        reservation.modified(),
        payload,
        new SourceObject(
            ObjectType.RESERVATION.code(),
            reservation.id(),
            role,
            position,
            reservation.modified(),
            reservation.arrival(),
            reservation.departure()));
  }

  /** The six extracted fields under the engine's names, then the rest under {@code person}. */
  private static Map<String, Object> personPayload(Person person) {
    Map<String, Object> payload = new LinkedHashMap<>();
    put(payload, "firstName", person.firstName());
    put(payload, "lastName", person.lastName());
    put(payload, "email", person.email());
    put(payload, "phone", person.phone());
    put(payload, "birthdate", person.birthDate());
    Set<String> placed = new HashSet<>(Person.EXTRACTED);
    if (person.identificationType() != null && person.identificationNumber() != null) {
      payload.put(
          "idDocument",
          Map.of("type", person.identificationType(), "number", person.identificationNumber()));
    } else {
      // Half an id document is not an identifier, but it is still a fact about the person.
      placed.remove("identificationType");
      placed.remove("identificationNumber");
    }
    payload.put("person", person.rest(placed));
    return payload;
  }

  private static void put(Map<String, Object> payload, String key, String value) {
    if (value != null) {
      payload.put(key, value);
    }
  }

  private static Map<String, Object> pick(Map<String, Object> fields, Set<String> keys) {
    Map<String, Object> picked = new LinkedHashMap<>();
    fields.forEach(
        (key, value) -> {
          if (keys.contains(key)) {
            picked.put(key, value);
          }
        });
    return picked;
  }
}
