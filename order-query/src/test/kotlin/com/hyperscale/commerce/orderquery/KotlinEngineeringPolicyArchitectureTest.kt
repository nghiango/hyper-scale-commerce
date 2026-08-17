package com.hyperscale.commerce.orderquery

import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses

@AnalyzeClasses(
    packages = ["com.hyperscale.commerce.orderquery"],
    importOptions = [ImportOption.DoNotIncludeTests::class],
)
class KotlinEngineeringPolicyArchitectureTest {

  @ArchTest
  val productionCodeMustNotUseGlobalScope: ArchRule =
      noClasses()
          .should()
          .dependOnClassesThat()
          .haveFullyQualifiedName("kotlinx.coroutines.GlobalScope")

  @ArchTest
  val productionCodeMustNotUseUnboundedCachedThreadPool: ArchRule =
      noClasses().should().callMethod("java.util.concurrent.Executors", "newCachedThreadPool")

  @ArchTest
  val productionCodeMustNotCreateVirtualThreadPerTaskExecutor: ArchRule =
      noClasses()
          .should()
          .callMethod("java.util.concurrent.Executors", "newVirtualThreadPerTaskExecutor")

  @ArchTest
  val productionCodeMustNotUseValueAnnotation: ArchRule =
      noClasses()
          .should()
          .dependOnClassesThat()
          .haveFullyQualifiedName("org.springframework.beans.factory.annotation.Value")
}
