package dev.webinfra.labs.orders;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Only the shop's own web app may call this API from a browser. CORS is enforced by browsers:
 * it protects users from other websites, not the API from curl or servers.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {
  @Override
  public void addCorsMappings(CorsRegistry registry) {
    registry.addMapping("/**")
        .allowedOrigins("https://shop.example.com")
        .allowedMethods("GET", "POST")
        .allowedHeaders("Content-Type", "X-API-Key", "X-Request-Id")
        // Without this, browser JavaScript can't read these response headers.
        .exposedHeaders("X-Request-Id", "X-RateLimit-Remaining", "Retry-After")
        .maxAge(600);   // browsers may cache the preflight answer for 10 minutes
  }
}
