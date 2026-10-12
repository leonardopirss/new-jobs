package com.jobs.newjobs.notion.infrastructure;

import java.util.Set;

import org.springframework.stereotype.Component;

import com.jobs.newjobs.notion.application.NotionViewProvisioner.Capability;
import com.jobs.newjobs.notion.application.NotionViewProvisioner.CreateResult;
import com.jobs.newjobs.notion.application.NotionViewProvisioner.ViewDefinition;
import com.jobs.newjobs.notion.application.NotionViewProvisioner.ViewGateway;

@Component
public final class CurrentNotionViewGateway implements ViewGateway {
	@Override
	public Capability capability() {
		return Capability.CREATE_ONLY;
	}

	@Override
	public Set<String> existingNames() {
		throw new UnsupportedOperationException("Notion API 2026-03-11 does not expose view discovery");
	}

	@Override
	public CreateResult create(ViewDefinition definition) {
		return new CreateResult.Failed("VIEW_IDEMPOTENCY_UNAVAILABLE");
	}
}
