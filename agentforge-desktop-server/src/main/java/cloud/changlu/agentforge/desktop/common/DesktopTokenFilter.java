package cloud.changlu.agentforge.desktop.common;

import cloud.changlu.agentforge.desktop.config.DesktopProperties;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DesktopTokenFilter extends OncePerRequestFilter {

    public static final String TOKEN_HEADER = "X-AgentForge-Desktop-Token";

    private final DesktopProperties properties;

    public DesktopTokenFilter(DesktopProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }
        String expected = properties.getToken();
        if (StringUtils.hasText(expected)) {
            String actual = request.getHeader(TOKEN_HEADER);
            if (!expected.equals(actual)) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":401,\"msg\":\"invalid desktop token\"}");
                return;
            }
        }
        filterChain.doFilter(request, response);
    }
}
