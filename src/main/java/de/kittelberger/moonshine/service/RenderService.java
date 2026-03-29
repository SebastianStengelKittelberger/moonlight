package de.kittelberger.moonshine.service;

import de.kittelberger.moonshine.model.MapConfig;
import de.kittelberger.moonshine.model.SlotConfig;
import de.kittelberger.moonshine.model.TemplateProperties;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.util.Comparator;
import java.util.HashMap;
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


  /** Convenience overload defaulting to editMode=false. */
  public String renderTemplate(
    final String country,
    final String language,
    final String sku,
    final String pageName) {
    return renderTemplate(country, language, sku, pageName, false);
  }

  public String renderTemplate(
    final String country,
    final String language,
    final String sku,
    final String pageName,
    final boolean editMode) {

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
      return renderSlotBasedPage(properties, data, sku, mapConfig, editMode);
    }
    return renderTemplate(properties, data, sku, mapConfig, editMode);
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

  private static final String EDIT_CSS = """
      <style>
        [data-illusion-ukey] {
          outline: 2px dashed #6366f1 !important;
          cursor: pointer !important;
          border-radius: 2px;
          transition: background 0.15s, opacity 0.15s;
          pointer-events: auto !important;
          position: relative !important;
        }
        [data-illusion-ukey]:hover {
          background: rgba(99,102,241,0.15);
          z-index: 1;
        }
        /* Empty elements collapse to 0px — give them a minimum clickable area */
        [data-illusion-ukey]:empty {
          display: inline-block !important;
          min-width: 80px;
          min-height: 1.2em;
        }
        [data-illusion-ukey]:empty::before {
          content: attr(data-illusion-ukey);
          font-size: 10px;
          color: rgba(99,102,241,0.6);
          font-family: monospace;
          font-style: italic;
        }
        img[data-illusion-ukey] {
          display: block !important;
        }
        img[data-illusion-ukey]:hover {
          background: transparent;
          opacity: 0.8;
          outline-width: 3px !important;
        }
      </style>
      """;

  private String renderSlotBasedPage(
    final TemplateProperties properties,
    final Map<String, Map<String, Object>> data,
    final String sku,
    final List<MapConfig> mapConfig,
    final boolean editMode
  ) {
    String assembledBody = properties.getSlots().stream()
        .filter(SlotConfig::isEnabled)
        .sorted(Comparator.comparingInt(SlotConfig::getOrder))
        .map(slot -> {
          String content = loadSlotContent(slot.getComponent());
          // Each vorlage gets its own counter so data-illusion-index is relative
          // to the vorlage file, matching what handleUkeyReplace counts on patch.
          Map<String, Integer> slotCounters = editMode ? new HashMap<>() : null;
          return replaceSkuAttributeCalls(content, mapConfig, editMode, slot.getComponent(), slotCounters);
        })
        .map(this::replaceLabelCalls)
        .collect(java.util.stream.Collectors.joining("\n"));

    String fullPage = PAGE_WRAPPER.formatted(assembledBody);

    Context context = new Context();
    context.setVariable("dataMap", data);
    context.setVariable("sku", sku);
    context.setVariable("labels", properties.getLabels());
    context.setVariable("stageGallery", buildStageGallery(data, mapConfig));

    String rendered = templateEngine.process(fullPage, context);
    if (editMode) {
      rendered = rendered.replace("</head>", EDIT_CSS + "</head>");
    }
    return rendered;
  }

  private String renderSlotBasedPage(
    final TemplateProperties properties,
    final Map<String, Map<String, Object>> data,
    final String sku,
    final List<MapConfig> mapConfig
  ) {
    return renderSlotBasedPage(properties, data, sku, mapConfig, false);
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
    final List<MapConfig> mapConfig,
    final boolean editMode
  ) {
    String resolvedTemplate = replaceSkuAttributeCalls(
        properties.getTemplate(), mapConfig, editMode, null, editMode ? new HashMap<>() : null);
    resolvedTemplate = replaceLabelCalls(resolvedTemplate);

    String fullPage = PAGE_WRAPPER.formatted(extractBodyContent(resolvedTemplate));

    Context context = new Context();
    context.setVariable("dataMap", data);
    context.setVariable("sku", sku);
    context.setVariable("labels", properties.getLabels());
    context.setVariable("stageGallery", buildStageGallery(data, mapConfig));

    String rendered = templateEngine.process(fullPage, context);
    if (editMode) {
      rendered = rendered.replace("</head>", EDIT_CSS + "</head>");
    }
    return rendered;
  }

  private String renderTemplate(
    final TemplateProperties properties,
    final Map<String, Map<String, Object>> data,
    final String sku,
    final List<MapConfig> mapConfig
  ) {
    return renderTemplate(properties, data, sku, mapConfig, false);
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
    return replaceSkuAttributeCalls(code, mapConfigs, false, null, null);
  }

  public String replaceSkuAttributeCalls(
    final String code,
    final List<MapConfig> mapConfigs,
    final boolean editMode,
    final String vorlageName,
    final Map<String, Integer> ukeyCounters
  ) {
    if (mapConfigs == null) return code;
    Matcher matcher = SKU_ATTR_PATTERN.matcher(code);
    StringBuilder sb = new StringBuilder();
    int lastAppendPos = 0;

    while (matcher.find()) {
      String ukey = matcher.group(1);

      String textBefore = code.substring(0, matcher.start());
      boolean insideThymeleafExpr = isInsideThymeleafExpression(textBefore);
      boolean insideAttrValue     = !insideThymeleafExpr && isInsideAttributeValue(textBefore);

      Optional<MapConfig> matchingConfig = mapConfigs.stream()
        .filter(config -> config.getUkey().equalsIgnoreCase(ukey))
        .findFirst();

      if (matchingConfig.isPresent()) {
        MapConfig config = matchingConfig.get();
        String f = config.getTargetField();
        String t = config.getTargetFieldType();
        boolean isImage = "IMAGE".equalsIgnoreCase(t);

        // OGNL 3.3.4 does not support ?. – use explicit null-check ternary instead
        if (insideThymeleafExpr) {
          String expr = isImage
              ? "dataMap['" + f + "'] != null ? dataMap['" + f + "'].IMAGE.url : ''"
              : "dataMap['" + f + "'] != null ? dataMap['" + f + "']." + t + " : ''";
          sb.append(code, lastAppendPos, matcher.start());
          sb.append(expr);
          lastAppendPos = matcher.end();

        } else if (insideAttrValue) {
          String urlExpr = isImage
              ? "${dataMap['" + f + "'] != null ? dataMap['" + f + "'].IMAGE.url : ''}"
              : "${dataMap['" + f + "'] != null ? dataMap['" + f + "']." + t + " : ''}";

          // In editMode: inject data-attributes into the opening tag for ALL types
          // (TEXT in th:text="$token$" and IMAGE in th:src="$token$" / src="$token$")
          if (editMode && ukeyCounters != null) {
            int tagStart = textBefore.lastIndexOf('<');
            if (tagStart >= lastAppendPos) {
              int tagNameEnd = findTagNameEnd(code, tagStart + 1);
              int idx = ukeyCounters.merge(ukey.toUpperCase(), 1, Integer::sum) - 1;
              sb.append(code, lastAppendPos, tagStart + 1);     // up to and including '<'
              sb.append(code, tagStart + 1, tagNameEnd);        // tag name, e.g. "img" or "span"
              sb.append(" ").append(buildEditModeAttrs(ukey, vorlageName, idx, isImage ? "IMAGE" : "TEXT"));
              sb.append(code, tagNameEnd, matcher.start());     // e.g. " th:text=" (up to token)
              sb.append(urlExpr);
              lastAppendPos = matcher.end();
            } else {
              // Tag was already emitted (second attr in same tag) – just replace the value
              sb.append(code, lastAppendPos, matcher.start());
              sb.append(urlExpr);
              lastAppendPos = matcher.end();
            }
          } else {
            sb.append(code, lastAppendPos, matcher.start());
            sb.append(urlExpr);
            lastAppendPos = matcher.end();
          }

        } else {
          // Standalone token — in editMode, annotate the enclosing element
          final String expr;
          if (editMode && ukeyCounters != null) {
            int idx = ukeyCounters.merge(ukey.toUpperCase(), 1, Integer::sum) - 1;
            String thAttr = isImage
                ? "th:src=\"${dataMap['" + f + "'] != null ? dataMap['" + f + "'].IMAGE.url : ''}\""
                : "th:text=\"${dataMap['" + f + "'] != null ? dataMap['" + f + "']." + t + " : ''}\"";
            expr = buildEditModeAttrs(ukey, vorlageName, idx, isImage ? "IMAGE" : "TEXT")
                + " " + thAttr;
          } else {
            expr = isImage
                ? "th:src=\"${dataMap['" + f + "'] != null ? dataMap['" + f + "'].IMAGE.url : ''}\""
                : "th:text=\"${dataMap['" + f + "'] != null ? dataMap['" + f + "']." + t + " : ''}\"";
          }
          sb.append(code, lastAppendPos, matcher.start());
          sb.append(expr);
          lastAppendPos = matcher.end();
        }

      } else {
        // No mapping for this ukey – render empty/falsy
        final String expr;
        if (insideThymeleafExpr) {
          expr = "false";   // safe for both th:if and th:text contexts
        } else if (insideAttrValue) {
          expr = "''";
        } else {
          expr = "th:text=\"''\"";
        }
        sb.append(code, lastAppendPos, matcher.start());
        sb.append(expr);
        lastAppendPos = matcher.end();
      }
    }

    sb.append(code, lastAppendPos, code.length());
    return sb.toString();
  }

  private String buildEditModeAttrs(
      final String ukey,
      final String vorlageName,
      final int idx,
      final String fieldtype
  ) {
    String vorlageAttr = vorlageName != null
        ? " data-illusion-vorlage=\"" + vorlageName + "\""
        : "";
    return "data-illusion-ukey=\"" + ukey.toUpperCase() + "\""
        + " data-illusion-type=\"SKU\""
        + " data-illusion-index=\"" + idx + "\""
        + vorlageAttr
        + " data-illusion-fieldtype=\"" + fieldtype + "\""
        + " th:classappend=\"'illusion-editable'\"";
  }

  private int findTagNameEnd(final String code, final int start) {
    int pos = start;
    if (pos < code.length() && code.charAt(pos) == '/') pos++; // skip / in closing tags
    while (pos < code.length()
        && !Character.isWhitespace(code.charAt(pos))
        && code.charAt(pos) != '>'
        && code.charAt(pos) != '/') {
      pos++;
    }
    return pos;
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
