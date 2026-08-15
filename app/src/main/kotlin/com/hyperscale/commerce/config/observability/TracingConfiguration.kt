package com.hyperscale.commerce.config.observability

import brave.Tracing
import brave.handler.SpanHandler
import io.micrometer.observation.ObservationRegistry
import io.micrometer.tracing.BaggageManager
import io.micrometer.tracing.CurrentTraceContext
import io.micrometer.tracing.Tracer
import io.micrometer.tracing.brave.bridge.BraveBaggageManager
import io.micrometer.tracing.brave.bridge.BraveCurrentTraceContext
import io.micrometer.tracing.brave.bridge.BravePropagator
import io.micrometer.tracing.brave.bridge.BraveTracer
import io.micrometer.tracing.handler.DefaultTracingObservationHandler
import io.micrometer.tracing.handler.PropagatingReceiverTracingObservationHandler
import io.micrometer.tracing.handler.PropagatingSenderTracingObservationHandler
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class TracingConfiguration {

  @Bean
  @ConditionalOnMissingBean(Tracing::class)
  fun braveTracing(
      @Value("\${spring.application.name:app}") serviceName: String,
  ): Tracing {
    return Tracing.newBuilder()
        .localServiceName(serviceName)
        .addSpanHandler(SpanHandler.NOOP)
        .build()
  }

  @Bean fun braveTracer(braveTracing: Tracing): brave.Tracer = braveTracing.tracer()

  @Bean
  fun currentTraceContext(braveTracing: Tracing): CurrentTraceContext =
      BraveCurrentTraceContext(braveTracing.currentTraceContext())

  @Bean fun baggageManager(): BaggageManager = BraveBaggageManager()

  @Bean
  fun tracer(
      braveTracer: brave.Tracer,
      currentTraceContext: CurrentTraceContext,
      baggageManager: BaggageManager,
  ): Tracer = BraveTracer(braveTracer, currentTraceContext, baggageManager)

  @Bean
  @ConditionalOnMissingBean(ObservationRegistry::class)
  fun observationRegistry(
      tracer: Tracer,
      braveTracing: Tracing,
  ): ObservationRegistry {
    val registry = ObservationRegistry.create()
    val propagator = BravePropagator(braveTracing)
    registry
        .observationConfig()
        .observationHandler(DefaultTracingObservationHandler(tracer))
        .observationHandler(
            PropagatingReceiverTracingObservationHandler<
                io.micrometer.observation.transport.ReceiverContext<*>>(
                tracer, propagator))
        .observationHandler(
            PropagatingSenderTracingObservationHandler<
                io.micrometer.observation.transport.SenderContext<*>>(
                tracer, propagator))
    return registry
  }
}
