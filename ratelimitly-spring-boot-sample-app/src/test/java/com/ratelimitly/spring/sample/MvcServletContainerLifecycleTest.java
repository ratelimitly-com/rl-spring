package com.ratelimitly.spring.sample;

import org.springframework.boot.test.context.SpringBootTest;

/** Exercise the same real-container contract at the alternative MVC admission boundary. */
@SpringBootTest(classes = ServletContainerLifecycleTest.Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"ratelimitly.enabled=true", "ratelimitly.method.enabled=false",
        "ratelimitly.servlet.filter-enabled=false", "ratelimitly.servlet.interceptor-enabled=true"})
class MvcServletContainerLifecycleTest extends ServletContainerLifecycleTest {
}
