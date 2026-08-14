package com.hyperscale.commerce.modules.shared

import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses

@AnalyzeClasses(
    packages = ["com.hyperscale.commerce"],
    importOptions = [ImportOption.DoNotIncludeTests::class],
)
class SharedArchitectureTest {
  @ArchTest
  val sharedShouldNotDependOnModules: ArchRule =
      noClasses()
          .that()
          .resideInAPackage("..shared..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "..modules.order..",
              "..modules.inventory..",
              "..modules.catalog..",
          )
}
