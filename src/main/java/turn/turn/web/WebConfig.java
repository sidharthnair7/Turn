package turn.turn.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Short page URLs: / is the landing page, /floor the host view, /wait the guest page (all in frontend/). */
@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/floor").setViewName("forward:/floor.html");
        registry.addViewController("/wait").setViewName("forward:/wait.html");
    }
}
