package com.hyperscale.commerce.modules.catalog;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(
    packages = "com.hyperscale.commerce",
    importOptions = ImportOption.DoNotIncludeTests.class)
class CatalogArchitectureTest {

  @ArchTest
  static final ArchRule domainShouldNotDependOnHigherLayers =
      noClasses()
          .that()
          .resideInAPackage("..catalog.domain..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "..catalog.application..", "..catalog.infrastructure..", "..catalog.api..");

  @ArchTest
  static final ArchRule applicationShouldNotDependOnInfrastructureOrApi =
      noClasses()
          .that()
          .resideInAPackage("..catalog.application..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("..catalog.infrastructure..", "..catalog.api..");

  @ArchTest
  static final ArchRule apiShouldNotDependOnInfrastructure =
      noClasses()
          .that()
          .resideInAPackage("..catalog.api..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..catalog.infrastructure..");

  @ArchTest
  static final ArchRule infrastructureShouldNotDependOnApplicationOrApi =
      noClasses()
          .that()
          .resideInAPackage("..catalog.infrastructure..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("..catalog.application..", "..catalog.api..");
}
