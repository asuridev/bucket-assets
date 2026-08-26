package com.bnpparibas.cardif.cloud.contentms.infrastructure.configurations.storage;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registra {@link StorageProperties} como bean. Al implementar el puerto
 * {@code StoragePolicies}, con esto queda tambien satisfecha esa dependencia de los
 * casos de uso.
 */
@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class StoragePolicyConfig {
}
