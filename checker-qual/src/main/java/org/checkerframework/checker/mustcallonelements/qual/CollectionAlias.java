package org.checkerframework.checker.mustcallonelements.qual;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation indicating the non-ownership of the annotated collection for the purposes of Must Call
 * On Elements checking. The annotation is valid for method parameters and return types. The
 * annotated collection cannot add or overwrite elements from the underlying collection and can only
 * remove elements if an iterator is used and the removed element gets its calling obligations
 * fulfilled. This is a declaration annotation rather than a type annotation.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.PARAMETER})
public @interface CollectionAlias {}
