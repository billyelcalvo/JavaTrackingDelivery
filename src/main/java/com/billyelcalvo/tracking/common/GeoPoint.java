package com.billyelcalvo.tracking.common;

/** A WGS84 coordinate. Construction fails fast so an invalid point never reaches the database. */
public record GeoPoint(double lat, double lng) {

  private static final double EARTH_RADIUS_METERS = 6_371_008.8;

  public GeoPoint {
    if (Double.isNaN(lat) || lat < -90 || lat > 90) {
      throw new IllegalArgumentException("lat must be between -90 and 90");
    }
    if (Double.isNaN(lng) || lng < -180 || lng > 180) {
      throw new IllegalArgumentException("lng must be between -180 and 180");
    }
  }

  /** Haversine distance. Good enough for sanity checks; real searches go through PostGIS. */
  public double distanceMetersTo(GeoPoint other) {
    double dLat = Math.toRadians(other.lat - lat);
    double dLng = Math.toRadians(other.lng - lng);
    double a =
        Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat))
                * Math.cos(Math.toRadians(other.lat))
                * Math.sin(dLng / 2)
                * Math.sin(dLng / 2);
    return 2 * EARTH_RADIUS_METERS * Math.asin(Math.sqrt(a));
  }
}
