package com.hyperscale.commerce.orderquery;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(
    packages = "com.hyperscale.commerce.orderquery",
    importOptions = ImportOption.DoNotIncludeTests.class)
class OrderQueryArchitectureTest {
  @ArchTest
  static final ArchRule domainShouldNotDependOnHigherLayers =
      noClasses()
          .that()
          .resideInAPackage("..orderquery.domain..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "..orderquery.application..", "..orderquery.api..", "..orderquery.config..");

  @ArchTest
  static final ArchRule applicationShouldNotDependOnApiOrConfig =
      noClasses()
          .that()
          .resideInAPackage("..orderquery.application..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("..orderquery.api..", "..orderquery.config..");

  @ArchTest
  static final ArchRule apiShouldNotDependOnConfig =
      noClasses()
          .that()
          .resideInAPackage("..orderquery.api..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..orderquery.config..");

  @ArchTest
  static final ArchRule queryAndProjectionShouldNotUseWriteRepository =
      noClasses()
          .that()
          .resideInAPackage("..orderquery.application..")
          .and()
          .haveNameMatching(".*(OrderQueryService|OrderPlacedProjection)")
          .should()
          .dependOnClassesThat()
          .haveSimpleName("OrderRepository");
}
