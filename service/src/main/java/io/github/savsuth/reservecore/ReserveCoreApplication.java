package io.github.savsuth.reservecore;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ReserveCoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReserveCoreApplication.class, args);
    }
}
