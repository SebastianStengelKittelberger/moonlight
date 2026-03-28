package de.kittelberger.moonshine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching
public class MoonshineApplication {

	public static void main(String[] args) {
		SpringApplication.run(MoonshineApplication.class, args);
	}

}
