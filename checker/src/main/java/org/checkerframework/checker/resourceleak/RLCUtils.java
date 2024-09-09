package org.checkerframework.checker.resourceleak;

import org.checkerframework.checker.calledmethodsonelements.CalledMethodsOnElementsChecker;
import org.checkerframework.checker.mustcall.MustCallChecker;
import org.checkerframework.checker.mustcallonelements.MustCallOnElementsChecker;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.checker.rlccalledmethods.RLCCalledMethodsChecker;
import org.checkerframework.common.basetype.BaseTypeChecker;
import org.checkerframework.framework.source.SourceChecker;
import org.checkerframework.framework.type.AnnotatedTypeFactory;

/**
 * Collection of utility functions related to the various checkers within the ResourceLeakChecker.
 */
public class RLCUtils {
  /**
   * Returns the type factory corresponding to the desired checker class within the
   * ResourceLeakChecker given a checker part of the ResourceLeakChecker.
   *
   * @param targetClass the desired checker class
   * @param referenceChecker the current checker
   * @return the type factory of the desired class
   */
  public static @Nullable AnnotatedTypeFactory getTypeFactory(
      Class<? extends SourceChecker> targetClass, SourceChecker referenceChecker) {
    return ((BaseTypeChecker) getChecker(targetClass, referenceChecker)).getTypeFactory();
  }

  /**
   * Returns the checker of the desired class within the ResourceLeakChecker given a type factory
   * part of the ResourceLeakChecker.
   *
   * @param targetClass the desired checker class
   * @param referenceAtf the current atf
   * @return the checker of the desired class
   */
  public static @Nullable SourceChecker getChecker(
      Class<? extends SourceChecker> targetClass, AnnotatedTypeFactory referenceAtf) {
    return getChecker(targetClass, referenceAtf.getChecker());
  }

  /**
   * Returns the type factory corresponding to the desired checker class within the
   * ResourceLeakChecker given a type factory part of the ResourceLeakChecker.
   *
   * @param targetClass the desired checker class
   * @param referenceAtf the current atf
   * @return the type factory of the desired class
   */
  public static @Nullable AnnotatedTypeFactory getTypeFactory(
      Class<? extends SourceChecker> targetClass, AnnotatedTypeFactory referenceAtf) {
    return ((BaseTypeChecker) getChecker(targetClass, referenceAtf.getChecker())).getTypeFactory();
  }

  /**
   * Returns the checker of the desired class within the ResourceLeakChecker given a checker part of
   * the ResourceLeakChecker.
   *
   * @param targetClass the desired checker class
   * @param referenceChecker the current checker
   * @return the checker of the desired class
   */
  public static @Nullable SourceChecker getChecker(
      Class<? extends SourceChecker> targetClass, SourceChecker referenceChecker) {
    Class<?> refClass = referenceChecker.getClass();
    if (refClass == targetClass) {
      // base case - we found the desired checker
      return referenceChecker;
    } else if (refClass == MustCallChecker.class) {
      return getChecker(targetClass, referenceChecker.getParentChecker());
    } else if (refClass == CalledMethodsOnElementsChecker.class) {
      return getChecker(targetClass, referenceChecker.getParentChecker());
    } else if (refClass == ResourceLeakChecker.class) {
      return getChecker(
          targetClass, referenceChecker.getSubchecker(MustCallOnElementsChecker.class));
    } else if (refClass == RLCCalledMethodsChecker.class) {
      if (targetClass == MustCallChecker.class) {
        return referenceChecker.getSubchecker(MustCallChecker.class);
      } else {
        return getChecker(targetClass, referenceChecker.getParentChecker());
      }
    } else if (refClass == MustCallOnElementsChecker.class) {
      if (targetClass == ResourceLeakChecker.class) {
        return referenceChecker.getParentChecker();
      } else if (targetClass == CalledMethodsOnElementsChecker.class) {
        return referenceChecker.getSubchecker(CalledMethodsOnElementsChecker.class);
      } else {
        return getChecker(
            targetClass, referenceChecker.getSubchecker(RLCCalledMethodsChecker.class));
      }
    } else {
      return null;
    }
  }
}
