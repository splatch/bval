/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.    
 */
package org.apache.bval.jsr303;


import org.apache.bval.BeanValidator;
import org.apache.bval.jsr303.groups.Group;
import org.apache.bval.jsr303.groups.Groups;
import org.apache.bval.jsr303.groups.GroupsComputer;
import org.apache.bval.jsr303.util.ClassHelper;
import org.apache.bval.jsr303.util.SecureActions;
import org.apache.bval.model.Features;
import org.apache.bval.model.MetaBean;
import org.apache.bval.model.MetaProperty;
import org.apache.bval.model.ValidationContext;
import org.apache.bval.util.AccessStrategy;
import org.apache.bval.util.PropertyAccess;
import org.apache.commons.lang.ClassUtils;

import javax.validation.ConstraintViolation;
import javax.validation.ValidationException;
import javax.validation.Validator;
import javax.validation.groups.Default;
import javax.validation.metadata.BeanDescriptor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * API class -
 * Description:
 * instance is able to validate bean instances (and the associated object graphs).
 * concurrent, multithreaded access implementation is safe.
 * It is recommended to cache the instance.
 * <br/>
 */
public class ClassValidator extends BeanValidator implements Validator {
    protected final ApacheFactoryContext factoryContext;
    protected final GroupsComputer groupsComputer = new GroupsComputer();

    public ClassValidator(ApacheFactoryContext factoryContext) {
        super(factoryContext.getMetaBeanFinder());
        this.factoryContext = factoryContext;
    }

    /** @deprecated provided for backward compatibility */
    public ClassValidator(ApacheValidatorFactory factory) {
        this(factory.usingContext());
    }

    /**
     * validate all constraints on object
     *
     * @throws javax.validation.ValidationException
     *          if a non recoverable error happens during the validation process
     */
    public <T> Set<ConstraintViolation<T>> validate(T object, Class<?>... groupArray) {
        if (object == null) throw new IllegalArgumentException("cannot validate null");
        checkGroups(groupArray);
        
        try {
            final GroupValidationContext<ConstraintValidationListener<T>> context =
                  createContext(factoryContext.getMetaBeanFinder()
                        .findForClass(object.getClass()), object, (Class<T>)object.getClass(), groupArray);
            final ConstraintValidationListener result = context.getListener();
            final Groups groups = context.getGroups();
            // 1. process groups
            for (Group current : groups.getGroups()) {
                context.setCurrentGroup(current);
                validateBeanNet(context);
            }
            // 2. process sequences
            for (List<Group> eachSeq : groups.getSequences()) {
                for (Group current : eachSeq) {
                    context.setCurrentGroup(current);
                    validateBeanNet(context);
                    /**
                     * if one of the group process in the sequence leads to one or more validation failure,
                     * the groups following in the sequence must not be processed
                     */
                    if (!result.isEmpty()) break;
                }
                if (!result.isEmpty()) break; 
            }
            return result.getConstaintViolations();
        } catch (RuntimeException ex) {
            throw unrecoverableValidationError(ex, object);
        }
    }

