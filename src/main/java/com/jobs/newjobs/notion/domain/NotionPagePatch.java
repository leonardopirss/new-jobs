package com.jobs.newjobs.notion.domain;

import java.util.Map;

public record NotionPagePatch(Map<String, NotionPropertyValue> properties) {
	public NotionPagePatch {
		properties = properties == null ? Map.of() : Map.copyOf(properties);
		if (properties.isEmpty()) {
			throw new IllegalArgumentException("page patch must contain at least one property");
		}
	}
}
