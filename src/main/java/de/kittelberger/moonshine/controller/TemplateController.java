package de.kittelberger.moonshine.controller;

import de.kittelberger.moonshine.model.TemplateProperties;
import de.kittelberger.moonshine.service.TemplateStorageService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
public class TemplateController {

  private final TemplateStorageService templateStorageService;

  public TemplateController(TemplateStorageService templateStorageService) {
    this.templateStorageService = templateStorageService;
  }

  // ── Seiten ────────────────────────────────────────────────────────────────

  @GetMapping("/{country}/{language}/pages")
  public List<String> listPages(@PathVariable String country, @PathVariable String language) {
    return templateStorageService.listPageNames(country, language);
  }

  @GetMapping("/{country}/{language}/page/{pageName}")
  public TemplateProperties getPage(
    @PathVariable String country, @PathVariable String language,
    @PathVariable String pageName
  ) {
    return templateStorageService.loadPage(country, language, pageName);
  }

  @PutMapping("/{country}/{language}/page/{pageName}")
  public void putPage(
    @PathVariable String country, @PathVariable String language,
    @PathVariable String pageName,
    @RequestBody TemplateProperties config
  ) {
    templateStorageService.savePage(country, language, pageName, config);
  }

  // ── Labels (global per country/language) ─────────────────────────────────

  @GetMapping("/{country}/{language}/labels")
  public Map<String, String> getLabels(@PathVariable String country, @PathVariable String language) {
    return templateStorageService.loadLabels(country, language);
  }

  @PutMapping("/{country}/{language}/labels")
  public void putLabels(
    @PathVariable String country, @PathVariable String language,
    @RequestBody Map<String, String> labels
  ) {
    templateStorageService.saveLabels(country, language, labels);
  }

  // ── Vorlagen (global) ─────────────────────────────────────────────────────

  @GetMapping("/vorlagen")
  public List<String> listVorlagen() {
    return templateStorageService.listVorlagen();
  }

  @GetMapping(value = "/vorlage/{name}", produces = MediaType.TEXT_HTML_VALUE)
  public String getVorlage(@PathVariable String name) {
    return templateStorageService.loadVorlage(name);
  }

  @PutMapping(value = "/vorlage/{name}", consumes = MediaType.TEXT_PLAIN_VALUE)
  public void putVorlage(@PathVariable String name, @RequestBody String html) {
    templateStorageService.saveVorlage(name, html);
  }

  @DeleteMapping("/vorlage/{name}")
  public void deleteVorlage(@PathVariable String name) {
    templateStorageService.deleteVorlage(name);
  }

  @GetMapping("/vorlage/{name}/history")
  public List<Map<String, String>> getVorlageHistory(@PathVariable String name) {
    return templateStorageService.loadVorlageHistory(name);
  }

  @GetMapping(value = "/vorlage/{name}/history/{historyId}", produces = MediaType.TEXT_HTML_VALUE)
  public String getVorlageVersion(@PathVariable String name, @PathVariable String historyId) {
    return templateStorageService.loadVorlageVersion(historyId);
  }

  // ── Legacy (backward compat) ──────────────────────────────────────────────

  @GetMapping("/{country}/{language}/config")
  public TemplateProperties getConfig(@PathVariable String country, @PathVariable String language) {
    return templateStorageService.loadConfig(country, language);
  }

  @PutMapping("/{country}/{language}/config")
  public void putConfig(@PathVariable String country, @PathVariable String language,
                        @RequestBody TemplateProperties config) {
    templateStorageService.saveConfig(country, language, config);
  }

  @GetMapping(value = "/{country}/{language}/template/{slot}", produces = MediaType.TEXT_HTML_VALUE)
  public String getTemplate(@PathVariable String country, @PathVariable String language,
                            @PathVariable String slot) {
    return templateStorageService.loadSlotTemplate(country, language, slot);
  }

  @PutMapping(value = "/{country}/{language}/template/{slot}", consumes = MediaType.TEXT_PLAIN_VALUE)
  public void putTemplate(@PathVariable String country, @PathVariable String language,
                          @PathVariable String slot, @RequestBody String html) {
    templateStorageService.saveSlotTemplate(country, language, slot, html);
  }

  @GetMapping("/{country}/{language}/templates")
  public List<String> listTemplates(@PathVariable String country, @PathVariable String language) {
    return templateStorageService.listSlots(country, language);
  }
}
