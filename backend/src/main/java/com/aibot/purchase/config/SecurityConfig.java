package com.aibot.purchase.config;

import com.aibot.purchase.auth.AuthTokenFilter;
import com.aibot.purchase.common.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class SecurityConfig {

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      AuthTokenFilter authTokenFilter,
      ObjectMapper objectMapper
  ) throws Exception {
    return http
        .csrf(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .cors(cors -> {
        })
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth
            .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
            .requestMatchers(HttpMethod.GET, "/api/auth/config").permitAll()
            .requestMatchers(HttpMethod.POST, "/api/auth/wechat-login").permitAll()
            .requestMatchers(HttpMethod.GET, "/api/auth/wechat-qr").permitAll()
            .requestMatchers(HttpMethod.GET, "/api/auth/wechat-qr/**").permitAll()
            .requestMatchers(HttpMethod.POST, "/api/auth/wechat-qr/confirm").permitAll()
            .requestMatchers(HttpMethod.POST, "/api/auth/wechat-qr/mock-confirm").permitAll()
            .requestMatchers("/actuator/health").permitAll()
            .requestMatchers("/files/**").permitAll()
            .anyRequest().authenticated()
        )
        .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint((request, response, exception) -> {
          response.setStatus(401);
          response.setCharacterEncoding("UTF-8");
          response.setContentType(MediaType.APPLICATION_JSON_VALUE);
          objectMapper.writeValue(response.getWriter(), ApiResponse.error("未登录或登录已过期"));
        }))
        .addFilterBefore(authTokenFilter, UsernamePasswordAuthenticationFilter.class)
        .build();
  }

  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  CorsConfigurationSource corsConfigurationSource(@Value("${app.cors-allowed-origins:*}") String allowedOrigins) {
    CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowedOriginPatterns(List.of(allowedOrigins.split(",")));
    configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    configuration.setAllowedHeaders(List.of("*"));
    configuration.setAllowCredentials(false);
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);
    return source;
  }
}
