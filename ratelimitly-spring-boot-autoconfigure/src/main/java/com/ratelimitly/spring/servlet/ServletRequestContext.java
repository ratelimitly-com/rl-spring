package com.ratelimitly.spring.servlet;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public record ServletRequestContext(
    HttpServletRequest request,
    HttpServletResponse response,
    Object handler
) {
}
