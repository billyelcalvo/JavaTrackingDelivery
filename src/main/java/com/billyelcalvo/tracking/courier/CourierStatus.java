package com.billyelcalvo.tracking.courier;

public enum CourierStatus {
  /** Not working, never picked for an order. */
  OFFLINE,
  /** Working and free, eligible for assignment. */
  AVAILABLE,
  /** Carrying an order. Only the system moves a courier in or out of this state. */
  BUSY
}
