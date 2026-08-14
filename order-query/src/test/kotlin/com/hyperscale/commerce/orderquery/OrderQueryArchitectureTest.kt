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
class OrderQueryArchitectureTest {
  @ArchTest
  val domainShouldNotDependOnHigherLayers: ArchRule =
      noClasses()
          .that()
          .resideInAPackage("..orderquery.domain..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "..orderquery.application..",
              "..orderquery.api..",
              "..orderquery.config..",
          )

  @ArchTest
  val applicationShouldNotDependOnApiOrConfig: ArchRule =
      noClasses()
          .that()
          .resideInAPackage("..orderquery.application..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "..orderquery.api..",
              "..orderquery.config..",
          )

  @ArchTest
  val apiShouldNotDependOnConfig: ArchRule =
      noClasses()
          .that()
          .resideInAPackage("..orderquery.api..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..orderquery.config..")

  @ArchTest
  val queryAndProjectionShouldNotUseWriteRepository: ArchRule =
      noClasses()
          .that()
          .resideInAPackage("..orderquery.application..")
          .and()
          .haveNameMatching(".*(OrderQueryService|OrderPlacedProjection)")
          .should()
          .dependOnClassesThat()
          .haveSimpleName("OrderRepository")
}
