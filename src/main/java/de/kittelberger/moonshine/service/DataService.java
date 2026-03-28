package de.kittelberger.moonshine.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.kittelberger.moonshine.model.MapConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads mapped product data for a single SKU directly from the Elasticsearch index
 * written by Illusion ({@code illusion-{country}-{language}}).
 *
 * <p>The ES document is a flat map of {@code targetField → value}. This service
 * reconstructs the type-wrapper expected by the templates
 * ({@code fieldName → {TYPE → value}}) using the {@link MapConfig} entries.
 */
@Service
public class DataService {

  private static final Logger log = LoggerFactory.getLogger(DataService.class);

  private final RestClient esClient;
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final String indexPrefix;

  public DataService(
    @Value("${elasticsearch.host:localhost}") String host,
    @Value("${elasticsearch.port:9200}") int port,
    @Value("${elasticsearch.index-prefix:illusion}") String indexPrefix
  ) {
    this.esClient = RestClient.builder()
        .baseUrl("http://" + host + ":" + port)
        .build();
    this.indexPrefix = indexPrefix;
  }

  /**
   * Fetches the mapped fields for {@code sku} from the Illusion ES index and
   * wraps each value as {@code {targetFieldType → value}} so templates can
   * access e.g. {@code dataMap['headline'].STRING} or {@code dataMap['img'].IMAGE.url}.
   *
   * @return field map for the SKU, or an empty map when the document is not found
   */
  public Map<String, Map<String, Object>> fetchData(
      final String country,
      final String language,
      final String sku,
      final List<MapConfig> mapConfigs
  ) {
    String indexName = indexPrefix + "-" + country.toLowerCase() + "-" + language.toLowerCase();
    try {
      String json = esClient.get()
          .uri("/" + indexName + "/_doc/" + sku)
          .accept(MediaType.APPLICATION_JSON)
          .retrieve()
          .body(String.class);

      JsonNode source = objectMapper.readTree(json).path("_source");
      if (source.isMissingNode()) {
        log.warn("No _source in ES response for SKU '{}' in index '{}'", sku, indexName);
        return Map.of();
      }

      Map<String, Map<String, Object>> result = new HashMap<>();
      for (MapConfig config : mapConfigs) {
        String fieldName = config.getTargetField();
        String fieldType = config.getTargetFieldType();
        if (fieldName == null || fieldType == null) continue;

        JsonNode fieldNode = source.path(fieldName);
        if (!fieldNode.isMissingNode()) {
          Object value = objectMapper.treeToValue(fieldNode, Object.class);
          result.put(fieldName, Map.of(fieldType, value));
        }
      }
      return result;

    } catch (RestClientException e) {
      if (e.getMessage() != null && e.getMessage().contains("404")) {
        log.warn("SKU '{}' not found in ES index '{}'", sku, indexName);
        return Map.of();
      }
      throw e;
    } catch (IOException e) {
      log.error("Failed to parse ES response for SKU '{}' in index '{}'", sku, indexName, e);
      return Map.of();
    }
  }

  /**
   * Loads the latest Illusion mapping config for the given country/language from ES.
   * Used as fallback when the Moonlight page has no explicit mapConfig.
   */
  public List<MapConfig> loadIllusionMappingConfig(final String country, final String language) {
    try {
      String query = """
          {"query":{"bool":{"must":[{"term":{"country":"%s"}},{"term":{"language":"%s"}}]}},"sort":[{"timestamp":"desc"}],"size":1}
          """.formatted(country.toLowerCase(), language.toLowerCase());

      String json = esClient.post()
          .uri("/illusion-mapping-config/_search")
          .contentType(MediaType.APPLICATION_JSON)
          .body(query)
          .retrieve()
          .body(String.class);

      JsonNode hits = objectMapper.readTree(json).path("hits").path("hits");
      if (hits.isEmpty()) {
        log.warn("No Illusion mapping config found for {}/{}", country, language);
        return List.of();
      }

      JsonNode configs = hits.get(0).path("_source").path("configs");
      List<MapConfig> result = new ArrayList<>();
      for (JsonNode c : configs) {
        MapConfig mc = new MapConfig();
        mc.setUkey(c.path("ukey").asText(null));
        mc.setTargetField(c.path("targetField").asText(null));
        mc.setTargetFieldType(c.path("targetFieldType").asText(null));
        if (mc.getUkey() != null && mc.getTargetField() != null) {
          result.add(mc);
        }
      }
      log.debug("Loaded {} mapping configs from Illusion for {}/{}", result.size(), country, language);
      return result;

    } catch (Exception e) {
      log.error("Failed to load Illusion mapping config for {}/{}", country, language, e);
      return List.of();
    }
  }
}
