package com.ratelimitly.spring.method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ratelimitly.spring.properties.RateLimitlyProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

class ExpressionBoundaryTest {
    private final MethodExpressionEvaluator evaluator = new MethodExpressionEvaluator("42");
    @AfterEach void clearRequest() { RequestContextHolder.resetRequestAttributes(); }

    @Test
    void httpInputsCannotReplaceReservedObjectsOrPositionalArguments() throws Exception {
        var request = new MockHttpServletRequest("GET", "/trusted-route");
        Map<String, String> collisions = new LinkedHashMap<>();
        for (String name : List.of("apiKeyId", "arguments", "method", "target", "request", "pathVariables", "queryParams", "a0", "p0")) {
            collisions.put(name, "path-" + name);
            request.addParameter(name, "query-" + name);
        }
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, collisions);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        var context = context("named", "argument");
        assertThat(evaluator.evaluate("{#request.requestURI}:{#method.name}:{#arguments[0]}:{#a0}:{#p0}:{#apiKeyId}", context))
            .isEqualTo("/trusted-route:named:argument:argument:argument:42");
        assertThat(evaluator.evaluate("{#pathVariables['request']}:{#queryParams['request']}", context))
            .isEqualTo("path-request:query-request");
        assertThat(evaluator.evaluate("{#target == #root}", context)).isEqualTo("true");
    }

    @Test
    void methodArgumentNamesCannotReplaceReservedMetadataEvenAfterLookup() throws Exception {
        var method = Target.class.getMethod("colliding", String.class, String.class, String.class, String.class,
            String.class, String.class, String.class, String.class, String.class);
        var context = new MethodInvocationContext(new Target(), method,
            List.of("first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth"));
        assertThat(evaluator.evaluate("{#p0}:{#arguments[1]}:{#method.name}:{#apiKeyId}:{#request == null}:{#pathVariables.size()}:{#queryParams.size()}:{#a0}", context))
            .isEqualTo("first:second:colliding:42:true:0:0:first");
    }

    @Test
    void namedArgumentPrecedenceDoesNotDependOnExpressionReadOrder() throws Exception {
        var request = new MockHttpServletRequest();
        request.addParameter("customer", "query");
        request.addParameter("region", "query-region");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
            Map.of("customer", "path", "region", "path-region"));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        var context = context("named", "argument");
        assertThat(evaluator.evaluate("{#customer}:{#p0}:{#customer}:{#region}", context))
            .isEqualTo("argument:argument:argument:path-region");
        assertThat(evaluator.evaluate("{#p0}:{#customer}", context)).isEqualTo("argument:argument");
    }

    @Test
    void substitutedInputIsNotEvaluatedAgain() throws Exception {
        String data = "{#apiKeyId}:#{literal-data}";
        assertThat(evaluator.evaluate("bucket:{#p0}", context("named", data))).isEqualTo("bucket:" + data);
        assertThat(evaluator.evaluate("{T(java.lang.Math).max(2, 3)}", context("named", data))).isEqualTo("3");
    }

    @Test
    void nullArgumentWinsOverHttpInputAndMissingAliasesStayReserved() throws Exception {
        var request = new MockHttpServletRequest();
        request.addParameter("customer", "query-customer");
        request.addParameter("a9", "query-alias");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
            Map.of("customer", "path-customer", "p9", "path-alias"));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        assertThat(evaluator.evaluate("{#customer == null}:{#a0 == null}:{#p9 == null}:{#a9 == null}", context("named", null)))
            .isEqualTo("true:true:true:true");
    }

    @Test
    void invocationArgumentsAreDefensivelyCopiedWithoutRejectingNull() throws Exception {
        var arguments = new java.util.ArrayList<Object>(Arrays.asList((Object) null));
        var context = new MethodInvocationContext(new Target(), Target.class.getMethod("named", String.class), arguments);
        arguments.set(0, "changed");
        assertThat(context.arguments()).containsExactly((Object) null);
        assertThrows(UnsupportedOperationException.class, () -> context.arguments().set(0, "changed"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void blankResolvedGuardIsRejectedInsteadOfSilentlyRemoved(String value) throws Exception {
        var resolver = new DefaultMethodRateLimitlyPolicyResolver(new RateLimitlyProperties(), evaluator);
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(context("guarded", value)));
    }

    @Test
    void nonblankGuardStillUsesTheExactResolvedName() throws Exception {
        var resolver = new DefaultMethodRateLimitlyPolicyResolver(new RateLimitlyProperties(), evaluator);
        assertThat(resolver.resolve(context("guarded", "service-A")).guards().getFirst().latencyTrackerName())
            .isEqualTo("service-A");
    }

    private static MethodInvocationContext context(String method, String value) throws Exception {
        return new MethodInvocationContext(new Target(), Target.class.getMethod(method, String.class), Arrays.asList(value));
    }
    public static class Target {
        public void named(String customer) { }
        public void colliding(String apiKeyId, String arguments, String method, String target, String request,
                String pathVariables, String queryParams, String a0, String p0) { }
        @RateLimited(guards = @MethodLatencyGuard(service = "{#p0}"))
        public void guarded(String service) { }
    }
}
