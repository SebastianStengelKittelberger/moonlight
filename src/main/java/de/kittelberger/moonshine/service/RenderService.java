package de.kittelberger.moonshine.service;

import de.kittelberger.moonshine.model.MapConfig;
import de.kittelberger.moonshine.model.SlotConfig;
import de.kittelberger.moonshine.model.TemplateProperties;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.util.Comparator;
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
  private final TemplateStorageService templateStorageService;
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
    final TemplateStorageService templateStorageService,
    final TemplateEngine templateEngine
    ) {
    this.dataService = dataService;
    this.configService = configService;
    this.templateStorageService = templateStorageService;
    this.templateEngine = templateEngine;
  }


  public String renderTemplate(
    final String country,
    final String language,
    final String sku,
    final String pageName) {

    TemplateProperties properties = templateStorageService.loadPage(country, language, pageName);
    if (properties.getSlots() == null || properties.getSlots().isEmpty()) {
      // fallback: try legacy classpath config
      Optional<TemplateProperties> legacyProps = configService.loadConfig("example");
      if (legacyProps.isEmpty()) return "No template found for page '" + pageName + "'";
      properties = legacyProps.get();
    }

    // Always use Illusion's mapping config as the single source of truth.
    List<MapConfig> mapConfig = dataService.loadIllusionMappingConfig(country, language);

    Map<String, String> globalLabels = templateStorageService.loadLabels(country, language);
    if (properties.getLabels() != null) {
      globalLabels.putAll(properties.getLabels());
    }
    properties.setLabels(globalLabels);

    Map<String, Map<String, Object>> data = dataService.fetchData(country, language, sku, mapConfig);

    if (properties.getSlots() != null && !properties.getSlots().isEmpty()) {
      return renderSlotBasedPage(properties, data, sku, mapConfig);
    }
    return renderTemplate(properties, data, sku, mapConfig);
  }

  /** Convenience overload defaulting to "produktseite". */
  public String renderTemplate(final String country, final String language, final String sku) {
    return renderTemplate(country, language, sku, "produktseite");
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

  private String renderSlotBasedPage(
    final TemplateProperties properties,
    final Map<String, Map<String, Object>> data,
    final String sku,
    final List<MapConfig> mapConfig
  ) {
    String assembledBody = properties.getSlots().stream()
        .filter(SlotConfig::isEnabled)
        .sorted(Comparator.comparingInt(SlotConfig::getOrder))
        .map(slot -> loadSlotContent(slot.getComponent()))
        .map(content -> replaceSkuAttributeCalls(content, mapConfig))
        .map(this::replaceLabelCalls)
        .collect(java.util.stream.Collectors.joining("\n"));

    String fullPage = PAGE_WRAPPER.formatted(assembledBody);

    Context context = new Context();
    context.setVariable("dataMap", data);
    context.setVariable("sku", sku);
    context.setVariable("labels", properties.getLabels());
    context.setVariable("stageGallery", buildStageGallery(data, mapConfig));

    return templateEngine.process(fullPage, context);
  }

  private String loadSlotContent(final String component) {
    String html = templateStorageService.loadVorlage(component);
    if (html == null || html.isBlank()) {
      return "<!-- vorlage '" + component + "' not found -->";
    }
    return html;
  }

  private String renderTemplate(
    final TemplateProperties properties,
    final Map<String, Map<String, Object>> data,
    final String sku,
    final List<MapConfig> mapConfig
  ) {
    String resolvedTemplate = replaceSkuAttributeCalls(properties.getTemplate(), mapConfig);
    resolvedTemplate = replaceLabelCalls(resolvedTemplate);

    String fullPage = PAGE_WRAPPER.formatted(extractBodyContent(resolvedTemplate));

    Context context = new Context();
    context.setVariable("dataMap", data);
    context.setVariable("sku", sku);
    context.setVariable("labels", properties.getLabels());
    context.setVariable("stageGallery", buildStageGallery(data, mapConfig));

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
    if (mapConfigs == null) return code;
    Matcher matcher = SKU_ATTR_PATTERN.matcher(code);
    StringBuilder sb = new StringBuilder();
    while (matcher.find()) {
      String ukey = matcher.group(1);

      String textBefore = code.substring(0, matcher.start());
      boolean insideThymeleafExpr = isInsideThymeleafExpression(textBefore);
      boolean insideAttrValue     = !insideThymeleafExpr && isInsideAttributeValue(textBefore);

      Optional<MapConfig> matchingConfig = mapConfigs.stream()
        .filter(config -> config.getUkey().equalsIgnoreCase(ukey))
        .findFirst();

      final String expr;
      if (matchingConfig.isPresent()) {
        MapConfig config = matchingConfig.get();
        String f = config.getTargetField();
        String t = config.getTargetFieldType();

        // OGNL 3.3.4 does not support ?. – use explicit null-check ternary instead
        if (insideThymeleafExpr) {
          expr = "IMAGE".equalsIgnoreCase(t)
              ? "dataMap['" + f + "'] != null ? dataMap['" + f + "'].IMAGE.url : ''"
              : "dataMap['" + f + "'] != null ? dataMap['" + f + "']." + t + " : ''";
        } else if (insideAttrValue) {
          expr = "IMAGE".equalsIgnoreCase(t)
              ? "${dataMap['" + f + "'] != null ? dataMap['" + f + "'].IMAGE.url : ''}"
              : "${dataMap['" + f + "'] != null ? dataMap['" + f + "']." + t + " : ''}";
        } else {
          expr = "IMAGE".equalsIgnoreCase(t)
              ? "th:src=\"${dataMap['" + f + "'] != null ? dataMap['" + f + "'].IMAGE.url : ''}\""
              : "th:text=\"${dataMap['" + f + "'] != null ? dataMap['" + f + "']." + t + " : ''}\"";;
        }
      } else {
        // No mapping for this ukey – render empty/falsy
        if (insideThymeleafExpr) {
          expr = "false";   // safe for both th:if and th:text contexts
        } else if (insideAttrValue) {
          expr = "''";
        } else {
          expr = "th:text=\"''\"";
        }
      }

      matcher.appendReplacement(sb, Matcher.quoteReplacement(expr));
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
      // OGNL 3.3.4 does not support ?: – use explicit null-check ternary
      matcher.appendReplacement(sb, Matcher.quoteReplacement(
          "${labels['" + labelKey + "'] != null ? labels['" + labelKey + "'] : ''}"));
    }
    matcher.appendTail(sb);
    return sb.toString();
  }
}
