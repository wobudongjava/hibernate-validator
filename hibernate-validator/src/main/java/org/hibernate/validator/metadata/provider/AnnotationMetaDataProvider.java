/*
* JBoss, Home of Professional Open Source
* Copyright 2011, Red Hat, Inc. and/or its affiliates, and individual contributors
* by the @authors tag. See the copyright.txt in the distribution for a
* full listing of individual contributors.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
* http://www.apache.org/licenses/LICENSE-2.0
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package org.hibernate.validator.metadata.provider;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import javax.validation.GroupDefinitionException;
import javax.validation.GroupSequence;
import javax.validation.Valid;

import org.hibernate.validator.group.DefaultGroupSequenceProvider;
import org.hibernate.validator.group.GroupSequenceProvider;
import org.hibernate.validator.metadata.AnnotationIgnores;
import org.hibernate.validator.metadata.BeanMetaConstraint;
import org.hibernate.validator.metadata.ConstraintDescriptorImpl;
import org.hibernate.validator.metadata.ConstraintHelper;
import org.hibernate.validator.metadata.ConstraintOrigin;
import org.hibernate.validator.metadata.MethodMetaConstraint;
import org.hibernate.validator.metadata.MethodMetaData;
import org.hibernate.validator.metadata.ParameterMetaData;
import org.hibernate.validator.util.ReflectionHelper;

import static org.hibernate.validator.util.CollectionHelper.newArrayList;
import static org.hibernate.validator.util.CollectionHelper.newHashSet;

/**
 * @author Gunnar Morling
 */
public class AnnotationMetaDataProvider extends MetaDataProviderImplBase {

	/**
	 * Used as prefix for parameter names, if no explicit names are given.
	 */
	public static final String DEFAULT_PARAMETER_NAME_PREFIX = "arg";

	private final AnnotationIgnores annotationIgnores;

	public AnnotationMetaDataProvider(ConstraintHelper constraintHelper, Class<?> beanClass, AnnotationIgnores annotationIgnores) {

		super( constraintHelper );

		this.annotationIgnores = annotationIgnores;

		for ( Class<?> oneHierarchyClass : ReflectionHelper.computeClassHierarchy( beanClass, true ) ) {
			retrieveBeanMetaData( oneHierarchyClass );
		}
	}

	public AnnotationIgnores getAnnotationIgnores() {
		return null;
	}

	/**
	 * Checks whether there is a default group sequence defined for this class.
	 * See HV-113.
	 */
	private void retrieveBeanMetaData(Class<?> beanClass) {
		GroupSequenceProvider groupSequenceProviderAnnotation = beanClass.getAnnotation( GroupSequenceProvider.class );
		GroupSequence groupSequenceAnnotation = beanClass.getAnnotation( GroupSequence.class );

		if ( groupSequenceAnnotation != null && groupSequenceProviderAnnotation != null ) {
			throw new GroupDefinitionException(
					"GroupSequence and GroupSequenceProvider annotations cannot be used at the same time"
			);
		}

		List<Class<?>> defaultGroupSequence = groupSequenceAnnotation != null ? Arrays.asList( groupSequenceAnnotation.value() ) : null;
		Class<? extends DefaultGroupSequenceProvider<?>> defaultGroupSequenceProvider = groupSequenceProviderAnnotation != null ? groupSequenceProviderAnnotation
				.value() : null;

		if ( defaultGroupSequence == null && defaultGroupSequenceProvider == null ) {
			defaultGroupSequence = Arrays.<Class<?>>asList( beanClass );
		}

		Set<Member> cascadedMembers = newHashSet();
		Set<BeanMetaConstraint<?>> beanConstraints = newHashSet();

		final Field[] fields = ReflectionHelper.getDeclaredFields( beanClass );
		for ( Field field : fields ) {

			// HV-172
			if ( Modifier.isStatic( field.getModifiers() ) ) {
				continue;
			}

			if ( annotationIgnores.isIgnoreAnnotations( field ) ) {
				continue;
			}

			for ( ConstraintDescriptorImpl<?> constraintDescription : findConstraints( field, ElementType.FIELD ) ) {
				ReflectionHelper.setAccessibility( field );
				beanConstraints.add( createBeanMetaConstraint( field, beanClass, constraintDescription ) );
			}

			// HV-433 Make sure the field is marked as cascaded in case it was configured via xml/programmatic API or
			// it hosts the @Valid annotation
			if ( field.isAnnotationPresent( Valid.class ) ) {
				cascadedMembers.add( field );
			}
		}

		beanConstraints.addAll( getClassLevelConstraints( beanClass, annotationIgnores ) );

		Set<MethodMetaData> methodMetaData = getMethodConstraints( beanClass, annotationIgnores );
		for ( MethodMetaData oneMethodMetaData : methodMetaData ) {
			if ( ReflectionHelper.isGetterMethod( oneMethodMetaData.getMethod() ) ) {
				for ( MethodMetaConstraint<?> oneReturnValueConstraint : oneMethodMetaData ) {
					beanConstraints.add(
							getAsBeanMetaConstraint(
									oneReturnValueConstraint, oneMethodMetaData.getMethod()
							)
					);
				}
				if ( oneMethodMetaData.isCascading() ) {
					cascadedMembers.add( oneMethodMetaData.getMethod() );
				}
			}
		}
		configuredBeans.put(
				beanClass,
				createBeanConfiguration(
						beanClass,
						beanConstraints,
						cascadedMembers,
						getMethodConstraints( beanClass, annotationIgnores ),
						defaultGroupSequence,
						defaultGroupSequenceProvider
				)
		);
	}

