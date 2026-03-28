package de.kittelberger.moonshine.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.kittelberger.moonshine.model.SlotConfig;
import de.kittelberger.moonshine.model.TemplateProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
 * Persists template data in Elasticsearch across three global/scoped indices:
 *
 * <ul>
 *   <li>{@code moonlight-vorlagen}  – global slot-HTML Vorlagen (one doc per vorlage name)</li>
 *   <li>{@code moonlight-pages}     – pages per country/language (mapConfig + slots)</li>
 *   <li>{@code moonlight-labels}    – global labels per country/language</li>
 * </ul>
 *
 * Legacy indices ({@code moonlight-template-config}, {@code moonlight-slot-templates}) are kept
 * for backward compatibility via the old {@code loadConfig}/{@code saveConfig} methods.
 */
@Service
public class TemplateStorageService {

  private static final Logger log = LoggerFactory.getLogger(TemplateStorageService.class);

  // ── New indices ────────────────────────────────────────────────────────────
  private static final String PAGES_INDEX    = "moonlight-pages";
  private static final String VORLAGEN_INDEX = "moonlight-vorlagen";
  private static final String LABELS_INDEX   = "moonlight-labels";

  // ── Legacy indices (backward compat) ──────────────────────────────────────
  private static final String CONFIG_INDEX = "moonlight-template-config";
  private static final String SLOT_INDEX   = "moonlight-slot-templates";

  private final RestClient esClient;
  private final ConfigService configService;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public TemplateStorageService(
    ConfigService configService,
    @Value("${elasticsearch.host:localhost}") String host,
    @Value("${elasticsearch.port:9200}") int port
  ) {
    this.configService = configService;
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
  }

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

  // ── Legacy (backward compat) ──────────────────────────────────────────────

  public TemplateProperties loadConfig(String country, String language) {
    Map<String, Object> query = Map.of(
      "query", Map.of("bool", Map.of("filter", List.of(
        Map.of("term", Map.of("country.keyword", country)),
        Map.of("term", Map.of("language.keyword", language))
      ))),
      "sort", List.of(Map.of("timestamp", "desc")),
      "size", 1
    );
    try {
      String json = esClient.post()
        .uri("/" + CONFIG_INDEX + "/_search")
        .accept(MediaType.APPLICATION_JSON)
        .contentType(MediaType.APPLICATION_JSON)
        .body(objectMapper.writeValueAsString(query))
        .retrieve().body(String.class);

      JsonNode hits = objectMapper.readTree(json).path("hits").path("hits");
      if (hits.isArray() && !hits.isEmpty()) {
        return objectMapper.treeToValue(hits.get(0).path("_source").path("config"), TemplateProperties.class);
      }
    } catch (RestClientException e) {
      if (e.getMessage() == null || !e.getMessage().contains("index_not_found")) throw e;
    } catch (IOException e) {
      log.warn("Failed to parse config from ES, falling back to classpath", e);
    }
    log.info("No template config in ES for {}/{} — using classpath fallback", country, language);
    return configService.loadConfig("example").orElseGet(TemplateProperties::new);
  }

  public void saveConfig(String country, String language, TemplateProperties config) {
    Map<String, Object> doc = Map.of(
      "country", country, "language", language,
      "timestamp", Instant.now().toString(), "config", config
    );
    try {
      esClient.post()
        .uri("/" + CONFIG_INDEX + "/_doc")
        .accept(MediaType.APPLICATION_JSON)
        .contentType(MediaType.APPLICATION_JSON)
        .body(objectMapper.writeValueAsString(doc))
        .retrieve().body(String.class);
    } catch (IOException e) {
      throw new RuntimeException("Failed to serialize config", e);
    }
  }

  public String loadSlotTemplate(String country, String language, String slot) {
    String docId = country + "-" + language + "-" + slot;
    try {
      String json = esClient.get()
        .uri("/" + SLOT_INDEX + "/_doc/" + docId)
        .accept(MediaType.APPLICATION_JSON)
        .retrieve().body(String.class);
      JsonNode node = objectMapper.readTree(json);
      if (node.path("found").booleanValue()) {
        return node.path("_source").path("html").textValue();
      }
    } catch (RestClientException e) {
      if (e.getMessage() != null && (e.getMessage().contains("404") || e.getMessage().contains("index_not_found"))) {
        // fall through
      } else throw e;
    } catch (IOException e) {
      log.warn("Failed to parse slot template from ES", e);
    }
    ClassPathResource resource = new ClassPathResource("templates/slots/" + slot + ".html");
    if (resource.exists()) {
      try (InputStream in = resource.getInputStream()) {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
      } catch (IOException e) {
        log.warn("Failed to read classpath slot template for slot {}", slot, e);
      }
    }
    return "";
  }

  public void saveSlotTemplate(String country, String language, String slot, String html) {
    String docId = country + "-" + language + "-" + slot;
    Map<String, Object> doc = Map.of(
      "country", country, "language", language,
      "slot", slot, "html", html, "timestamp", Instant.now().toString()
    );
    try {
      esClient.put()
        .uri("/" + SLOT_INDEX + "/_doc/" + docId)
        .accept(MediaType.APPLICATION_JSON)
        .contentType(MediaType.APPLICATION_JSON)
        .body(objectMapper.writeValueAsString(doc))
        .retrieve().body(String.class);
    } catch (IOException e) {
      throw new RuntimeException("Failed to serialize slot template", e);
    }
  }

  public List<String> listSlots(String country, String language) {
    LinkedHashSet<String> slots = new LinkedHashSet<>();
    loadConfig(country, language).getSlots().stream()
      .map(SlotConfig::getComponent)
      .forEach(slots::add);
    for (String known : List.of("stage", "description", "benefits")) slots.add(known);
    return new ArrayList<>(slots);
  }
}
