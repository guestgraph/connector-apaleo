package io.guestgraph.connector.apaleo.mapping;

import io.guestgraph.connector.apaleo.apaleo.model.Booking;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * A booking has no dates of its own; the earliest arrival and the latest departure of its
 * reservations stand in (contracts/mapping.md), kept as Apaleo wrote them. Null when it lists none.
 */
public record BookingDates(String start, String end) {

  public static BookingDates of(Booking booking) {
    String start = null;
    String end = null;
    for (Map<String, Object> reservation : booking.reservations()) {
      String arrival = text(reservation.get("arrival"));
      String departure = text(reservation.get("departure"));
      if (readable(arrival) && (start == null || instant(arrival).isBefore(instant(start)))) {
        start = arrival;
      }
      if (readable(departure) && (end == null || instant(departure).isAfter(instant(end)))) {
        end = departure;
      }
    }
    return new BookingDates(start, end);
  }

  /** A date that is not one is left out of the span rather than guessed at. */
  private static boolean readable(String dateTime) {
    if (dateTime == null) {
      return false;
    }
    try {
      OffsetDateTime.parse(dateTime);
      return true;
    } catch (DateTimeParseException e) {
      return false;
    }
  }

  private static Instant instant(String dateTime) {
    return OffsetDateTime.parse(dateTime).toInstant();
  }

  private static String text(Object value) {
    return value == null ? null : String.valueOf(value);
  }
}