	private <A extends Annotation> BeanMetaConstraint<A> getAsBeanMetaConstraint(MethodMetaConstraint<A> methodMetaConstraint, Method method) {
		return new BeanMetaConstraint<A>(
				methodMetaConstraint.getDescriptor(), methodMetaConstraint.getLocation().getBeanClass(), method
		);
	}

	private Set<BeanMetaConstraint<?>> getClassLevelConstraints(Class<?> clazz, AnnotationIgnores annotationIgnores) {
		if ( annotationIgnores.isIgnoreAnnotations( clazz ) ) {
			return Collections.emptySet();
		}

		Set<BeanMetaConstraint<?>> classLevelConstraints = newHashSet();

		// HV-262
		List<ConstraintDescriptorImpl<?>> classMetaData = findClassLevelConstraints( clazz );

		for ( ConstraintDescriptorImpl<?> constraintDescription : classMetaData ) {
			classLevelConstraints.add( createBeanMetaConstraint( clazz, null, constraintDescription ) );
		}

		return classLevelConstraints;
	}

	private Set<MethodMetaData> getMethodConstraints(Class<?> clazz, AnnotationIgnores annotationIgnores) {

		Set<MethodMetaData> methodMetaData = newHashSet();

		final Method[] declaredMethods = ReflectionHelper.getDeclaredMethods( clazz );

		for ( Method method : declaredMethods ) {

			// HV-172; ignoring synthetic methods (inserted by the compiler), as they can't have any constraints
			// anyway and possibly hide the actual method with the same signature in the built meta model
			if ( Modifier.isStatic( method.getModifiers() ) || annotationIgnores.isIgnoreAnnotations( method ) || method
					.isSynthetic() ) {
				continue;
			}

			methodMetaData.add( findMethodMetaData( method ) );
		}

		return methodMetaData;
	}

	private <A extends Annotation> BeanMetaConstraint<?> createBeanMetaConstraint(Class<?> declaringClass, Member m, ConstraintDescriptorImpl<A> descriptor) {
		return new BeanMetaConstraint<A>( descriptor, declaringClass, m );
	}

	private <A extends Annotation> BeanMetaConstraint<?> createBeanMetaConstraint(Member m, Class<?> beanClass, ConstraintDescriptorImpl<A> descriptor) {
		return new BeanMetaConstraint<A>( descriptor, beanClass, m );
	}

	private <A extends Annotation> MethodMetaConstraint<A> createParameterMetaConstraint(Method method, int parameterIndex, ConstraintDescriptorImpl<A> descriptor) {
		return new MethodMetaConstraint<A>( descriptor, method, parameterIndex );
	}

	private <A extends Annotation> MethodMetaConstraint<A> createReturnValueMetaConstraint(Method method, ConstraintDescriptorImpl<A> descriptor) {
		return new MethodMetaConstraint<A>( descriptor, method );
	}

	/**
	 * Finds all constraint annotations defined for the given method.
	 *
	 * @param method The method to check for constraints annotations.
	 *
	 * @return A meta data object describing the constraints specified for the
	 *         given method.
	 */
	private MethodMetaData findMethodMetaData(Method method) {

		List<ParameterMetaData> parameterConstraints = getParameterMetaData( method );
		boolean isCascading = isValidAnnotationPresent( method );
		List<MethodMetaConstraint<?>> constraints =
				convertToMetaConstraints( findConstraints( method, ElementType.METHOD ), method );

		return new MethodMetaData( method, parameterConstraints, constraints, isCascading );
	}

	private List<MethodMetaConstraint<?>> convertToMetaConstraints(List<ConstraintDescriptorImpl<?>> constraintsDescriptors, Method method) {

		List<MethodMetaConstraint<?>> constraints = newArrayList();

		for ( ConstraintDescriptorImpl<?> oneDescriptor : constraintsDescriptors ) {
			constraints.add( createReturnValueMetaConstraint( method, oneDescriptor ) );
		}

		return constraints;
	}

	private boolean isValidAnnotationPresent(Member member) {
		return ( (AnnotatedElement) member ).isAnnotationPresent( Valid.class );
	}

