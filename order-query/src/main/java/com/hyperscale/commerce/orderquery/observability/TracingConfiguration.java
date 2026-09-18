package com.hyperscale.commerce.orderquery.observability;

import brave.Tracing;
import brave.handler.SpanHandler;
import com.hyperscale.commerce.orderquery.config.AppProperties;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.transport.ReceiverContext;
import io.micrometer.observation.transport.SenderContext;
import io.micrometer.tracing.BaggageManager;
import io.micrometer.tracing.CurrentTraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.brave.bridge.BraveBaggageManager;
import io.micrometer.tracing.brave.bridge.BraveCurrentTraceContext;
import io.micrometer.tracing.brave.bridge.BravePropagator;
import io.micrometer.tracing.brave.bridge.BraveTracer;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.handler.PropagatingReceiverTracingObservationHandler;
import io.micrometer.tracing.handler.PropagatingSenderTracingObservationHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TracingConfiguration {

  @Bean
  @ConditionalOnMissingBean(Tracing.class)
  public Tracing braveTracing(AppProperties appProperties) {
    return Tracing.newBuilder()
        .localServiceName(appProperties.name())
        .addSpanHandler(SpanHandler.NOOP)
        .build();
  }

  @Bean
  public brave.Tracer braveTracer(Tracing braveTracing) {
    return braveTracing.tracer();
  }

  @Bean
  public CurrentTraceContext currentTraceContext(Tracing braveTracing) {
    return new BraveCurrentTraceContext(braveTracing.currentTraceContext());
  }

  @Bean
  public BaggageManager baggageManager() {
    return new BraveBaggageManager();
  }

  @Bean
  public Tracer tracer(
      brave.Tracer braveTracer,
      CurrentTraceContext currentTraceContext,
      BaggageManager baggageManager) {
    return new BraveTracer(braveTracer, currentTraceContext, baggageManager);
  }

  @Bean
  @ConditionalOnMissingBean(ObservationRegistry.class)
  public ObservationRegistry observationRegistry(Tracer tracer, Tracing braveTracing) {
    ObservationRegistry registry = ObservationRegistry.create();
    BravePropagator propagator = new BravePropagator(braveTracing);
    registry
        .observationConfig()
        .observationHandler(new DefaultTracingObservationHandler(tracer))
        .observationHandler(
            new PropagatingReceiverTracingObservationHandler<ReceiverContext<?>>(
                tracer, propagator))
        .observationHandler(
            new PropagatingSenderTracingObservationHandler<SenderContext<?>>(tracer, propagator));
    return registry;
  }
}
