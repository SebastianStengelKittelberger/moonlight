package de.kittelberger.moonshine.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.kittelberger.moonshine.model.RouteConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Persists URL routing tables per country/language in Elasticsearch index {@code moonlight-routes}.
 *
 * Each document stores the complete list of {@link RouteConfig} entries for one country/language
 * combination and is keyed by {@code {country}-{language}}.
 */
@Service
public class RoutingStorageService {

  private static final Logger log = LoggerFactory.getLogger(RoutingStorageService.class);
  private static final String ROUTES_INDEX = "moonlight-routes";

  private final RestClient esClient;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public RoutingStorageService(
    @Value("${elasticsearch.host:localhost}") String host,
    @Value("${elasticsearch.port:9200}") int port) {
    this.esClient = RestClient.builder()
      .baseUrl("http://" + host + ":" + port)
      .build();
  }

  public List<RouteConfig> loadRoutes(String country, String language) {
    String docId = country.toLowerCase() + "-" + language.toLowerCase();
    try {
      String json = esClient.get()
        .uri("/" + ROUTES_INDEX + "/_doc/" + docId)
        .accept(MediaType.APPLICATION_JSON)
        .retrieve().body(String.class);

      JsonNode node = objectMapper.readTree(json);
      if (node.path("found").booleanValue()) {
        JsonNode routesNode = node.path("_source").path("routes");
        if (!routesNode.isMissingNode()) {
          return objectMapper.treeToValue(routesNode, new TypeReference<List<RouteConfig>>() {});
        }
      }
    } catch (RestClientException e) {
      if (e.getMessage() != null && (e.getMessage().contains("404") || e.getMessage().contains("index_not_found"))) {
        log.info("No routes found for {}/{}", country, language);
      } else {
        log.warn("Failed to load routes for {}/{}", country, language, e);
      }
    } catch (IOException e) {
      log.warn("Failed to parse routes for {}/{}", country, language, e);
    }
    return new ArrayList<>();
  }

  public void saveRoutes(String country, String language, List<RouteConfig> routes) {
    String docId = country.toLowerCase() + "-" + language.toLowerCase();
    Map<String, Object> doc = Map.of(
      "country", country,
      "language", language,
      "timestamp", Instant.now().toString(),
      "routes", routes
    );
    try {
      esClient.put()
        .uri("/" + ROUTES_INDEX + "/_doc/" + docId)
        .accept(MediaType.APPLICATION_JSON)
        .contentType(MediaType.APPLICATION_JSON)
        .body(objectMapper.writeValueAsString(doc))
        .retrieve().body(String.class);
      log.info("Saved {} routes for {}/{}", routes.size(), country, language);
    } catch (IOException e) {
      throw new RuntimeException("Failed to serialize routes", e);
    }
  }

  /**
   * Finds the best matching route for a given path.
   * Exact match wins over pattern match ({url} segments treated as wildcards).
   */
  public RouteConfig findRoute(String country, String language, String path) {
    List<RouteConfig> routes = loadRoutes(country, language);
    // First pass: exact match
    for (RouteConfig route : routes) {
      if (normalise(route.getUrl()).equals(normalise(path))) {
        return route;
      }
    }
    // Second pass: simple {placeholder} wildcard match
    for (RouteConfig route : routes) {
      if (matchesPattern(normalise(route.getUrl()), normalise(path))) {
        return route;
      }
    }
    return null;
  }

  private String normalise(String url) {
    if (url == null) return "/";
    return url.startsWith("/") ? url : "/" + url;
  }

  /** Matches a route pattern like /products/{sku} against a concrete path like /products/ABC123 */
  private boolean matchesPattern(String pattern, String path) {
    String[] patternParts = pattern.split("/");
    String[] pathParts = path.split("/");
    if (patternParts.length != pathParts.length) return false;
    for (int i = 0; i < patternParts.length; i++) {
      if (patternParts[i].startsWith("{") && patternParts[i].endsWith("}")) continue;
      if (!patternParts[i].equals(pathParts[i])) return false;
    }
    return true;
  }

  /**
   * Extracts path variable values from a matched pattern.
   * E.g. pattern="/products/{sku}", path="/products/ABC123" → {"sku": "ABC123"}
   */
  public Map<String, String> extractPathVariables(String pattern, String path) {
    Map<String, String> vars = new java.util.LinkedHashMap<>();
    String[] patternParts = normalise(pattern).split("/");
    String[] pathParts = normalise(path).split("/");
    for (int i = 0; i < Math.min(patternParts.length, pathParts.length); i++) {
      if (patternParts[i].startsWith("{") && patternParts[i].endsWith("}")) {
        String varName = patternParts[i].substring(1, patternParts[i].length() - 1);
        vars.put(varName, pathParts[i]);
      }
    }
    return vars;
  }
}
