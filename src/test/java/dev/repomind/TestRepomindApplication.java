package dev.repomind;

import org.springframework.boot.SpringApplication;

public class TestRepomindApplication {

	public static void main(String[] args) {
		SpringApplication.from(RepomindApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
