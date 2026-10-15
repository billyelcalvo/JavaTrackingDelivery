package com.billyelcalvo.tracking.assignment;

import com.billyelcalvo.tracking.config.TrackingProperties;
import com.billyelcalvo.tracking.order.DeliveryOrder;
import com.billyelcalvo.tracking.order.OrderRepository;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Orders created when nobody was nearby stay PENDING. This job retries them in creation order every
 * few seconds, which also covers couriers that just finished a delivery or came online.
 */
@Component
public class PendingOrderRetryJob implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(PendingOrderRetryJob.class);

  private final OrderRepository orders;
  private final CourierAssigner assigner;
  private final TransactionTemplate tx;
  private final TaskScheduler scheduler;
  private final TrackingProperties.Assignment config;
  private volatile ScheduledFuture<?> task;

  public PendingOrderRetryJob(
      OrderRepository orders,
      CourierAssigner assigner,
      TransactionTemplate tx,
      TaskScheduler scheduler,
      TrackingProperties properties) {
    this.orders = orders;
    this.assigner = assigner;
    this.tx = tx;
    this.scheduler = scheduler;
    this.config = properties.assignment();
  }

  /**
   * @return how many orders got a courier in this round
   */
  public int runOnce() {
    Integer count =
        tx.execute(
            status -> {
              List<DeliveryOrder> pending = orders.lockPendingBatch(config.retryBatchSize());
              int assignedNow = 0;
              for (DeliveryOrder order : pending) {
                if (assigner.tryAssign(order).courierId() != null) {
                  assignedNow++;
                }
              }
              return assignedNow;
            });
    int assigned = count == null ? 0 : count;
    if (assigned > 0) {
      log.info("Pending orders assigned on retry count={}", assigned);
    }
    return assigned;
  }

  @Override
  public void start() {
    task = scheduler.scheduleWithFixedDelay(this::runSafely, config.retryInterval());
  }

  @Override
  public void stop() {
    if (task != null) {
      task.cancel(false);
      task = null;
    }
  }

  @Override
  public boolean isRunning() {
    return task != null;
  }

  private void runSafely() {
    try {
      runOnce();
    } catch (RuntimeException ex) {
      log.error("Pending order retry failed, will try again next round", ex);
    }
  }
}
