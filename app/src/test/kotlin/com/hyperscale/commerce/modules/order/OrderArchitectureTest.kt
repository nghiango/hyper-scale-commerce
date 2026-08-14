package com.hyperscale.commerce.modules.order

import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses

@AnalyzeClasses(
    packages = ["com.hyperscale.commerce"],
    importOptions = [ImportOption.DoNotIncludeTests::class],
)
class OrderArchitectureTest {
  @ArchTest
  val domainShouldNotDependOnHigherLayers: ArchRule =
      noClasses()
          .that()
          .resideInAPackage("..order.domain..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "..order.application..",
              "..order.infrastructure..",
              "..order.api..",
          )

  @ArchTest
  val applicationShouldNotDependOnInfrastructureOrApi: ArchRule =
      noClasses()
          .that()
          .resideInAPackage("..order.application..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "..order.infrastructure..",
              "..order.api..",
          )

  @ArchTest
  val apiShouldNotDependOnInfrastructure: ArchRule =
      noClasses()
          .that()
          .resideInAPackage("..order.api..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..order.infrastructure..")

  @ArchTest
  val infrastructureShouldNotDependOnApplicationOrApi: ArchRule =
      noClasses()
          .that()
          .resideInAPackage("..order.infrastructure..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "..order.application..",
              "..order.api..",
          )
}
