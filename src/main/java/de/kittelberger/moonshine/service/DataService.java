package de.kittelberger.moonshine.service;

import de.kittelberger.moonshine.model.MapConfig;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Service
public class DataService {

  private final RestClient restClient;

  public DataService(@Value("${bosch.illusion.url}") String illusionUrl) {
    this.restClient = RestClient.builder()
        .baseUrl(illusionUrl)
        .build();
  }

  public Map<String, Map<String, Map<String, Object>>> fetchData(
      final String country,
      final String language,
      final List<MapConfig> mapConfigs
  ) {
    return restClient.post()
        .uri("/{country}/{language}/index", country, language)
        .body(mapConfigs)
        .retrieve()
        .body(new ParameterizedTypeReference<>() {});
  }
}
