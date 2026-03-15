package de.kittelberger.moonshine.controller;

import de.kittelberger.moonshine.service.RenderService;
import org.springframework.web.bind.annotation.*;

@ResponseBody
@RestController
public class ProductController {

  private final RenderService renderService;

  public ProductController(RenderService renderService) {
    this.renderService = renderService;
  }

  @GetMapping("/{country}/{language}/product-{sku}")
  public String productHandler(
    @PathVariable final String country,
    @PathVariable final String language,
    @PathVariable final String sku
  ) {
    return renderService.renderTemplate(country, language, sku);
  }

}
