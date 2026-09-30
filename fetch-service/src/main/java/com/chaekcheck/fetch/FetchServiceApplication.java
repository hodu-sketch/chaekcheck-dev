package com.chaekcheck.fetch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class FetchServiceApplication {

	public static void main(String[] args) {
		TlsCompatibility.allowRsaKeyExchange();
		SpringApplication.run(FetchServiceApplication.class, args);
	}

}
