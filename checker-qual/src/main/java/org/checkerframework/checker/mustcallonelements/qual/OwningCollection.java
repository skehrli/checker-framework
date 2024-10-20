package org.checkerframework.checker.mustcallonelements.qual;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation indicating the ownership of the annotated collection for the purposes of Must Call On
 * Elements checking. In that sense, the annotation ports the semantics of {@code @}{@link
 * OwningCollection} to collections. The must-call-on-elements obligations of the annotated
 * collection mean that the listed methods must be called on each element of the collection. This is
 * a declaration annotation rather than a type annotation.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.PARAMETER, ElementType.FIELD, ElementType.LOCAL_VARIABLE})
public @interface OwningCollection {}
