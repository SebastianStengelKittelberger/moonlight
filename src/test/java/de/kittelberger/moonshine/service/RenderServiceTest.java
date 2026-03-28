package de.kittelberger.moonshine.service;

import de.kittelberger.moonshine.model.MapConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.thymeleaf.TemplateEngine;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class RenderServiceTest {

    @Mock DataService dataService;
    @Mock ConfigService configService;
    @Mock TemplateStorageService templateStorageService;
    @Mock TemplateEngine templateEngine;

    RenderService renderService;

    @BeforeEach
    void setUp() {
        renderService = new RenderService(dataService, configService, templateStorageService, templateEngine);
    }

    private MapConfig mapConfig(String ukey, String targetField, String targetFieldType) {
        MapConfig mc = new MapConfig();
        mc.setUkey(ukey);
        mc.setTargetField(targetField);
        mc.setTargetFieldType(targetFieldType);
        return mc;
    }

    // ── replaceSkuAttributeCalls ───────────────────────────────────────────

    @Test
    void replaceSkuAttr_standalone_stringType_produces_thText() {
        MapConfig mc = mapConfig("TITLE", "title", "STRING");
        String result = renderService.replaceSkuAttributeCalls(
                "<div $skuAttr(TITLE)$.getText()>", List.of(mc));
        assertThat(result).isEqualTo(
                "<div th:text=\"${dataMap['title'] != null ? dataMap['title'].STRING : ''}\">");
    }

    @Test
    void replaceSkuAttr_insideThymeleafExpression_noWrapping() {
        MapConfig mc = mapConfig("TITLE", "title", "STRING");
        String result = renderService.replaceSkuAttributeCalls(
                "${$skuAttr(TITLE)$.getText()}", List.of(mc));
        assertThat(result).isEqualTo(
                "${dataMap['title'] != null ? dataMap['title'].STRING : ''}");
    }

    @Test
    void replaceSkuAttr_insideAttributeValue_imageType_wrapsInDollarBraces() {
        MapConfig mc = mapConfig("PRODIMG", "prodimg", "IMAGE");
        String result = renderService.replaceSkuAttributeCalls(
                "<img src=\"$skuAttr(PRODIMG)$.getImgUrl()\">", List.of(mc));
        assertThat(result).isEqualTo(
                "<img src=\"${dataMap['prodimg'] != null ? dataMap['prodimg'].IMAGE.url : ''}\">");
    }

    @Test
    void replaceSkuAttr_insideAttributeValue_stringType_wrapsInDollarBraces() {
        MapConfig mc = mapConfig("TITLE", "title", "STRING");
        String result = renderService.replaceSkuAttributeCalls(
                "<input value=\"$skuAttr(TITLE)$.getText()\">", List.of(mc));
        assertThat(result).isEqualTo(
                "<input value=\"${dataMap['title'] != null ? dataMap['title'].STRING : ''}\">");
    }

    @Test
    void replaceSkuAttr_standalone_imageType_produces_thSrc() {
        MapConfig mc = mapConfig("PRODIMG", "prodimg", "IMAGE");
        String result = renderService.replaceSkuAttributeCalls(
                "<img $skuAttr(PRODIMG)$.getImgUrl()>", List.of(mc));
        assertThat(result).isEqualTo(
                "<img th:src=\"${dataMap['prodimg'] != null ? dataMap['prodimg'].IMAGE.url : ''}\">");
    }

    @Test
    void replaceSkuAttr_noMappingFound_standalone_emitsThTextWithEmptyString() {
        String result = renderService.replaceSkuAttributeCalls(
                "<div $skuAttr(UNKNOWN)$.getText()>", List.of());
        assertThat(result).isEqualTo("<div th:text=\"''\">");
    }

    @Test
    void replaceSkuAttr_noMappingFound_insideThymeleafExpr_emitsFalse() {
        String result = renderService.replaceSkuAttributeCalls(
                "${$skuAttr(UNKNOWN)$.getText()}", List.of());
        assertThat(result).isEqualTo("${false}");
    }

    @Test
    void replaceSkuAttr_noMappingFound_insideAttributeValue_emitsEmptyStringLiteral() {
        String result = renderService.replaceSkuAttributeCalls(
                "<img src=\"$skuAttr(UNKNOWN)$.getImgUrl()\">", List.of());
        assertThat(result).isEqualTo("<img src=\"''\">");
    }

    @Test
    void replaceSkuAttr_nullMapConfigs_returnsCodeUnchanged() {
        String code = "<div $skuAttr(TITLE)$.getText()>";
        String result = renderService.replaceSkuAttributeCalls(code, null);
        assertThat(result).isEqualTo(code);
    }

    @Test
    void replaceSkuAttr_emptyMapConfigs_appliesFallbacks() {
        String result = renderService.replaceSkuAttributeCalls(
                "<div $skuAttr(TITLE)$.getText()>", Collections.emptyList());
        assertThat(result).isEqualTo("<div th:text=\"''\">");
    }

    @Test
    void replaceSkuAttr_caseInsensitiveUkeyMatching() {
        MapConfig mc = mapConfig("TITLE", "title", "STRING");
        String result = renderService.replaceSkuAttributeCalls(
                "<div $skuAttr(title)$.getText()>", List.of(mc));
        assertThat(result).isEqualTo(
                "<div th:text=\"${dataMap['title'] != null ? dataMap['title'].STRING : ''}\">");
    }

    @Test
    void replaceSkuAttr_noPatternInCode_returnsUnchanged() {
        String code = "<div>hello world</div>";
        MapConfig mc = mapConfig("TITLE", "title", "STRING");
        String result = renderService.replaceSkuAttributeCalls(code, List.of(mc));
        assertThat(result).isEqualTo(code);
    }

    @Test
    void replaceSkuAttr_multipleMatchesInCode() {
        MapConfig title = mapConfig("TITLE", "title", "STRING");
        MapConfig img   = mapConfig("PRODIMG", "prodimg", "IMAGE");
        String input  = "<h1 $skuAttr(TITLE)$.getText()><img $skuAttr(PRODIMG)$.getImgUrl()>";
        String result = renderService.replaceSkuAttributeCalls(input, List.of(title, img));
        assertThat(result).contains("th:text=");
        assertThat(result).contains("th:src=");
    }

    // ── replaceLabelCalls ──────────────────────────────────────────────────

    @Test
    void replaceLabelCalls_singleLabel() {
        String result = renderService.replaceLabelCalls("§header.title§");
        assertThat(result).isEqualTo(
                "${labels['header.title'] != null ? labels['header.title'] : ''}");
    }

    @Test
    void replaceLabelCalls_multipleLabels() {
        String result = renderService.replaceLabelCalls("§header.title§ and §footer.copy§");
        assertThat(result).isEqualTo(
                "${labels['header.title'] != null ? labels['header.title'] : ''}" +
                " and " +
                "${labels['footer.copy'] != null ? labels['footer.copy'] : ''}");
    }

    @Test
    void replaceLabelCalls_noLabels_returnsUnchanged() {
        String code = "<div>no labels here</div>";
        assertThat(renderService.replaceLabelCalls(code)).isEqualTo(code);
    }

    @Test
    void replaceLabelCalls_labelWithDotsAndDashes() {
        String result = renderService.replaceLabelCalls("§my-label.key§");
        assertThat(result).isEqualTo(
                "${labels['my-label.key'] != null ? labels['my-label.key'] : ''}");
    }

    @Test
    void replaceLabelCalls_labelEmbeddedInHtml() {
        String result = renderService.replaceLabelCalls("<h1>§page.headline§</h1>");
        assertThat(result).isEqualTo(
                "<h1>${labels['page.headline'] != null ? labels['page.headline'] : ''}</h1>");
    }
}
