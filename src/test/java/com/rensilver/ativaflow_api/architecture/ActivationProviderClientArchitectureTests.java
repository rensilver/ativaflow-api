package com.rensilver.ativaflow_api.architecture;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

class ActivationProviderClientArchitectureTests {

	@Test
	void providerContractDependsOnlyOnJavaAndItsOwnTypes() {
		var contractClasses = new ClassFileImporter()
				.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
				.importPackages("com.rensilver.ativaflow_api");

		classes().that().haveFullyQualifiedName("com.rensilver.ativaflow_api.client.provider.ActivationProviderClient")
				.or().haveFullyQualifiedName("com.rensilver.ativaflow_api.client.provider.dto.ActivationProviderRequest")
				.or().haveFullyQualifiedName("com.rensilver.ativaflow_api.domain.ProviderOutcome")
				.or().haveNameMatching("com\\.rensilver\\.ativaflow_api\\.domain\\.ProviderOutcome\\$.*")
				.should().onlyDependOnClassesThat().resideInAnyPackage(
						"java.lang..", "java.time..", "java.util..",
						"com.rensilver.ativaflow_api.client.provider.dto",
						"com.rensilver.ativaflow_api.domain")
				.because("the provider port and its models must be independent of transport and application infrastructure")
				.check(contractClasses);
	}
}