    /**
     * Validates a bean and all its cascaded related beans for the currently
     * defined group.
     * 
     * Special code is present to manage the {@link Default} group.
     * 
     * TODO: More descriptive name and don't override method from BeanValidator.
     * 
     * @param ValidationContext
     *            The current context of this validation call.
     */
    @Override
    protected void validateBeanNet(ValidationContext vcontext) {
        
        GroupValidationContext<?> context = (GroupValidationContext<?>)vcontext;
        
        // If reached a cascaded bean which is null
        if ( context.getBean() == null ) {
            return;
        }
        
        // If reached a cascaded bean which has already been validated for the current group
        if ( !context.collectValidated() ) {
            return;
        }
        
        
        // ### First, validate the bean
        
        // Default is a special case
        if ( context.getCurrentGroup().isDefault() ) {
            
            List<Group> defaultGroups = expandDefaultGroup(context);
            final ConstraintValidationListener result = (ConstraintValidationListener) context.getListener();
            
            // If the rootBean defines a GroupSequence
            if ( defaultGroups.size() > 1 ) {
                
                int numViolations = result.violationsSize();
                
                // Validate the bean for each group in the sequence
                Group currentGroup = context.getCurrentGroup();
                for (Group each : defaultGroups) {
                    context.setCurrentGroup(each);
                    super.validateBean(context);
                    // Spec 3.4.3 - Stop validation if errors already found
                    if ( result.violationsSize() > numViolations ) {
                        break;
                    }
                }
                context.setCurrentGroup(currentGroup);
            }
            else {
                
                // For each class in the hierarchy of classes of rootBean,
                // validate the constraints defined in that class according
                // to the GroupSequence defined in the same class
                
                // Obtain the full class hierarchy
                List<Class<?>> classHierarchy = new ArrayList<Class<?>>();
                ClassHelper.fillFullClassHierarchyAsList(classHierarchy, context.getMetaBean().getBeanClass());
                Class<?> initialOwner = context.getCurrentOwner();
                
                // For each owner in the hierarchy
                for ( Class<?> owner : classHierarchy ) {
                    context.setCurrentOwner(owner);
                    
                    int numViolations = result.violationsSize();
                    
                    // Obtain the group sequence of the owner, and use it for the constraints that belong to it
                    List<Group> ownerDefaultGroups = context.getMetaBean().getFeature("{GroupSequence:"+owner.getCanonicalName()+"}");
                    for (Group each : ownerDefaultGroups) {
                        context.setCurrentGroup(each);
                        super.validateBean(context);
                        // Spec 3.4.3 - Stop validation if errors already found
                        if ( result.violationsSize() > numViolations ) {
                            break;
                        }
                    }
                    
                }
                context.setCurrentOwner(initialOwner);
                context.setCurrentGroup(Group.DEFAULT);
                
            }
            
        }
        // if not the default group, proceed as normal
        else {
            super.validateBean(context);
        }
        
        
        // ### Then, the cascaded beans (@Valid)
        for (MetaProperty prop : context.getMetaBean().getProperties()) {
            validateCascadedBean(context, prop);
        }
         
    }

    /**
     * TODO: Currently, almost the same code as super.validateRelatedBean, but
     * as it is being called at a different time, I have explicitly added the
     * code here with a different method name.
     * 
     * @param context
     *            The current context
     * @param prop
     *            The property to cascade from (in case it is possible).
     */
    private void validateCascadedBean(GroupValidationContext<?> context, MetaProperty prop) {
        AccessStrategy[] access = prop.getFeature(Features.Property.REF_CASCADE);
        if (access == null && prop.getMetaBean() != null) { // single property access strategy
            // save old values from context
            final Object bean = context.getBean();
            final MetaBean mbean = context.getMetaBean();
            // modify context state for relationship-target bean
            context.moveDown(prop, new PropertyAccess(bean.getClass(), prop.getName()));
            followCascadedConstraint(context);
            // restore old values in context
            context.moveUp(bean, mbean);
        } else if (access != null) { // different accesses to relation
            // save old values from context
            final Object bean = context.getBean();
            final MetaBean mbean = context.getMetaBean();
            for (AccessStrategy each : access) {
                // modify context state for relationship-target bean
                context.moveDown(prop, each);
                // Now, if the related bean is an instance of Map/Array/etc, 
                followCascadedConstraint(context);
                // restore old values in context
                context.moveUp(bean, mbean);
            }
        }
    }
    
    
    /**
     * TODO: Currently almost the same code as super.validateContext, but as it
     * is being called at a different time, I have explicitly added the code
     * here with a different method name.
     * 
     * Methods defined in {@link BeanValidator} take care of setting the path
     * and current bean correctly and call
     * {@link #validateBeanNet(ValidationContext)} for each individual bean.
     * 
     * @param context
     *            The current validation context.
     */
    private void followCascadedConstraint(GroupValidationContext<?> context) {
        if ( context.getBean() != null ) {
            if (context.getBean() instanceof Map<?, ?>) {
                validateMapInContext(context);
            } else if (context.getBean() instanceof List<?>) {
                validateIteratableInContext(context);
            } else if (context.getBean() instanceof Iterable<?>) {
                validateNonPositionalIteratableInContext(context);
            } else if (context.getBean() instanceof Object[]) {
                validateArrayInContext(context);
            } else { // to One Bean (or Map like Bean) 
                validateBeanInContext(context);
            }
        }
    }
    

    /**
     * in case of a default group return the list of groups
     * for a redefined default GroupSequence
     *
     * @return null when no in default group or default group sequence not redefined
     */
    private List<Group> expandDefaultGroup(GroupValidationContext context) {
        if (context.getCurrentGroup().isDefault()) {
            // mention if metaBean redefines the default group
            List<Group> groupSeq =
                  context.getMetaBean().getFeature(Jsr303Features.Bean.GROUP_SEQUENCE);
            if (groupSeq != null) {
                context.getGroups().assertDefaultGroupSequenceIsExpandable(groupSeq);
            }
            return groupSeq;
        } else {
            return null;
        }
    }

