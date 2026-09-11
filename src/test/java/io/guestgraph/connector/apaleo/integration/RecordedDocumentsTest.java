package io.guestgraph.connector.apaleo.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.guestgraph.connector.apaleo.testing.Recorded;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The recorded documents are one truth: every list page carries exactly the single documents it
 * pages, and everything parses. Pure JVM — no Spring, no container — so the mapper tests of T013
 * inherit a checked premise.
 */
class RecordedDocumentsTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final List<String> SINGLE_RESERVATIONS =
      List.of(
          "reservation-example.json",
          "reservation-three-persons.json",
          "reservation-second.json",
          "reservation-canceled-single.json");
  private static final List<String> SINGLE_BOOKINGS =
      List.of(
          "booking-example.json",
          "booking-distinct-booker.json",
          "booking-canceled-single.json",
          "booking-no-reservations.json");

  @Test
  @DisplayName("every recorded document parses, and the reachability check is a system message")
  void everyDocumentParses() {
    for (String name :
        List.of(
            "reservations-page-1.json",
            "reservations-page-2.json",
            "bookings-page-1.json",
            "event-reservation-changed.json",
            "event-booking-changed.json",
            "token.json")) {
      assertThat(MAPPER.readTree(Recorded.document(name)).isObject()).as(name).isTrue();
    }
    SINGLE_RESERVATIONS.forEach(n -> assertThat(read(n).get("id").asString()).isNotBlank());
    SINGLE_BOOKINGS.forEach(n -> assertThat(read(n).get("id").asString()).isNotBlank());
    // As Apaleo sent it to the sandbox walk (research R11 item 4): no entity, a topic of its own.
    assertThat(read("event-reachability.json").get("topic").asString()).isEqualTo("system");
    assertThat(read("event-reachability.json").has("data")).isFalse();
  }

  @Test
  @DisplayName("the reservation pages are exactly the single reservation documents, in order")
  void reservationPagesEqualTheSingleDocuments() {
    List<JsonNode> paged =
        List.of("reservations-page-1.json", "reservations-page-2.json").stream()
            .flatMap(n -> stream(read(n).get("reservations")))
            .toList();

    assertThat(paged).hasSize(SINGLE_RESERVATIONS.size());
    for (int i = 0; i < paged.size(); i++) {
      assertThat(paged.get(i))
          .as(SINGLE_RESERVATIONS.get(i))
          .isEqualTo(read(SINGLE_RESERVATIONS.get(i)));
    }
    assertThat(read("reservations-page-1.json").get("count").asInt()).isEqualTo(paged.size());
  }

  @Test
  @DisplayName("the booking page is exactly the single booking documents, in order")
  void bookingPageEqualsTheSingleDocuments() {
    List<JsonNode> paged = stream(read("bookings-page-1.json").get("bookings")).toList();

    assertThat(paged).hasSize(SINGLE_BOOKINGS.size());
    for (int i = 0; i < paged.size(); i++) {
      assertThat(paged.get(i)).as(SINGLE_BOOKINGS.get(i)).isEqualTo(read(SINGLE_BOOKINGS.get(i)));
    }
  }

  @Test
  @DisplayName("the documents carry what the mapping tests need to prove")
  void documentsCarryWhatTheMapperTestsNeed() {
    JsonNode three = read("reservation-three-persons.json");
    assertThat(three.get("additionalGuests")).hasSize(2);
    assertThat(three.get("additionalGuests").get(1).has("identificationType")).isTrue();
    assertThat(three.get("additionalGuests").get(1).has("identificationNumber")).isFalse();
    for (String never : List.of("paymentAccount", "registeredCard", "timeSlices", "services")) {
      assertThat(three.has(never)).as(never + " present so its absence can be proven").isTrue();
    }
    assertThat(read("reservation-second.json").get("additionalGuests")).isEmpty();
    assertThat(read("booking-no-reservations.json").get("reservations")).isEmpty();
    JsonNode example = read("booking-example.json");
    assertThat(example.get("booker").get("email").asString())
        .isEqualTo(read("reservation-example.json").get("primaryGuest").get("email").asString());
    assertThat(read("booking-distinct-booker.json").get("booker").get("lastName").asString())
        .isEqualTo("Weber");
  }

  private static JsonNode read(String name) {
    return MAPPER.readTree(Recorded.document(name));
  }

  private static Stream<JsonNode> stream(JsonNode array) {
    List<JsonNode> items = new ArrayList<>();
    array.forEach(items::add);
    return items.stream();
  }
}
