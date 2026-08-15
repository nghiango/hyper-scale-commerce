# Phase 2 — Catalog Profile Report

Environment: local Docker PostgreSQL, JDK 21, Spring Boot, 1,000 seeded products.

## EXPLAIN ANALYZE

### findById
```
Index Scan using products_pkey on products  (cost=0.27..8.29 rows=1 width=1210) (actual time=0.011..0.011 rows=1 loops=1)
  Index Cond: (id = 1)
  Buffers: shared hit=6
Planning:
  Buffers: shared hit=5
Planning Time: 0.038 ms
Execution Time: 0.017 ms
```

### findBySku
```
Index Scan using idx_products_sku on products  (cost=0.27..8.29 rows=1 width=1210) (actual time=0.004..0.004 rows=1 loops=1)
  Index Cond: ((sku)::text = 'PERF-SKU-00001'::text)
  Buffers: shared hit=3
Planning:
  Buffers: shared hit=8
Planning Time: 0.018 ms
Execution Time: 0.007 ms
```

### search
```
Limit  (cost=21.81..21.82 rows=1 width=1210) (actual time=0.394..0.395 rows=20 loops=1)
  Buffers: shared hit=23
  ->  Sort  (cost=21.81..21.82 rows=1 width=1210) (actual time=0.393..0.394 rows=20 loops=1)
        Sort Key: id
        Sort Method: top-N heapsort  Memory: 30kB
        Buffers: shared hit=23
        ->  Seq Scan on products  (cost=0.00..21.80 rows=1 width=1210) (actual time=0.031..0.354 rows=1000 loops=1)
              Filter: (((name)::text ~~* '%Product%'::text) OR ((sku)::text ~~* '%Product%'::text))
              Buffers: shared hit=20
Planning:
  Buffers: shared hit=14
Planning Time: 0.030 ms
Execution Time: 0.399 ms
```

### count
```
Aggregate  (cost=21.80..21.81 rows=1 width=8) (actual time=0.331..0.331 rows=1 loops=1)
  Buffers: shared hit=20
  ->  Seq Scan on products  (cost=0.00..21.80 rows=1 width=0) (actual time=0.003..0.304 rows=1000 loops=1)
        Filter: (((name)::text ~~* '%Product%'::text) OR ((sku)::text ~~* '%Product%'::text))
        Buffers: shared hit=20
Planning Time: 0.017 ms
Execution Time: 0.336 ms
```

## Micrometer HTTP timings (sample)
- `http_server_requests_seconds_count{error="none",exception="none",method="GET",outcome="SUCCESS",status="200",uri="/catalog/products"} 40`
- `http_server_requests_seconds_sum{error="none",exception="none",method="GET",outcome="SUCCESS",status="200",uri="/catalog/products"} 0.046545788`
- `http_server_requests_seconds_count{error="none",exception="none",method="GET",outcome="SUCCESS",status="200",uri="/catalog/products/{id}"} 20`
- `http_server_requests_seconds_sum{error="none",exception="none",method="GET",outcome="SUCCESS",status="200",uri="/catalog/products/{id}"} 0.007855626`
- `http_server_requests_seconds_count{error="none",exception="none",method="GET",outcome="SUCCESS",status="200",uri="/catalog/products/{id}/availability"} 20`
- `http_server_requests_seconds_sum{error="none",exception="none",method="GET",outcome="SUCCESS",status="200",uri="/catalog/products/{id}/availability"} 0.007037376`
- `http_server_requests_seconds_max{error="none",exception="none",method="GET",outcome="SUCCESS",status="200",uri="/catalog/products"} 0.002810208`
- `http_server_requests_seconds_max{error="none",exception="none",method="GET",outcome="SUCCESS",status="200",uri="/catalog/products/{id}"} 0.001782958`
- `http_server_requests_seconds_max{error="none",exception="none",method="GET",outcome="SUCCESS",status="200",uri="/catalog/products/{id}/availability"} 7.64708E-4`

## JVM metrics (sample)
- `jvm_memory_used_bytes{area="heap",id="G1 Eden Space"} 5.0331648E7`
- `jvm_memory_used_bytes{area="heap",id="G1 Old Gen"} 1.17334232E8`
- `jvm_memory_used_bytes{area="heap",id="G1 Survivor Space"} 9781440.0`

## Hikari pool metrics (sample)
- `hikaricp_connections_acquire_seconds_count{pool="HikariPool-10"} 128`
- `hikaricp_connections_acquire_seconds_sum{pool="HikariPool-10"} 0.001`
- `hikaricp_connections_acquire_seconds_max{pool="HikariPool-10"} 0.001`

## Bottleneck analysis
The search and count queries use a sequential scan on catalog.products because ILIKE '%...%' cannot use the existing B-tree indexes. This is the primary bottleneck and will degrade as the catalog grows.
