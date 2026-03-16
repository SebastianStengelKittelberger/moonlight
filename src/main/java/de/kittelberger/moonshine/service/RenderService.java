package de.kittelberger.moonshine.service;

import de.kittelberger.moonshine.model.MapConfig;
import de.kittelberger.moonshine.model.TemplateProperties;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    Pattern.compile("§([\\w.\\-]+)§");

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

    return renderTemplate(properties, data, sku);
  }

  private static final Pattern BODY_CONTENT_PATTERN =
      Pattern.compile("(?is)<body[^>]*>(.*)</body>");

  private static final String PAGE_WRAPPER = """
      <!DOCTYPE html>
      <html xmlns:th="http://www.thymeleaf.org" lang="de">
      <head>
        <th:block th:replace="~{fragments/layout :: head}"></th:block>
      </head>
      <body>
        <div th:replace="~{fragments/layout :: header}"></div>
        %s
        <div th:replace="~{fragments/layout :: footer}"></div>
      </body>
      </html>
      """;

  private String renderTemplate(
    final TemplateProperties properties,
    final Map<String, Map<String, Object>> data,
    final String sku
  ) {
    String resolvedTemplate = replaceSkuAttributeCalls(properties.getTemplate(), properties.getMapConfig());
    resolvedTemplate = replaceLabelCalls(resolvedTemplate);

    String fullPage = PAGE_WRAPPER.formatted(extractBodyContent(resolvedTemplate));

    Context context = new Context();
    context.setVariable("dataMap", data);
    context.setVariable("sku", sku);
    context.setVariable("labels", properties.getLabels());
    context.setVariable("stageGallery", buildStageGallery(data, properties.getMapConfig()));

    return templateEngine.process(fullPage, context);
  }

  private List<Map<String, Object>> buildStageGallery(
      final Map<String, Map<String, Object>> data,
      final List<MapConfig> mapConfigs
  ) {
    if (data == null) return List.of();

    return mapConfigs.stream()
        .filter(c -> "IMAGE".equalsIgnoreCase(c.getTargetFieldType()))
        .map(c -> data.get(c.getTargetField()))
        .filter(Objects::nonNull)
        .toList();
  }

  private String extractBodyContent(final String template) {
    Matcher bodyMatcher = BODY_CONTENT_PATTERN.matcher(template);
    if (bodyMatcher.find()) {
      return bodyMatcher.group(1).trim();
    }
    return template;
  }

  public String replaceSkuAttributeCalls(
    final String code,
    final List<MapConfig> mapConfigs
  ) {
    Matcher matcher = SKU_ATTR_PATTERN.matcher(code);
    StringBuilder sb = new StringBuilder();
    while (matcher.find()) {
      String ukey      = matcher.group(1);
      String methodCall = matcher.group(2);

      Optional<MapConfig> matchingConfig = mapConfigs.stream()
        .filter(config -> config.getUkey().equalsIgnoreCase(ukey))
        .findFirst();

      if (matchingConfig.isPresent()) {
        MapConfig config = matchingConfig.get();
        String targetFieldName = config.getTargetField();
        String targetFieldType = config.getTargetFieldType();

        String textBefore = code.substring(0, matcher.start());
        boolean insideThymeleafExpr = isInsideThymeleafExpression(textBefore);
        boolean insideAttrValue     = !insideThymeleafExpr && isInsideAttributeValue(textBefore);

        String expr;
        if (insideThymeleafExpr) {
          // Innerhalb von ${...}: nur den reinen Ausdruck, kein ${}
          expr = "dataMap['" + targetFieldName + "']." + targetFieldType;
        } else if (insideAttrValue) {
          // Innerhalb eines Attributwerts: mit ${} wrappen, kein th:-Prefix
          expr = "IMAGE".equalsIgnoreCase(targetFieldType)
              ? "${dataMap['" + targetFieldName + "'].IMAGE.url}"
              : "${dataMap['" + targetFieldName + "']." + targetFieldType + "}";
        } else {
          // Standalone im Tag: vollständiges th:-Attribut generieren
          expr = "IMAGE".equalsIgnoreCase(targetFieldType)
              ? "th:src=\"${dataMap['" + targetFieldName + "'].IMAGE.url}\""
              : "th:text=\"${dataMap['" + targetFieldName + "']." + targetFieldType + "}\"";
        }

        matcher.appendReplacement(sb, Matcher.quoteReplacement(expr));
      }
    }
    matcher.appendTail(sb);
    return sb.toString();
  }

  private boolean isInsideThymeleafExpression(final String textBefore) {
    int depth = 0;
    for (int i = 0; i < textBefore.length() - 1; i++) {
      if (textBefore.charAt(i) == '$' && textBefore.charAt(i + 1) == '{') {
        depth++;
        i++;
      } else if (textBefore.charAt(i) == '}' && depth > 0) {
        depth--;
      }
    }
    return depth > 0;
  }

  private boolean isInsideAttributeValue(final String textBefore) {
    int lastTagOpen = textBefore.lastIndexOf('<');
    if (lastTagOpen < 0) return false;
    long quoteCount = textBefore.substring(lastTagOpen).chars().filter(c -> c == '"').count();
    return quoteCount % 2 == 1;
  }

  public String replaceLabelCalls(final String code) {
    Matcher matcher = LABEL_PATTERN.matcher(code);
    StringBuilder sb = new StringBuilder();
    while (matcher.find()) {
      String labelKey = matcher.group(1);
      // Nur den Ausdruck ausgeben – der th:*-Kontext ist bereits im Template definiert
      matcher.appendReplacement(sb, Matcher.quoteReplacement("${labels['" + labelKey + "']}"));
    }
    matcher.appendTail(sb);
    return sb.toString();
  }
}
