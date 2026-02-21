package com.ratelimiter.redis_rate_limiter.filter;

import com.ratelimiter.redis_rate_limiter.service.RateLimiterService;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class RateLimitFilter implements Filter {

    private final RateLimiterService rateLimiterService;

    @Override
    public void doFilter(ServletRequest request,
                         ServletResponse response,
                         FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String userId = httpRequest.getHeader("userId");

        if (!rateLimiterService.isAllowed(userId)) {
            httpResponse.setStatus(429);
            httpResponse.getWriter().write("Too many requests");
            return;
        }

        chain.doFilter(request, response);
    }
}
