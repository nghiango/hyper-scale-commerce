# HyperScale Commerce — Kotlin/JVM Engineering Guidelines

## 1. Scope and Objective

These guidelines define the engineering standard for Kotlin/JVM development across HyperScale Commerce (`app`, `order-query`, `contracts`).

All Kotlin code must adhere to:
1. Strict type-safety and nullability semantics;
2. Zero Kotlin compiler warnings in production code (`allWarningsAsErrors = true`);
3. Bounded concurrency ownership and deterministic lifecycle management;
4. Safe context propagation (`ThreadLocal`, Brave trace context, SLF4J MDC);
5. Validated `@ConfigurationProperties` over scattered `@Value` injection.

---

## 2. Shared Kotlin Compiler Policy

The build enforces a centralized Kotlin compiler policy defined in `build.gradle.kts`:

```kotlin
plugins.withId("org.jetbrains.kotlin.jvm") {
  configure<KotlinJvmProjectExtension> {
    jvmToolchain(21)
  }

  tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
      jvmTarget.set(JvmTarget.JVM_21)
      freeCompilerArgs.addAll(
          "-Xjsr305=strict",
          "-opt-in=kotlin.RequiresOptIn",
          "-Xannotation-default-target=param-property",
      )
    }
  }

  tasks.named<KotlinCompile>("compileKotlin") {
    compilerOptions {
      allWarningsAsErrors.set(true)
    }
  }
}
```

### Compiler Option Rationale
- **`jvmToolchain(21)` & `jvmTarget = JVM_21`:** Pinned to JDK 21 LTS, leveraging modern JVM bytecode optimizations and standard diagnostic tooling (JFR).
- **`-Xjsr305=strict`:** Enforces strict nullability handling for Java/Spring framework interop (treating `@Nullable` and `@NonNull` as compile-time constraints).
- **`-opt-in=kotlin.RequiresOptIn`:** Standardizes opt-in requirements for internal or incubating APIs.
- **`-Xannotation-default-target=param-property`:** Resolves Kotlin 2.2+ constructor annotation ambiguities by applying annotations to both constructor parameter and backing property unless explicitly qualified with `@param:` or `@field:`.
- **`allWarningsAsErrors = true`:** Hand-written production Kotlin (`compileKotlin`) must compile with zero warnings. Warnings cannot be bypassed through global compiler flags.

---

## 3. Static Analysis and Code Formatting

### 3.1 Detekt
- Centralized configuration: `config/detekt/detekt.yml`.
- `maxIssues: 0` ensures zero static analysis debt.
- Generated sources (e.g. jOOQ Java classes under `build/generated-sources/jooq`) are strictly excluded.
- Coroutine rules disallow `GlobalScope` and enforce structured dispatcher injection.

### 3.2 Spotless (`ktfmt`)
- All Kotlin source files and Gradle Kotlin DSL scripts must pass Spotless formatting (`./gradlew spotlessCheck`).
- Auto-formatting command: `./gradlew spotlessApply`.

---

## 4. Concurrency Invariants and Ownership

HyperScale Commerce relies on bounded, deterministic concurrency. The following rules are mandatory:

### 4.1 Production Default Model (ADR-0027)
- **Request Processing:** Spring MVC on Tomcat with bounded platform thread pools (`server.tomcat.threads.max: 200`, `min-spare: 10`, `max-connections: 10000`).
- **Database Concurrency:** HikariCP connection pools (30 primary, 20 replica per pod) serve as the definitive backpressure boundary protecting PostgreSQL from connection exhaustion.
- **Background Relays & Schedulers:** Managed via `@Scheduled` on bounded Spring TaskSchedulers (`OutboxRelay`, `RoutingDataSource` lag check).
- **Messaging:** Spring Kafka listener containers (`concurrency: 3`).

### 4.2 Forbidden Concurrency Patterns
1. **`GlobalScope`:** Unowned coroutine launches are strictly forbidden.
2. **Production `runBlocking`:** Blocking on asynchronous or reactive streams in production code is forbidden.
3. **Unbounded Thread Creation:** Direct `new Thread()` or `Executors.newCachedThreadPool()` without bounded queue/capacity is forbidden.
4. **Cross-Thread Transactions:** Active `@Transactional` database operations must not cross thread or dispatcher boundaries without verified transaction manager synchronization.
5. **Swallowed Interruption:** Never swallow `InterruptedException` without restoring the thread's interrupt status (`Thread.currentThread().interrupt()`).

### 4.3 Context Propagation (Trace & MDC)
All request filters, Kafka interceptors, and background workers must guarantee thread-local cleanup:

```kotlin
// Example: Safe MDC and Span scope lifecycle
val span = tracer.nextSpan().name("operation").start()
val scope = tracer.withSpan(span)
MDC.put(CorrelationIdFilter.CORRELATION_ID_MDC_KEY, correlationId)
try {
  executeBusinessLogic()
} finally {
  MDC.remove(CorrelationIdFilter.CORRELATION_ID_MDC_KEY)
  scope.close()
  span.end()
}
```

---

## 5. Type-Safe Configuration & Boundary Hardening

1. **No Production `@Value` Injection:** Use immutable, validated `@ConfigurationProperties` classes with `@Validated`, `@Min`, `@Max`, `@NotBlank`, etc.
2. **Secret Redaction:** Configuration properties containing passwords or tokens must not be logged or exposed via unauthenticated endpoints.
3. **Explicit Null-Safety:** Avoid non-null assertions (`!!`). Use `requireNotNull(...)`, `checkNotNull(...)`, safe calls `?.`, or explicit Elvis operators `?:` with descriptive error handling.
4. **Exhaustive Outcomes:** Model multi-outcome domain operations using Kotlin `sealed interface` or `sealed class` hierarchies, ensuring compile-time exhaustiveness in `when` expressions.