    protected RuntimeException unrecoverableValidationError(RuntimeException ex,
                                                               Object object) {
        if (ex instanceof UnknownPropertyException) {
            // Convert to IllegalArgumentException
            return new IllegalArgumentException(ex.getMessage(), ex);
        }
        else if (ex instanceof ValidationException) {
            return ex; // do not wrap specific ValidationExceptions (or instances from subclasses)
        } else {
            return new ValidationException("error during validation of " + object, ex);
        }
    }

    /**
     * validate all constraints on <code>propertyName</code> property of object
     *
     * @param propertyName - the attribute name, or nested property name (e.g. prop[2].subpropA.subpropB)
     * @throws javax.validation.ValidationException
     *          if a non recoverable error happens
     *          during the validation process
     */
    public <T> Set<ConstraintViolation<T>> validateProperty(T object, String propertyName,
                                                            Class<?>... groups) {
        if (object == null) throw new IllegalArgumentException("cannot validate null");
        
        checkPropertyName(propertyName);
        checkGroups(groups);
        
        try {
            MetaBean metaBean =
                  factoryContext.getMetaBeanFinder().findForClass(object.getClass());
            GroupValidationContext<ConstraintValidationListener<T>> context =
                  createContext(metaBean, object, (Class<T>)object.getClass(), groups);
            ConstraintValidationListener result = context.getListener();
            NestedMetaProperty nestedProp = getNestedProperty(metaBean, object, propertyName);
            context.setMetaProperty(nestedProp.getMetaProperty());
            if (nestedProp.isNested()) {
                context.setFixedValue(nestedProp.getValue());
            } else {
                context.setMetaProperty(nestedProp.getMetaProperty());
            }
            if (context.getMetaProperty() == null) throw new IllegalArgumentException(
                  "Unknown property " + object.getClass().getName() + "." + propertyName);
            Groups sequence = context.getGroups();
            // 1. process groups
            for (Group current : sequence.getGroups()) {
                context.setCurrentGroup(current);
                validatePropertyInGroup(context);
            }
            // 2. process sequences
            for (List<Group> eachSeq : sequence.getSequences()) {
                for (Group current : eachSeq) {
                    context.setCurrentGroup(current);
                    validatePropertyInGroup(context);
                    /**
                     * if one of the group process in the sequence leads to one or more validation failure,
                     * the groups following in the sequence must not be processed
                     */
                    if (!result.isEmpty()) break;
                }
                if (!result.isEmpty()) break;
            }
            return result.getConstaintViolations();
        } catch (RuntimeException ex) {
            throw unrecoverableValidationError(ex, object);
        }
    }

    private void validatePropertyInGroup(GroupValidationContext context) {
        Group currentGroup = context.getCurrentGroup();
        List<Group> defaultGroups = expandDefaultGroup(context);
        if (defaultGroups != null) {
            for (Group each : defaultGroups) {
                context.setCurrentGroup(each);
                validateProperty(context);
                // continue validation, even if errors already found: if (!result.isEmpty())
            }
            context.setCurrentGroup(currentGroup); // restore
        } else {
            validateProperty(context);
        }
    }

    /**
     * find the MetaProperty for the given propertyName,
     * which could contain a path, following the path on a given object to resolve
     * types at runtime from the instance
     */
    private NestedMetaProperty getNestedProperty(MetaBean metaBean, Object t,
                                                 String propertyName) {
        NestedMetaProperty nested = new NestedMetaProperty(propertyName, t);
        nested.setMetaBean(metaBean);
        nested.parse();
        return nested;
    }

    /**
     * validate all constraints on <code>propertyName</code> property
     * if the property value is <code>value</code>
     *
     * @throws javax.validation.ValidationException
     *          if a non recoverable error happens
     *          during the validation process
     */
    public <T> Set<ConstraintViolation<T>> validateValue(Class<T> beanType,
                                                         String propertyName, Object value,
                                                         Class<?>... groups) {
        
        checkBeanType(beanType);
        checkPropertyName(propertyName);
        checkGroups(groups);
        
        try {
            MetaBean metaBean = factoryContext.getMetaBeanFinder().findForClass(beanType);
            GroupValidationContext<ConstraintValidationListener<T>> context =
                  createContext(metaBean, null, beanType, groups);
            ConstraintValidationListener result = context.getListener();
            context.setMetaProperty(
                  getNestedProperty(metaBean, null, propertyName).getMetaProperty());
            context.setFixedValue(value);
            Groups sequence = context.getGroups();
            // 1. process groups
            for (Group current : sequence.getGroups()) {
                context.setCurrentGroup(current);
                validatePropertyInGroup(context);
            }
            // 2. process sequences
            for (List<Group> eachSeq : sequence.getSequences()) {
                for (Group current : eachSeq) {
                    context.setCurrentGroup(current);
                    validatePropertyInGroup(context);
                    /**
                     * if one of the group process in the sequence leads to one or more validation failure,
                     * the groups following in the sequence must not be processed
                     */
                    if (!result.isEmpty()) break;
                }
                if (!result.isEmpty()) break;
            }
            return result.getConstaintViolations();
        } catch (RuntimeException ex) {
            throw unrecoverableValidationError(ex, value);
        }
    }

