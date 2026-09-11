package io.guestgraph.connector.apaleo.apaleo;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.guestgraph.connector.apaleo.apaleo.model.Booking;
import io.guestgraph.connector.apaleo.apaleo.model.Page;
import io.guestgraph.connector.apaleo.apaleo.model.Reservation;
import io.guestgraph.connector.apaleo.config.Http;
import io.guestgraph.connector.apaleo.testing.Recorded;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Spec 005 task T010: the client against a stub Apaleo, no Spring. */
class ApaleoClientTest {

  static final WireMockServer APALEO =
      new WireMockServer(WireMockConfiguration.options().dynamicPort());

  /** A clock the test moves, and a sleeper that records instead of waiting. */
  Instant now = Instant.parse("2026-09-10T10:00:00Z");

  final List<Duration> slept = new ArrayList<>();
  ApaleoClient client;

  @BeforeAll
  static void start() {
    APALEO.start();
  }

  @AfterAll
  static void stop() {
    APALEO.stop();
  }

  @BeforeEach
  void build() {
    APALEO.resetAll();
    slept.clear();
    APALEO.stubFor(
        post(urlPathEqualTo("/connect/token"))
            .willReturn(json("{\"access_token\":\"tok-1\",\"expires_in\":3600}")));
    Clock clock =
        new Clock() {
          @Override
          public ZoneOffset getZone() {
            return ZoneOffset.UTC;
          }

          @Override
          public Clock withZone(ZoneId zone) {
            return this;
          }

          @Override
          public Instant instant() {
            return now;
          }
        };
    ApaleoAuth auth =
        new ApaleoAuth(
            Http.client()
                .baseUrl(APALEO.baseUrl())
                .defaultStatusHandler(status -> true, (r, s) -> {})
                .build(),
            "id",
            "secret",
            clock);
    client =
        new ApaleoClient(
            Http.client()
                .baseUrl(APALEO.baseUrl())
                .defaultStatusHandler(status -> true, (r, s) -> {})
                .build(),
            auth,
            slept::add,
            Duration.ofSeconds(1),
            Duration.ofSeconds(60));
  }

  @Test
  @DisplayName("a refused token names the OAuth error code, which carries no secret")
  void refusedTokenNamesTheErrorCode() {
    APALEO.stubFor(
        post(urlPathEqualTo("/connect/token"))
            .willReturn(
                aResponse()
                    .withStatus(400)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"error\":\"invalid_scope\",\"error_description\":\"x\"}")));

