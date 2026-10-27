package com.hyperscale.commerce.modules.inventory;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(
    packages = "com.hyperscale.commerce",
    importOptions = ImportOption.DoNotIncludeTests.class)
class InventoryArchitectureTest {

  @ArchTest
  static final ArchRule domainShouldNotDependOnHigherLayers =
      noClasses()
          .that()
          .resideInAPackage("..inventory.domain..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("..inventory.application..", "..inventory.infrastructure..");

  @ArchTest
  static final ArchRule applicationShouldNotDependOnInfrastructure =
      noClasses()
          .that()
          .resideInAPackage("..inventory.application..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..inventory.infrastructure..");

  @ArchTest
  static final ArchRule infrastructureShouldNotDependOnApplication =
      noClasses()
          .that()
          .resideInAPackage("..inventory.infrastructure..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..inventory.application..");
}
