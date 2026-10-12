package com.jobs.newjobs.notion.domain;

import java.util.Locale;

public enum NotionPropertyType {
	TITLE,
	RICH_TEXT,
	SELECT,
	STATUS,
	URL,
	DATE,
	MULTI_SELECT,
	CHECKBOX,
	NUMBER,
	UNKNOWN;

	public static NotionPropertyType fromApi(String value) {
		if (value == null || value.isBlank()) {
			return UNKNOWN;
		}
		try {
			return valueOf(value.toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException exception) {
			return UNKNOWN;
		}
	}
}
