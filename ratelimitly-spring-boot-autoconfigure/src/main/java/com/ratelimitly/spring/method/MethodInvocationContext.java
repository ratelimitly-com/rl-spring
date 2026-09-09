package com.ratelimitly.spring.method;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record MethodInvocationContext(
    Object target,
    Method method,
    List<Object> arguments
) {
    public MethodInvocationContext {
        // Null is a valid application argument; keep an immutable defensive copy.
        arguments = arguments == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(arguments));
    }
}
