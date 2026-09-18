package com.hyperscale.commerce.modules.order;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(
    packages = "com.hyperscale.commerce",
    importOptions = ImportOption.DoNotIncludeTests.class)
class OrderArchitectureTest {

  @ArchTest
  static final ArchRule domainShouldNotDependOnHigherLayers =
      noClasses()
          .that()
          .resideInAPackage("..order.domain..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("..order.application..", "..order.infrastructure..", "..order.api..");

  @ArchTest
  static final ArchRule applicationShouldNotDependOnInfrastructureOrApi =
      noClasses()
          .that()
          .resideInAPackage("..order.application..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("..order.infrastructure..", "..order.api..");

  @ArchTest
  static final ArchRule apiShouldNotDependOnInfrastructure =
      noClasses()
          .that()
          .resideInAPackage("..order.api..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..order.infrastructure..");

  @ArchTest
  static final ArchRule infrastructureShouldNotDependOnApplicationOrApi =
      noClasses()
          .that()
          .resideInAPackage("..order.infrastructure..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("..order.application..", "..order.api..");
}
