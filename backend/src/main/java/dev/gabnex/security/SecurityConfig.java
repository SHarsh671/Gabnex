package dev.gabnex.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Configuration
public class SecurityConfig {
  @Value("${app.jwt-secret}") private String secret;
  @Value("${app.frontend-origin}") private String frontendOrigin;
  @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
  private SecretKey key() { return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)); }
  @Bean JwtService jwtService() { return new JwtService(key()); }
  @Bean JwtFilter jwtFilter(JwtService jwt) { return new JwtFilter(jwt); }
  @Bean SecurityFilterChain filterChain(HttpSecurity http, JwtFilter jwtFilter) throws Exception {
    return http.csrf(c -> c.disable()).cors(c -> c.configurationSource(req -> {
      var cfg = new org.springframework.web.cors.CorsConfiguration(); cfg.setAllowedOrigins(java.util.List.of(frontendOrigin));
      cfg.setAllowedMethods(java.util.List.of("GET","POST","PUT","DELETE","OPTIONS")); cfg.setAllowedHeaders(java.util.List.of("Authorization","Content-Type")); cfg.setAllowCredentials(false); return cfg;
    })).sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
      .authorizeHttpRequests(a -> a.requestMatchers("/api/v1/auth/register","/api/v1/auth/login","/actuator/health","/swagger-ui/**","/v3/api-docs/**").permitAll().anyRequest().authenticated())
      .exceptionHandling(e -> e.authenticationEntryPoint((req,res,ex) -> res.sendError(HttpServletResponse.SC_UNAUTHORIZED)))
      .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class).build();
  }
  public static class JwtService {
    private final SecretKey key; JwtService(SecretKey key) { this.key=key; }
    public String create(long id, String email) { return Jwts.builder().subject(email).claim("uid",id).issuedAt(new Date()).expiration(new Date(System.currentTimeMillis()+86400000L)).signWith(key).compact(); }
    public Claims parse(String token) { return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload(); }
  }
  public static class JwtFilter extends OncePerRequestFilter {
    private final JwtService jwt; JwtFilter(JwtService jwt) { this.jwt=jwt; }
    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
      String h=req.getHeader(HttpHeaders.AUTHORIZATION);
      if(h!=null && h.startsWith("Bearer ")) try { Claims c=jwt.parse(h.substring(7)); long id=((Number)c.get("uid")).longValue();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(id, null, java.util.List.of()));
      } catch (JwtException | IllegalArgumentException ignored) { }
      chain.doFilter(req,res);
    }
  }
}
