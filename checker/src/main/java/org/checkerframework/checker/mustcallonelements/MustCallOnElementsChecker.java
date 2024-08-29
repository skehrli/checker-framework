package org.checkerframework.checker.mustcallonelements;

import java.util.LinkedHashSet;
import java.util.Set;
import org.checkerframework.checker.calledmethodsonelements.CalledMethodsOnElementsChecker;
import org.checkerframework.checker.mustcallonelements.qual.MustCallOnElements;
import org.checkerframework.checker.rlccalledmethods.RLCCalledMethodsChecker;
import org.checkerframework.common.basetype.BaseTypeChecker;
import org.checkerframework.framework.source.SourceChecker;
import org.checkerframework.framework.source.SupportedOptions;

/**
 * This typechecker ensures that {@code @}{@link MustCallOnElements} annotations are consistent with
 * one another. The Resource Leak Checker verifies that the given methods are actually called.
 */
@SupportedOptions({
  MustCallOnElementsChecker.NO_LIGHTWEIGHT_OWNERSHIP,
})
public class MustCallOnElementsChecker extends BaseTypeChecker {

  /**
   * Disables @Owning/@NotOwning support. Not of interest to most users. Not documented in the
   * manual.
   */
  public static final String NO_LIGHTWEIGHT_OWNERSHIP = "noLightweightOwnership";

  /** Returns a {@code MustCallOnElementsChecker} */
  public MustCallOnElementsChecker() {
    super();
  }

  @Override
  protected Set<Class<? extends SourceChecker>> getImmediateSubcheckerClasses() {
    Set<Class<? extends SourceChecker>> checkers = new LinkedHashSet<>(2);
    checkers.add(RLCCalledMethodsChecker.class);
    checkers.add(CalledMethodsOnElementsChecker.class);

    return checkers;
  }
}
