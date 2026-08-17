# Phase 2 — Catalog Profile Report

Environment: local Docker PostgreSQL, JDK 21, Spring Boot, 1,000 seeded products.

## EXPLAIN ANALYZE

### findById
```
Index Scan using products_pkey on products  (cost=0.27..8.29 rows=1 width=1210) (actual time=0.038..0.038 rows=1 loops=1)
  Index Cond: (id = 1)
  Buffers: shared hit=6
Planning:
  Buffers: shared hit=6
Planning Time: 0.052 ms
Execution Time: 0.046 ms
```

### findBySku
```
Index Scan using idx_products_sku on products  (cost=0.27..8.29 rows=1 width=1210) (actual time=0.005..0.005 rows=1 loops=1)
  Index Cond: ((sku)::text = 'PERF-SKU-00001'::text)
  Buffers: shared hit=3
Planning:
  Buffers: shared hit=8
Planning Time: 0.035 ms
Execution Time: 0.010 ms
```

### search
```
Limit  (cost=21.81..21.82 rows=1 width=1210) (actual time=0.423..0.424 rows=20 loops=1)
  Buffers: shared hit=23
  ->  Sort  (cost=21.81..21.82 rows=1 width=1210) (actual time=0.423..0.423 rows=20 loops=1)
        Sort Key: id
        Sort Method: top-N heapsort  Memory: 30kB
        Buffers: shared hit=23
        ->  Seq Scan on products  (cost=0.00..21.80 rows=1 width=1210) (actual time=0.041..0.378 rows=1000 loops=1)
              Filter: (((name)::text ~~* '%Product%'::text) OR ((sku)::text ~~* '%Product%'::text))
              Buffers: shared hit=20
Planning:
  Buffers: shared hit=20
Planning Time: 0.064 ms
Execution Time: 0.430 ms
```

### count
```
Aggregate  (cost=21.80..21.81 rows=1 width=8) (actual time=0.358..0.358 rows=1 loops=1)
  Buffers: shared hit=20
  ->  Seq Scan on products  (cost=0.00..21.80 rows=1 width=0) (actual time=0.005..0.332 rows=1000 loops=1)
        Filter: (((name)::text ~~* '%Product%'::text) OR ((sku)::text ~~* '%Product%'::text))
        Buffers: shared hit=20
Planning Time: 0.021 ms
Execution Time: 0.364 ms
```

## Micrometer HTTP timings (sample)
- `http_server_requests_seconds{error="none",exception="none",method="GET",outcome="SUCCESS",status="200",uri="/catalog/products",quantile="0.95"} 8.17152E-4`
- `http_server_requests_seconds_count{error="none",exception="none",method="GET",outcome="SUCCESS",status="200",uri="/catalog/products"} 40`
- `http_server_requests_seconds_sum{error="none",exception="none",method="GET",outcome="SUCCESS",status="200",uri="/catalog/products"} 0.01993896`
- `http_server_requests_seconds{error="none",exception="none",method="GET",outcome="SUCCESS",status="200",uri="/catalog/products/{id}",quantile="0.95"} 0.002981888`
- `http_server_requests_seconds_count{error="none",exception="none",method="GET",outcome="SUCCESS",status="200",uri="/catalog/products/{id}"} 20`
- `http_server_requests_seconds_sum{error="none",exception="none",method="GET",outcome="SUCCESS",status="200",uri="/catalog/products/{id}"} 0.033515251`
- `http_server_requests_seconds{error="none",exception="none",method="GET",outcome="SUCCESS",status="200",uri="/catalog/products/{id}/availability",quantile="0.95"} 0.002195456`
- `http_server_requests_seconds_count{error="none",exception="none",method="GET",outcome="SUCCESS",status="200",uri="/catalog/products/{id}/availability"} 20`
- `http_server_requests_seconds_sum{error="none",exception="none",method="GET",outcome="SUCCESS",status="200",uri="/catalog/products/{id}/availability"} 0.030937082`
- `http_server_requests_seconds_max{error="none",exception="none",method="GET",outcome="SUCCESS",status="200",uri="/catalog/products"} 0.009943167`

## JVM metrics (sample)
- `jvm_memory_used_bytes{area="heap",id="G1 Eden Space"} 1.17440512E8`
- `jvm_memory_used_bytes{area="heap",id="G1 Old Gen"} 1.84844264E8`
- `jvm_memory_used_bytes{area="heap",id="G1 Survivor Space"} 9674880.0`

## Hikari pool metrics (sample)
- `hikaricp_connections_acquire_seconds_count{pool="hyperscale-primary"} 51`
- `hikaricp_connections_acquire_seconds_sum{pool="hyperscale-primary"} 0.0`
- `hikaricp_connections_acquire_seconds_count{pool="hyperscale-replica"} 1`

## Bottleneck analysis
The search and count queries use a sequential scan on catalog.products because ILIKE '%...%' cannot use the existing B-tree indexes. This is the primary bottleneck and will degrade as the catalog grows.
