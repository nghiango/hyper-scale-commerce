package com.hyperscale.commerce.modules.catalog.performance;

public record LoadResult(
    String name,
    int totalRequests,
    int successfulRequests,
    int failedRequests,
    double p50,
    double p95,
    double p99,
    double throughputRps,
    double errorRate) {}
