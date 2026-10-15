package com.billyelcalvo.tracking.courier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.billyelcalvo.tracking.common.ConflictException;
import com.billyelcalvo.tracking.common.NotFoundException;
import com.billyelcalvo.tracking.security.CourierTokenService;
import com.billyelcalvo.tracking.security.CourierTokenService.IssuedToken;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CourierServiceTest {

  private final CourierRepository repository = mock(CourierRepository.class);
  private final CourierTokenService tokens = mock(CourierTokenService.class);
  private final CourierService service = new CourierService(repository, tokens);
  private final UUID id = UUID.randomUUID();

  private Courier courier(CourierStatus status) {
    return new Courier(id, "Ana", status, Instant.EPOCH);
  }

  @Test
  void registerTrimsTheNameAndIssuesAToken() {
    when(repository.insert("Ana")).thenReturn(courier(CourierStatus.OFFLINE));
    when(tokens.issue(id)).thenReturn(new IssuedToken("jwt", Instant.EPOCH));

    var registered = service.register("  Ana ");

    assertThat(registered.courier().id()).isEqualTo(id);
    assertThat(registered.token().token()).isEqualTo("jwt");
  }

  @Test
  void goingAvailableFromOfflineUpdatesTheStatus() {
    when(repository.findById(id))
        .thenReturn(Optional.of(courier(CourierStatus.OFFLINE)))
        .thenReturn(Optional.of(courier(CourierStatus.AVAILABLE)));
    when(repository.updateStatus(id, CourierStatus.OFFLINE, CourierStatus.AVAILABLE))
        .thenReturn(true);

    assertThat(service.changeAvailability(id, CourierStatus.AVAILABLE).status())
        .isEqualTo(CourierStatus.AVAILABLE);
  }

  @Test
  void sameStatusIsANoOp() {
    when(repository.findById(id)).thenReturn(Optional.of(courier(CourierStatus.AVAILABLE)));

    service.changeAvailability(id, CourierStatus.AVAILABLE);

    verify(repository, never()).updateStatus(any(), any(), any());
  }

  @Test
  void aBusyCourierCannotGoOffline() {
    when(repository.findById(id)).thenReturn(Optional.of(courier(CourierStatus.BUSY)));

    assertThatThrownBy(() -> service.changeAvailability(id, CourierStatus.OFFLINE))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void couriersCannotMakeThemselvesBusy() {
    assertThatThrownBy(() -> service.changeAvailability(id, CourierStatus.BUSY))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void lostRaceIsReportedAsConflict() {
    when(repository.findById(id)).thenReturn(Optional.of(courier(CourierStatus.AVAILABLE)));
    when(repository.updateStatus(id, CourierStatus.AVAILABLE, CourierStatus.OFFLINE))
        .thenReturn(false);

    assertThatThrownBy(() -> service.changeAvailability(id, CourierStatus.OFFLINE))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void unknownCourierIsNotFound() {
    when(repository.findById(id)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.get(id)).isInstanceOf(NotFoundException.class);
  }
}
