package com.billyelcalvo.tracking.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class GeoPointTest {

  @ParameterizedTest
  @CsvSource({"90.0001,0", "-90.0001,0", "0,180.0001", "0,-180.0001", "NaN,0", "0,NaN"})
  void rejectsCoordinatesOutsideWgs84(double lat, double lng) {
    assertThatThrownBy(() -> new GeoPoint(lat, lng)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void acceptsTheEdges() {
    assertThat(new GeoPoint(90, 180)).isNotNull();
    assertThat(new GeoPoint(-90, -180)).isNotNull();
  }

  @Test
  void distanceMatchesAKnownValue() {
    // Plaza de Armas de Lima -> Parque Kennedy (Miraflores), roughly 8.4 km in a straight line
    GeoPoint plaza = new GeoPoint(-12.0464, -77.0300);
    GeoPoint kennedy = new GeoPoint(-12.1211, -77.0297);

    assertThat(plaza.distanceMetersTo(kennedy)).isBetween(8_200.0, 8_500.0);
    assertThat(plaza.distanceMetersTo(plaza)).isZero();
  }
}
