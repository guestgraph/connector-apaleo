package io.guestgraph.connector.apaleo.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses a body larger than the configured cap with a problem detail before anything reads it, on
 * every path: a delivery is stored as received, and what is stored is never larger than the
 * connector meant to store. An Apaleo event is a few hundred bytes.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestSizeLimitFilter extends OncePerRequestFilter {

  private final long maxRequestBytes;

  public RequestSizeLimitFilter(
      @Value("${connector.max-request-bytes:1048576}") long maxRequestBytes) {
    this.maxRequestBytes = maxRequestBytes;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (request.getContentLengthLong() > maxRequestBytes) {
      response.setStatus(HttpStatus.CONTENT_TOO_LARGE.value());
      response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
      response
          .getWriter()
          .write(
              "{\"type\":\"about:blank\",\"title\":\"Payload Too Large\",\"status\":413,"
                  + "\"detail\":\"the body may not exceed "
                  + maxRequestBytes
                  + " bytes\"}");
      return;
    }
    chain.doFilter(request, response);
  }
}
