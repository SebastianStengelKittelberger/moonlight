package de.kittelberger.moonshine.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TemplateProperties {

  private String template;
  private Map<String, String> labels;
  private List<MapConfig> mapConfig;

}
