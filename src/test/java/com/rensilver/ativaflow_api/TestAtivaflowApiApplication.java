package com.rensilver.ativaflow_api;

import org.springframework.boot.SpringApplication;

public class TestAtivaflowApiApplication {

	public static void main(String[] args) {
		SpringApplication.from(AtivaflowApiApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