    assertThatThrownBy(() -> client.getReservation("XPGMSXGF-1"))
        .isInstanceOf(ApaleoException.class)
        .hasMessage("token: Apaleo answered 400 (invalid_scope)");
  }

  @Test
  @DisplayName("the token is fetched once, reused, and refreshed a minute before expiry")
  void tokenIsCachedAndRefreshedAheadOfExpiry() {
    stubReservation();

    client.getReservation("XPGMSXGF-1");
    client.getReservation("XPGMSXGF-1");
    APALEO.verify(1, postRequestedFor(urlPathEqualTo("/connect/token")));

    now = now.plusSeconds(3600 - 61);
    client.getReservation("XPGMSXGF-1");
    APALEO.verify(1, postRequestedFor(urlPathEqualTo("/connect/token")));

    now = now.plusSeconds(31);
    client.getReservation("XPGMSXGF-1");

    APALEO.verify(2, postRequestedFor(urlPathEqualTo("/connect/token")));
    APALEO.verify(
        postRequestedFor(urlPathEqualTo("/connect/token"))
            .withHeader("Authorization", equalTo("Basic aWQ6c2VjcmV0")));
    APALEO.verify(
        getRequestedFor(urlPathEqualTo("/booking/v1/reservations/XPGMSXGF-1"))
            .withHeader("Authorization", equalTo("Bearer tok-1")));
  }

  @Test
  @DisplayName("a list is read page by page until Apaleo answers 204")
  void listEndsOnNoContent() {
    for (int page = 1; page <= 2; page++) {
      APALEO.stubFor(
          get(urlPathEqualTo("/booking/v1/reservations"))
              .withQueryParam("pageNumber", equalTo(String.valueOf(page)))
              .willReturn(json(Recorded.document("reservations-page-" + page + ".json"))));
    }
    APALEO.stubFor(
        get(urlPathEqualTo("/booking/v1/reservations"))
            .withQueryParam("pageNumber", equalTo("3"))
            .willReturn(aResponse().withStatus(204)));

    Optional<Page<Reservation>> first = client.listReservations(List.of("BER"), null, 1);
    Optional<Page<Reservation>> second = client.listReservations(List.of("BER"), null, 2);
    Optional<Page<Reservation>> third = client.listReservations(List.of("BER"), null, 3);

    assertThat(first).isPresent();
    assertThat(first.get().items())
        .extracting(Reservation::id)
        .containsExactly("XPGMSXGF-1", "KLMNPQRS-1");
    assertThat(first.get().count()).isEqualTo(4);
    assertThat(second.get().items())
        .extracting(Reservation::id)
        .containsExactly("KLMNPQRS-2", "QRTLMNOP-1");
    assertThat(third).isEmpty();
    APALEO.verify(
        getRequestedFor(urlPathEqualTo("/booking/v1/reservations"))
            .withQueryParam("sort", equalTo("updated:asc"))
            .withQueryParam("pageSize", equalTo("500"))
            .withQueryParam("propertyIds", equalTo("BER")));
  }

  @Test
  @DisplayName("a modification filter is sent only when a point is given")
  void modificationFilterIsOptional() {
    APALEO.stubFor(
        get(urlPathEqualTo("/booking/v1/reservations")).willReturn(aResponse().withStatus(204)));

    client.listReservations(List.of(), Instant.parse("2026-09-01T00:00:00Z"), 1);
    client.listReservations(List.of(), null, 1);

    APALEO.verify(
        1,
        getRequestedFor(urlPathEqualTo("/booking/v1/reservations"))
            .withQueryParam("dateFilter", equalTo("Modification"))
            .withQueryParam("from", equalTo("2026-09-01T00:00:00Z")));
    APALEO.verify(2, getRequestedFor(urlPathEqualTo("/booking/v1/reservations")));
  }

  @Test
  @DisplayName("bookings are always read with their reservations expanded")
  void bookingsAreExpanded() {
    APALEO.stubFor(
        get(urlPathEqualTo("/booking/v1/bookings/KLMNPQRS"))
            .willReturn(json(Recorded.document("booking-distinct-booker.json"))));
    APALEO.stubFor(
        get(urlPathEqualTo("/booking/v1/bookings"))
            .willReturn(json(Recorded.document("bookings-page-1.json"))));

    Booking booking = client.getBooking("KLMNPQRS");
    Optional<Page<Booking>> page = client.listBookings(1);

    assertThat(booking.booker().lastName()).isEqualTo("Weber");
    assertThat(booking.reservations()).hasSize(2);
    assertThat(booking.has("paymentAccount")).as("payment fields never enter the model").isFalse();
    assertThat(page.get().items())
        .extracting(Booking::id)
        .containsExactly("XPGMSXGF", "KLMNPQRS", "QRTLMNOP", "EMPTYBKG");
    APALEO.verify(
        getRequestedFor(urlPathEqualTo("/booking/v1/bookings/KLMNPQRS"))
            .withQueryParam("expand", equalTo("reservations")));
    APALEO.verify(
        getRequestedFor(urlPathEqualTo("/booking/v1/bookings"))
            .withQueryParam("expand", equalTo("reservations")));
  }

  @Test
  @DisplayName("a 429 with Retry-After is waited out as asked, then retried")
  void rateLimitWithRetryAfterIsHonored() {
    APALEO.stubFor(
        get(urlPathEqualTo("/booking/v1/reservations/XPGMSXGF-1"))
            .inScenario("limit")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "2"))
            .willSetStateTo("open"));
    APALEO.stubFor(
        get(urlPathEqualTo("/booking/v1/reservations/XPGMSXGF-1"))
            .inScenario("limit")
            .whenScenarioStateIs("open")
            .willReturn(json(Recorded.document("reservation-example.json"))));

    Reservation reservation = client.getReservation("XPGMSXGF-1");

    assertThat(reservation.id()).isEqualTo("XPGMSXGF-1");
    assertThat(slept).containsExactly(Duration.ofSeconds(2));
  }

  @Test
  @DisplayName("a 429 without Retry-After backs off exponentially and never gives up")
  void rateLimitWithoutHeaderBacksOff() {
    APALEO.stubFor(
        get(urlPathEqualTo("/booking/v1/reservations/XPGMSXGF-1"))
            .inScenario("limit")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse().withStatus(429))
            .willSetStateTo("second"));
    APALEO.stubFor(
        get(urlPathEqualTo("/booking/v1/reservations/XPGMSXGF-1"))
            .inScenario("limit")
            .whenScenarioStateIs("second")
            .willReturn(aResponse().withStatus(429))
            .willSetStateTo("open"));
    APALEO.stubFor(
        get(urlPathEqualTo("/booking/v1/reservations/XPGMSXGF-1"))
            .inScenario("limit")
            .whenScenarioStateIs("open")
            .willReturn(json(Recorded.document("reservation-example.json"))));

    client.getReservation("XPGMSXGF-1");

    assertThat(slept).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
  }

  @Test
  @DisplayName("the reservation model keeps every field but the ones that never travel")
  void reservationModelStripsWhatNeverTravels() {
    stubReservation();

    Reservation r = client.getReservation("XPGMSXGF-1");

    assertThat(r.modified()).isEqualTo("2026-07-09T14:30:00Z");
    assertThat(r.primaryGuest().email()).isEqualTo("anna@example.com");
    assertThat(r.additionalGuests()).hasSize(1);
    assertThat(r.propertyId()).isEqualTo("BER");
    for (String never : Reservation.NEVER_TRAVELS) {
      assertThat(r.has(never)).as(never).isFalse();
    }
    assertThat(r.has("channelCode")).isTrue();
  }

  private void stubReservation() {
    APALEO.stubFor(
        get(urlPathEqualTo("/booking/v1/reservations/XPGMSXGF-1"))
            .willReturn(json(Recorded.document("reservation-example.json"))));
  }

  private static ResponseDefinitionBuilder json(String body) {
    return aResponse()
        .withStatus(200)
        .withHeader("Content-Type", "application/json")
        .withBody(body);
  }
}
