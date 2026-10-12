package com.jobs.newjobs;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class NewJobsApplication {

	public static void main(String[] args) {
		SpringApplication.run(NewJobsApplication.class, args);
	}

}
