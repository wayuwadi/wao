package cn.wayuwa.wao;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class WaoServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(WaoServerApplication.class, args);
    }
}
