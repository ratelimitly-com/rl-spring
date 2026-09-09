package com.ratelimitly.spring.autoconfigure;

import org.aopalliance.aop.Advice;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.autoproxy.AbstractAdvisorAutoProxyCreator;
import org.springframework.aop.framework.autoproxy.DefaultAdvisorAutoProxyCreator;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.Pointcuts;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Role;

import com.ratelimitly.RateLimitlyClient;
import com.ratelimitly.ApiKeyDecoder;
import com.ratelimitly.RateLimitlyException;
import com.ratelimitly.spring.method.DefaultMethodDenialHandler;
import com.ratelimitly.spring.method.DefaultMethodFailureHandler;
import com.ratelimitly.spring.method.DefaultMethodRateLimitlyLabelResolver;
import com.ratelimitly.spring.method.DefaultMethodRateLimitlyPolicyResolver;
import com.ratelimitly.spring.method.MethodExpressionEvaluator;
import com.ratelimitly.spring.method.MethodDenialHandler;
import com.ratelimitly.spring.method.MethodInvocationContext;
import com.ratelimitly.spring.method.MethodRateLimitEnforcer;
import com.ratelimitly.spring.method.RateLimited;
import com.ratelimitly.spring.method.RateLimitlyMethodInterceptor;
import com.ratelimitly.spring.observation.RateLimitlyObservationRecorder;
import com.ratelimitly.spring.policy.RateLimitlyFailureHandler;
import com.ratelimitly.spring.policy.RateLimitlyLabelResolver;
import com.ratelimitly.spring.policy.RateLimitlyPolicyResolver;
import com.ratelimitly.spring.properties.RateLimitlyProperties;
import com.ratelimitly.spring.support.RateLimitDecisionEvaluator;

@AutoConfiguration(after = RateLimitlyAutoConfiguration.class,
    afterName = "org.springframework.boot.autoconfigure.aop.AopAutoConfiguration")
@ConditionalOnClass(Advice.class)
@ConditionalOnBean(RateLimitlyClient.class)
@ConditionalOnProperty(prefix = "ratelimitly", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "ratelimitly.method", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RateLimitlyMethodAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public MethodExpressionEvaluator methodExpressionEvaluator(RateLimitlyProperties properties) throws RateLimitlyException {
        String apiKeyId = properties.getApiKey() == null || properties.getApiKey().isBlank() ? ""
            : Long.toUnsignedString(ApiKeyDecoder.decode(properties.getApiKey()).keyId());
        return new MethodExpressionEvaluator(apiKeyId);
    }

    @Bean
    @ConditionalOnMissingBean
    public RateLimitlyPolicyResolver<MethodInvocationContext> methodRateLimitlyPolicyResolver(
        RateLimitlyProperties properties,
        MethodExpressionEvaluator expressionEvaluator
    ) {
        return new DefaultMethodRateLimitlyPolicyResolver(properties, expressionEvaluator);
    }

    @Bean
    @ConditionalOnMissingBean
    public RateLimitlyLabelResolver<MethodInvocationContext> methodRateLimitlyLabelResolver(
        MethodExpressionEvaluator expressionEvaluator
    ) {
        return new DefaultMethodRateLimitlyLabelResolver(expressionEvaluator);
    }

    @Bean
    @ConditionalOnMissingBean
    public MethodDenialHandler methodDenialHandler() {
        return new DefaultMethodDenialHandler();
    }

    @Bean
    @ConditionalOnMissingBean
    public RateLimitlyFailureHandler<MethodInvocationContext> methodRateLimitlyFailureHandler() {
        return new DefaultMethodFailureHandler();
    }

    @Bean
    @ConditionalOnMissingBean
    public MethodRateLimitEnforcer methodRateLimitEnforcer(
        RateLimitlyClient client,
        RateLimitlyPolicyResolver<MethodInvocationContext> policyResolver,
        RateLimitlyLabelResolver<MethodInvocationContext> labelResolver,
        MethodDenialHandler denialHandler,
        RateLimitlyFailureHandler<MethodInvocationContext> failureHandler,
        RateLimitDecisionEvaluator decisionEvaluator,
        RateLimitlyObservationRecorder observationRecorder,
        RateLimitlyProperties properties
    ) {
        return new MethodRateLimitEnforcer(
            client,
            policyResolver,
            labelResolver,
            denialHandler,
            failureHandler,
            decisionEvaluator,
            observationRecorder,
            properties.getClient().isDebug()
        );
    }

    @Bean
    @ConditionalOnMissingBean(name = "rateLimitlyMethodInterceptor")
    public MethodInterceptor rateLimitlyMethodInterceptor(MethodRateLimitEnforcer enforcer) {
        return new RateLimitlyMethodInterceptor(enforcer);
    }

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    @ConditionalOnMissingBean(name = "rateLimitlyMethodAdvisor")
    public Advisor rateLimitlyMethodAdvisor(
        @Qualifier("rateLimitlyMethodInterceptor") MethodInterceptor interceptor
    ) {
        return new DefaultPointcutAdvisor(
            Pointcuts.union(
                AnnotationMatchingPointcut.forMethodAnnotation(RateLimited.class),
                AnnotationMatchingPointcut.forClassAnnotation(RateLimited.class)
            ),
            interceptor
        );
    }

    @Bean
    public static BeanFactoryPostProcessor rateLimitlyAdvisorAutoProxyCreatorRegistration() {
        return beanFactory -> {
            // The intentionally named override is the admission advisor too; do not
            // silently exclude it when reusing an infrastructure-only creator.
            if (beanFactory.containsBeanDefinition("rateLimitlyMethodAdvisor")) {
                beanFactory.getBeanDefinition("rateLimitlyMethodAdvisor").setRole(BeanDefinition.ROLE_INFRASTRUCTURE);
            }
            // Boot may register its infrastructure creator from a factory post-processor,
            // too late for ConditionalOnMissingBean during configuration parsing.
            // Run after Boot's AOP configuration and inspect definitions without creating beans.
            if (beanFactory.getBeanNamesForType(AbstractAdvisorAutoProxyCreator.class, true, false).length == 0) {
                if (!(beanFactory instanceof BeanDefinitionRegistry registry)) {
                    throw new IllegalStateException("RateLimitly requires an advisor-aware auto-proxy creator");
                }
                RootBeanDefinition creator = new RootBeanDefinition(DefaultAdvisorAutoProxyCreator.class);
                creator.setRole(BeanDefinition.ROLE_INFRASTRUCTURE);
                creator.getPropertyValues().add("proxyTargetClass", true);
                registry.registerBeanDefinition("rateLimitlyAdvisorAutoProxyCreator", creator);
            }
        };
    }
}
