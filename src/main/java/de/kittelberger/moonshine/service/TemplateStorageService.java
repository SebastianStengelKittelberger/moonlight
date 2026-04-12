package de.kittelberger.moonshine.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.kittelberger.moonshine.model.TemplateProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Persists template data in Elasticsearch across three indices:
 *
 * <ul>
 *   <li>{@code moonlight-vorlagen}  – global slot-HTML Vorlagen (one doc per vorlage name)</li>
 *   <li>{@code moonlight-pages}     – pages per country/language (mapConfig + slots)</li>
 *   <li>{@code moonlight-labels}    – global labels per country/language</li>
 * </ul>
 */
@Service
public class TemplateStorageService {

  private static final Logger log = LoggerFactory.getLogger(TemplateStorageService.class);

  // ── Indices ────────────────────────────────────────────────────────────────
  private static final String PAGES_INDEX           = "moonlight-pages";
  private static final String VORLAGEN_INDEX        = "moonlight-vorlagen";
  private static final String VORLAGEN_HISTORY_INDEX = "moonlight-vorlagen-history";
  private static final String LABELS_INDEX          = "moonlight-labels";

  private final RestClient esClient;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public TemplateStorageService(
    @Value("${elasticsearch.host:localhost}") String host,
    @Value("${elasticsearch.port:9200}") int port
  ) {
    this.esClient = RestClient.builder()
      .baseUrl("http://" + host + ":" + port)
      .build();
  }

  // ── Seiten (Pages) ────────────────────────────────────────────────────────

