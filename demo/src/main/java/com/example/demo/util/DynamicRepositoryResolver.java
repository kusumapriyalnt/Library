package com.example.demo.util;


import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Component;
import jakarta.persistence.EntityManager;
import org.springframework.context.ApplicationContext;
import org.springframework.data.repository.core.RepositoryMetadata;
import java.util.Map;

@Component
public class DynamicRepositoryResolver {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private EntityManager entityManager;

    public JpaRepository<?, ?> getRepositoryForEntity(Class<?> entityClass) {
        // Get all beans of type JpaRepository
        Map<String, JpaRepository> repositories = applicationContext.getBeansOfType(JpaRepository.class);

        CustomJpaRepositoryFactory factory = new CustomJpaRepositoryFactory(entityManager);

        for (Map.Entry<String, JpaRepository> entry : repositories.entrySet()) {
            String beanName = entry.getKey();
            JpaRepository<?, ?> repository = entry.getValue();

            // Use Spring's ApplicationContext to find the original interface
            Class<?> repositoryInterface = applicationContext.getType(beanName);

            if (repositoryInterface == null) {
                continue;
            }

            try {
                RepositoryMetadata metadata = factory.getMetadataFor(repositoryInterface);

                if (metadata.getDomainType().equals(entityClass)) {
                    return repository;
                }
            } catch (IllegalArgumentException ex) {
                // Log or handle the exception if necessary
                System.err.println("Skipping bean " + beanName + ": " + ex.getMessage());
            }
        }
        return null;
    }
}
