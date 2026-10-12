package com.jobs.newjobs.hiringcafe.config;

import java.net.http.HttpClient;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(HiringCafeProperties.class)
public class HiringCafeConfiguration {
	@Bean
	HttpClient hiringCafeHttpClient(HiringCafeProperties properties) {
		return HttpClient.newBuilder()
			.connectTimeout(properties.connectTimeout())
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();
	}
}
