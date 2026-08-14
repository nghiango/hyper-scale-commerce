# Phase 2 — Catalog Profile Report

Environment: local Docker PostgreSQL, JDK 21, Spring Boot, 1,000 seeded products.

## EXPLAIN ANALYZE

### findById
```
Index Scan using products_pkey on products  (cost=0.27..8.29 rows=1 width=1210) (actual time=0.014..0.014 rows=1 loops=1)
  Index Cond: (id = 1)
  Buffers: shared hit=6
Planning:
  Buffers: shared hit=5
Planning Time: 0.045 ms
Execution Time: 0.020 ms
```

### findBySku
```
Index Scan using idx_products_sku on products  (cost=0.27..8.29 rows=1 width=1210) (actual time=0.005..0.005 rows=1 loops=1)
  Index Cond: ((sku)::text = 'PERF-SKU-00001'::text)
  Buffers: shared hit=3
Planning:
  Buffers: shared hit=8
Planning Time: 0.033 ms
Execution Time: 0.011 ms
```

### search
```
Limit  (cost=21.81..21.82 rows=1 width=1210) (actual time=0.432..0.434 rows=20 loops=1)
  Buffers: shared hit=23
  ->  Sort  (cost=21.81..21.82 rows=1 width=1210) (actual time=0.432..0.432 rows=20 loops=1)
        Sort Key: id
        Sort Method: top-N heapsort  Memory: 30kB
        Buffers: shared hit=23
        ->  Seq Scan on products  (cost=0.00..21.80 rows=1 width=1210) (actual time=0.045..0.390 rows=1000 loops=1)
              Filter: (((name)::text ~~* '%Product%'::text) OR ((sku)::text ~~* '%Product%'::text))
              Buffers: shared hit=20
Planning:
  Buffers: shared hit=14
Planning Time: 0.042 ms
Execution Time: 0.439 ms
```

### count
```
Aggregate  (cost=21.80..21.81 rows=1 width=8) (actual time=0.373..0.373 rows=1 loops=1)
  Buffers: shared hit=20
  ->  Seq Scan on products  (cost=0.00..21.80 rows=1 width=0) (actual time=0.004..0.343 rows=1000 loops=1)
        Filter: (((name)::text ~~* '%Product%'::text) OR ((sku)::text ~~* '%Product%'::text))
        Buffers: shared hit=20
Planning Time: 0.022 ms
Execution Time: 0.379 ms
```

## Micrometer HTTP timings (sample)
- `http_server_requests_seconds_count{error="none",exception="none",method="GET",outcome="SUCCESS",status="200",uri="/catalog/products"} 40`
- `http_server_requests_seconds_sum{error="none",exception="none",method="GET",outcome="SUCCESS",status="200",uri="/catalog/products"} 0.088544459`
- `http_server_requests_seconds_count{error="none",exception="none",method="GET",outcome="SUCCESS",status="200",uri="/catalog/products/{id}"} 20`
- `http_server_requests_seconds_sum{error="none",exception="none",method="GET",outcome="SUCCESS",status="200",uri="/catalog/products/{id}"} 0.016251414`
- `http_server_requests_seconds_count{error="none",exception="none",method="GET",outcome="SUCCESS",status="200",uri="/catalog/products/{id}/availability"} 20`
- `http_server_requests_seconds_sum{error="none",exception="none",method="GET",outcome="SUCCESS",status="200",uri="/catalog/products/{id}/availability"} 0.015226831`
- `http_server_requests_seconds_max{error="none",exception="none",method="GET",outcome="SUCCESS",status="200",uri="/catalog/products"} 0.009414334`
- `http_server_requests_seconds_max{error="none",exception="none",method="GET",outcome="SUCCESS",status="200",uri="/catalog/products/{id}"} 0.002074416`
- `http_server_requests_seconds_max{error="none",exception="none",method="GET",outcome="SUCCESS",status="200",uri="/catalog/products/{id}/availability"} 0.0011435`

## JVM metrics (sample)
- `jvm_memory_used_bytes{area="heap",id="G1 Eden Space"} 4.0894464E7`
- `jvm_memory_used_bytes{area="heap",id="G1 Old Gen"} 1.17500368E8`
- `jvm_memory_used_bytes{area="heap",id="G1 Survivor Space"} 9437184.0`

## Hikari pool metrics (sample)
- `hikaricp_connections_acquire_seconds_count{pool="HikariPool-10"} 128`
- `hikaricp_connections_acquire_seconds_sum{pool="HikariPool-10"} 0.001`
- `hikaricp_connections_acquire_seconds_max{pool="HikariPool-10"} 0.001`

## Bottleneck analysis
The search and count queries use a sequential scan on catalog.products because ILIKE '%...%' cannot use the existing B-tree indexes. This is the primary bottleneck and will degrade as the catalog grows.
