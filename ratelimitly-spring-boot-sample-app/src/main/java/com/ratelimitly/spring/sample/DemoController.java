package com.ratelimitly.spring.sample;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/demo")
public class DemoController {
    private final DemoService demoService;

    public DemoController(DemoService demoService) {
        this.demoService = demoService;
    }

    @GetMapping("/users/{userId}")
    public Map<String, Object> user(@PathVariable("userId") String userId) {
        return demoService.loadUserGreeting(userId);
    }

    @GetMapping("/customers/{customerId}")
    public Map<String, Object> customer(
        @PathVariable("customerId") String customerId,
        @RequestParam(name = "region", defaultValue = "global") String region,
        @RequestParam(name = "status", defaultValue = "active") String status
    ) {
        return demoService.loadCustomerGreeting(customerId, region, status);
    }

    @GetMapping("/slow/{customerId}")
    public Map<String, Object> slow(
        @PathVariable("customerId") String customerId,
        @RequestParam(name = "region", defaultValue = "global") String region
    ) {
        return demoService.loadSlowCustomerOperation(customerId, region);
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "ok");
        payload.put("note", "set RATELIMITLY_ENABLED=true and RATELIMITLY_API_KEY to enable live enforcement");
        return payload;
    }
}
