package org.checkerframework.checker.resourceleak;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.VariableElement;
import org.checkerframework.checker.calledmethodsonelements.CalledMethodsOnElementsChecker;
import org.checkerframework.checker.mustcall.MustCallChecker;
import org.checkerframework.checker.mustcallonelements.MustCallOnElementsChecker;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.rlccalledmethods.RLCCalledMethodsChecker;
import org.checkerframework.common.basetype.BaseTypeChecker;
import org.checkerframework.framework.source.SourceChecker;
import org.checkerframework.framework.type.AnnotatedTypeFactory;

/**
 * Collection of static utility functions related to the various checkers within the
 * ResourceLeakChecker.
 */
public class RLCUtils {

  /** List of checker names associated with the Resource Leak Checker. */
  public static List<String> rlcCheckers =
      new ArrayList<>(
          Arrays.asList(
              "ResourceLeakChecker",
              "RLCCalledMethodsChecker",
              "MustCallChecker",
              "MustCallOnElementsChecker",
              "CalledMethodsOnElementsChecker"));

  /**
   * Defines the different classifications of method signatures with respect to their safety in the
   * context of them being called on an {@code OwningCollection} receiver.
   */
  public static enum MethodSigType {
    SAFE, /* Methods that are handled and cosidered safe. */
    UNSAFE, /* Methods that are either not handled or handled and cosidered unsafe. */
    ADD_E /* All other method signatures require special handling. */
  }

  /**
   * Returns the {@code MethodSigType} of the passed {@code method}. {@code MethodSigType}
   * classifies method signatures by their safety in the context of this method being called on an
   * {@code OwningCollection}.
   *
   * <p>This method exists since multiple code locations must have a consistent method
   * classification, such as the consistency analyzer to handle the change of obligation through the
   * method call and the {@code MustCallOnElements} transfer function to decide the type change of
   * the receiver collection.
   *
   * @param method the method to consider
   * @return the {@code MethodSigType} of the passed {@code method}
   */
  public static @NonNull MethodSigType getMethodSigType(ExecutableElement method) {
    List<? extends VariableElement> parameters = method.getParameters();
    String methodSignature =
        method.getSimpleName().toString()
            + parameters.stream()
                .map(param -> param.asType().toString())
                .collect(Collectors.joining(",", "(", ")"));
    switch (methodSignature) {
      case "add(E)":
        return MethodSigType.ADD_E;
      case "size()":
      case "get(int)":
        return MethodSigType.SAFE;
      default:
        return MethodSigType.UNSAFE;
    }
  }

  /**
   * Returns the type factory corresponding to the desired checker class within the
   * ResourceLeakChecker given a checker part of the ResourceLeakChecker.
   *
   * @param targetClass the desired checker class
   * @param referenceChecker the current checker
   * @return the type factory of the desired class
   */
  public static @NonNull AnnotatedTypeFactory getTypeFactory(
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
  public static @NonNull SourceChecker getChecker(
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
  public static @NonNull AnnotatedTypeFactory getTypeFactory(
      Class<? extends SourceChecker> targetClass, AnnotatedTypeFactory referenceAtf) {
    return ((BaseTypeChecker) getChecker(targetClass, referenceAtf.getChecker())).getTypeFactory();
  }

  /**
   * Returns the checker of the desired class given a checker part of the RLC. Both the targetClass
   * and reference checker must be checkers from the RLC ecosystem, as defined by {@code
   * this.rlcCheckers}.
   *
   * @param targetClass the desired checker class
   * @param referenceChecker the current checker
   * @return the checker of the desired class
   * @throws IllegalArgumentException when either of the arguments is not one of the RLC checkers
   */
  public static @NonNull SourceChecker getChecker(
      Class<? extends SourceChecker> targetClass, SourceChecker referenceChecker) {
    if (!rlcCheckers.contains(targetClass.getCanonicalName())) {
      throw new IllegalArgumentException(
          "Argument targetClass to RLCUtils#getChecker(targetClass, referenceChecker) expected to be an RLC checker but is "
              + targetClass.getCanonicalName());
    }
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
      throw new IllegalArgumentException(
          "Argument referenceChecker to RLCUtils#getChecker(targetClass, referenceChecker) expected to be an RLC checker but is "
              + refClass.getCanonicalName());
    }
  }
}
