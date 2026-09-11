package io.guestgraph.connector.apaleo.api;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.yaml.snakeyaml.Yaml;

/**
 * Serves the connector's contract at GET /api-docs: the union of the documents bundled from api/ at
 * build time, which the engine repository owns and the service check holds against it. Outside the
 * ops token, since the document carries no data.
 */
@RestController
public class ApiDocsController {

  private final Map<String, Object> document;

  public ApiDocsController() {
    this.document = load();
  }

  @GetMapping(value = "/api-docs", produces = "application/json")
  public Map<String, Object> apiDocs() {
    return document;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> load() {
    Map<String, Object> merged = new LinkedHashMap<>();
    Map<String, Object> paths = new LinkedHashMap<>();
    try {
      Resource[] resources =
          new PathMatchingResourcePatternResolver().getResources("classpath:api/*.yaml");
      Yaml yaml = new Yaml();
      for (Resource resource : resources) {
        try (InputStream in = resource.getInputStream()) {
          Map<String, Object> contract = yaml.load(in);
          if (merged.isEmpty()) {
            merged.putAll(contract);
          }
          if (contract.get("paths") instanceof Map<?, ?> m) {
            paths.putAll((Map<String, Object>) m);
          }
        }
      }
    } catch (IOException e) {
      throw new IllegalStateException("Cannot load the bundled contracts", e);
    }
    merged.put("paths", paths);
    return merged;
  }
}
