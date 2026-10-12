package com.jobs.newjobs.sync.application;

import org.springframework.stereotype.Service;

import com.jobs.newjobs.filters.application.PreviewService;
import com.jobs.newjobs.filters.application.SavedFilterService;
import com.jobs.newjobs.filters.domain.SavedFilter;
import com.jobs.newjobs.notion.config.NotionProperties;
import com.jobs.newjobs.ranking.domain.RankingPreferences;
import com.jobs.newjobs.shared.config.FeatureProperties;
import com.jobs.newjobs.shared.lifecycle.GracefulWorkShutdown;
import com.jobs.newjobs.shared.lifecycle.GracefulWorkShutdown.WorkKind;
import com.jobs.newjobs.sync.controller.ManualSyncRequest;
import com.jobs.newjobs.sync.domain.SyncRunResult;

@Service
public final class ManualSyncService {
	private final FeatureProperties features;
	private final NotionProperties notionProperties;
	private final SavedFilterService savedFilters;
	private final PreviewService previewService;
	private final SyncCoordinator coordinator;
	private final GracefulWorkShutdown shutdown;

	public ManualSyncService(
		FeatureProperties features,
		NotionProperties notionProperties,
		SavedFilterService savedFilters,
		PreviewService previewService,
		SyncCoordinator coordinator,
		GracefulWorkShutdown shutdown
	) {
		this.features = features;
		this.notionProperties = notionProperties;
		this.savedFilters = savedFilters;
		this.previewService = previewService;
		this.coordinator = coordinator;
		this.shutdown = shutdown;
	}

	public SyncRunResult synchronize(ManualSyncRequest request) {
		request.validateMode();
		if (!features.notionWritesEnabled()) {
			throw new SyncPreflightUnavailableException("NOTION_WRITES_DISABLED",
				"Notion synchronization is disabled");
		}
		var permit = shutdown.begin(WorkKind.MANUAL_SYNC)
			.orElseThrow(() -> new SyncPreflightUnavailableException("SERVICE_SHUTTING_DOWN",
				"New synchronization work is not accepted during shutdown"));
		try (permit) {
			return synchronizeAccepted(request);
		}
	}

	private SyncRunResult synchronizeAccepted(ManualSyncRequest request) {
		if (request.usesSavedFilter()) {
			SavedFilter snapshot = savedFilters.get(request.savedFilterId());
			var preview = previewService.preview(
				snapshot.source(), snapshot.specification(), snapshot.rankingPreferences());
			return coordinator.synchronize(notionProperties.dataSourceId(), preview.jobs());
		}

		RankingPreferences preferences = com.jobs.newjobs.filters.controller.RankingPreferencesRequest
			.effective(request.rankingPreferences());
		var preview = previewService.preview(request.source(), request.specification().toDomain(), preferences);
		return coordinator.synchronize(notionProperties.dataSourceId(), preview.jobs());
	}
}
