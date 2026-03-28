package de.kittelberger.moonshine.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.templateresolver.StringTemplateResolver;

@Configuration
public class ThymeleafConfig {

  @Bean
  public TemplateEngine stringTemplateEngine() {
    // Resolver 1: Classpath-Dateien (für Fragments) – höhere Priorität
    ClassLoaderTemplateResolver classPathResolver = new ClassLoaderTemplateResolver();
    classPathResolver.setPrefix("templates/");
    classPathResolver.setSuffix(".html");
    classPathResolver.setTemplateMode(TemplateMode.HTML);
    classPathResolver.setCharacterEncoding("UTF-8");
    classPathResolver.setCacheable(false);
    classPathResolver.setOrder(1);
    classPathResolver.setCheckExistence(true);

    // Resolver 2: String-Templates (für dynamische Templates) – niedrigere Priorität
    StringTemplateResolver stringResolver = new StringTemplateResolver();
    stringResolver.setTemplateMode(TemplateMode.HTML);
    stringResolver.setCacheable(false);
    stringResolver.setOrder(2);

    TemplateEngine engine = new TemplateEngine();
    engine.addTemplateResolver(classPathResolver);
    engine.addTemplateResolver(stringResolver);
    return engine;
  }
}
