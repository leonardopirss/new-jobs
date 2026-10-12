package com.jobs.newjobs.notion.domain;

import java.util.List;
import java.util.Map;

public record NotionSchema(String dataSourceId, Map<String, PropertyDefinition> properties) {
	public NotionSchema {
		if (dataSourceId == null || dataSourceId.isBlank()) {
			throw new IllegalArgumentException("data source ID is required");
		}
		properties = properties == null ? Map.of() : Map.copyOf(properties);
	}

	public record PropertyDefinition(String id, String name, NotionPropertyType type, List<String> options) {
		public PropertyDefinition {
			if (name == null || name.isBlank() || type == null) {
				throw new IllegalArgumentException("property name and type are required");
			}
			options = options == null ? List.of() : List.copyOf(options);
		}
	}
}
