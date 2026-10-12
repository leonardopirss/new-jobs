package com.jobs.newjobs.sync.application;

import org.springframework.stereotype.Service;

import com.jobs.newjobs.filters.application.PreviewService;
import com.jobs.newjobs.filters.application.SavedFilterService;
import com.jobs.newjobs.filters.domain.SavedFilter;
import com.jobs.newjobs.notion.config.NotionProperties;
import com.jobs.newjobs.ranking.domain.RankingPreferences;
import com.jobs.newjobs.shared.config.FeatureProperties;
import com.jobs.newjobs.sync.controller.ManualSyncRequest;
import com.jobs.newjobs.sync.domain.SyncRunResult;

@Service
public final class ManualSyncService {
	private final FeatureProperties features;
	private final NotionProperties notionProperties;
	private final SavedFilterService savedFilters;
	private final PreviewService previewService;
	private final SyncCoordinator coordinator;

	public ManualSyncService(
		FeatureProperties features,
		NotionProperties notionProperties,
		SavedFilterService savedFilters,
		PreviewService previewService,
		SyncCoordinator coordinator
	) {
		this.features = features;
		this.notionProperties = notionProperties;
		this.savedFilters = savedFilters;
		this.previewService = previewService;
		this.coordinator = coordinator;
	}

	public SyncRunResult synchronize(ManualSyncRequest request) {
		request.validateMode();
		if (!features.notionWritesEnabled()) {
			throw new SyncPreflightUnavailableException("NOTION_WRITES_DISABLED",
				"Notion synchronization is disabled");
		}

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
