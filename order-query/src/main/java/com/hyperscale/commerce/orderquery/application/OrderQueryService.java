package com.hyperscale.commerce.orderquery.application;

import static com.hyperscale.commerce.orderquery.jooq.order_query.Tables.ORDER_READ_MODEL;

import com.hyperscale.commerce.orderquery.application.cache.CacheInvalidationPublisher;
import com.hyperscale.commerce.orderquery.application.cache.L2CacheStore;
import com.hyperscale.commerce.orderquery.application.cache.NearCache;
import com.hyperscale.commerce.orderquery.domain.OrderNotFoundException;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class OrderQueryService {
  public static final int MIN_PAGE = 0;
  public static final int MIN_SIZE = 1;
  public static final int MAX_SIZE = 100;
  public static final long ORDER_CACHE_MAX_SIZE = 50_000L;
  public static final long ORDER_CACHE_TTL_SECONDS = 30L;
  public static final long ORDER_CACHE_L2_TTL_MINUTES = 10L;
  public static final String ORDER_CACHE_NAME = "order_query";
  public static final TypeReference<List<OrderItemDto>> ORDER_ITEMS_TYPE =
      new TypeReference<List<OrderItemDto>>() {};

  private final DSLContext dsl;
  private final ObjectMapper objectMapper;
  private final L2CacheStore l2CacheStore;
  private final CacheInvalidationPublisher invalidationPublisher;
  private final NearCache<Long, OrderDto> orderCache;

  @Autowired
  public OrderQueryService(
      DSLContext dsl,
      ObjectMapper objectMapper,
      @Autowired(required = false) @Nullable MeterRegistry meterRegistry,
      L2CacheStore l2CacheStore,
      CacheInvalidationPublisher invalidationPublisher) {
    this.dsl = dsl;
    this.objectMapper = objectMapper;
    this.l2CacheStore = l2CacheStore;
    this.invalidationPublisher = invalidationPublisher;
    this.orderCache =
        new NearCache<>(
            ORDER_CACHE_NAME,
            ORDER_CACHE_MAX_SIZE,
            Duration.ofSeconds(ORDER_CACHE_TTL_SECONDS),
            Duration.ofMinutes(ORDER_CACHE_L2_TTL_MINUTES),
            l2CacheStore,
            OrderDto.class,
            objectMapper,
            meterRegistry);
  }

  public OrderQueryService(
      DSLContext dsl,
      ObjectMapper objectMapper,
      MeterRegistry meterRegistry,
      L2CacheStore l2CacheStore) {
    this(dsl, objectMapper, meterRegistry, l2CacheStore, (cacheName, key) -> {});
  }

  public OrderQueryService(DSLContext dsl, ObjectMapper objectMapper, L2CacheStore l2CacheStore) {
    this(dsl, objectMapper, null, l2CacheStore);
  }

  @Transactional(readOnly = true)
  public OrderDto getOrder(long id) {
    OrderDto cached =
        orderCache.get(
            id,
            () -> {
              Record row =
                  dsl.select(
                          ORDER_READ_MODEL.ORDER_ID,
                          ORDER_READ_MODEL.STATUS,
                          ORDER_READ_MODEL.ITEMS,
                          ORDER_READ_MODEL.CREATED_AT)
                      .from(ORDER_READ_MODEL)
                      .where(ORDER_READ_MODEL.ORDER_ID.eq(id))
                      .fetchOne();
              return row != null ? toDto(row) : null;
            });

    if (cached == null) {
      throw new OrderNotFoundException("Order with id " + id + " not found");
    }
    return cached;
  }

  public void evictOrder(long id) {
    orderCache.evict(id);
    invalidationPublisher.publish(ORDER_CACHE_NAME, Long.toString(id));
  }

  public void evictAll() {
    orderCache.evictAll();
    invalidationPublisher.publish(ORDER_CACHE_NAME, null);
  }

  public void evictLocal(long id) {
    orderCache.evictLocal(id);
  }

  public void evictAllLocal() {
    orderCache.evictAllLocal();
  }

  @Transactional(readOnly = true)
  public PagedOrdersDto listOrders(int page, int size) {
    if (page < MIN_PAGE) {
      throw new IllegalArgumentException("Page must not be negative");
    }
    if (size < MIN_SIZE || size > MAX_SIZE) {
      throw new IllegalArgumentException("Size must be between " + MIN_SIZE + " and " + MAX_SIZE);
    }
    Long totalValue = dsl.selectCount().from(ORDER_READ_MODEL).fetchOne(0, Long.class);
    long total = totalValue != null ? totalValue : 0L;
    List<OrderDto> orders =
        dsl.select(
                ORDER_READ_MODEL.ORDER_ID,
                ORDER_READ_MODEL.STATUS,
                ORDER_READ_MODEL.ITEMS,
                ORDER_READ_MODEL.CREATED_AT)
            .from(ORDER_READ_MODEL)
            .orderBy(ORDER_READ_MODEL.CREATED_AT.desc(), ORDER_READ_MODEL.ORDER_ID.desc())
            .limit(size)
            .offset(page * size)
            .fetch(record -> toDto(record));
    return new PagedOrdersDto(total, orders);
  }

  private OrderDto toDto(Record record) {
    List<OrderItemDto> items =
        objectMapper.readValue(record.getValue(ORDER_READ_MODEL.ITEMS).data(), ORDER_ITEMS_TYPE);
    return new OrderDto(
        record.getValue(ORDER_READ_MODEL.ORDER_ID),
        record.getValue(ORDER_READ_MODEL.STATUS),
        items,
        record.getValue(ORDER_READ_MODEL.CREATED_AT).toInstant());
  }
}
