package org.checkerframework.framework.source;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.tools.Diagnostic;

/**
 * An abstract {@link SourceChecker} that runs independent subcheckers and interleaves their
 * messages.
 *
 * <p>Though each checker is run on a whole compilation unit before the next checker is run, error
 * and warning messages are collected and sorted based on the location in the source file before
 * being printed. (See {@link #printOrStoreMessage(Diagnostic.Kind, String, Tree,
 * CompilationUnitTree)}.)
 *
 * <p>Though each checker is run on a whole compilation unit before the next checker is run, error
 * and warning messages are collected and sorted based on the location in the source file before
 * being printed. (See {@link #printOrStoreMessage(Diagnostic.Kind, String, Tree,
 * CompilationUnitTree)}.)
 *
 * <p>This class delegates {@code AbstractTypeProcessor} responsibilities to each component checker.
 *
 * <p>Checker writers need to subclass this class and only override {@link
 * #getImmediateSubcheckerClasses()} ()} to indicate the classes of the checkers to be bundled.
 */
public abstract class AggregateChecker extends SourceChecker {

  /** Create a new AggregateChecker. */
  protected AggregateChecker() {}

  /**
   * Returns the list of independent subcheckers to be run together. Subclasses need to override
   * this method.
   *
   * @return the list of checkers to be run
   */
  protected abstract Collection<Class<? extends SourceChecker>> getSupportedCheckers();

  @Override
  protected final Set<Class<? extends SourceChecker>> getImmediateSubcheckerClasses() {
    return new LinkedHashSet<>(getSupportedCheckers());
  }

  @Override
  protected SourceVisitor<?, ?> createSourceVisitor() {
    return new SourceVisitor<Void, Void>(this) {
      // Aggregate checkers do not visit source,
      // the checkers in the aggregate checker do.
    };
  }
}
