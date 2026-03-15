package de.kittelberger.moonshine.service;

import de.kittelberger.moonshine.model.MapConfig;
import de.kittelberger.moonshine.model.TemplateProperties;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class RenderService {

  private final DataService dataService;
  private final ConfigService configService;
  private final TemplateEngine templateEngine;

  private static final Pattern SKU_ATTR_PATTERN =
    Pattern.compile("\\$skuAttr\\((\\w+)\\)\\$\\.(\\w+\\(\\))");

  private static final Pattern LABEL_PATTERN =
    Pattern.compile("§(\\w+)§");

  private static final Map<String, String> METHOD_HANDLERS = Map.of(
    "getText()", "STRING",
    "getNumVal()",  "NUMBER",
    "getBoolVal()", "BOOLEAN",
    "getImgUrl()",  "IMAGE",
    "getLangSpecificText()", "CLTEXT"
  );

  public RenderService(
    final DataService dataService,
    final ConfigService configService,
    final TemplateEngine templateEngine
    ) {
    this.dataService = dataService;
    this.configService = configService;
    this.templateEngine = templateEngine;
  }


  public String renderTemplate(
    final String country,
    final String language,
    final String sku) {

    Optional<TemplateProperties> templateProperties = configService.loadConfig("example");
    if(templateProperties.isEmpty()) {
      return "No template found";
    }
    TemplateProperties properties = templateProperties.get();
    Map<String, Map<String, Map<String, Object>>> mappedData = dataService.fetchData(country, language, properties.getMapConfig());
    // TODO: Kompletter Overkill an Ressourcen. Später nur die Daten für die SKU auslesen und gut. ZB aus Elasticsearch
    Map<String, Map<String, Object>> data = mappedData.get(sku);

    return renderTemplate(properties, data);
  }

  private String renderTemplate(final TemplateProperties properties, final Map<String, Map<String, Object>> data) {
    String resolvedTemplate = replaceSkuAttributeCalls(properties.getTemplate(), properties.getMapConfig());
    resolvedTemplate = replaceLabelCalls(resolvedTemplate);

    Context context = new Context();
    context.setVariable("dataMap", data);
    context.setVariable("labels", properties.getLabels());

    return templateEngine.process(resolvedTemplate, context);
  }

  public String replaceSkuAttributeCalls(
    final String code,
    final List<MapConfig> mapConfigs
  ) {
    Matcher matcher = SKU_ATTR_PATTERN.matcher(code);
    StringBuilder sb = new StringBuilder();
    while (matcher.find()) {
      String ukey       = matcher.group(1);
      String methodCall = matcher.group(2);

      String dataType = METHOD_HANDLERS.get(methodCall);

      Optional<MapConfig> matchingConfig = mapConfigs.stream()
        .filter(config -> config.getUkey().equalsIgnoreCase(ukey.toLowerCase()))
        .findFirst();

      if (matchingConfig.isPresent()) {
        String thymeleafExpr = "";
        MapConfig config = matchingConfig.get();
        String targetFieldType = config.getTargetFieldType();
        String targetFieldName = config.getTargetField();
        if(targetFieldType.equalsIgnoreCase("IMAGE")) {
          thymeleafExpr = "th:text=\"${dataMap['" + targetFieldName + "']." + targetFieldType + ".url}\"";
        } else {
          thymeleafExpr = "th:text=\"${dataMap['" + targetFieldName + "']." + targetFieldType + "}\"";
        }

        matcher.appendReplacement(sb, Matcher.quoteReplacement(thymeleafExpr));
      }
    }

    matcher.appendTail(sb);
    return sb.toString();
  }

  public String replaceLabelCalls(final String code) {
    Matcher matcher = LABEL_PATTERN.matcher(code);
    StringBuilder sb = new StringBuilder();
    while (matcher.find()) {
      String labelKey = matcher.group(1);
      String thymeleafExpr = "th:text=\"${labels['" + labelKey + "']}\"";
      matcher.appendReplacement(sb, Matcher.quoteReplacement(thymeleafExpr));
    }
    matcher.appendTail(sb);
    return sb.toString();
  }
}