    protected <T> GroupValidationContext<ConstraintValidationListener<T>> createContext(
          MetaBean metaBean, T object, Class<T> objectClass, Class<?>[] groups) {
        ConstraintValidationListener<T> listener = new ConstraintValidationListener<T>(object, objectClass);
        GroupValidationContextImpl<ConstraintValidationListener<T>> context =
              new GroupValidationContextImpl(listener,
                    this.factoryContext.getMessageInterpolator(),
                    this.factoryContext.getTraversableResolver(), metaBean);
        context.setBean(object, metaBean);
        context.setGroups(groupsComputer.computeGroups(groups));
        return context;
    }

    /**
     * Return the descriptor object describing bean constraints
     * The returned object (and associated objects including ConstraintDescriptors)
     * are immutable.
     *
     * @throws ValidationException if a non recoverable error happens
     *                             during the metadata discovery or if some
     *                             constraints are invalid.
     */
    public BeanDescriptor getConstraintsForClass(Class<?> clazz) {
        if (clazz == null) {
            throw new IllegalArgumentException("Class cannot be null");
        }
        try {
            MetaBean metaBean = factoryContext.getMetaBeanFinder().findForClass(clazz);
            BeanDescriptorImpl edesc =
                  metaBean.getFeature(Jsr303Features.Bean.BEAN_DESCRIPTOR);
            if (edesc == null) {
                edesc = createBeanDescriptor(metaBean);
                metaBean.putFeature(Jsr303Features.Bean.BEAN_DESCRIPTOR, edesc);
            }
            return edesc;
        } catch (RuntimeException ex) {
            throw new ValidationException("error retrieving constraints for " + clazz, ex);
        }
    }

    protected BeanDescriptorImpl createBeanDescriptor(MetaBean metaBean) {
        return new BeanDescriptorImpl(factoryContext, metaBean, metaBean.getValidations());
    }

    /**
     * Return an object of the specified type to allow access to the
     * provider-specific API.  If the Bean Validation provider
     * implementation does not support the specified class, the
     * ValidationException is thrown.
     *
     * @param type the class of the object to be returned.
     * @return an instance of the specified class
     * @throws ValidationException if the provider does not
     *                             support the call.
     */
    public <T> T unwrap(Class<T> type) {
        if (type.isAssignableFrom(getClass())) {
            return (T) this;
        } else if (!type.isInterface()) {
            return SecureActions.newInstance(type, new Class[]{ApacheFactoryContext.class},
                  new Object[]{factoryContext});
        } else {
            try {
                Class<T> cls = ClassUtils.getClass(type.getName() + "Impl");
                return SecureActions.newInstance(cls,
                      new Class[]{ApacheFactoryContext.class}, new Object[]{factoryContext});
            } catch (ClassNotFoundException e) {
                throw new ValidationException("Type " + type + " not supported");
            }
        }
    }

    /**
     * Checks that beanType is valid according to spec Section 4.1.1 i. Throws
     * an {@link IllegalArgumentException} if it is not.
     * 
     * @param beanType
     *            Bean type to check.
     */
    private void checkBeanType(Class<?> beanType) {
        if (beanType == null) {
            throw new IllegalArgumentException("Bean type cannot be null.");
        }
    }

    /**
     * Checks that the property name is valid according to spec Section 4.1.1 i.
     * Throws an {@link IllegalArgumentException} if it is not.
     * 
     * @param propertyName
     *            Property name to check.
     */
    private void checkPropertyName(String propertyName) {
        if (propertyName == null || propertyName.isEmpty() ) {
            throw new IllegalArgumentException("Property path cannot be null or empty.");
        }
    }

    /**
     * Checks that the groups array is valid according to spec Section 4.1.1 i.
     * Throws an {@link IllegalArgumentException} if it is not.
     * 
     * @param groups
     *            The groups to check.
     */
    private void checkGroups(Class<?>[] groups) {
        if ( groups == null ) {
            throw new IllegalArgumentException("Groups cannot be null.");
        }
    }
}