package com.jobs.newjobs.notion.domain;

import java.util.List;
import java.util.Map;

public record NotionPageDraft(Map<String, NotionPropertyValue> properties, List<String> descriptionChunks) {
	public NotionPageDraft {
		properties = properties == null ? Map.of() : Map.copyOf(properties);
		descriptionChunks = descriptionChunks == null ? List.of() : List.copyOf(descriptionChunks);
	}
}
