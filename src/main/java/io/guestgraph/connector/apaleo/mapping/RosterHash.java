package io.guestgraph.connector.apaleo.mapping;

import io.guestgraph.connector.apaleo.apaleo.model.Booking;
import io.guestgraph.connector.apaleo.apaleo.model.Person;
import io.guestgraph.connector.apaleo.apaleo.model.Reservation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Data-model rule 3: the hash of an object's persons — role, position and the extracted and name
 * fields, in role order, trimmed — and on a booking its derived dates. Equal hashes mean no person
 * changed and nothing is submitted (spec FR-009). Pure.
 */
public final class RosterHash {

  private RosterHash() {}

  public static String of(Reservation reservation) {
    StringBuilder canonical = new StringBuilder();
    Person primary = reservation.primaryGuest();
    if (primary != null) {
      line(canonical, "PRIMARY_GUEST", 0, primary);
    }
    List<Person> additional = reservation.additionalGuests();
    for (int i = 0; i < additional.size(); i++) {
      line(canonical, "ADDITIONAL_GUEST", i, additional.get(i));
    }
    return sha256(canonical.toString());
  }

  public static String of(Booking booking) {
    StringBuilder canonical = new StringBuilder();
    Person booker = booking.booker();
    if (booker != null) {
      line(canonical, "BOOKER", 0, booker);
    }
    BookingDates dates = BookingDates.of(booking);
    canonical
        .append("dates|")
        .append(trim(dates.start()))
        .append('|')
        .append(trim(dates.end()))
        .append('\n');
    return sha256(canonical.toString());
  }

  private static void line(StringBuilder canonical, String role, int position, Person person) {
    canonical
        .append(role)
        .append('|')
        .append(position)
        .append('|')
        .append(trim(person.firstName()))
        .append('|')
        .append(trim(person.lastName()))
        .append('|')
        .append(trim(person.email()))
        .append('|')
        .append(trim(person.phone()))
        .append('|')
        .append(trim(person.birthDate()))
        .append('|')
        .append(trim(person.identificationType()))
        .append('|')
        .append(trim(person.identificationNumber()))
        .append('\n');
  }

  /** Length-prefixed, so a separator inside a value cannot make two rosters read as one. */
  private static String trim(String value) {
    String trimmed = value == null ? "" : value.trim();
    return trimmed.length() + ":" + trimmed;
  }

  private static String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
