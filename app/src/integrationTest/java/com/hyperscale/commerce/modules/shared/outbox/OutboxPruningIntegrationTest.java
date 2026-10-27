package com.hyperscale.commerce.modules.shared.outbox;

import static com.hyperscale.commerce.jooq.order.Tables.OUTBOX_EVENTS;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
public class OutboxPruningIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"));

  private final OutboxRepository outboxRepository;
  private final OutboxPruningService outboxPruningService;
  private final DSLContext dsl;

  @Autowired
  public OutboxPruningIntegrationTest(
      OutboxRepository outboxRepository,
      OutboxPruningService outboxPruningService,
      DSLContext dsl) {
    this.outboxRepository = outboxRepository;
    this.outboxPruningService = outboxPruningService;
    this.dsl = dsl;
  }

  @BeforeEach
  void clean() {
    dsl.deleteFrom(OUTBOX_EVENTS).execute();
  }

  @Test
  @DisplayName("prunes only published outbox events older than retention threshold")
  void prunesOnlyPublishedOutboxEventsOlderThanRetentionThreshold() {
    Instant now = Instant.now();
    Instant tenDaysAgo = now.minus(Duration.ofDays(10));
    Instant oneDayAgo = now.minus(Duration.ofDays(1));

    // 1. Old and published -> SHOULD BE PRUNED
    long idOldPublished = outboxRepository.insert("agg-1", "OrderPlaced", "{}");
    dsl.update(OUTBOX_EVENTS)
        .set(OUTBOX_EVENTS.PUBLISHED_AT, tenDaysAgo.atOffset(ZoneOffset.UTC))
        .where(OUTBOX_EVENTS.ID.eq(idOldPublished))
        .execute();

    // 2. Old but UNPUBLISHED -> MUST NOT BE PRUNED
    long idOldUnpublished = outboxRepository.insert("agg-2", "OrderPlaced", "{}");

    // 3. Recent and published -> MUST NOT BE PRUNED
    long idRecentPublished = outboxRepository.insert("agg-3", "OrderPlaced", "{}");
    dsl.update(OUTBOX_EVENTS)
        .set(OUTBOX_EVENTS.PUBLISHED_AT, oneDayAgo.atOffset(ZoneOffset.UTC))
        .where(OUTBOX_EVENTS.ID.eq(idRecentPublished))
        .execute();

    int prunedCount = outboxPruningService.pruneOutbox();
    assertThat(prunedCount).isEqualTo(1);

    List<Long> remainingIds =
        dsl.select(OUTBOX_EVENTS.ID).from(OUTBOX_EVENTS).fetch(OUTBOX_EVENTS.ID);
    assertThat(remainingIds).containsExactlyInAnyOrder(idOldUnpublished, idRecentPublished);
  }
}
