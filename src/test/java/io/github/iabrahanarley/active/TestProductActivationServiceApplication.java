package io.github.iabrahanarley.active;

import org.springframework.boot.SpringApplication;

public class TestProductActivationServiceApplication {

	public static void main(String[] args) {
		SpringApplication.from(ProductActivationServiceApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
