package com.hyperscale.commerce.modules.inventory

import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses

@AnalyzeClasses(
    packages = ["com.hyperscale.commerce"],
    importOptions = [ImportOption.DoNotIncludeTests::class],
)
class InventoryArchitectureTest {
  @ArchTest
  val domainShouldNotDependOnHigherLayers: ArchRule =
      noClasses()
          .that()
          .resideInAPackage("..inventory.domain..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "..inventory.application..",
              "..inventory.infrastructure..",
          )

  @ArchTest
  val applicationShouldNotDependOnInfrastructure: ArchRule =
      noClasses()
          .that()
          .resideInAPackage("..inventory.application..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..inventory.infrastructure..")

  @ArchTest
  val infrastructureShouldNotDependOnApplication: ArchRule =
      noClasses()
          .that()
          .resideInAPackage("..inventory.infrastructure..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..inventory.application..")
}
