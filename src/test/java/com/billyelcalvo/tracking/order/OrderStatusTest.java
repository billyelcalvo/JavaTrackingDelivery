package com.billyelcalvo.tracking.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.billyelcalvo.tracking.common.ConflictException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OrderStatusTest {

  @ParameterizedTest
  @CsvSource({
    "PENDING,ASSIGNED,true",
    "PENDING,CANCELLED,true",
    "PENDING,DELIVERED,false",
    "ASSIGNED,PICKED_UP,true",
    "ASSIGNED,CANCELLED,true",
    "ASSIGNED,DELIVERED,false",
    "PICKED_UP,DELIVERED,true",
    "PICKED_UP,CANCELLED,false",
    "DELIVERED,CANCELLED,false",
    "CANCELLED,PENDING,false"
  })
  void allowsOnlyTheDocumentedTransitions(OrderStatus from, OrderStatus to, boolean allowed) {
    assertThat(from.canTransitionTo(to)).isEqualTo(allowed);
  }

  @Test
  void ensureThrowsConflictOnIllegalMove() {
    assertThatThrownBy(() -> OrderStatus.DELIVERED.ensureCanTransitionTo(OrderStatus.PENDING))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("DELIVERED")
        .hasMessageContaining("PENDING");
  }

  @Test
  void activeAndTerminalFlags() {
    assertThat(OrderStatus.ASSIGNED.isActive()).isTrue();
    assertThat(OrderStatus.PICKED_UP.isActive()).isTrue();
    assertThat(OrderStatus.PENDING.isActive()).isFalse();
    assertThat(OrderStatus.DELIVERED.isTerminal()).isTrue();
    assertThat(OrderStatus.CANCELLED.isTerminal()).isTrue();
    assertThat(OrderStatus.PICKED_UP.isTerminal()).isFalse();
  }
}
