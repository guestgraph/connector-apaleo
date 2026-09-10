package io.guestgraph.connector.apaleo.apaleo.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An Apaleo document as received, minus the fields that must never travel. Typed accessors read
 * what the mapping needs; {@link #rest(Set)} yields everything else, so a field the mapping does
 * not name is nested verbatim rather than dropped (contracts/mapping.md).
 */
public abstract class ApaleoObject {

  private final Map<String, Object> fields;

  protected ApaleoObject(Map<String, Object> raw, Set<String> neverTravels) {
    Map<String, Object> kept = new LinkedHashMap<>(raw);
    kept.keySet().removeAll(neverTravels);
    this.fields = Map.copyOf(kept);
  }

  public String text(String key) {
    Object value = fields.get(key);
    return value == null ? null : String.valueOf(value);
  }

  public boolean has(String key) {
    return fields.containsKey(key);
  }

  public Object field(String key) {
    return fields.get(key);
  }

  @SuppressWarnings("unchecked")
  protected Map<String, Object> map(String key) {
    return fields.get(key) instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
  }

  @SuppressWarnings("unchecked")
  protected List<Map<String, Object>> maps(String key) {
    return fields.get(key) instanceof List<?> list
        ? list.stream().map(item -> (Map<String, Object>) item).toList()
        : List.of();
  }

  /** Every field except the named ones, in document order. */
  public Map<String, Object> rest(Set<String> except) {
    Map<String, Object> rest = new LinkedHashMap<>(fields);
    rest.keySet().removeAll(except);
    return rest;
  }

  public Map<String, Object> fields() {
    return fields;
  }
}
