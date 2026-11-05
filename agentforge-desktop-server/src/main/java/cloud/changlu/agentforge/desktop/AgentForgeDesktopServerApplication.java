package cloud.changlu.agentforge.desktop;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("cloud.changlu.agentforge.desktop.modules.session.dao")
public class AgentForgeDesktopServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentForgeDesktopServerApplication.class, args);
    }
}
