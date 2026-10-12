package com.jobs.newjobs.notion.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public sealed interface NotionPropertyValue permits NotionPropertyValue.Text, NotionPropertyValue.Option,
	NotionPropertyValue.Link, NotionPropertyValue.Date, NotionPropertyValue.Options,
	NotionPropertyValue.Flag, NotionPropertyValue.Numeric {

	record Text(NotionPropertyType type, String value) implements NotionPropertyValue {
		public Text {
			if (type != NotionPropertyType.TITLE && type != NotionPropertyType.RICH_TEXT) {
				throw new IllegalArgumentException("text value must be title or rich text");
			}
		}
	}

	record Option(NotionPropertyType type, String value) implements NotionPropertyValue {
		public Option {
			if (type != NotionPropertyType.SELECT && type != NotionPropertyType.STATUS) {
				throw new IllegalArgumentException("option value must be select or status");
			}
		}
	}

	record Link(String value) implements NotionPropertyValue { }
	record Date(Instant value) implements NotionPropertyValue { }
	record Options(List<String> values) implements NotionPropertyValue {
		public Options {
			values = values == null ? List.of() : List.copyOf(values);
		}
	}
	record Flag(boolean value) implements NotionPropertyValue { }
	record Numeric(BigDecimal value) implements NotionPropertyValue { }
}