	/**
	 * Retrieves constraint related meta data for the parameters of the given
	 * method.
	 *
	 * @param method The method of interest.
	 *
	 * @return A list with parameter meta data for the given method.
	 */
	private List<ParameterMetaData> getParameterMetaData(Method method) {
		List<ParameterMetaData> metaData = newArrayList();

		int i = 0;
		Class<?>[] parameterTypes = method.getParameterTypes();

		for ( Annotation[] annotationsOfOneParameter : method.getParameterAnnotations() ) {

			boolean parameterIsCascading = false;
			String parameterName = DEFAULT_PARAMETER_NAME_PREFIX + i;
			List<MethodMetaConstraint<?>> constraintsOfOneParameter = newArrayList();

			for ( Annotation oneAnnotation : annotationsOfOneParameter ) {

				//1. collect constraints if this annotation is a constraint annotation
				List<ConstraintDescriptorImpl<?>> constraints = findConstraintAnnotations(
						method.getDeclaringClass(), oneAnnotation, ElementType.PARAMETER
				);
				for ( ConstraintDescriptorImpl<?> constraintDescriptorImpl : constraints ) {
					constraintsOfOneParameter.add(
							createParameterMetaConstraint(
									method, i, constraintDescriptorImpl
							)
					);
				}

				//2. mark parameter as cascading if this annotation is the @Valid annotation
				if ( oneAnnotation.annotationType().equals( Valid.class ) ) {
					parameterIsCascading = true;
				}
			}

			metaData.add(
					new ParameterMetaData(
							i, parameterTypes[i], parameterName, constraintsOfOneParameter, parameterIsCascading
					)
			);
			i++;
		}

		return metaData;
	}

	/**
	 * Finds all constraint annotations defined for the given field/method and returns them in a list of
	 * constraint descriptors.
	 *
	 * @param member The fields or method to check for constraints annotations.
	 * @param type The element type the constraint/annotation is placed on.
	 *
	 * @return A list of constraint descriptors for all constraint specified for the given field or method.
	 */
	private List<ConstraintDescriptorImpl<?>> findConstraints(Member member, ElementType type) {
		assert member instanceof Field || member instanceof Method;

		List<ConstraintDescriptorImpl<?>> metaData = new ArrayList<ConstraintDescriptorImpl<?>>();
		for ( Annotation annotation : ( (AnnotatedElement) member ).getAnnotations() ) {
			metaData.addAll( findConstraintAnnotations( member.getDeclaringClass(), annotation, type ) );
		}

		return metaData;
	}

	/**
	 * Finds all constraint annotations defined for the given class and returns them in a list of
	 * constraint descriptors.
	 *
	 * @param beanClass The class to check for constraints annotations.
	 *
	 * @return A list of constraint descriptors for all constraint specified on the given class.
	 */
	private List<ConstraintDescriptorImpl<?>> findClassLevelConstraints(Class<?> beanClass) {
		List<ConstraintDescriptorImpl<?>> metaData = new ArrayList<ConstraintDescriptorImpl<?>>();
		for ( Annotation annotation : beanClass.getAnnotations() ) {
			metaData.addAll( findConstraintAnnotations( beanClass, annotation, ElementType.TYPE ) );
		}
		return metaData;
	}

	/**
	 * Examines the given annotation to see whether it is a single- or multi-valued constraint annotation.
	 *
	 * @param clazz the class we are currently processing
	 * @param annotation The annotation to examine
	 * @param type the element type on which the annotation/constraint is placed on
	 *
	 * @return A list of constraint descriptors or the empty list in case <code>annotation</code> is neither a
	 *         single nor multi-valued annotation.
	 */
	private <A extends Annotation> List<ConstraintDescriptorImpl<?>> findConstraintAnnotations(Class<?> clazz, A annotation, ElementType type) {
		List<ConstraintDescriptorImpl<?>> constraintDescriptors = new ArrayList<ConstraintDescriptorImpl<?>>();

		List<Annotation> constraints = new ArrayList<Annotation>();
		Class<? extends Annotation> annotationType = annotation.annotationType();
		if ( constraintHelper.isConstraintAnnotation( annotationType )
				|| constraintHelper.isBuiltinConstraint( annotationType ) ) {
			constraints.add( annotation );
		}
		else if ( constraintHelper.isMultiValueConstraint( annotationType ) ) {
			constraints.addAll( constraintHelper.getMultiValueConstraints( annotation ) );
		}

		for ( Annotation constraint : constraints ) {
			final ConstraintDescriptorImpl<?> constraintDescriptor = buildConstraintDescriptor(
					clazz, constraint, type
			);
			constraintDescriptors.add( constraintDescriptor );
		}
		return constraintDescriptors;
	}

	private <A extends Annotation> ConstraintDescriptorImpl<A> buildConstraintDescriptor(Class<?> clazz, A annotation, ElementType type) {
		ConstraintDescriptorImpl<A> constraintDescriptor;
		ConstraintOrigin definedIn = ConstraintOrigin.DEFINED_LOCALLY;
		if ( clazz.isInterface() ) {
			constraintDescriptor = new ConstraintDescriptorImpl<A>(
					annotation, constraintHelper, clazz, type, definedIn
			);
		}
		else {
			constraintDescriptor = new ConstraintDescriptorImpl<A>( annotation, constraintHelper, type, definedIn );
		}
		return constraintDescriptor;
	}
}