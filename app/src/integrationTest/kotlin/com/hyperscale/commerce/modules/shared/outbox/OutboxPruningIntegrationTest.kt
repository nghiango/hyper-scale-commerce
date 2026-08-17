package com.hyperscale.commerce.modules.shared.outbox

import com.hyperscale.commerce.jooq.order.Tables.OUTBOX_EVENTS
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class OutboxPruningIntegrationTest
@Autowired
constructor(
    private val outboxRepository: OutboxRepository,
    private val outboxPruningService: OutboxPruningService,
    private val dsl: DSLContext,
) {

  companion object {
    @Container
    @JvmStatic
    @ServiceConnection
    val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")
  }

  @BeforeEach
  fun clean() {
    dsl.deleteFrom(OUTBOX_EVENTS).execute()
  }

  @Test
  fun `prunes only published outbox events older than retention threshold`() {
    val now = Instant.now()
    val tenDaysAgo = now.minus(Duration.ofDays(10))
    val oneDayAgo = now.minus(Duration.ofDays(1))

    // 1. Old and published -> SHOULD BE PRUNED
    val idOldPublished = outboxRepository.insert("agg-1", "OrderPlaced", "{}")
    dsl.update(OUTBOX_EVENTS)
        .set(OUTBOX_EVENTS.PUBLISHED_AT, tenDaysAgo.atOffset(ZoneOffset.UTC))
        .where(OUTBOX_EVENTS.ID.eq(idOldPublished))
        .execute()

    // 2. Old but UNPUBLISHED -> MUST NOT BE PRUNED
    val idOldUnpublished = outboxRepository.insert("agg-2", "OrderPlaced", "{}")

    // 3. Recent and published -> MUST NOT BE PRUNED
    val idRecentPublished = outboxRepository.insert("agg-3", "OrderPlaced", "{}")
    dsl.update(OUTBOX_EVENTS)
        .set(OUTBOX_EVENTS.PUBLISHED_AT, oneDayAgo.atOffset(ZoneOffset.UTC))
        .where(OUTBOX_EVENTS.ID.eq(idRecentPublished))
        .execute()

    val prunedCount = outboxPruningService.pruneOutbox()
    assertThat(prunedCount).isEqualTo(1)

    val remainingIds = dsl.select(OUTBOX_EVENTS.ID).from(OUTBOX_EVENTS).fetch(OUTBOX_EVENTS.ID)
    assertThat(remainingIds).containsExactlyInAnyOrder(idOldUnpublished, idRecentPublished)
  }
}
