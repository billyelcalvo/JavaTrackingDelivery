package com.billyelcalvo.tracking.common;

import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Maps domain exceptions to RFC 7807 problem responses so clients get a consistent shape. */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(NotFoundException.class)
  ProblemDetail notFound(NotFoundException ex) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
  }

  @ExceptionHandler(ConflictException.class)
  ProblemDetail conflict(ConflictException ex) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
  }

  @ExceptionHandler(ForbiddenException.class)
  ProblemDetail forbidden(ForbiddenException ex) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ProblemDetail invalidBody(MethodArgumentNotValidException ex) {
    String detail =
        ex.getBindingResult().getFieldErrors().stream()
            .map(e -> e.getField() + " " + e.getDefaultMessage())
            .sorted()
            .collect(Collectors.joining("; "));
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
  }

  @ExceptionHandler(HandlerMethodValidationException.class)
  ProblemDetail invalidParams(HandlerMethodValidationException ex) {
    String detail =
        ex.getAllErrors().stream()
            .map(e -> e.getDefaultMessage())
            .sorted()
            .collect(Collectors.joining("; "));
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ProblemDetail illegalArgument(IllegalArgumentException ex) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class
  })
  ProblemDetail badRequest(Exception ex) {
    // Don't echo parser internals back to the client, they leak class names
    log.debug("Rejected malformed request", ex);
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Malformed request");
  }
}
