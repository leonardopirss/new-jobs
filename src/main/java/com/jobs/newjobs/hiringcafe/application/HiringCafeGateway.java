package com.jobs.newjobs.hiringcafe.application;

import java.util.Map;

public interface HiringCafeGateway {
	HiringCafeProviderPage search(Map<String, Object> arguments);
}
