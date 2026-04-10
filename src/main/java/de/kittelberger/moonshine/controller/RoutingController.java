package de.kittelberger.moonshine.controller;

import de.kittelberger.moonshine.model.RouteConfig;
import de.kittelberger.moonshine.service.RenderService;
import de.kittelberger.moonshine.service.RoutingStorageService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
public class RoutingController {

  private final RoutingStorageService routingStorageService;
  private final RenderService renderService;

  public RoutingController(RoutingStorageService routingStorageService, RenderService renderService) {
    this.routingStorageService = routingStorageService;
    this.renderService = renderService;
  }

  // ── Route table CRUD ──────────────────────────────────────────────────────

  @GetMapping("/{country}/{language}/routes")
  public List<RouteConfig> getRoutes(
    @PathVariable String country,
    @PathVariable String language) {
    return routingStorageService.loadRoutes(country, language);
  }

  @PutMapping("/{country}/{language}/routes")
  public void putRoutes(
    @PathVariable String country,
    @PathVariable String language,
    @RequestBody List<RouteConfig> routes) {
    routingStorageService.saveRoutes(country, language, routes);
  }

  // ── Catch-all page renderer ───────────────────────────────────────────────

  /**
   * Catch-all route that matches any path after country/language and looks it up
   * in the routing table. Renders either a CMS_PAGE (labels only) or a PRODUCT_PAGE
   * (with SKU extracted from the URL pattern).
   *
   * The more specific {@code /{country}/{language}/product-{sku}} route in
   * {@link ProductController} takes priority and is not affected.
   */
  @GetMapping(value = "/{country}/{language}/**", produces = MediaType.TEXT_HTML_VALUE)
  public String catchAll(
    @PathVariable String country,
    @PathVariable String language,
    @RequestParam(defaultValue = "false") boolean editMode,
    HttpServletRequest request) {

    // Extract the path suffix after /{country}/{language}/
    String fullPath = request.getRequestURI();
    String prefix = "/" + country + "/" + language;
    String path = fullPath.startsWith(prefix) ? fullPath.substring(prefix.length()) : fullPath;

    // Strip /moonlight context path if present
    if (path.startsWith("/moonlight")) {
      path = path.substring("/moonlight".length());
    }

    RouteConfig route = routingStorageService.findRoute(country, language, path);
    if (route == null) {
      return "<html><body><h2>404 – Keine Route für \"" + path + "\" gefunden</h2></body></html>";
    }

    return switch (route.getPageType()) {
      case CMS_PAGE -> renderService.renderCmsPage(country, language, route.getPageName(), editMode);
      case PRODUCT_PAGE -> {
        Map<String, String> vars = routingStorageService.extractPathVariables(route.getUrl(), path);
        String sku = vars.getOrDefault("sku", "EXAMPLE");
        yield renderService.renderTemplate(country, language, sku, route.getPageName(), editMode);
      }
    };
  }
}
