package com.ratelimitly.spring.autoconfigure;

import com.ratelimitly.DnsResolver;
import com.ratelimitly.RateLimitlyClient;
import com.ratelimitly.RateLimitlyClients;
import com.ratelimitly.RateLimitlyException;
import com.ratelimitly.spring.properties.RateLimitlyProperties;
import com.ratelimitly.spring.support.ClientConfigMapper;
import com.ratelimitly.spring.support.RateLimitDecisionEvaluator;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnClass(RateLimitlyClient.class)
@ConditionalOnProperty(prefix = "ratelimitly", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(RateLimitlyProperties.class)
public class RateLimitlyAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public RateLimitDecisionEvaluator rateLimitDecisionEvaluator() {
        return new RateLimitDecisionEvaluator();
    }

    @Bean(name = RateLimitlyBeanNames.CLIENT_CONFIG_MAPPER)
    @ConditionalOnMissingBean
    public ClientConfigMapper rateLimitlyClientConfigMapper() {
        return new ClientConfigMapper();
    }

    @Bean(name = RateLimitlyBeanNames.CLIENT, destroyMethod = "close")
    @ConditionalOnMissingBean
    public RateLimitlyClient rateLimitlyClient(
        RateLimitlyProperties properties,
        ClientConfigMapper clientConfigMapper,
        ObjectProvider<DnsResolver> dnsResolverProvider
    ) throws RateLimitlyException {
        return RateLimitlyClients.create(clientConfigMapper.map(properties, dnsResolverProvider.getIfAvailable()));
    }
}
