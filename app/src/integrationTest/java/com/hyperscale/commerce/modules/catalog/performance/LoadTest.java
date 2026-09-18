package com.hyperscale.commerce.modules.catalog.performance;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public class LoadTest {

  private static final double NANOS_PER_MILLISECOND = 1_000_000.0;
  private static final int HTTP_OK = 200;
  private static final int REQUEST_FAILED = -1;
  private static final double PERCENTILE_50 = 0.50;
  private static final double PERCENTILE_95 = 0.95;
  private static final double PERCENTILE_99 = 0.99;

  private final HttpClient httpClient;
  private final String name;
  private final Supplier<HttpRequest> requestFactory;

  public LoadTest(HttpClient httpClient, String name, Supplier<HttpRequest> requestFactory) {
    this.httpClient = httpClient;
    this.name = name;
    this.requestFactory = requestFactory;
  }

  public LoadResult run(int concurrentUsers, int rampUpSeconds, int durationSeconds)
      throws InterruptedException, ExecutionException {
    ExecutorService executor = Executors.newFixedThreadPool(concurrentUsers);
    int totalDurationSeconds = rampUpSeconds + durationSeconds;
    long testEnd = System.nanoTime() + TimeUnit.SECONDS.toNanos(totalDurationSeconds);
    long rampUpNanos = TimeUnit.SECONDS.toNanos(rampUpSeconds);
    long delayPerWorker = concurrentUsers > 1 ? rampUpNanos / (concurrentUsers - 1) : 0L;

    List<Callable<List<LoadSample>>> callables = new ArrayList<>();
    for (int workerId = 0; workerId < concurrentUsers; workerId++) {
      int wid = workerId;
      callables.add(
          () -> {
            long workerStartDelay = wid * delayPerWorker;
            if (workerStartDelay > 0) {
              TimeUnit.NANOSECONDS.sleep(workerStartDelay);
            }

            List<LoadSample> samples = new ArrayList<>();
            while (System.nanoTime() < testEnd) {
              HttpRequest request = requestFactory.get();
              long start = System.nanoTime();
              int statusCode;
              try {
                statusCode =
                    httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
              } catch (Exception e) {
                statusCode = REQUEST_FAILED;
              }
              long end = System.nanoTime();
              double latencyMs = (end - start) / NANOS_PER_MILLISECOND;
              samples.add(new LoadSample(statusCode, latencyMs));
            }

            return samples;
          });
    }

    List<Future<List<LoadSample>>> futures = executor.invokeAll(callables);
    List<LoadSample> allSamples = new ArrayList<>();
    for (Future<List<LoadSample>> future : futures) {
      allSamples.addAll(future.get());
    }

    executor.shutdown();
    executor.awaitTermination(1, TimeUnit.MINUTES);

    int totalRequests = allSamples.size();
    int successfulRequests =
        (int) allSamples.stream().filter(s -> s.statusCode() == HTTP_OK).count();
    int failedRequests = totalRequests - successfulRequests;
    List<Double> latencies =
        allSamples.stream()
            .filter(s -> s.statusCode() == HTTP_OK)
            .map(LoadSample::latencyMs)
            .sorted()
            .toList();

    double p50 = percentile(latencies, PERCENTILE_50);
    double p95 = percentile(latencies, PERCENTILE_95);
    double p99 = percentile(latencies, PERCENTILE_99);
    double throughputRps = durationSeconds > 0 ? (double) totalRequests / durationSeconds : 0.0;
    double errorRate = totalRequests > 0 ? (double) failedRequests / totalRequests : 0.0;

    return new LoadResult(
        name,
        totalRequests,
        successfulRequests,
        failedRequests,
        p50,
        p95,
        p99,
        throughputRps,
        errorRate);
  }

  private double percentile(List<Double> sorted, double fraction) {
    if (sorted.isEmpty()) {
      return 0.0;
    }
    int index = Math.min((int) Math.ceil(fraction * sorted.size()), sorted.size()) - 1;
    return sorted.get(index);
  }

  private record LoadSample(int statusCode, double latencyMs) {}
}
