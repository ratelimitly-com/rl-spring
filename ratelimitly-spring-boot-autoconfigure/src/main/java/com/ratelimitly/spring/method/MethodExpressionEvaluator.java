package com.ratelimitly.spring.method;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

public class MethodExpressionEvaluator {
    private static final Pattern TEMPLATE_EXPRESSION = Pattern.compile("\\{([^{}]+)\\}");
    private static final Pattern POSITIONAL_ALIAS = Pattern.compile("[ap][0-9]+");

    private final String apiKeyId;
    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();
    private final ExpressionParser expressionParser = new SpelExpressionParser();

    public MethodExpressionEvaluator(String apiKeyId) {
        this.apiKeyId = apiKeyId == null ? "" : apiKeyId;
    }

    public String evaluate(String value, MethodInvocationContext context) {
        if (value == null || value.isBlank()) {
            return value;
        }

        StandardEvaluationContext evaluationContext = createEvaluationContext(context);
        Matcher matcher = TEMPLATE_EXPRESSION.matcher(value);
        if (matcher.find()) {
            StringBuilder resolved = new StringBuilder();
            int last = 0;
            do {
                resolved.append(value, last, matcher.start());
                Object evaluated = evaluateExpression(matcher.group(1), evaluationContext);
                if (evaluated != null) {
                    resolved.append(evaluated);
                }
                last = matcher.end();
            } while (matcher.find());
            resolved.append(value.substring(last));
            return resolved.toString();
        }

        if (value.startsWith("#")) {
            Object evaluated = evaluateExpression(value, evaluationContext);
            return evaluated == null ? "" : evaluated.toString();
        }

        return value;
    }

    private Object evaluateExpression(String expression, StandardEvaluationContext evaluationContext) {
        Expression parsedExpression = expressionParser.parseExpression(expression);
        return parsedExpression.getValue(evaluationContext);
    }

    private StandardEvaluationContext createEvaluationContext(MethodInvocationContext context) {
        Object requestAttributes = RequestContextHolder.getRequestAttributes();
        HttpServletRequest request = requestAttributes instanceof ServletRequestAttributes attributes
            ? attributes.getRequest() : null;
        Map<String, Object> pathVariables = request == null ? Map.of() : resolvePathVariables(request);
        Map<String, Object> queryParams = request == null ? Map.of() : resolveQueryParams(request);
        var method = MethodIdentitySupport.resolveMethod(context);

        // Build eagerly, from lowest to highest precedence. Spring's lazy method
        // argument loading would otherwise make collisions depend on read order.
        Map<String, Object> variables = new LinkedHashMap<>(queryParams);
        variables.putAll(pathVariables);
        String[] parameterNames = parameterNameDiscoverer.getParameterNames(method);
        if (parameterNames != null) {
            for (int i = 0; i < parameterNames.length && i < context.arguments().size(); i++) {
                variables.put(parameterNames[i], context.arguments().get(i));
            }
        }
        // Reserve aliases even for indices absent from this invocation.
        variables.keySet().removeIf(name -> POSITIONAL_ALIAS.matcher(name).matches());
        for (int i = 0; i < context.arguments().size(); i++) {
            variables.put("a" + i, context.arguments().get(i));
            variables.put("p" + i, context.arguments().get(i));
        }
        variables.put("apiKeyId", apiKeyId);
        variables.put("arguments", context.arguments());
        variables.put("method", method);
        variables.put("target", context.target());
        variables.put("request", request);
        variables.put("pathVariables", pathVariables);
        variables.put("queryParams", queryParams);
        StandardEvaluationContext evaluationContext = new StandardEvaluationContext(context.target());
        evaluationContext.setVariables(variables);
        return evaluationContext;
    }

    private Map<String, Object> resolvePathVariables(HttpServletRequest request) {
        Object attribute = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (!(attribute instanceof Map<?, ?> rawPathVariables)) {
            return Map.of();
        }
        Map<String, Object> pathVariables = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawPathVariables.entrySet()) {
            if (entry.getKey() != null) {
                pathVariables.put(entry.getKey().toString(), entry.getValue());
            }
        }
        return pathVariables;
    }

    private Map<String, Object> resolveQueryParams(HttpServletRequest request) {
        Map<String, Object> queryParams = new LinkedHashMap<>();
        request.getParameterMap().forEach((name, values) -> {
            if (values == null || values.length == 0) {
                queryParams.put(name, "");
            } else if (values.length == 1) {
                queryParams.put(name, values[0]);
            } else {
                queryParams.put(name, List.of(values));
            }
        });
        return queryParams;
    }
}
