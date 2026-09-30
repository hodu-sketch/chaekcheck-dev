package com.chaekcheck;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ChaekcheckApplication {

	public static void main(String[] args) {
		TlsCompatibility.allowRsaKeyExchange();
		SpringApplication.run(ChaekcheckApplication.class, args);
	}

}
