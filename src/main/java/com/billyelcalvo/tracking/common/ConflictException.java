package com.billyelcalvo.tracking.common;

/** The request is valid but clashes with the current state (e.g. delivering a pending order). */
public class ConflictException extends RuntimeException {

  public ConflictException(String message) {
    super(message);
  }
}
