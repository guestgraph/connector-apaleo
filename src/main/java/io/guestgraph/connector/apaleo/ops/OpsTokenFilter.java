package io.guestgraph.connector.apaleo.ops;

import io.guestgraph.connector.apaleo.config.ConnectorProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Everything answers only to the bearer token from configuration (research R9), which is distinct
 * from any webhook secret, except the two paths that carry their own rule: the webhook endpoint,
 * whose secret is in its path, and health, which is for the platform. Closed by default, and the
 * two exceptions matched on the raw request path, so a matrix parameter or a percent-encoded
 * spelling of a guarded path is guarded too, whatever Spring later resolves it to.
 */
@Component
public class OpsTokenFilter extends OncePerRequestFilter {

  private final byte[] token;

  public OpsTokenFilter(ConnectorProperties properties) {
    this.token = properties.opsToken().getBytes(StandardCharsets.UTF_8);
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    return path.equals("/actuator/health") || path.startsWith("/apaleo/events/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String header = request.getHeader("Authorization");
    // The scheme is case-insensitive (RFC 9110); the token is compared in constant time.
    if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
      byte[] presented = header.substring(7).getBytes(StandardCharsets.UTF_8);
      if (MessageDigest.isEqual(token, presented)) {
        chain.doFilter(request, response);
        return;
      }
    }
    response.setStatus(HttpStatus.UNAUTHORIZED.value());
    response.setHeader("WWW-Authenticate", "Bearer");
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response
        .getWriter()
        .write("{\"type\":\"about:blank\",\"title\":\"Unauthorized\",\"status\":401}");
  }
}
