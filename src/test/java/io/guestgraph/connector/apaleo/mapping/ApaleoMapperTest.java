package io.guestgraph.connector.apaleo.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import io.guestgraph.connector.apaleo.apaleo.model.Booking;
import io.guestgraph.connector.apaleo.apaleo.model.Reservation;
import io.guestgraph.connector.apaleo.engine.model.IngestRecord;
import io.guestgraph.connector.apaleo.testing.Recorded;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** Spec 005 task T013: contracts/mapping.md, pinned on the recorded documents. Pure JVM. */
class ApaleoMapperTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private final ApaleoMapper mapper = new ApaleoMapper("apaleo");

  @Test
  @DisplayName("a reservation yields one record per person, keyed by the convention")
  void reservationYieldsOneRecordPerPerson() {
    List<IngestRecord> records = mapper.map(reservation("reservation-three-persons.json"));

    assertThat(records)
        .extracting(IngestRecord::externalKey)
        .containsExactly(
            "KLMNPQRS-1:primaryGuest:2026-07-10T08:15:00Z",
            "KLMNPQRS-1:additionalGuests[0]:2026-07-10T08:15:00Z",
            "KLMNPQRS-1:additionalGuests[1]:2026-07-10T08:15:00Z");
    assertThat(records)
        .extracting(r -> r.sourceObject().role())
        .containsExactly("PRIMARY_GUEST", "ADDITIONAL_GUEST", "ADDITIONAL_GUEST");
    assertThat(records).extracting(r -> r.sourceObject().position()).containsExactly(null, 0, 1);
    for (IngestRecord r : records) {
      assertThat(r.sourceSystem()).isEqualTo("apaleo");
      assertThat(r.recordTimestamp()).isEqualTo("2026-07-10T08:15:00Z");
      assertThat(r.sourceObject().type()).isEqualTo("reservation");
      assertThat(r.sourceObject().id()).isEqualTo("KLMNPQRS-1");
      assertThat(r.sourceObject().version()).isEqualTo("2026-07-10T08:15:00Z");
      assertThat(r.sourceObject().businessStart()).isEqualTo("2026-08-01T15:00:00+02:00");
      assertThat(r.sourceObject().businessEnd()).isEqualTo("2026-08-04T11:00:00+02:00");
    }
  }

  @Test
  @DisplayName("the six extracted fields sit at the top of the payload and nothing else does")
  void extractedFieldsAtTheTopEverythingElseNested() {
    IngestRecord primary = mapper.map(reservation("reservation-three-persons.json")).getFirst();
    Map<String, Object> payload = primary.payload();

    assertThat(payload)
        .containsEntry("firstName", "Eva")
        .containsEntry("lastName", "Keller")
        .containsEntry("email", "eva.keller@example.com")
        .containsEntry("phone", "+41441234567")
        .containsEntry("birthdate", "1979-06-21")
        .containsEntry("idDocument", Map.of("type", "PassportNumber", "number", "Y7654321"));
    assertThat(payload.keySet())
        .containsExactlyInAnyOrder(
            "firstName",
            "lastName",
            "email",
            "phone",
            "birthdate",
            "idDocument",
            "person",
            "reservation");
    Map<String, Object> person = map(payload.get("person"));
    assertThat(person)
        .containsKeys(
            "title", "gender", "address", "nationalityCountryCode", "company", "preferredLanguage")
        .doesNotContainKeys("firstName", "email", "identificationNumber");
    Map<String, Object> reservation = map(payload.get("reservation"));
    assertThat(reservation)
        .containsEntry("bookingId", "KLMNPQRS")
        .containsEntry("status", "Confirmed")
        .containsEntry("channelCode", "ChannelManager")
        .containsKeys(
            "property", "company", "externalReferences", "arrival", "departure", "modified")
        .doesNotContainKeys("primaryGuest", "additionalGuests", "booker");
    assertThat(payload).doesNotContainKeys("loyaltyId", "externalGuestId");
  }

  @Test
  @DisplayName("an id document is sent only when both type and number are present")
  void idDocumentNeedsBothParts() {
    List<IngestRecord> records = mapper.map(reservation("reservation-three-persons.json"));
    Map<String, Object> greta = records.get(2).payload();

    assertThat(greta).containsEntry("email", "greta@example.com").doesNotContainKey("idDocument");
    assertThat(map(greta.get("person"))).containsEntry("identificationType", "IdNumber");
  }

  @Test
  @DisplayName("what never travels is in no record, top level or nested")
  void nothingThatNeverTravelsTravels() {
    List<IngestRecord> reservationRecords =
        mapper.map(reservation("reservation-three-persons.json"));
    List<IngestRecord> bookingRecords = mapper.map(booking("booking-distinct-booker.json"));

    for (IngestRecord r : reservationRecords) {
      assertThat(JSON.writeValueAsString(r.payload()))
          .doesNotContain(
              "paymentAccount",
              "registeredCard",
              "hasActivePaymentAccount",
              "timeSlices",
              "services",
              "cardNumber");
    }
    for (IngestRecord r : bookingRecords) {
      assertThat(JSON.writeValueAsString(r.payload()))
          .doesNotContain(
              "paymentAccount", "registeredCard", "hasActivePaymentAccount", "cardNumber");
    }
  }

  @Test
  @DisplayName(
      "a booking yields its booker on the booking object with dates spanning its reservations")
  void bookingYieldsTheBooker() {
    List<IngestRecord> records = mapper.map(booking("booking-distinct-booker.json"));

    assertThat(records).hasSize(1);
    IngestRecord booker = records.getFirst();
    assertThat(booker.externalKey()).isEqualTo("KLMNPQRS:booker:2026-07-10T08:15:00Z");
    assertThat(booker.sourceObject().type()).isEqualTo("booking");
    assertThat(booker.sourceObject().role()).isEqualTo("BOOKER");
    assertThat(booker.sourceObject().position()).isNull();
    assertThat(booker.sourceObject().businessStart()).isEqualTo("2026-08-01T15:00:00+02:00");
    assertThat(booker.sourceObject().businessEnd()).isEqualTo("2026-08-06T11:00:00+02:00");
    assertThat(booker.payload())
        .containsEntry("firstName", "Daniel")
        .containsEntry("lastName", "Weber")
        .containsEntry("email", "daniel.weber@travel-agency.example");
    Map<String, Object> bookingBlock = map(booker.payload().get("booking"));
    assertThat(bookingBlock)
        .containsEntry("comment", "Agency booking")
        .containsKeys("created", "modified", "reservations")
        .doesNotContainKey("booker");
    List<?> summaries = (List<?>) bookingBlock.get("reservations");
    assertThat(summaries).hasSize(2);
    assertThat(map(summaries.getFirst()).keySet())
        .containsExactlyInAnyOrder(
            "id", "status", "property", "arrival", "departure", "channelCode");
  }

  @Test
  @DisplayName("a booker who is the primary guest is sent as a person like any other")
  void bookerSameAsPrimaryIsStillSent() {
    IngestRecord booker = mapper.map(booking("booking-example.json")).getFirst();
    IngestRecord primary = mapper.map(reservation("reservation-example.json")).getFirst();

    assertThat(booker.payload().get("email")).isEqualTo(primary.payload().get("email"));
    assertThat(booker.externalKey()).isEqualTo("XPGMSXGF:booker:2026-07-09T14:30:00Z");
  }

  @Test
  @DisplayName(
      "an empty additional-guest list yields the primary guest alone; no reservations, no dates")
  void emptyListsAreHandled() {
    assertThat(mapper.map(reservation("reservation-second.json"))).hasSize(1);
    IngestRecord empty = mapper.map(booking("booking-no-reservations.json")).getFirst();
    assertThat(empty.sourceObject().businessStart()).isNull();
    assertThat(empty.sourceObject().businessEnd()).isNull();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  static Reservation reservation(String name) {
    return new Reservation(JSON.readValue(Recorded.document(name), Map.class));
  }

  @SuppressWarnings("unchecked")
  static Booking booking(String name) {
    return new Booking(JSON.readValue(Recorded.document(name), Map.class));
  }
}
