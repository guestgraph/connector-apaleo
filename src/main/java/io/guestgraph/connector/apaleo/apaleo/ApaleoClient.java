package io.guestgraph.connector.apaleo.apaleo;

import io.guestgraph.connector.apaleo.apaleo.model.Booking;
import io.guestgraph.connector.apaleo.apaleo.model.Page;
import io.guestgraph.connector.apaleo.apaleo.model.Reservation;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;
import tools.jackson.databind.ObjectMapper;

/**
 * One connection's reads of Apaleo's Booking API (research R2): reservations listed by modification
 * and sorted by update, pages of 500 ending on 204, bookings always with their reservations
 * expanded, and a 429 waited out rather than given up on (spec FR-017).
 */
public class ApaleoClient {

  static final int PAGE_SIZE = 500;
  static final String ALL_STATUSES = "Confirmed,InHouse,CheckedOut,Canceled,NoShow";

  private static final ObjectMapper JSON = new ObjectMapper();

  private final RestClient api;
  private final ApaleoAuth auth;
  private final Sleeper sleeper;
  private final Duration backoffInitial;
  private final Duration backoffMax;

  public ApaleoClient(
      RestClient api,
      ApaleoAuth auth,
      Sleeper sleeper,
      Duration backoffInitial,
      Duration backoffMax) {
    this.api = api;
    this.auth = auth;
    this.sleeper = sleeper;
    this.backoffInitial = backoffInitial;
    this.backoffMax = backoffMax;
  }

  /** Reservations of the properties modified from {@code modifiedFrom} on, or all when null. */
  public Optional<Page<Reservation>> listReservations(
      List<String> propertyIds, Instant modifiedFrom, int page) {
    Function<UriBuilder, java.net.URI> uri =
        b -> {
          b.path("/booking/v1/reservations")
              .queryParam("status", ALL_STATUSES)
              .queryParam("sort", "updated:asc")
              .queryParam("pageNumber", page)
              .queryParam("pageSize", PAGE_SIZE);
          if (!propertyIds.isEmpty()) {
            b.queryParam("propertyIds", String.join(",", propertyIds));
          }
          if (modifiedFrom != null) {
            b.queryParam("dateFilter", "Modification").queryParam("from", modifiedFrom.toString());
          }
          return b.build();
        };
    return getPage(uri, "reservations", Reservation::new);
  }

  public Optional<Page<Booking>> listBookings(int page) {
    return getPage(
        b ->
            b.path("/booking/v1/bookings")
                .queryParam("expand", "reservations")
                .queryParam("pageNumber", page)
                .queryParam("pageSize", PAGE_SIZE)
                .build(),
        "bookings",
        Booking::new);
  }

  public Reservation getReservation(String id) {
    return new Reservation(getObject("/booking/v1/reservations/" + id, false));
  }

  public Booking getBooking(String id) {
    return new Booking(getObject("/booking/v1/bookings/" + id, true));
  }

  @SuppressWarnings("unchecked")
  private <T> Optional<Page<T>> getPage(
      Function<UriBuilder, java.net.URI> uri, String key, Function<Map<String, Object>, T> item) {
    ResponseEntity<String> response = exchange(spec -> spec.uri(uri));
    if (response.getStatusCode() == HttpStatus.NO_CONTENT || response.getBody() == null) {
      return Optional.empty();
    }
    Map<String, Object> body = parse(response.getBody());
    List<Map<String, Object>> raw = (List<Map<String, Object>>) body.getOrDefault(key, List.of());
    int count = ((Number) body.getOrDefault("count", raw.size())).intValue();
    return Optional.of(new Page<>(raw.stream().map(item).toList(), count));
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> getObject(String path, boolean expandReservations) {
    ResponseEntity<String> response =
        exchange(
            spec ->
                spec.uri(
                    b -> {
                      b.path(path);
                      if (expandReservations) {
                        b.queryParam("expand", "reservations");
                      }
                      return b.build();
                    }));
    if (response.getBody() == null) {
      throw new ApaleoException(path, response.getStatusCode().value());
    }
    return parse(response.getBody());
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> parse(String body) {
    return JSON.readValue(body, Map.class);
  }

  /** One authenticated GET; a 429 is waited out as Apaleo asks, or by backoff, without limit. */
  private ResponseEntity<String> exchange(
      Function<RestClient.RequestHeadersUriSpec<?>, RestClient.RequestHeadersSpec<?>> request) {
    Duration backoff = backoffInitial;
    while (true) {
      ResponseEntity<String> response =
          request
              .apply(api.get())
              .header("Authorization", "Bearer " + auth.token())
              .retrieve()
              .toEntity(String.class);
      if (response.getStatusCode() != HttpStatus.TOO_MANY_REQUESTS) {
        if (response.getStatusCode().isError()) {
          throw new ApaleoException("read", response.getStatusCode().value());
        }
        return response;
      }
      Duration wait = retryAfter(response).orElse(backoff);
      try {
        sleeper.sleep(wait);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new ApaleoException("interrupted while waiting on rate limit", 429);
      }
      backoff =
          backoff.multipliedBy(2).compareTo(backoffMax) > 0 ? backoffMax : backoff.multipliedBy(2);
    }
  }

  private static Optional<Duration> retryAfter(ResponseEntity<?> response) {
    String header = response.getHeaders().getFirst("Retry-After");
    if (header == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(Duration.ofSeconds(Long.parseLong(header.trim())));
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
  }
}
