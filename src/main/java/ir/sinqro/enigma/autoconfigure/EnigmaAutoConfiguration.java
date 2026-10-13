package ir.sinqro.enigma.autoconfigure;

import ir.sinqro.enigma.specification.*;
import jakarta.persistence.EntityManager;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.data.jpa.domain.Specification;

import java.time.ZoneId;

@AutoConfiguration
@ConditionalOnClass({Specification.class, EntityManager.class})
@EnableConfigurationProperties(SpecProperties.class)
public class EnigmaAutoConfiguration {


    @Bean
    @ConditionalOnMissingBean
    public ConversionService conversionService() {
        return new DefaultConversionService();
    }

    @Bean
    @ConditionalOnMissingBean
    public FieldMetaRegistry fieldMetaRegistry() {
        return new FieldMetaRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    public ValueConverter valueConverter(ConversionService conversionService, SpecProperties properties) {
        ZoneId zoneId = ZoneId.of(properties.getTimezone());
        return new ValueConverter(conversionService, zoneId, properties.getSpec().getMaxInSize());
    }

    @Bean
    @ConditionalOnMissingBean
    public PathResolver pathResolver(FieldMetaRegistry fieldMetaRegistry) {
        return new PathResolver(fieldMetaRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public DtoMapperHelper dtoMapperHelper() {
        return new DtoMapperHelper();
    }

    @Bean
    @ConditionalOnMissingBean
    public CollectionProjectionPlanner collectionProjectionPlanner() {
        return new CollectionProjectionPlanner();
    }

    @Bean
    @ConditionalOnMissingBean
    public CollectionProjectionLoader collectionProjectionLoader(EntityManager entityManager,
                                                                 ConversionService conversionService,
                                                                 DtoMapperHelper dtoMapperHelper,
                                                                 SpecProperties properties) {
        SpecProperties.Spec spec = properties.getSpec();
        return new CollectionProjectionLoader(entityManager, conversionService, dtoMapperHelper,
                spec.getMaxJoins(), spec.getCollectionBatchSize(), spec.getMaxCollectionRows());
    }

    @Bean
    @ConditionalOnMissingBean
    public SpecificationBuilder specificationBuilder(FieldMetaRegistry fieldMetaRegistry,
                                                     ValueConverter valueConverter,
                                                     PathResolver pathResolver,
                                                     SpecProperties properties) {
        SpecProperties.Spec spec = properties.getSpec();
        return new SpecificationBuilder(
                fieldMetaRegistry,
                valueConverter,
                pathResolver,
                spec.getMaxFilterDepth(),
                spec.getMaxSortFields(),
                spec.getMaxFilterNodes(),
                spec.getMaxJoins());
    }
}