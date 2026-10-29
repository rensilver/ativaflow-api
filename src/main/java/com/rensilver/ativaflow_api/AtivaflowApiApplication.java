package com.rensilver.ativaflow_api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AtivaflowApiApplication {

	public static void main(String[] args) {
		SpringApplication.run(AtivaflowApiApplication.class, args);
	}

}
