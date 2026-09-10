package io.guestgraph.connector.apaleo.apaleo.model;

import java.util.List;

/** One page of a list; Apaleo answers the page after the last with 204, which is no page. */
public record Page<T>(List<T> items, int count) {}
