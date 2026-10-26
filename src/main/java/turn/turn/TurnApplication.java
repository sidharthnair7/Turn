package turn.turn;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class TurnApplication {

    public static void main(String[] args) {
        SpringApplication.run(TurnApplication.class, args);
    }

}
