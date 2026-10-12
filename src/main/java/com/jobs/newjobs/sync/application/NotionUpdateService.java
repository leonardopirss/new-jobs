package com.jobs.newjobs.sync.application;

import java.time.Clock;

import org.springframework.stereotype.Service;

import com.jobs.newjobs.jobs.domain.NormalizedJob;
import com.jobs.newjobs.notion.application.NotionGateway;
import com.jobs.newjobs.notion.application.NotionPageMapper;
import com.jobs.newjobs.notion.domain.NotionPagePatch;

@Service
public final class NotionUpdateService {
	private final NotionGateway notion;
	private final NotionPageMapper mapper;
	private final Clock clock;

	public NotionUpdateService(NotionGateway notion, NotionPageMapper mapper, Clock clock) {
		this.notion = notion;
		this.mapper = mapper;
		this.clock = clock;
	}

	public NotionGateway.UpdateResult update(String pageId, NormalizedJob job) {
		NotionPagePatch atomicPatch = mapper.updatePatch(job, clock.instant());
		NotionGateway.UpdateResult first = notion.updatePage(pageId, atomicPatch);
		if (!(first instanceof NotionGateway.UpdateResult.ConclusiveNotApplied)) {
			return first;
		}
		return notion.updatePage(pageId, atomicPatch);
	}
}
