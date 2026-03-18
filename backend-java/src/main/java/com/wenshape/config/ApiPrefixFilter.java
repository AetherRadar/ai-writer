package com.wenshape.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 兼容 Python 后端的 /api 前缀过滤器
 * Python 后端同时挂载了 / 和 /api 前缀，这里做同样处理：
 * /api/projects/... → /projects/...
 */
@Component
@Order(1)
public class ApiPrefixFilter extends OncePerRequestFilter {

    private static final String API_PREFIX = "/api";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String uri = request.getRequestURI();

        if (uri.startsWith(API_PREFIX + "/") || uri.equals(API_PREFIX)) {
            String stripped = uri.substring(API_PREFIX.length());
            if (stripped.isEmpty()) stripped = "/";

            final String newUri = stripped;
            HttpServletRequestWrapper wrapper = new HttpServletRequestWrapper(request) {
                @Override
                public String getRequestURI() {
                    return newUri;
                }

                @Override
                public String getServletPath() {
                    return newUri;
                }

                @Override
                public StringBuffer getRequestURL() {
                    StringBuffer url = new StringBuffer();
                    url.append(request.getScheme()).append("://")
                       .append(request.getServerName());
                    int port = request.getServerPort();
                    if (port != 80 && port != 443) {
                        url.append(":").append(port);
                    }
                    url.append(newUri);
                    return url;
                }
            };
            filterChain.doFilter(wrapper, response);
        } else {
            filterChain.doFilter(request, response);
        }
    }
}
