package com.chaekcheck.embed;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class EmbedServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(EmbedServiceApplication.class, args);
	}

}
