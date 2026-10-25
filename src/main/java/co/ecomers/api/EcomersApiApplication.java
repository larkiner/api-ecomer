package co.ecomers.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class EcomersApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(EcomersApiApplication.class, args);
    }
}
