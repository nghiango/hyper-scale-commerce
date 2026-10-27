package com.hyperscale.commerce.modules.shared;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(
    packages = "com.hyperscale.commerce",
    importOptions = ImportOption.DoNotIncludeTests.class)
class SharedArchitectureTest {

  @ArchTest
  static final ArchRule sharedShouldNotDependOnModules =
      noClasses()
          .that()
          .resideInAPackage("..shared..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("..modules.order..", "..modules.inventory..", "..modules.catalog..");
}
