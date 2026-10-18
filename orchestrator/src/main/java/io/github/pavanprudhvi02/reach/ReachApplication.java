package io.github.pavanprudhvi02.reach;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ReachApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReachApplication.class, args);
    }
}
