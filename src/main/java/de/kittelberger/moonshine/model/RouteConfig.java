package de.kittelberger.moonshine.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RouteConfig {

  public enum PageType {
    PRODUCT_PAGE,
    CMS_PAGE
  }

  /** URL path to match, e.g. "/ueber-uns" or "/products/{sku}" */
  private String url;

  /** Whether this route renders a product page (needs SKU) or a CMS page (labels only) */
  private PageType pageType;

  /** The page name in Moonlight to render (maps to TemplateProperties) */
  private String pageName;

  /** Human-readable label for display in Summerlight routing editor */
  private String label;
}
