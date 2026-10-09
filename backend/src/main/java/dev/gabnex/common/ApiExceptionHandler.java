package dev.gabnex.common;

import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
  @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<?> badRequest(IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("error",e.getMessage())); }
  @ExceptionHandler(MethodArgumentNotValidException.class) ResponseEntity<?> invalid(MethodArgumentNotValidException e) { return ResponseEntity.badRequest().body(Map.of("error",e.getBindingResult().getFieldErrors().stream().findFirst().map(x -> x.getDefaultMessage()).orElse("Invalid request"))); }
  @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class) ResponseEntity<?> duplicate() { return ResponseEntity.status(409).body(Map.of("error","That record already exists")); }
  @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class) ResponseEntity<?> integrity() { return ResponseEntity.status(409).body(Map.of("error","The change conflicts with existing records")); }
  @ExceptionHandler(jakarta.validation.ConstraintViolationException.class) ResponseEntity<?> constraint(jakarta.validation.ConstraintViolationException e) { return ResponseEntity.badRequest().body(Map.of("error",e.getConstraintViolations().stream().findFirst().map(v->v.getMessage()).orElse("Invalid request"))); }
  @ExceptionHandler(org.springframework.web.client.RestClientException.class) ResponseEntity<?> provider() { return ResponseEntity.status(503).body(Map.of("error","Market data is temporarily unavailable")); }
  @ExceptionHandler(java.util.NoSuchElementException.class) ResponseEntity<?> missing(java.util.NoSuchElementException e) { return ResponseEntity.status(404).body(Map.of("error",e.getMessage())); }
}
