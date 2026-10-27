package com.hyperscale.commerce;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(
    packages = "com.hyperscale.commerce",
    importOptions = ImportOption.DoNotIncludeTests.class)
class EngineeringPolicyArchitectureTest {

  @ArchTest
  static final ArchRule productionCodeMustNotUseUnboundedCachedThreadPool =
      noClasses().should().callMethod("java.util.concurrent.Executors", "newCachedThreadPool");

  @ArchTest
  static final ArchRule productionCodeMustNotCreateVirtualThreadPerTaskExecutor =
      noClasses()
          .should()
          .callMethod("java.util.concurrent.Executors", "newVirtualThreadPerTaskExecutor");

  @ArchTest
  static final ArchRule productionCodeMustNotUseValueAnnotation =
      noClasses()
          .should()
          .dependOnClassesThat()
          .haveFullyQualifiedName("org.springframework.beans.factory.annotation.Value");
}
