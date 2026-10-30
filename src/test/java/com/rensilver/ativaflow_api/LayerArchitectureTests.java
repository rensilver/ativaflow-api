package com.rensilver.ativaflow_api;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class LayerArchitectureTests {

	private static final String BASE_PACKAGE = "com.rensilver.ativaflow_api";
	private static final JavaClasses PRODUCTION = new ClassFileImporter()
			.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
			.importPackages(BASE_PACKAGE);

	@Test
	void controllersCannotDependDirectlyOnRepositories() {
		noClasses().that().resideInAPackage(BASE_PACKAGE + ".controller..")
				.should().dependOnClassesThat().resideInAnyPackage(
						BASE_PACKAGE + ".repository..", "org.springframework.data.repository..",
						"org.springframework.data.jpa.repository..")
				.because("controllers must access persistence through application services")
				.check(PRODUCTION);
	}

	@Test
	void domainDependsOnlyOnJavaAndItsOwnTypes() {
		classes().that().resideInAPackage(BASE_PACKAGE + ".domain..")
				.should().onlyDependOnClassesThat().resideInAnyPackage(
						"java..", BASE_PACKAGE + ".domain..")
				.because("business rules must be independent of application and infrastructure details")
				.check(PRODUCTION);
	}

	@Test
	void entitiesCannotDependOnApplicationOrInfrastructureComponents() {
		// model contains JPA entities: persistence mappings and auditing annotations remain valid.
		noClasses().that().resideInAPackage(BASE_PACKAGE + ".model..")
				.should().dependOnClassesThat().resideInAnyPackage(
						BASE_PACKAGE + ".client..", BASE_PACKAGE + ".config..",
						BASE_PACKAGE + ".controller..", BASE_PACKAGE + ".dto..",
						BASE_PACKAGE + ".exception..", BASE_PACKAGE + ".http..",
						BASE_PACKAGE + ".mapper..", BASE_PACKAGE + ".observability..",
						BASE_PACKAGE + ".repository..", BASE_PACKAGE + ".security..",
						BASE_PACKAGE + ".service..", BASE_PACKAGE + ".worker..")
				.because("entities may map persistence but must not orchestrate application or infrastructure components")
				.check(PRODUCTION);
	}
}
