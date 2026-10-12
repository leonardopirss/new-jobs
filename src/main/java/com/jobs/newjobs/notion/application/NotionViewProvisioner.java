package com.jobs.newjobs.notion.application;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public final class NotionViewProvisioner {
	private static final List<ViewDefinition> REQUIRED_VIEWS = List.of(
		view("All Opportunities", ViewType.TABLE, null, null),
		view("New Jobs - Last 24 Hours", ViewType.TABLE, "First Synced At", "PAST_24_HOURS"),
		view("HiringCafe Jobs", ViewType.TABLE, "Source", "HIRING_CAFE"),
		view("LinkedIn Jobs", ViewType.TABLE, "Source", "LINKEDIN"),
		view("Junior Opportunities", ViewType.TABLE, "Seniority", "JUNIOR"),
		view("Mid-Level Opportunities", ViewType.TABLE, "Seniority", "MID_LEVEL"),
		view("Senior Opportunities", ViewType.TABLE, "Seniority", "SENIOR"),
		view("Remote Opportunities", ViewType.TABLE, "Work Mode", "REMOTE"),
		view("International Opportunities", ViewType.TABLE, "Eligibility", "INTERNATIONAL"),
		new ViewDefinition("Application Tracker", ViewType.BOARD, null, "Status", List.of())
	);

	private final ViewGateway gateway;

	@Autowired
	public NotionViewProvisioner(Optional<ViewGateway> gateway) {
		this(gateway.orElseGet(UnavailableGateway::new));
	}

	public NotionViewProvisioner(ViewGateway gateway) {
		this.gateway = gateway;
	}

	public ProvisioningResult provision() {
		if (gateway.capability() != Capability.READ_BEFORE_CREATE) {
			return fallback("VIEW_DISCOVERY_UNAVAILABLE", 0, List.of());
		}

		Set<String> existing;
		try {
			existing = new LinkedHashSet<>(gateway.existingNames());
		}
		catch (RuntimeException exception) {
			return fallback("VIEW_DISCOVERY_FAILED", 0, List.of());
		}

		int created = 0;
		List<String> failed = new ArrayList<>();
		for (ViewDefinition definition : REQUIRED_VIEWS) {
			if (existing.contains(definition.name())) continue;
			try {
				CreateResult result = gateway.create(definition);
				if (result instanceof CreateResult.Created || result instanceof CreateResult.AlreadyExists) {
					existing.add(definition.name());
					if (result instanceof CreateResult.Created) created++;
				}
				else {
					failed.add(definition.name());
				}
			}
			catch (RuntimeException exception) {
				failed.add(definition.name());
			}
		}

		return failed.isEmpty()
			? new ProvisioningResult(ProvisioningStatus.PROVISIONED, created,
				REQUIRED_VIEWS.size() - created, List.of(), null)
			: fallback("VIEW_CREATE_FAILED", created, failed);
	}

	public static List<ViewDefinition> requiredViews() {
		return REQUIRED_VIEWS;
	}

	private static ViewDefinition view(String name, ViewType type, String property, String value) {
		List<FilterRule> filters = property == null ? List.of() : List.of(new FilterRule(property, "EQUALS", value));
		return new ViewDefinition(name, type, null, null, filters);
	}

	private static ProvisioningResult fallback(String reason, int created, List<String> failed) {
		return new ProvisioningResult(ProvisioningStatus.MANUAL_FALLBACK_REQUIRED, created, 0, failed, reason);
	}

	public interface ViewGateway {
		Capability capability();
		Set<String> existingNames();
		CreateResult create(ViewDefinition definition);
	}

	public enum Capability { READ_BEFORE_CREATE, CREATE_ONLY, UNAVAILABLE }
	public enum ProvisioningStatus { PROVISIONED, MANUAL_FALLBACK_REQUIRED }
	public enum ViewType { TABLE, BOARD }

	public record FilterRule(String property, String operator, String value) { }
	public record ViewDefinition(String name, ViewType type, String sortProperty, String groupBy,
		List<FilterRule> filters) {
		public ViewDefinition { filters = List.copyOf(filters); }
	}
	public record ProvisioningResult(ProvisioningStatus status, int created, int existing,
		List<String> failed, String reasonCode) {
		public ProvisioningResult { failed = List.copyOf(failed); }
	}

	public sealed interface CreateResult permits CreateResult.Created, CreateResult.AlreadyExists,
		CreateResult.Failed {
		record Created(String viewId) implements CreateResult { }
		record AlreadyExists(String viewId) implements CreateResult { }
		record Failed(String reasonCode) implements CreateResult { }
	}

	private static final class UnavailableGateway implements ViewGateway {
		@Override public Capability capability() { return Capability.UNAVAILABLE; }
		@Override public Set<String> existingNames() { return Set.of(); }
		@Override public CreateResult create(ViewDefinition definition) {
			throw new IllegalStateException("Notion view gateway is unavailable");
		}
	}
}
