package com.jobs.newjobs.notion.domain;

import java.util.Map;

public record NotionPageSnapshot(
	String pageId,
	boolean archived,
	boolean inTrash,
	Map<String, NotionPropertyValue> properties
) {
	public NotionPageSnapshot {
		if (pageId == null || pageId.isBlank()) {
			throw new IllegalArgumentException("page ID is required");
		}
		properties = properties == null ? Map.of() : Map.copyOf(properties);
	}
}
