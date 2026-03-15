package de.kittelberger.moonshine.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.kittelberger.moonshine.model.TemplateProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.Optional;

@Service
public class ConfigService {

  private static final String CONFIG_DIR = "configs/";

  public Optional<TemplateProperties> loadConfig(final String name) {
    try {
      ObjectMapper objectMapper = new ObjectMapper();
      ClassPathResource resource = new ClassPathResource(CONFIG_DIR + name + ".json");
      try (InputStream inputStream = resource.getInputStream()) {
        return Optional.of(objectMapper.readValue(inputStream, TemplateProperties.class));
      }
    } catch (Exception e) {
      return Optional.empty();
    }
  }
}
