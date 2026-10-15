package com.billyelcalvo.tracking.location;

/**
 * Gets every accepted ping right away, before it hits the database. The live tracking fan-out plugs
 * in here, so customers don't wait for the next flush to see the courier move.
 */
public interface LocationListener {

  void onLocation(LocationPing ping);
}
