package com.jobs.newjobs.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.core.io.FileSystemResource;

class OpenApiContractTests {
	private static final String CONTRACT = "specs/001-job-discovery-sync/contracts/openapi.yaml";

	@Test
	void parsesOpenApi31AndResolvesEveryInternalReference() {
		Map<String, Object> document = loadContract();
		assertThat(document.get("openapi")).isEqualTo("3.1.0");

		List<String> references = new ArrayList<>();
		collectReferences(document, references);
		assertThat(references).isNotEmpty();
		assertThat(references).allSatisfy(reference -> assertThat(resolve(document, reference)).isNotNull());
	}

	@Test
	void declaresSeparateManualAndImportTrustBoundariesAndRequestIds() {
		Map<String, Object> document = loadContract();
		Map<String, Object> paths = map(document.get("paths"));

		assertSecurity(paths, "/api/v1/filters", "get", "manualBearer");
		assertSecurity(paths, "/api/v1/jobs/preview", "post", "manualBearer");
		assertSecurity(paths, "/api/v1/jobs/sync", "post", "manualBearer");
		assertSecurity(paths, "/api/v1/jobs/import", "post", "importBearer");

		Map<String, Object> schemas = map(map(document.get("components")).get("schemas"));
		Map<String, Object> problem = map(schemas.get("Problem"));
		assertThat(list(problem.get("required"))).contains("code", "requestId", "status");
	}

	private static void assertSecurity(
		Map<String, Object> paths,
		String path,
		String method,
		String scheme
	) {
		Map<String, Object> operation = map(map(paths.get(path)).get(method));
		List<Object> security = list(operation.get("security"));
		assertThat(security).anySatisfy(item -> assertThat(map(item)).containsKey(scheme));
	}

	private static Map<String, Object> loadContract() {
		YamlMapFactoryBean yaml = new YamlMapFactoryBean();
		yaml.setResources(new FileSystemResource(CONTRACT));
		Map<String, Object> document = yaml.getObject();
		assertThat(document).isNotNull();
		return document;
	}

	private static void collectReferences(Object value, List<String> references) {
		if (value instanceof Map<?, ?> values) {
			Object reference = values.get("$ref");
			if (reference instanceof String ref && ref.startsWith("#/")) {
				references.add(ref);
			}
			values.values().forEach(child -> collectReferences(child, references));
		}
		else if (value instanceof List<?> values) {
			values.forEach(child -> collectReferences(child, references));
		}
	}

	private static Object resolve(Map<String, Object> document, String reference) {
		Object current = document;
		for (String segment : reference.substring(2).split("/")) {
			current = map(current).get(segment.replace("~1", "/").replace("~0", "~"));
		}
		return current;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> map(Object value) {
		return (Map<String, Object>) value;
	}

	@SuppressWarnings("unchecked")
	private static <T> List<T> list(Object value) {
		return (List<T>) value;
	}
}
