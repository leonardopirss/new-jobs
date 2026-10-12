package com.jobs.newjobs.notion.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.jobs.newjobs.notion.application.NotionViewProvisioner;
import com.jobs.newjobs.notion.application.NotionViewProvisioner.Capability;
import com.jobs.newjobs.notion.application.NotionViewProvisioner.CreateResult;
import com.jobs.newjobs.notion.application.NotionViewProvisioner.ProvisioningStatus;
import com.jobs.newjobs.notion.application.NotionViewProvisioner.ViewDefinition;
import com.jobs.newjobs.notion.application.NotionViewProvisioner.ViewGateway;

class NotionViewProvisionerTests {
	@Test
	void currentCreateOnlyApiFallsBackWithoutRiskingDuplicateViews() {
		CurrentNotionViewGateway gateway = new CurrentNotionViewGateway();

		var result = new NotionViewProvisioner(gateway).provision();

		assertThat(result.status()).isEqualTo(ProvisioningStatus.MANUAL_FALLBACK_REQUIRED);
		assertThat(result.reasonCode()).isEqualTo("VIEW_DISCOVERY_UNAVAILABLE");
	}

	@Test
	void verifiedReadBeforeCreateCapabilityProvisioningIsIdempotent() {
		RecordingGateway gateway = new RecordingGateway(Capability.READ_BEFORE_CREATE);
		gateway.names.add("All Opportunities");
		NotionViewProvisioner provisioner = new NotionViewProvisioner(gateway);

		var first = provisioner.provision();
		var second = provisioner.provision();

		assertThat(first.status()).isEqualTo(ProvisioningStatus.PROVISIONED);
		assertThat(first.created()).isEqualTo(9);
		assertThat(second.status()).isEqualTo(ProvisioningStatus.PROVISIONED);
		assertThat(second.created()).isZero();
		assertThat(gateway.names).extracting(String::toString)
			.containsExactlyInAnyOrderElementsOf(NotionViewProvisioner.requiredViews().stream()
				.map(ViewDefinition::name).toList());
	}

	@Test
	void viewFailureReturnsNonDestructiveFallbackInsteadOfFailingSynchronization() {
		RecordingGateway gateway = new RecordingGateway(Capability.READ_BEFORE_CREATE);
		gateway.failedName = "Application Tracker";

		var result = new NotionViewProvisioner(gateway).provision();

		assertThat(result.status()).isEqualTo(ProvisioningStatus.MANUAL_FALLBACK_REQUIRED);
		assertThat(result.reasonCode()).isEqualTo("VIEW_CREATE_FAILED");
		assertThat(result.failed()).containsExactly("Application Tracker");
	}

	private static final class RecordingGateway implements ViewGateway {
		private final Capability capability;
		private final Set<String> names = new LinkedHashSet<>();
		private int createCalls;
		private String failedName;

		private RecordingGateway(Capability capability) {
			this.capability = capability;
		}

		@Override public Capability capability() { return capability; }
		@Override public Set<String> existingNames() { return Set.copyOf(names); }

		@Override
		public CreateResult create(ViewDefinition definition) {
			createCalls++;
			if (definition.name().equals(failedName)) return new CreateResult.Failed("PROVIDER_REJECTED");
			names.add(definition.name());
			return new CreateResult.Created("view-" + createCalls);
		}
	}
}
