package com.example.demo.listener;

import com.example.demo.config.JsonLoader;
import com.example.demo.dto.*;
import com.example.demo.util.DynamicRepositoryResolver;
import com.example.demo.util.SpringContext;
import jakarta.persistence.Id;
import org.apache.commons.jexl3.*;
import org.hibernate.event.spi.PostInsertEvent;
import org.hibernate.event.spi.PostInsertEventListener;
import org.hibernate.event.spi.PostUpdateEvent;
import org.hibernate.event.spi.PostUpdateEventListener;
import org.hibernate.persister.entity.EntityPersister;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class EntityListener implements PostUpdateEventListener, PostInsertEventListener {
    private BaseConfig baseConfig;
    private JsonLoader jsonLoader;
    private DynamicRepositoryResolver repositoryResolver;
    private static final String REDIS_CHANNEL = "entity-status-channel";
    private final Jedis jedis;
    DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
    public EntityListener() {
        this.jedis = new Jedis("localhost", 6379); // Redis connection
    }

    @Override
    public void onPostInsert(PostInsertEvent postInsertEvent) {
        //load json file everytime
        loadJsonConfig();
        if(this.repositoryResolver==null){
            this.repositoryResolver = SpringContext.getBean(DynamicRepositoryResolver.class);
        }
        // Find configuration for insertion
        Object updatedEntity = postInsertEvent.getEntity();
        Entity configEntity = getConfigEntity(updatedEntity.getClass().getSimpleName());
        if (configEntity != null) {
            System.out.println("PostPersist for entity: " + updatedEntity.getClass().getSimpleName());
            Insertion insertion = configEntity.getInsertion();
            if("Yes".equalsIgnoreCase(insertion.getOnInsertion())){
                String message = "Row is added to "+ updatedEntity.getClass().getSimpleName()+" : "+ updatedEntity;
                jedis.publish(REDIS_CHANNEL, message);
            }
            if(insertion.getRowwiseConditions() != null){
                checkRowWiseConditions(insertion.getRowwiseConditions(),updatedEntity);
            }
        }
    }
    private void loadJsonConfig() {
        if(this.jsonLoader == null){
            this.jsonLoader = SpringContext.getBean(JsonLoader.class);
        }
        this.baseConfig = jsonLoader.getBaseConfig();
    }

    private Object getPrimaryKeyValue(Object entity) throws IllegalAccessException {
        Class<?> entityClass = entity.getClass();
        // Loop through all fields of the entity class
        for (Field field : entityClass.getDeclaredFields()) {
            if (field.isAnnotationPresent(Id.class)) {
                // Found the primary key field
                field.setAccessible(true); // Ensure we can access private fields
                return field.get(entity); // Return the primary key value
            }
        }
        throw new IllegalStateException("No primary key (@Id) field found in entity: " + entityClass.getSimpleName());
    }
    private void checkRowWiseConditions(List<RowwiseCondition> rowwiseConditions, Object entity) {
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
        JpaRepository<?, ?> repository = repositoryResolver.getRepositoryForEntity(entity.getClass());
        if (repository != null) {
            System.out.println("Repository found for entity: " + entity.getClass().getSimpleName());
        } else {
            System.err.println("No repository found for entity: " + entity.getClass().getSimpleName());
        }
        for (RowwiseCondition row: rowwiseConditions) {
            String columnName = row.getColumnName();
            List<Condition> conditions = row.getConditions();
            for (Condition condition:conditions) {
                String checkExpression = condition.getCheck();
                String delay = condition.getDelayInMin();
                String checkOnce = condition.getCheckOnce();
                Runnable task = () -> {
                    Object repoEntity = null;
                    try {
                        // Get the primary key value from the entity
                        Object primaryKeyValue = getPrimaryKeyValue(entity);
                        Optional<?> entityOptional = null;
                        // Get the primary key's type dynamically
                        Class<?> primaryKeyType = primaryKeyValue.getClass();
                        if (primaryKeyType.equals(Long.class)) {
                            Long pk = (Long) primaryKeyValue;
                            entityOptional = ((JpaRepository<Object, Long>) repository).findById(pk);
                        } else if (primaryKeyType.equals(String.class)) {
                            String pk = (String) primaryKeyValue;
                            entityOptional = ((JpaRepository<Object, String>) repository).findById(pk);
                        } else if (primaryKeyType.equals(Integer.class)) {
                            Integer pk = (Integer) primaryKeyValue;
                            entityOptional = ((JpaRepository<Object, Integer>) repository).findById(pk);
                        } else {
                            System.err.println("Unsupported primary key type: " + primaryKeyType.getSimpleName());
                        }
                        if(entityOptional!=null && entityOptional.isPresent()){
                            repoEntity= entityOptional.get();
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    Boolean result = replacePlaceholders(checkExpression,repoEntity, columnName);
                    if (result) {
                        replacePlaceholdersInMessage(condition.getMessage(), repoEntity);
                    }else{
                        scheduler.shutdown();
                    }
                };
                if (checkOnce != null){
                    if(checkOnce.equalsIgnoreCase("Yes")){
                        int delayInMinutes = delay != null && !delay.isEmpty() ? Integer.parseInt(delay) : 0;
                        if(delayInMinutes>0){
                            scheduler.schedule(task, delayInMinutes, TimeUnit.MINUTES);
                        }else{
                            task.run();
                        }
                    }// add code if checkOnce is "No"
                }else {
                    System.out.println("There is no check condition here");
                }
            }
        }
    }
    private Boolean replacePlaceholders(String expression, Object entity, String mainField) {
        try {
            // Resolve the main field value
            Field mainFieldRef = entity.getClass().getDeclaredField(mainField);
            mainFieldRef.setAccessible(true);
            Object mainFieldValue = mainFieldRef.get(entity);
            if (expression.contains(".")) {
                String regex = "(==|!=|<=|>=|<|>)";
                // Handle nested fields
                String[] parts = expression.split(regex); // Split into field and expected value
                if (parts.length != 2) {
                    throw new IllegalArgumentException("Invalid expression format: " + expression);
                }
                String fieldChain = parts[0].trim(); // The chain of fields, e.g., "workOrder.priority.codeDisplayText"
                if (fieldChain.startsWith(mainField)) {
                    // Extract the nested fields after the mainField
                    String nestedFields = fieldChain.substring(mainField.length() + 1); // Skip the mainField and dot
                    Object resolvedValue = resolveNestedFieldValue(mainFieldValue, nestedFields);

                    // Replace the expression and evaluate
                    if (resolvedValue != null) {
                        expression = expression.replace(parts[0], resolvedValue.toString());
                        return evaluateExpression(expression); // Evaluate the final expression
                    }
                }
            }
            else if(expression.contains(":")){


            }
            else {
                // Handle simple fields
                for (Field field : entity.getClass().getDeclaredFields()) {
                    field.setAccessible(true);
                    if (expression.contains(field.getName())) {
                        Object fieldValue = field.get(entity);
                        if (fieldValue == null || fieldValue.toString().isEmpty()) {
                            return evaluateExpression(expression.replace(field.getName(), ""));
                        } else {
                            expression = expression.replace(field.getName(), fieldValue.toString());
                            return evaluateExpression(expression);
                        }
                    }
                }
            }

            System.out.println(expression); // Debug log
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    /**
     * Recursively resolves the value of a nested field chain.
     *
     * @param entity The root object to start from.
     * @param fieldChain The chain of fields separated by dots.
     * @return The resolved value, or null if any field in the chain is null or not found.
     */
    private Object resolveNestedFieldValue(Object entity, String fieldChain) {
        try {
            if (entity == null || fieldChain == null || fieldChain.isEmpty()) {
                return null; // Base case
            }

            String[] fields = fieldChain.split("\\.", 2); // Split into current field and remaining chain
            String currentField = fields[0];
            String remainingChain = fields.length > 1 ? fields[1] : null;

            Field field = entity.getClass().getDeclaredField(currentField);
            field.setAccessible(true);
            Object fieldValue = field.get(entity);

            // Recurse if there's more in the chain; otherwise, return the resolved value
            return (remainingChain != null) ? resolveNestedFieldValue(fieldValue, remainingChain) : fieldValue;
        } catch (NoSuchFieldException | IllegalAccessException e) {
            System.err.println("Error resolving field chain: " + fieldChain);
            e.printStackTrace();
        }
        return null;
    }

    private Object getEntityByField(Object entity,String entityName, String fieldName, Object fieldValue) throws IllegalAccessException {
        // Get the entity class from the entity name
        Class<?> targetClass = entity.getClass();
        // Get the package name dynamically from the entity's class
        String packageName = targetClass.getPackage().getName();
        Class<?> entityClass;
        try {
            entityClass = Class.forName(packageName+ "." + entityName); // Adjust package name as needed
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("Entity class not found for name: " + entityName, e);
        }
        // Get the repository for the entity
        JpaRepository<?, ?> repository = repositoryResolver.getRepositoryForEntity(entityClass);
        if (repository == null) {
            throw new IllegalStateException("No repository found for entity: " + entityName);
        }
        // Find the field in the entity class
        Field targetField = null;
        for (Field field : entityClass.getDeclaredFields()) {
            if (field.getName().equals(fieldName)) {
                targetField = field;
                break;
            }
        }
        if (targetField == null) {
            throw new IllegalArgumentException("Field '" + fieldName + "' not found in entity: " + entityName);
        }
        List<?> results;
        try {
            // Assume the repository supports a `findBy<FieldName>` method
            String methodName = "findBy" + capitalize(fieldName);
            Method findByFieldMethod = repository.getClass().getMethod(methodName, targetField.getType());
            results = (List<?>) findByFieldMethod.invoke(repository, fieldValue);
        } catch (NoSuchMethodException e) {
            throw new UnsupportedOperationException(
                    "Repository does not support finding by field: " + fieldName, e);
        } catch (InvocationTargetException | IllegalAccessException e) {
            throw new RuntimeException("Error invoking repository method for finding by field: " + fieldName, e);
        }
        // Return the first result or null if no matches
        return results.isEmpty() ? null : results.get(0);
    }
    public boolean evaluateExpression(String expression) {
        try {
            System.out.println("Original Expression: " + expression);
            expression = replaceTimestamps(expression);
            expression = quoteStrings(expression);
            System.out.println("Transformed Expression: " + expression);
            JexlEngine jexl = new JexlBuilder().create();
            JexlExpression jexlExpression = jexl.createExpression(expression);
            JexlContext context = new MapContext();
            Object result = jexlExpression.evaluate(context);
            if (result instanceof Boolean) {
                return (Boolean) result;
            }
            return false;
        } catch (Exception e) {
            System.err.println("Error evaluating expression: " + expression);
            e.printStackTrace();
            return false;
        }
    }

    private String replaceTimestamps(String expression) {
        Pattern pattern = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}");
        Matcher matcher = pattern.matcher(expression);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String timestamp = matcher.group();
            try {
                LocalDateTime dateTime = LocalDateTime.parse(timestamp, FORMATTER);
                long epochMillis = dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                matcher.appendReplacement(result, String.valueOf(epochMillis));
            } catch (Exception ex) {
                System.err.println("Error parsing timestamp: " + timestamp);
                ex.printStackTrace();
            }
        }
        matcher.appendTail(result);
        return result.toString();
    }
    private String quoteStrings(String expression) {
        Pattern pattern = Pattern.compile("\\b([a-zA-Z]+)\\b");
        Matcher matcher = pattern.matcher(expression);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String token = matcher.group(1);
            matcher.appendReplacement(result, "\"" + token + "\"");
        }
        matcher.appendTail(result);
        return result.toString();
    }
    private void checkColumnWiseConditions(List<ColumnWiseCondition> columnWiseConditions, Object entity) {
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
        JpaRepository<?, ?> repository = repositoryResolver.getRepositoryForEntity(entity.getClass());
        if (repository != null) {
            System.out.println("Repository found for entity: " + entity.getClass().getSimpleName());
        } else {
            System.err.println("No repository found for entity: " + entity.getClass().getSimpleName());
        }
        for (ColumnWiseCondition column:columnWiseConditions) {
            String columnName = column.getColumnName();
            List<Condition> conditions = column.getConditions();
            try {
                Field columnField = entity.getClass().getDeclaredField(columnName);
                columnField.setAccessible(true);
                for (Condition condition : conditions) {
                    String check = condition.getCheck();
                    String delayInMin = condition.getDelayInMin();
                    String message = condition.getMessage();
                    String checkOnce = condition.getCheckOnce();
                    Runnable task = () -> {
                        Object repoEntity = null;
                        try {
                            // Get the primary key value from the entity
                            Object primaryKeyValue = getPrimaryKeyValue(entity);
                            Optional<?> entityOptional = null;
                            // Get the primary key's type dynamically
                            Class<?> primaryKeyType = primaryKeyValue.getClass();
                            if (primaryKeyType.equals(Long.class)) {
                                Long pk = (Long) primaryKeyValue;
                                entityOptional = ((JpaRepository<Object, Long>) repository).findById(pk);
                            } else if (primaryKeyType.equals(String.class)) {
                                String pk = (String) primaryKeyValue;
                                entityOptional = ((JpaRepository<Object, String>) repository).findById(pk);
                            } else if (primaryKeyType.equals(Integer.class)) {
                                Integer pk = (Integer) primaryKeyValue;
                                entityOptional = ((JpaRepository<Object, Integer>) repository).findById(pk);
                            } else {
                                System.err.println("Unsupported primary key type: " + primaryKeyType.getSimpleName());
                            }
                            if(entityOptional!=null && entityOptional.isPresent()){
                                repoEntity= entityOptional.get();
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                        boolean value = checkCondition(check, repoEntity, columnName);
                        if(value){
                            replacePlaceholdersInMessage(message,repoEntity);
                        }else{
                            scheduler.shutdown();
                        }
                    };
                    if(check!=null) {
                        if(checkOnce.equalsIgnoreCase("Yes")) {
                            int delayInMinutes = delayInMin != null && !delayInMin.isEmpty() ? Integer.parseInt(delayInMin) : 0;
                            if(delayInMinutes>0) {
                                scheduler.schedule(task,delayInMinutes,TimeUnit.MINUTES);
                            }else{
                                task.run();
                            }
                        }else{
                            int delayInMinutes = delayInMin != null && !delayInMin.isEmpty() ? Integer.parseInt(delayInMin) : 0;
                            if(delayInMinutes>0) {
                                scheduler.scheduleAtFixedRate(task,0,delayInMinutes,TimeUnit.MINUTES);
                            }else{
                               task.run();
                            }
                        }
                    }
                }
            } catch (NoSuchFieldException e) {
                e.printStackTrace();
            }
        }
    }
    private void replacePlaceholdersInMessage(String message, Object entity) {
        try {
            // Replace placeholders like ${id}, ${workOrderId}, etc.
            for (Field field : entity.getClass().getDeclaredFields()) {
                field.setAccessible(true);
                String placeholder = "${" + field.getName() + "}";
                if (message.contains(placeholder)) {
                    Object fieldValue = field.get(entity);
                    message = message.replace(placeholder, fieldValue != null ? fieldValue.toString() : "null");
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        jedis.publish(REDIS_CHANNEL, message);
    }
    private boolean checkCondition(String check, Object entity, String columnName) {
        try {
            Field columnField = entity.getClass().getDeclaredField(columnName);
            columnField.setAccessible(true); // Allow access to private fields
            Object columnValue = columnField.get(entity);// Retrieve the field value
            System.out.println(columnValue.toString());
            if (check.contains(",") && columnValue!=null) {
                String[] parts = check.split(",");
                // Handle the case where there are multiple values in the check condition
                if(parts.length<=2){
                    if (parts[0].contains("Any") && !parts[1].contains("Any")) {
                        if (columnValue.toString().equalsIgnoreCase(parts[1])) {
                            return true;
                        }
                    } else if (!parts[0].contains("Any") && parts[1].contains("Any")) {
                        if (columnValue.toString().equalsIgnoreCase(parts[0])) {
                            return true;
                        }
                    } else if (parts[0].contains("Any") && parts[1].contains("Any")) {
                        return false;
                    } else {
                        if (parts[1].contains("/")) {
                            String[] innerParts = parts[1].split("/");
                            return !columnValue.toString().equalsIgnoreCase(innerParts[0]) && !columnValue.toString().equalsIgnoreCase(innerParts[1]);
                        } else {
                            return !columnValue.toString().equalsIgnoreCase(parts[1]);
                        }
                    }
                }
            } else {
                // Handle the single value condition
                if (columnValue.toString().equalsIgnoreCase(check.trim())) {
                    return true;
                } else {
                    for (Field field : entity.getClass().getDeclaredFields()) {
                        field.setAccessible(true);
                        if (check.contains(field.getName())) {
                            Object fieldValue = field.get(entity);
                            if (fieldValue != null) {
                                check = check.replace(field.getName(), fieldValue.toString());
                            }
                        }
                    }
                    return evaluateExpression(check);
                }
            }
        } catch (NoSuchFieldException | IllegalAccessException e) {
            e.printStackTrace();
        }
        return false;
    }
    private Entity getConfigEntity(String entityName) {
        for (Entity configEntity : baseConfig.getEntities()) {
            if (configEntity.getName().equals(entityName)) {
                return configEntity;
            }
        }
        return null;
    }
    @Override
    public void onPostUpdate(PostUpdateEvent postUpdateEvent) {
        //load config file everytime
        loadJsonConfig();
        if(this.repositoryResolver==null){
            this.repositoryResolver = SpringContext.getBean(DynamicRepositoryResolver.class);
        }
        Object updatedEntity = postUpdateEvent.getEntity();
        Entity configEntity = getConfigEntity(updatedEntity.getClass().getSimpleName());
        if (configEntity != null) {
            System.out.println("PostUpdate for entity: " + postUpdateEvent.getClass().getSimpleName());
            System.out.println(configEntity.toString());
            Updation updation = configEntity.getUpdation();
            if(updation.getColumnWiseConditions()!=null){
                checkColumnWiseConditions(updation.getColumnWiseConditions(),updatedEntity);
            }
        }
        if (configEntity != null) {
            Updation updation = configEntity.getUpdation();
            String onUpdation = updation.getOnUpdation();
            if(onUpdation.equalsIgnoreCase("Yes")){
                String[] updatedProperties = postUpdateEvent.getPersister().getPropertyNames();
                Object[] previousState = postUpdateEvent.getOldState();
                StringBuilder message = new StringBuilder();
                for (int i = 1; i < updatedProperties.length; i++) {
                    Object oldValue = previousState[i];
                    Object newValue = getUpdatedValue(updatedEntity, updatedProperties[i]);
                    if ((oldValue != null && !oldValue.equals(newValue)) || (oldValue == null && newValue != null)) {

                        message.append("   The field '")
                                .append(updatedProperties[i])
                                .append("'  updated from  ")
                                .append(oldValue != null ? oldValue.toString() : "null")
                                .append("  to  ")
                                .append(newValue != null ? newValue.toString() : "null");
                    }
                }
                jedis.publish(REDIS_CHANNEL, message.toString());
            }
        }
    }
    private Object getUpdatedValue(Object entity, String propertyName) {
        try {
            return entity.getClass().getMethod("get" + capitalize(propertyName)).invoke(entity);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
    private String capitalize(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }
    @Override
    public boolean requiresPostCommitHandling(EntityPersister entityPersister) {
        return false;
    }


}
