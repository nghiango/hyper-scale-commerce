package com.hyperscale.commerce.modules.catalog

import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses

@AnalyzeClasses(
    packages = ["com.hyperscale.commerce"],
    importOptions = [ImportOption.DoNotIncludeTests::class],
)
class CatalogArchitectureTest {
  @ArchTest
  val domainShouldNotDependOnHigherLayers: ArchRule =
      noClasses()
          .that()
          .resideInAPackage("..catalog.domain..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "..catalog.application..",
              "..catalog.infrastructure..",
              "..catalog.api..",
          )

  @ArchTest
  val applicationShouldNotDependOnInfrastructureOrApi: ArchRule =
      noClasses()
          .that()
          .resideInAPackage("..catalog.application..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "..catalog.infrastructure..",
              "..catalog.api..",
          )

  @ArchTest
  val apiShouldNotDependOnInfrastructure: ArchRule =
      noClasses()
          .that()
          .resideInAPackage("..catalog.api..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..catalog.infrastructure..")

  @ArchTest
  val infrastructureShouldNotDependOnApplicationOrApi: ArchRule =
      noClasses()
          .that()
          .resideInAPackage("..catalog.infrastructure..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "..catalog.application..",
              "..catalog.api..",
          )
}
