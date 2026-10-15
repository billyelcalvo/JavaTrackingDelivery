package com.billyelcalvo.tracking.realtime;

/**
 * Wire format a courier sends on /ws/couriers: {@code
 * {"lat":-12.05,"lng":-77.04,"ts":1734000000000}}. {@code ts} is optional epoch millis from the
 * device; when missing we use the server time.
 */
public record CourierPingMessage(Double lat, Double lng, Long ts) {}