  public List<String> listPageNames(String country, String language) {
    Map<String, Object> query = Map.of(
      "query", Map.of("bool", Map.of("filter", List.of(
        Map.of("term", Map.of("country.keyword", country)),
        Map.of("term", Map.of("language.keyword", language))
      ))),
      "_source", List.of("pageName"),
      "size", 100
    );
    try {
      String json = esClient.post()
        .uri("/" + PAGES_INDEX + "/_search")
        .accept(MediaType.APPLICATION_JSON)
        .contentType(MediaType.APPLICATION_JSON)
        .body(objectMapper.writeValueAsString(query))
        .retrieve().body(String.class);

      JsonNode hits = objectMapper.readTree(json).path("hits").path("hits");
      if (hits.isArray() && !hits.isEmpty()) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (JsonNode hit : hits) {
          String name = hit.path("_source").path("pageName").textValue();
          if (name != null && !name.isBlank()) names.add(name);
        }
        if (!names.isEmpty()) return new ArrayList<>(names);
      }
    } catch (RestClientException e) {
      if (e.getMessage() == null || !e.getMessage().contains("index_not_found")) {
        log.warn("Failed to list pages from ES", e);
      }
    } catch (IOException e) {
      log.warn("Failed to parse page list from ES", e);
    }
    return new ArrayList<>(List.of("produktseite"));
  }

  @Cacheable(value = "pages", key = "#country + '-' + #language + '-' + #pageName")
  public TemplateProperties loadPage(String country, String language, String pageName) {
    String docId = country.toLowerCase() + "-" + language.toLowerCase() + "-" + pageName;
    try {
      String json = esClient.get()
        .uri("/" + PAGES_INDEX + "/_doc/" + docId)
        .accept(MediaType.APPLICATION_JSON)
        .retrieve().body(String.class);

      JsonNode node = objectMapper.readTree(json);
      if (node.path("found").booleanValue()) {
        TemplateProperties props = objectMapper.treeToValue(
          node.path("_source").path("config"), TemplateProperties.class);
        props.setName(pageName);
        return props;
      }
    } catch (RestClientException e) {
      if (e.getMessage() != null && (e.getMessage().contains("404") || e.getMessage().contains("index_not_found"))) {
        log.info("Page '{}' not found for {}/{}", pageName, country, language);
      } else {
        log.warn("Failed to load page '{}' from ES", pageName, e);
      }
    } catch (IOException e) {
      log.warn("Failed to parse page '{}' from ES", pageName, e);
    }
    return TemplateProperties.builder()
      .name(pageName)
      .slots(new ArrayList<>())
      .build();
  }

  @CacheEvict(value = "pages", key = "#country + '-' + #language + '-' + #pageName")
  public void savePage(String country, String language, String pageName, TemplateProperties config) {
    config.setName(pageName);
    String docId = country.toLowerCase() + "-" + language.toLowerCase() + "-" + pageName;
    Map<String, Object> doc = Map.of(
      "country", country,
      "language", language,
      "pageName", pageName,
      "timestamp", Instant.now().toString(),
      "config", config
    );
    try {
      esClient.put()
        .uri("/" + PAGES_INDEX + "/_doc/" + docId)
        .accept(MediaType.APPLICATION_JSON)
        .contentType(MediaType.APPLICATION_JSON)
        .body(objectMapper.writeValueAsString(doc))
        .retrieve().body(String.class);
      log.info("Saved page '{}' to ES for {}/{}", pageName, country, language);
    } catch (IOException e) {
      throw new RuntimeException("Failed to serialize page config", e);
    }
  }

  // ── Vorlagen (global slot HTML) ───────────────────────────────────────────

  public List<String> listVorlagen() {
    Map<String, Object> query = Map.of("query", Map.of("match_all", Map.of()), "_source", List.of("vorlage"), "size", 100);
    try {
      String json = esClient.post()
        .uri("/" + VORLAGEN_INDEX + "/_search")
        .accept(MediaType.APPLICATION_JSON)
        .contentType(MediaType.APPLICATION_JSON)
        .body(objectMapper.writeValueAsString(query))
        .retrieve().body(String.class);

      JsonNode hits = objectMapper.readTree(json).path("hits").path("hits");
      LinkedHashSet<String> names = new LinkedHashSet<>();
      if (hits.isArray()) {
        for (JsonNode hit : hits) {
          String name = hit.path("_source").path("vorlage").textValue();
          if (name != null && !name.isBlank()) names.add(name);
        }
      }
      // Always include classpath fallbacks
      for (String known : List.of("stage", "description", "benefits")) names.add(known);
      return new ArrayList<>(names);
    } catch (RestClientException e) {
      if (e.getMessage() == null || !e.getMessage().contains("index_not_found")) {
        log.warn("Failed to list vorlagen from ES", e);
      }
    } catch (IOException e) {
      log.warn("Failed to parse vorlage list from ES", e);
    }
    return new ArrayList<>(List.of("stage", "description", "benefits"));
  }

  @Cacheable(value = "vorlagen", key = "#name")
  public String loadVorlage(String name) {
    try {
      String json = esClient.get()
        .uri("/" + VORLAGEN_INDEX + "/_doc/" + name)
        .accept(MediaType.APPLICATION_JSON)
        .retrieve().body(String.class);

      JsonNode node = objectMapper.readTree(json);
      if (node.path("found").booleanValue()) {
        return node.path("_source").path("html").textValue();
      }
    } catch (RestClientException e) {
      if (e.getMessage() != null && (e.getMessage().contains("404") || e.getMessage().contains("index_not_found"))) {
        // fall through to classpath
      } else {
        throw e;
      }
    } catch (IOException e) {
      log.warn("Failed to parse vorlage '{}' from ES", name, e);
    }
    // Classpath fallback
    ClassPathResource resource = new ClassPathResource("templates/slots/" + name + ".html");
    if (resource.exists()) {
      try (InputStream in = resource.getInputStream()) {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
      } catch (IOException e) {
        log.warn("Failed to read classpath vorlage '{}'", name, e);
      }
    }
    return "";
  }

  @Caching(evict = {
      @CacheEvict(value = "vorlagen", key = "#name"),
      @CacheEvict(value = "vorlage-history", key = "#name")
  })
  public void saveVorlage(String name, String html) {
    Map<String, Object> doc = Map.of("vorlage", name, "html", html, "timestamp", Instant.now().toString());
    try {
      esClient.put()
        .uri("/" + VORLAGEN_INDEX + "/_doc/" + name)
        .accept(MediaType.APPLICATION_JSON)
        .contentType(MediaType.APPLICATION_JSON)
        .body(objectMapper.writeValueAsString(doc))
        .retrieve().body(String.class);
      log.info("Saved vorlage '{}' globally", name);
    } catch (IOException e) {
      throw new RuntimeException("Failed to serialize vorlage", e);
    }
    // Also write a history entry
    String historyId = name + "-" + Instant.now().toEpochMilli();
    Map<String, Object> historyDoc = Map.of("vorlage", name, "html", html, "timestamp", Instant.now().toString());
    try {
      esClient.put()
        .uri("/" + VORLAGEN_HISTORY_INDEX + "/_doc/" + historyId)
        .accept(MediaType.APPLICATION_JSON)
        .contentType(MediaType.APPLICATION_JSON)
        .body(objectMapper.writeValueAsString(historyDoc))
        .retrieve().body(String.class);
    } catch (Exception e) {
      log.warn("Failed to write vorlage history for '{}'", name, e);
    }
  }

  @Cacheable(value = "vorlage-history", key = "#name")
  public List<Map<String, String>> loadVorlageHistory(String name) {
    Map<String, Object> query = Map.of(
      "query", Map.of("term", Map.of("vorlage.keyword", name)),
      "sort", List.of(Map.of("timestamp", Map.of("order", "desc"))),
      "size", 20,
      "_source", List.of("timestamp")
    );
    try {
      String json = esClient.post()
        .uri("/" + VORLAGEN_HISTORY_INDEX + "/_search")
        .accept(MediaType.APPLICATION_JSON)
        .contentType(MediaType.APPLICATION_JSON)
        .body(objectMapper.writeValueAsString(query))
        .retrieve().body(String.class);
      JsonNode hits = objectMapper.readTree(json).path("hits").path("hits");
      List<Map<String, String>> result = new ArrayList<>();
      if (hits.isArray()) {
        for (JsonNode hit : hits) {
          String id = hit.path("_id").textValue();
          String ts = hit.path("_source").path("timestamp").textValue();
          if (id != null && ts != null) result.add(Map.of("id", id, "timestamp", ts));
        }
      }
      return result;
    } catch (Exception e) {
      log.warn("Failed to load history for vorlage '{}'", name, e);
      return List.of();
    }
  }

  public String loadVorlageVersion(String historyId) {
    try {
      String json = esClient.get()
        .uri("/" + VORLAGEN_HISTORY_INDEX + "/_doc/" + historyId)
        .accept(MediaType.APPLICATION_JSON)
        .retrieve().body(String.class);
      JsonNode node = objectMapper.readTree(json);
      if (node.path("found").booleanValue()) {
        return node.path("_source").path("html").textValue();
      }
    } catch (Exception e) {
      log.warn("Failed to load vorlage version '{}'", historyId, e);
    }
    return "";
  }

  @Caching(evict = {
      @CacheEvict(value = "vorlagen", key = "#name"),
      @CacheEvict(value = "vorlage-history", key = "#name")
  })
  public void deleteVorlage(String name) {
    try {
      esClient.delete()
        .uri("/" + VORLAGEN_INDEX + "/_doc/" + name)
        .retrieve().body(String.class);
      log.info("Deleted vorlage '{}'", name);
    } catch (Exception e) {
      log.warn("Failed to delete vorlage '{}'", name, e);
    }
  }

  // ── Labels (global per country/language) ─────────────────────────────────

  @Cacheable(value = "labels", key = "#country + '-' + #language")
  public Map<String, String> loadLabels(String country, String language) {
    String docId = country.toLowerCase() + "-" + language.toLowerCase();
    try {
      String json = esClient.get()
        .uri("/" + LABELS_INDEX + "/_doc/" + docId)
        .accept(MediaType.APPLICATION_JSON)
        .retrieve().body(String.class);

      JsonNode node = objectMapper.readTree(json);
      if (node.path("found").booleanValue()) {
        JsonNode labelsNode = node.path("_source").path("labels");
        if (!labelsNode.isMissingNode()) {
          @SuppressWarnings("unchecked")
          Map<String, String> labels = objectMapper.treeToValue(labelsNode, Map.class);
          return labels;
        }
      }
    } catch (RestClientException e) {
      if (e.getMessage() != null && (e.getMessage().contains("404") || e.getMessage().contains("index_not_found"))) {
        log.info("No global labels found for {}/{}", country, language);
      } else {
        log.warn("Failed to load labels for {}/{}", country, language, e);
      }
    } catch (IOException e) {
      log.warn("Failed to parse labels for {}/{}", country, language, e);
    }
    return new java.util.HashMap<>();
  }

  @CacheEvict(value = "labels", key = "#country + '-' + #language")
  public void saveLabels(String country, String language, Map<String, String> labels) {
    String docId = country.toLowerCase() + "-" + language.toLowerCase();
    Map<String, Object> doc = Map.of(
      "country", country, "language", language,
      "timestamp", Instant.now().toString(),
      "labels", labels
    );
    try {
      esClient.put()
        .uri("/" + LABELS_INDEX + "/_doc/" + docId)
        .accept(MediaType.APPLICATION_JSON)
        .contentType(MediaType.APPLICATION_JSON)
        .body(objectMapper.writeValueAsString(doc))
        .retrieve().body(String.class);
      log.info("Saved global labels for {}/{}", country, language);
    } catch (IOException e) {
      throw new RuntimeException("Failed to serialize labels", e);
    }
  }

}
