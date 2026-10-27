package turn.turn.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Short page URLs: / is the landing page, /floor the host view, /wait the guest page (all in frontend/). */
@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final StaffAccess staffAccess;

    public WebConfig(StaffAccess staffAccess) {
        this.staffAccess = staffAccess;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(staffAccess);
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/floor").setViewName("forward:/floor.html");
        registry.addViewController("/wait").setViewName("forward:/wait.html");
        registry.addViewController("/flow").setViewName("forward:/flow.html");
        registry.addViewController("/login").setViewName("forward:/login.html");
    }
}
