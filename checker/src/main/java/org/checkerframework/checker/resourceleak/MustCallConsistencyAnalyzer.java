package org.checkerframework.checker.resourceleak;

import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ArrayAccessTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import org.checkerframework.checker.calledmethods.qual.CalledMethods;
import org.checkerframework.checker.calledmethodsonelements.CalledMethodsOnElementsAnnotatedTypeFactory;
import org.checkerframework.checker.calledmethodsonelements.CalledMethodsOnElementsChecker;
import org.checkerframework.checker.mustcall.CreatesMustCallForToJavaExpression;
import org.checkerframework.checker.mustcall.MustCallAnnotatedTypeFactory;
import org.checkerframework.checker.mustcall.MustCallChecker;
import org.checkerframework.checker.mustcall.qual.MustCall;
import org.checkerframework.checker.mustcall.qual.MustCallAlias;
import org.checkerframework.checker.mustcall.qual.NotOwning;
import org.checkerframework.checker.mustcall.qual.Owning;
import org.checkerframework.checker.mustcallonelements.MustCallOnElementsAnnotatedTypeFactory;
import org.checkerframework.checker.mustcallonelements.MustCallOnElementsChecker;
import org.checkerframework.checker.mustcallonelements.qual.CollectionAlias;
import org.checkerframework.checker.mustcallonelements.qual.OwningCollection;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.checker.resourceleak.CollectionTransfer.MethodSigType;
import org.checkerframework.checker.rlccalledmethods.RLCCalledMethodsAnalysis;
import org.checkerframework.checker.rlccalledmethods.RLCCalledMethodsAnnotatedTypeFactory;
import org.checkerframework.checker.rlccalledmethods.RLCCalledMethodsAnnotatedTypeFactory.PotentiallyFulfillingLoop;
import org.checkerframework.checker.rlccalledmethods.RLCCalledMethodsChecker;
import org.checkerframework.checker.rlccalledmethods.RLCCalledMethodsVisitor;
import org.checkerframework.common.accumulation.AccumulationStore;
import org.checkerframework.common.accumulation.AccumulationValue;
import org.checkerframework.dataflow.cfg.ControlFlowGraph;
import org.checkerframework.dataflow.cfg.UnderlyingAST;
import org.checkerframework.dataflow.cfg.UnderlyingAST.Kind;
import org.checkerframework.dataflow.cfg.block.Block;
import org.checkerframework.dataflow.cfg.block.Block.BlockType;
import org.checkerframework.dataflow.cfg.block.ConditionalBlock;
import org.checkerframework.dataflow.cfg.block.ExceptionBlock;
import org.checkerframework.dataflow.cfg.block.SingleSuccessorBlock;
import org.checkerframework.dataflow.cfg.node.ArrayAccessNode;
import org.checkerframework.dataflow.cfg.node.ArrayCreationNode;
import org.checkerframework.dataflow.cfg.node.AssignmentNode;
import org.checkerframework.dataflow.cfg.node.ClassNameNode;
import org.checkerframework.dataflow.cfg.node.FieldAccessNode;
import org.checkerframework.dataflow.cfg.node.ImplicitThisNode;
import org.checkerframework.dataflow.cfg.node.LessThanNode;
import org.checkerframework.dataflow.cfg.node.LocalVariableNode;
import org.checkerframework.dataflow.cfg.node.MethodAccessNode;
import org.checkerframework.dataflow.cfg.node.MethodInvocationNode;
import org.checkerframework.dataflow.cfg.node.Node;
import org.checkerframework.dataflow.cfg.node.NullLiteralNode;
import org.checkerframework.dataflow.cfg.node.ObjectCreationNode;
import org.checkerframework.dataflow.cfg.node.ReturnNode;
import org.checkerframework.dataflow.cfg.node.SuperNode;
import org.checkerframework.dataflow.cfg.node.ThisNode;
import org.checkerframework.dataflow.cfg.node.VariableDeclarationNode;
import org.checkerframework.dataflow.expression.FieldAccess;
import org.checkerframework.dataflow.expression.IteratedCollectionElement;
import org.checkerframework.dataflow.expression.JavaExpression;
import org.checkerframework.dataflow.expression.LocalVariable;
import org.checkerframework.dataflow.expression.ThisReference;
import org.checkerframework.dataflow.util.NodeUtils;
import org.checkerframework.framework.flow.CFStore;
import org.checkerframework.framework.flow.CFValue;
import org.checkerframework.framework.type.AnnotatedTypeMirror;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedExecutableType;
import org.checkerframework.framework.util.JavaExpressionParseUtil.JavaExpressionParseException;
import org.checkerframework.framework.util.StringToJavaExpression;
import org.checkerframework.javacutil.AnnotationUtils;
import org.checkerframework.javacutil.BugInCF;
import org.checkerframework.javacutil.ElementUtils;
import org.checkerframework.javacutil.TreePathUtil;
import org.checkerframework.javacutil.TreeUtils;
import org.checkerframework.javacutil.TypeSystemError;
import org.checkerframework.javacutil.TypesUtils;
import org.plumelib.util.CollectionsPlume;
import org.plumelib.util.IPair;

/**
 * An analyzer that checks consistency of {@link MustCall} and {@link CalledMethods} types, thereby
 * detecting resource leaks. For any expression <em>e</em> the analyzer ensures that when <em>e</em>
 * goes out of scope, there exists a resource alias <em>r</em> of <em>e</em> (which might be
 * <em>e</em> itself) such that the must-call methods of <em>r</em> (i.e. the values of <em>r</em>'s
 * MustCall type) are contained in the value of <em>r</em>'s CalledMethods type. For any <em>e</em>
 * for which this property does not hold, the analyzer reports a {@code
 * "required.method.not.called"} error, indicating a possible resource leak.
 *
 * <p>Mechanically, the analysis does two tasks.
 *
 * <ul>
 *   <li>Tracks must-aliases, implemented via a dataflow analysis. Each dataflow fact is a set of
 *       resource-aliases that refer to the same resource. Furthermore, that resource is owned. No
 *       dataflow facts are maintained for a non-owned resource.
 *   <li>When the last resource alias in a resource-alias set goes out-of-scope, it checks their
 *       must-call and called-methods types. The analysis does not track must-call or called-methods
 *       types, but queries other checkers to obtain them.
 * </ul>
 *
 * <p>Class {@link Obligation} represents a single such dataflow fact. Abstractly, each dataflow
 * fact is a pair: a set of resource aliases to some resource, and the must-call obligations of that
 * resource (i.e the list of must-call methods that need to be called on one of the resource
 * aliases). Concretely, the Must Call Checker is responsible for tracking the latter - an
 * expression's must-call type indicates which methods must be called - so this dataflow analysis
 * only actually tracks the sets of resource aliases.
 *
 * <p>The dataflow algorithm adds, modifies, or removes dataflow facts when certain code patterns
 * are encountered, to account for ownership transfer. Here are non-exhaustive examples:
 *
 * <ul>
 *   <li>A new fact is added to the tracked set when a constructor or a method with an owning return
 *       is invoked.
 *   <li>A fact is modified when an expression with a tracked Obligation is the RHS of a
 *       (pseudo-)assignment. The LHS is added to the existing resource alias set.
 *   <li>A fact can be removed when a member of a resource-alias set is assigned to an owning field
 *       or passed to a method in a parameter location that is annotated as {@code @Owning}.
 * </ul>
 *
 * <p>The dataflow analysis for these Obligations is conservative in that it guarantees that for
 * every resource which actually does have a must-call obligation, at least one Obligation will
 * exist. However, it does not guarantee the opposite: Obligations may also exist for resources
 * without a must-call obligation (or for non-resources) as a result of analysis imprecision. That
 * is, the set of Obligations tracked by the analysis over-approximates the actual set of resources
 * in the analyzed program with must-call obligations.
 *
 * <p>Throughout, this class uses the temporary-variable facilities provided by the Must Call and
 * Resource Leak type factories both to emulate a three-address-form IR (simplifying some analysis
 * logic) and to permit expressions to have their types refined in their respective checkers'
 * stores. These temporary variables can be members of resource-alias sets. Without temporary
 * variables, the checker wouldn't be able to verify code such as {@code new Socket(host,
 * port).close()}, which would cause false positives. Temporaries are created for {@code new}
 * expressions, method calls (for the return value), and ternary expressions. Other types of
 * expressions may be supported in the future.
 */
public class MustCallConsistencyAnalyzer {

  /** True if errors related to static owning fields should be suppressed. */
  private final boolean permitStaticOwning;

  /** True if errors related to field initialization should be suppressed. */
  private final boolean permitInitializationLeak;

  /**
   * Aliases about which the checker has already reported about a resource leak, to avoid duplicate
   * reports.
   */
  private final Set<ResourceAlias> reportedErrorAliases = new HashSet<>();

  /**
   * The type factory for the Called Methods Checker, which is used to get called methods types and
   * to access the Must Call Checker.
   */
  private final RLCCalledMethodsAnnotatedTypeFactory cmAtf;

  /**
   * True iff this is a loop-body-analysis, i.e. the entry is the {@code public void
   * analyzeObligationFulfillingLoop(ControlFlowGraph, PotentiallyFulfillingLoop)} method.
   */
  private final boolean isLoopBodyAnalysis;

  /**
   * The type factory for the MustCallOnElements Checker, which is used to get MustCallOnElements
   * types
   */
  private final MustCallOnElementsAnnotatedTypeFactory mcoeTypeFactory;

  /**
   * The type factory for the CalledMethodsOnElements Checker, which is used to get
   * CalledMethodsOnElements types
   */
  private final CalledMethodsOnElementsAnnotatedTypeFactory cmoeTypeFactory;

  /**
   * A cache for the result of calling {@code RLCCalledMethodsAnnotatedTypeFactory.getStoreAfter()}
   * on a node. The cache prevents repeatedly computing least upper bounds on stores
   */
  private final IdentityHashMap<Node, AccumulationStore> cmStoreAfter = new IdentityHashMap<>();

  /**
   * A cache for the result of calling {@code MustCallAnnotatedTypeFactory.getStoreAfter()} on a
   * node. The cache prevents repeatedly computing least upper bounds on stores
   */
  private final IdentityHashMap<Node, CFStore> mcStoreAfter = new IdentityHashMap<>();

  /** The Resource Leak Checker, used to issue errors. */
  private final ResourceLeakChecker checker;

  /** True if -AnoLightweightOwnership was passed on the command line. */
  private final boolean noLightweightOwnership;

  /** True if -AcountMustCall was passed on the command line. */
  private final boolean countMustCall;

  /**
   * The set of @OwningCollection fields whose elements have already been assigned. If field is
   * already in this set, the new assignment is illegal.
   */
  private Set<Name> alreadyAllocatedArrays;

  /** A description for how a method might exit. */
  public enum MethodExitKind {

    /** The method exits normally by returning. */
    NORMAL_RETURN,

    /** The method exits by throwing an exception. */
    EXCEPTIONAL_EXIT;

    /** An immutable set containing all possible ways for a method to exit. */
    public static final Set<MethodExitKind> ALL =
        ImmutableSet.copyOf(EnumSet.allOf(MethodExitKind.class));
  }

  /**
   * An Obligation is a dataflow fact: a set of resource aliases and when those resources need to be
   * cleaned up. Abstractly, each Obligation represents a resource for which the analyzed program
   * might have a must-call obligation. Each Obligation is a pair of a set of resource aliases and
   * their must-call obligation. Must-call obligations are tracked by the {@link MustCallChecker}
   * and are accessed by looking up the type(s) in its type system of the resource aliases contained
   * in each {@code Obligation} using {@link
   * #getMustCallMethods(RLCCalledMethodsAnnotatedTypeFactory, CFStore)}.
   *
   * <p>An Obligation might not matter on all paths out of a method. For instance, after a
   * constructor assigns a resource to an {@link Owning} field, the resource only needs to be closed
   * if the constructor throws an exception. If the constructor exits normally then the obligation
   * is satisfied because the field is now responsible for its must-call obligations. See {@link
   * #whenToEnforce}, which defines when the Obligation needs to be enforced.
   *
   * <p>There is no guarantee that a given Obligation represents a resource with a real must-call
   * obligation. When the analysis can conclude that a given Obligation certainly does not represent
   * a real resource with a real must-call obligation (such as if the only resource alias is
   * certainly a null pointer, or if the must-call obligation is the empty set), the analysis can
   * discard the Obligation.
   */
  /*package-private*/ static class Obligation {

    /**
     * The set of resource aliases through which a must-call obligation can be satisfied. Calling
     * the required method(s) in the must-call obligation through any of them satisfies the
     * must-call obligation: that is, if the called-methods type of any alias contains the required
     * method(s), then the must-call obligation is satisfied. See {@link #getMustCallMethods}.
     *
     * <p>{@code Obligation} is deeply immutable. If some code were to accidentally mutate a {@code
     * resourceAliases} set it could be really nasty to debug, so this set is always immutable.
     */
    public final ImmutableSet<ResourceAlias> resourceAliases;

    /**
     * The ways a method can exit along which this Obligation has to be enforced. For example, this
     * will usually be {@link MethodExitKind#ALL}, indicating that this Obligation has to be
     * enforced no matter how the method exits. It may also be a smaller set indicating that the
     * Obligation only has to be enforced on certain exit conditions.
     *
     * <p>If this set is empty then the Obligation can be dropped as it never needs to be enforced.
     */
    public final ImmutableSet<MethodExitKind> whenToEnforce;

    /**
     * Create an Obligation from a set of resource aliases.
     *
     * @param resourceAliases a set of resource aliases
     * @param whenToEnforce when this Obligation should be enforced
     */
    public Obligation(Set<ResourceAlias> resourceAliases, Set<MethodExitKind> whenToEnforce) {
      this.resourceAliases = ImmutableSet.copyOf(resourceAliases);
      this.whenToEnforce = ImmutableSet.copyOf(whenToEnforce);
    }

    /**
     * Returns a new Obligation.
     *
     * <p>We need this method since we frequently need to replace obligations and if the old
     * obligation was a CollectionObligation we want the replacement to be as well. Dynamic method
     * dispatch then allows us to simply call getReplacement() on an obligation and get the
     * replacement of the right (sub)class.
     *
     * @param resourceAliases set of resource aliases for the new obligation
     * @return a new Obligation with the passed traits
     */
    public Obligation getReplacement(
        Set<ResourceAlias> resourceAliases, Set<MethodExitKind> whenToEnforce) {
      return new Obligation(resourceAliases, whenToEnforce);
    }

    /**
     * Creates and returns an obligation derived from the given tree that is either an {@code
     * ExpressionTree} or a {@code VariableTree}.
     *
     * @param tree the tree from which the Obligation is to be created. Must be ExpressionTree or
     *     VariableTree.
     * @return an obligation derived from the given tree
     */
    public static Obligation fromTree(Tree tree) {
      JavaExpression jx = null;
      Element elem = null;
      if (tree instanceof ExpressionTree) {
        jx = JavaExpression.fromTree((ExpressionTree) tree);
        elem = TreeUtils.elementFromTree((ExpressionTree) tree);
      } else if (tree instanceof VariableTree) {
        jx = JavaExpression.fromVariableTree((VariableTree) tree);
        elem = TreeUtils.elementFromDeclaration((VariableTree) tree);
      } else {
        throw new IllegalArgumentException(
            "Tree must be ExpressionTree or VariableTree but is " + tree.getClass());
      }
      return new Obligation(
          ImmutableSet.of(new ResourceAlias(jx, elem, tree)),
          Collections.singleton(MethodExitKind.NORMAL_RETURN));
    }

    /**
     * Returns the resource alias in this Obligation's resource alias set corresponding to {@code
     * localVariableNode} if one is present. Otherwise, returns null.
     *
     * @param localVariableNode a local variable
     * @return the resource alias corresponding to {@code localVariableNode} if one is present;
     *     otherwise, null
     */
    private @Nullable ResourceAlias getResourceAlias(LocalVariableNode localVariableNode) {
      Element element = localVariableNode.getElement();
      for (ResourceAlias alias : resourceAliases) {
        if (alias.reference instanceof LocalVariable && alias.element.equals(element)) {
          return alias;
        }
      }
      return null;
    }

    /**
     * Returns the resource alias in this Obligation's resource alias set corresponding to {@code
     * expression} if one is present. Otherwise, returns null.
     *
     * @param expression a Java expression
     * @return the resource alias corresponding to {@code expression} if one is present; otherwise,
     *     null
     */
    private @Nullable ResourceAlias getResourceAlias(JavaExpression expression) {
      for (ResourceAlias alias : resourceAliases) {
        if (alias.reference.equals(expression)) {
          return alias;
        }
      }
      return null;
    }

    /**
     * Returns true if this contains a resource alias corresponding to {@code localVariableNode},
     * meaning that calling the required methods on {@code localVariableNode} is sufficient to
     * satisfy the must-call obligation this object represents.
     *
     * @param localVariableNode a local variable node
     * @return true if a resource alias corresponding to {@code localVariableNode} is present
     */
    private boolean canBeSatisfiedThrough(LocalVariableNode localVariableNode) {
      return getResourceAlias(localVariableNode) != null;
    }

    /**
     * Returns true if this contains a resource alias corresponding to {@code localVariableNode},
     * meaning that calling the required methods on {@code localVariableNode} is sufficient to
     * satisfy the must-call obligation this object represents.
     *
     * @param tree a local variable tree
     * @return true if a resource alias corresponding to {@code tree} is present
     */
    private boolean canBeSatisfiedThrough(Tree tree) {
      for (ResourceAlias alias : resourceAliases) {
        if (alias.tree.equals(tree)
            || ((tree instanceof ExpressionTree)
                && JavaExpression.fromTree((ExpressionTree) tree) != null
                && alias.reference.equals(JavaExpression.fromTree((ExpressionTree) tree)))) {
          return true;
        }
      }
      return false;
    }

    /**
     * Does this Obligation contain any resource aliases that were derived from {@link
     * MustCallAlias} parameters?
     *
     * @return the logical or of the {@link ResourceAlias#derivedFromMustCallAliasParam} fields of
     *     this Obligation's resource aliases
     */
    public boolean derivedFromMustCallAlias() {
      for (ResourceAlias ra : resourceAliases) {
        if (ra.derivedFromMustCallAliasParam) {
          return true;
        }
      }
      return false;
    }

    /**
     * Gets the must-call methods (i.e. the list of methods that must be called to satisfy the
     * must-call obligation) of each resource alias represented by this Obligation.
     *
     * @param cmAtf the RLCCalledMethodsAnnotatedTypeFactory to get the called-methods store and
     *     MustCall atf
     * @param mcStore a CFStore produced by the MustCall checker's dataflow analysis. If this is
     *     null, then the default MustCall type of each variable's class will be used.
     * @return a map from each resource alias of this to a list of its must-call method names, or
     *     null if the must-call obligations are unsatisfiable (i.e. the value of some tracked
     *     resource alias of this in the Must Call store is MustCallUnknown)
     */
    public @Nullable Map<ResourceAlias, List<String>> getMustCallMethods(
        RLCCalledMethodsAnnotatedTypeFactory cmAtf, @Nullable CFStore mcStore) {
      Map<ResourceAlias, List<String>> result = new HashMap<>(this.resourceAliases.size());
      MustCallAnnotatedTypeFactory mustCallAnnotatedTypeFactory =
          cmAtf.getMustCallAnnotatedTypeFactory();

      for (ResourceAlias alias : this.resourceAliases) {
        AnnotationMirror mcAnno = getMustCallValue(alias, mcStore, mustCallAnnotatedTypeFactory);
        if (!AnnotationUtils.areSameByName(mcAnno, MustCall.class.getCanonicalName())) {
          // MustCallUnknown; cannot be satisfied
          return null;
        }
        List<String> annoVals = cmAtf.getMustCallValues(mcAnno);
        // Really, annoVals should never be empty here; we should not have created the
        // obligation in the first place.
        // TODO: add an assertion that annoVals is non-empty and address any failures
        result.put(alias, annoVals);
      }
      return result;
    }

    /**
     * Gets the must-call type associated with the given resource alias, falling on back on the
     * declared type if there is no refined type for the alias in the store.
     *
     * @param alias a resource alias
     * @param mcStore the must-call checker's store
     * @param mcAtf the must-call checker's annotated type factory
     * @return the annotation from the must-call type hierarchy associated with {@code alias}
     */
    private static AnnotationMirror getMustCallValue(
        ResourceAlias alias, @Nullable CFStore mcStore, MustCallAnnotatedTypeFactory mcAtf) {
      JavaExpression reference = alias.reference;
      CFValue value = mcStore == null ? null : mcStore.getValue(reference);
      if (value != null) {
        AnnotationMirror result =
            AnnotationUtils.getAnnotationByClass(value.getAnnotations(), MustCall.class);
        if (result != null) {
          return result;
        }
      }

      AnnotationMirror result =
          mcAtf.getAnnotatedType(alias.element).getEffectiveAnnotationInHierarchy(mcAtf.TOP);
      if (result != null && !AnnotationUtils.areSame(result, mcAtf.TOP)) {
        return result;
      }
      // There wasn't an @MustCall annotation for it in the store and the type factory has no
      // information, so fall back to the default must-call type for the class.
      // TODO: we currently end up in this case when checking a call to the return type
      // of a returns-receiver method on something with a MustCall type; for example,
      // see tests/socket/ZookeeperReport6.java. We should instead use a poly type if we can.
      TypeElement typeElt = TypesUtils.getTypeElement(reference.getType());
      if (typeElt == null) {
        // typeElt is null if reference.getType() was not a class, interface, annotation
        // type, or enum -- that is, was not an annotatable type.
        // That happens rarely, such as when it is a wildcard type. In these cases, fall
        // back on a safe default: top.
        return mcAtf.TOP;
      }
      if (typeElt.asType().getKind() == TypeKind.VOID) {
        // Void types can't have methods called on them, so returning bottom is safe.
        return mcAtf.BOTTOM;
      }

      return mcAtf.getAnnotatedType(typeElt).getPrimaryAnnotationInHierarchy(mcAtf.TOP);
    }

    @Override
    public String toString() {
      return "Obligation: resourceAliases="
          + Iterables.toString(resourceAliases)
          + ", whenToEnforce="
          + whenToEnforce;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
      if (this == obj) {
        return true;
      }
      if (obj == null || getClass() != obj.getClass()) {
        return false;
      }
      Obligation that = (Obligation) obj;
      return this.resourceAliases.equals(that.resourceAliases)
          && this.whenToEnforce.equals(that.whenToEnforce);
    }

    @Override
    public int hashCode() {
      return Objects.hash(resourceAliases, whenToEnforce);
    }
  }

  /** Obligation for a collection. To be fulfilled on its elements. */
  static class CollectionObligation extends Obligation {

    /**
     * Create a CollectionObligation from a set of resource aliases.
     *
     * @param resourceAliases a set of resource aliases
     * @param whenToEnforce when this Obligation should be enforced
     */
    public CollectionObligation(
        Set<ResourceAlias> resourceAliases, Set<MethodExitKind> whenToEnforce) {
      super(resourceAliases, whenToEnforce);
    }

    /**
     * Create a CollectionObligation from an Obligation
     *
     * @param obligation the obligation to create a CollectionObligation from
     */
    private CollectionObligation(Obligation obligation) {
      super(obligation.resourceAliases, obligation.whenToEnforce);
    }

    /**
     * Creates and returns a CollectionObligation derived from the given tree that is either an
     * {@code ExpressionTree} or a {@code VariableTree}.
     *
     * @param tree the tree from which the CollectionObligation is to be created. Must be
     *     ExpressionTree or VariableTree.
     * @return a CollectionObligation derived from the given tree
     */
    public static CollectionObligation fromTree(Tree tree) {
      return new CollectionObligation(Obligation.fromTree(tree));
    }

    /**
     * Returns a new CollectionObligation.
     *
     * <p>We need this method since we frequently need to replace obligations and if the old
     * obligation was a CollectionObligation we want the replacement to be as well. Dynamic method
     * dispatch then allows us to simply call getReplacement() on an obligation and get the
     * replacement of the right class.
     *
     * @param resourceAliases set of resource aliases for the new obligation
     * @return a new CollectionObligation with the passed traits
     */
    @Override
    public CollectionObligation getReplacement(
        Set<ResourceAlias> resourceAliases, Set<MethodExitKind> whenToEnforce) {
      return new CollectionObligation(resourceAliases, whenToEnforce);
    }
  }

  /**
   * Obligation for an iterator over a potentially resource-holding collection, i.e. a collection
   * that is either {@code OwningCollection} with open calling obligations or {@code
   * MustCallOnElementsUnknown}, i.e. a read-only alias of an {@code OwningCollection}. The
   * obligation tracks calls to {@code Iterator.next()} and {@code Iterator.remove()} to ensure
   * sound behavior.
   *
   * <p>In particular, an iterator over a {@code MustCallOnElementsUnknown} collection may never
   * call {@code Iterator.remove()} and an iterator over an {@code OwningCollection} with open
   * calling obligations may only call {@code Iterator.remove()} if the return value of the
   * preceeding {@code Iterator.next()} fulfilled the obligation. Such obligations created by calls
   * to {@code Iterator.next()} are tracked with the help of {@link IteratorNextObligation}s.
   */
  class IteratorObligation extends Obligation {
    /** The obligation for the most recent call to {@code Iterator.next()}. */
    IteratorNextObligation iterNextObligation = null;

    /**
     * The called methods store that was cached if the return value of {@code
     * this.iterNextObligation} already went out of scope.
     */
    private AccumulationStore cmStore = null;

    /**
     * The must call store that was cached if the return value of {@code this.iterNextObligation}
     * already went out of scope.
     */
    private CFStore mcStore = null;

    /**
     * When {@code Iterator.next()} is called on the iterator tracked by {@code this}, replace the
     * tracked {@link IteratorNextObligation} with the new one and remove cached stores, which now
     * refer to the previous {@code Iterator.next()} call.
     *
     * @param iterNextObligation the obligation of the new call to {@code Iterator.next()}
     */
    public void handleIterNextCall(IteratorNextObligation iterNextObligation) {
      this.iterNextObligation = iterNextObligation;
      this.cmStore = null;
      this.mcStore = null;
      // it doesn't matter if the previous iterNext had open calling obligations.
      // As long as Iterator.remove() was not called, the obligation simply stays
      // within the collection.
    }

    /**
     * When {@code Iterator.remove()} is called on the iterator tracked by {@code this}, check
     * whether the value returned by the preceeding {@code Iterator.next()} call, tracked by {@code
     * this.iterNextObligation} had its calling obligations fulfilled and report an error if not.
     * Remove the information about the preceeding {@code Iterator.next()} call after the check.
     *
     * <p>Report an error if the iterator tracked by {@code this} iterates over a read-only
     * reference.
     *
     * @param node the method invocation node of the {@code Iterator.remove()} call
     */
    public void handleIterRemoveCall(MethodInvocationNode node) {
      if (iterNextObligation == null) {
        // Iterator.remove() without preceeding Iterator.next() call.
        // It could be an actual programmer mistake, in which case there would be
        // a runtime exception, but the iterator could also be a field.
        // In the latter case, another method might have called Iterator.next().
        // There are other such cases, for instance if the iterator was returned by
        // a method or is a parameter.
        Node receiverIterator = node.getTarget().getReceiver();
        checker.reportError(node.getTree(), "unsafe.iterator.remove", receiverIterator.getTree());
      } else {
        iterNextObligation.checkObligation = true;

        if (this.mcStore != null && this.cmStore != null) {
          checkMustCall(
              iterNextObligation,
              this.cmStore,
              this.mcStore,
              "Removed from Collection by Iterator.remove() call",
              true);
        }
      }
      this.iterNextObligation = null;
      this.mcStore = null;
      this.cmStore = null;
    }

    /**
     * When the value returned by the preceeding {@code Iterator.next()} call for the iterator
     * tracked by {@code this} goes out of scope, cache the must call and called methods stores at
     * this program point. If a call to {@code Iterator.remove()} follows, the obligation will be
     * checked with the cached stores.
     *
     * @param cmStore the called methods store at the program point where the value returned by the
     *     preceeding {@code Iterator.next()} call for the iterator tracked by {@code this} goes out
     *     of scope
     * @param mcStore the must call store at the program point where the value returned by the
     *     preceeding {@code Iterator.next()} call for the iterator tracked by {@code this} goes out
     *     of scope
     */
    public void handleIteratorNextLeavingScope(AccumulationStore cmStore, CFStore mcStore) {
      assert cmStore != null && mcStore != null
          : "Stores passed to IteratorNextLeavingScope are null.";
      this.cmStore = cmStore;
      this.mcStore = mcStore;
    }

    // /**
    //  * Create an IteratorObligation from a set of resource aliases.
    //  *
    //  * @param resourceAliases a set of resource aliases
    //  * @param whenToEnforce when this Obligation should be enforced
    //  */
    // public IteratorObligation(
    //     Set<ResourceAlias> resourceAliases,
    //     Set<MethodExitKind> whenToEnforce) {
    //   super(resourceAliases, whenToEnforce);
    // }

    /**
     * Create an IteratorObligation.
     *
     * @param resourceAliases a set of resource aliases
     * @param whenToEnforce when this Obligation should be enforced
     * @param mcStore the cached mcStore
     * @param mcStore the cached cmStore
     * @param iterNextObligation the tracked {@link IteratorNextObligation}.
     */
    private IteratorObligation(
        Set<ResourceAlias> resourceAliases,
        Set<MethodExitKind> whenToEnforce,
        CFStore mcStore,
        AccumulationStore cmStore,
        IteratorNextObligation iterNextObligation) {
      super(resourceAliases, whenToEnforce);
      this.mcStore = mcStore;
      this.cmStore = cmStore;
      this.iterNextObligation = iterNextObligation;
    }

    /**
     * Create an IteratorObligation from an Obligation.
     *
     * @param obligation the obligation to create a IteratorObligation from
     */
    public IteratorObligation(Obligation obligation) {
      super(obligation.resourceAliases, obligation.whenToEnforce);
    }

    /**
     * Returns a new IteratorObligation.
     *
     * <p>We need this method since we frequently need to replace obligations and if the old
     * obligation was a IteratorObligation we want the replacement to be as well. Dynamic method
     * dispatch then allows us to simply call getReplacement() on an obligation and get the
     * replacement of the right class.
     *
     * @param resourceAliases set of resource aliases for the new obligation
     * @return a new IteratorObligation with the passed traits
     */
    @Override
    public IteratorObligation getReplacement(
        Set<ResourceAlias> resourceAliases, Set<MethodExitKind> whenToEnforce) {
      IteratorObligation replacement =
          new IteratorObligation(
              resourceAliases, whenToEnforce, this.mcStore, this.cmStore, this.iterNextObligation);
      if (this.iterNextObligation != null) {
        this.iterNextObligation.parentIteratorObligation = replacement;
      }
      return replacement;
    }
  }

  /**
   * Obligation for a value returned by {@code Iterator.next()}. This is a special case since the
   * returned value only takes ownership, if a call to {@code Iterator.remove()} follows. If no such
   * call follows, the obligation over the element remains in the collection. If there is such a
   * call, either the collection must have already had its obligations fulfilled
   */
  static class IteratorNextObligation extends Obligation {
    /**
     * The {@link IteratorObligation} for {@code iter} in the {@code iter.next()} call tracked by
     * {@code this}.
     */
    IteratorObligation parentIteratorObligation;

    /**
     * Whether to check the obligation when it goes out of scope. Initialized to false and set to
     * true if there is a call to {@code Iterator.remove()} on the parent iterator directly
     * succeeding the call tracked by {@code this}.
     */
    boolean checkObligation;

    /**
     * When the value returned by the {@code Iterator.next()} call tracked by {@code this} goes out
     * of scope, inform the parent iterator obligation.
     */
    public void leaveScope(AccumulationStore cmStore, CFStore mcStore) {
      parentIteratorObligation.handleIteratorNextLeavingScope(cmStore, mcStore);
    }

    /**
     * Create an IteratorNextObligation.
     *
     * @param resourceAliases a set of resource aliases
     * @param whenToEnforce when this Obligation should be enforced
     * @param parentIteratorObligation the obligation of the iterator
     * @param checkObligation whether to check this obligation when exiting
     */
    public IteratorNextObligation(
        Set<ResourceAlias> resourceAliases,
        Set<MethodExitKind> whenToEnforce,
        IteratorObligation parentIteratorObligation,
        boolean checkObligation) {
      super(resourceAliases, whenToEnforce);
      this.parentIteratorObligation = parentIteratorObligation;
      this.checkObligation = checkObligation;
    }

    /**
     * Creates and returns a IteratorNextObligation derived from the temp-var node and tree for an
     * {@code Iterator.next()} call and the {@link IteratorObligation} for the respective iterator.
     *
     * <p>The tree argument is the tree of the {@link MethodInvocationNode} and not the temp-var,
     * which is required for reporting errors, since the temp-var is not in the AST.
     *
     * @param tempVarNode the temp-var node for the {@code Iterator.next()} call, from which the
     *     IteratorNextObligation is to be created.
     * @param tree the tree for the {@code Iterator.next()} call.
     * @param parentIteratorObligation the iterator obligation for {@code iter} in the {@code
     *     iter.next()} call.
     * @return a IteratorNextObligation derived from the given arguments
     */
    public static IteratorNextObligation fromIterNextCall(
        Node tempVarNode, MethodInvocationTree tree, IteratorObligation parentIteratorObligation) {
      JavaExpression jx = JavaExpression.fromTree((ExpressionTree) tempVarNode.getTree());
      Element elem = TreeUtils.elementFromTree(tempVarNode.getTree());

      return new IteratorNextObligation(
          ImmutableSet.of(new ResourceAlias(jx, elem, tree)),
          Collections.singleton(MethodExitKind.NORMAL_RETURN),
          parentIteratorObligation,
          false);
    }

    /**
     * Returns a new IteratorNextObligation.
     *
     * <p>We need this method since we frequently need to replace obligations and if the old
     * obligation was a IteratorNextObligation we want the replacement to be as well. Dynamic method
     * dispatch then allows us to simply call getReplacement() on an obligation and get the
     * replacement of the right class.
     *
     * @param resourceAliases set of resource aliases for the new obligation
     * @return a new IteratorNextObligation with the passed traits
     */
    @Override
    public IteratorNextObligation getReplacement(
        Set<ResourceAlias> resourceAliases, Set<MethodExitKind> whenToEnforce) {
      IteratorNextObligation replacement =
          new IteratorNextObligation(
              resourceAliases, whenToEnforce, parentIteratorObligation, checkObligation);
      if (this.parentIteratorObligation != null) {
        this.parentIteratorObligation.iterNextObligation = replacement;
      }
      return replacement;
    }
  }

  // Is there a different Obligation on every line of the program, or is Obligation mutable?
  // (Or maybe Obligation is abstractly mutable when you consider the @MustCall types that are not
  // recorded in Obligation's representation.)  Could you clarify?  I found the first paragraph
  // confusing, including "correspond to".
  /**
   * A resource alias is a reference through which a must-call obligation can be satisfied. Any
   * must-call obligation might be satisfiable through one or more resource aliases. An {@link
   * Obligation} tracks one set of resource aliases that correspond to one must-call obligation in
   * the program.
   *
   * <p>A resource alias is always owning; non-owning aliases are, by definition, not tracked.
   *
   * <p>Internally, a resource alias is represented by a pair of a {@link JavaExpression} (the
   * "reference" through which the must-call obligations for the alias set to which it belongs can
   * be satisfied) and a tree that "assigns" the reference.
   */
  /*package-private*/ static class ResourceAlias {

    /** An expression from the source code or a temporary variable for an expression. */
    public final JavaExpression reference;

    /** The element for {@link #reference}. */
    public final Element element;

    /** The tree at which {@code reference} was assigned, for the purpose of error reporting. */
    public final Tree tree;

    /**
     * Was this ResourceAlias derived from a parameter to a method that was annotated as {@link
     * MustCallAlias}? If so, the obligation containing this resource alias must be discharged only
     * in one of the following ways:
     *
     * <ul>
     *   <li>it is passed to another method or constructor in an @MustCallAlias position, and then
     *       the enclosing method returns that method's result, or the call is a super() constructor
     *       call annotated with {@link MustCallAlias}, or
     *   <li>it is stored in an owning field of the class under analysis
     * </ul>
     */
    public final boolean derivedFromMustCallAliasParam;

    /**
     * Create a new resource alias. This constructor should only be used if the resource alias was
     * not derived from a method parameter annotated as {@link MustCallAlias}.
     *
     * @param reference the local variable
     * @param tree the tree
     */
    public ResourceAlias(LocalVariable reference, Tree tree) {
      this(reference, reference.getElement(), tree);
    }

    /**
     * Create a new resource alias. This constructor should only be used if the resource alias was
     * not derived from a method parameter annotated as {@link MustCallAlias}.
     *
     * @param reference the reference
     * @param element the element for the given reference
     * @param tree the tree
     */
    public ResourceAlias(JavaExpression reference, Element element, Tree tree) {
      this(reference, element, tree, false);
    }

    /**
     * Create a new resource alias.
     *
     * @param reference the local variable
     * @param element the element for the reference
     * @param tree the tree
     * @param derivedFromMustCallAliasParam true iff this resource alias was created because of an
     *     {@link MustCallAlias} parameter
     */
    public ResourceAlias(
        JavaExpression reference,
        Element element,
        Tree tree,
        boolean derivedFromMustCallAliasParam) {
      this.reference = reference;
      this.element = element;
      this.tree = tree;
      this.derivedFromMustCallAliasParam = derivedFromMustCallAliasParam;
    }

    @Override
    public String toString() {
      return "(ResourceAlias: reference: " + reference + " |||| tree: " + tree + ")";
    }

    @Override
    public boolean equals(@Nullable Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      ResourceAlias that = (ResourceAlias) o;
      return reference.equals(that.reference) && tree.equals(that.tree);
    }

    @Override
    public int hashCode() {
      return Objects.hash(reference, tree);
    }

    /**
     * Returns an appropriate String for representing this in an error message. In particular, if
     * {@link #reference} is a temporary variable, we return the String representation of {@link
     * #tree}, to avoid exposing the temporary name (which has no meaning for the user) in the error
     * message
     *
     * @return an appropriate String for representing this in an error message
     */
    public String stringForErrorMessage() {
      String referenceStr = reference.toString();
      // We assume that any temporary variable name will not be a syntactically-valid
      // identifier or keyword.
      return !SourceVersion.isIdentifier(referenceStr) ? tree.toString() : referenceStr;
    }
  }

  /**
   * Creates a consistency analyzer. Typically, the type factory's postAnalyze method would
   * instantiate a new consistency analyzer using this constructor and then call {@link
   * #analyze(ControlFlowGraph)}.
   *
   * @param rlc the ResourceLeakChecker
   * @param isLoopBodyAnalysis true iff. this is a loop-body-analysis (which is run after the
   *     RLCCm/Mc, but before the Mcoe/Cmoe checkers with entry point {@code void
   *     analyzeObligationFulfillingLoop(ControlFlowGraph, PotentiallyFulfillingLoop)})
   */
  public MustCallConsistencyAnalyzer(ResourceLeakChecker rlc, boolean isLoopBodyAnalysis) {
    this.isLoopBodyAnalysis = isLoopBodyAnalysis;
    this.cmAtf =
        (RLCCalledMethodsAnnotatedTypeFactory)
            rlc.getSubchecker(MustCallOnElementsChecker.class)
                .getSubchecker(RLCCalledMethodsChecker.class)
                .getTypeFactory();
    this.checker = rlc;
    if (isLoopBodyAnalysis) {
      this.mcoeTypeFactory = null;
      this.cmoeTypeFactory = null;
    } else {
      this.mcoeTypeFactory =
          (MustCallOnElementsAnnotatedTypeFactory)
              checker.getSubchecker(MustCallOnElementsChecker.class).getTypeFactory();
      this.cmoeTypeFactory =
          (CalledMethodsOnElementsAnnotatedTypeFactory)
              checker
                  .getSubchecker(MustCallOnElementsChecker.class)
                  .getSubchecker(CalledMethodsOnElementsChecker.class)
                  .getTypeFactory();
    }
    this.permitStaticOwning = rlc.hasOption("permitStaticOwning");
    this.permitInitializationLeak = rlc.hasOption("permitInitializationLeak");
    this.noLightweightOwnership = rlc.hasOption(MustCallChecker.NO_LIGHTWEIGHT_OWNERSHIP);
    this.countMustCall = rlc.hasOption(ResourceLeakChecker.COUNT_MUST_CALL);
  }

  /**
   * The main function of the consistency dataflow analysis. The analysis tracks dataflow facts
   * ("Obligations") of type {@link Obligation}, each representing a set of owning resource aliases
   * for some value with a non-empty {@code @MustCall} obligation. The set of tracked Obligations is
   * guaranteed to include at least one Obligation for each actual resource in the program, but
   * might include other, spurious Obligations, too (that is, it is a conservative
   * over-approximation of the true Obligation set).
   *
   * <p>The analysis improves its precision by removing Obligations from tracking when it can prove
   * that they do not represent real resources. For example, it is not necessary to track
   * expressions with empty {@code @MustCall} obligations, because they are trivially fulfilled. Nor
   * is tracking non-owning aliases necessary, because by definition they cannot be used to fulfill
   * must-call obligations.
   *
   * @param cfg the control flow graph of the method to check
   */
  // TODO: This analysis is currently implemented directly using a worklist; in the future, it
  // should be rewritten to use the dataflow framework of the Checker Framework.
  public void analyze(ControlFlowGraph cfg) {
    // The `visited` set contains everything that has been added to the worklist, even if it has
    // not yet been removed and analyzed.
    Set<BlockWithObligations> visited = new HashSet<>();
    Deque<BlockWithObligations> worklist = new ArrayDeque<>();
    this.alreadyAllocatedArrays = new HashSet<>();

    // verify that all @OwningCollection fields are final
    checkOwningCollectionFields(cfg);

    // Add any owning parameters to the initial set of variables to track.
    BlockWithObligations entry =
        new BlockWithObligations(cfg.getEntryBlock(), initialTrackedObligations(cfg));
    worklist.add(entry);
    visited.add(entry);

    while (!worklist.isEmpty()) {
      BlockWithObligations current = worklist.remove();
      propagateObligationsToSuccessorBlocks(
          cfg, current.obligations, current.block, visited, worklist);
    }
  }

  /**
   * Traverses the cfg of a method to find and mark enhanced-for-loops that are potentially {@code
   * MustCallOnElements} fulfilling.
   *
   * @param cfg the cfg of the method to analyze
   */
  public void findFulfillingForEachLoops(ControlFlowGraph cfg) {
    // The `visited` set contains everything that has been added to the worklist, even if it has
    // not yet been removed and analyzed.
    Set<BlockWithObligations> visited = new HashSet<>();
    Deque<BlockWithObligations> worklist = new ArrayDeque<>();

    // Add any owning parameters to the initial set of variables to track.
    BlockWithObligations entry =
        new BlockWithObligations(cfg.getEntryBlock(), new HashSet<Obligation>());
    worklist.add(entry);
    visited.add(entry);

    while (!worklist.isEmpty()) {
      BlockWithObligations current = worklist.remove();
      Block currentBlock = current.block;

      for (IPair<Block, @Nullable TypeMirror> successorAndExceptionType :
          getSuccessorsExceptIgnoredExceptions(currentBlock)) {
        for (Node node : currentBlock.getNodes()) {
          if (node instanceof MethodInvocationNode) {
            patternMatchEnhancedCollectionForLoop((MethodInvocationNode) node, cfg);
          } else if (node instanceof ArrayAccessNode) {
            patternMatchEnhancedArrayForLoop((ArrayAccessNode) node, cfg);
          }
        }
        propagate(
            new BlockWithObligations(successorAndExceptionType.first, new HashSet<Obligation>()),
            visited,
            worklist);
      }
    }
  }

  /**
   * Analyze the loop body of a 'potentially-mcoe-obligation-fulfilling-loop', as determined by a
   * pre-pattern-match in the MustCallVisitor (in the case of a normal for-loop) or determined by a
   * pre-pattern-match in {@code this.patternMatchEnhancedForLoop(MethodInvocationNode)} (in the
   * case of an enhanced-for-loop).
   *
   * <p>The analysis uses the CalledMethods type of the collection element iterated over to
   * determine the methods the loop calls on the collection elements.
   *
   * <p>This method should be called after the called-method-analysis is finished (in the {@code
   * postAnalyze(cfg)} method of the {@code RLCCalledMethodsAnnotatedTypeFactory}).
   *
   * @param cfg the cfg of the enclosing method
   * @param potentiallyFulfillingLoop the loop to check
   */
  public void analyzeObligationFulfillingLoop(
      ControlFlowGraph cfg, PotentiallyFulfillingLoop potentiallyFulfillingLoop) {

    // ensure checked loop is initialized in a valid way
    Objects.requireNonNull(
        potentiallyFulfillingLoop.collectionElementTree,
        "CollectionElementAccess tree provided to analyze loop body of an"
            + " mcoe-obligation-fulfilling loop is null.");
    Objects.requireNonNull(
        potentiallyFulfillingLoop.loopBodyEntryBlock,
        "Block provided to analyze loop body of an mcoe-obligation-fulfilling loop is null.");
    Objects.requireNonNull(
        potentiallyFulfillingLoop.loopUpdateBlock,
        "Block provided to analyze loop body of an mcoe-obligation-fulfilling loop is null.");

    Block loopBodyEntryBlock = potentiallyFulfillingLoop.loopBodyEntryBlock;
    Block loopUpdateBlock = potentiallyFulfillingLoop.loopUpdateBlock;
    Tree collectionElement = potentiallyFulfillingLoop.collectionElementTree;

    boolean emptyLoopBody = loopBodyEntryBlock == loopUpdateBlock;
    if (emptyLoopBody) {
      return;
    }

    // The `visited` set contains everything that has been added to the worklist, even if it has
    // not yet been removed and analyzed.
    Set<BlockWithObligations> visited = new HashSet<>();
    Deque<BlockWithObligations> worklist = new ArrayDeque<>();

    // Add an obligation for the element of the collection iterated over

    Obligation collectionElementObligation = Obligation.fromTree(collectionElement);
    if (collectionElement.getKind() == Tree.Kind.VARIABLE) {
      VariableElement varElt = TreeUtils.elementFromDeclaration((VariableTree) collectionElement);
      boolean hasMustCallAlias = cmAtf.hasMustCallAlias(varElt);
      collectionElementObligation =
          new Obligation(
              ImmutableSet.of(
                  new ResourceAlias(
                      new LocalVariable(varElt), varElt, collectionElement, hasMustCallAlias)),
              Collections.singleton(MethodExitKind.NORMAL_RETURN));
    }

    BlockWithObligations loopBodyEntry =
        new BlockWithObligations(
            loopBodyEntryBlock, Collections.singleton(collectionElementObligation));

    worklist.add(loopBodyEntry);
    visited.add(loopBodyEntry);
    Set<String> calledMethodsInLoop = null;

    // main loop: propagate obligations block-by-block
    while (!worklist.isEmpty()) {
      BlockWithObligations current = worklist.remove();
      Block currentBlock = current.block;

      for (IPair<Block, @Nullable TypeMirror> successorAndExceptionType :
          getSuccessorsExceptIgnoredExceptions(currentBlock)) {
        Set<Obligation> obligations = new LinkedHashSet<>(current.obligations);
        for (Node node : currentBlock.getNodes()) {
          if (node instanceof AssignmentNode) {
            updateObligationsForAssignment(obligations, cfg, (AssignmentNode) node);
          }
        }

        @SuppressWarnings("interning:not.interned")
        boolean isLastBlockOfBody = successorAndExceptionType.first == loopUpdateBlock;
        if (isLastBlockOfBody) {
          Set<String> calledMethodsAfterBlock =
              analyzeTypeOfCollectionElement(currentBlock, potentiallyFulfillingLoop, obligations);
          // intersect the called methods after this block with the accumulated ones so far.
          // This is required because there may be multiple "back edges" of the loop, in which
          // case we must intersect the called methods between those.
          if (calledMethodsInLoop == null) {
            calledMethodsInLoop = calledMethodsAfterBlock;
          } else {
            calledMethodsInLoop.retainAll(calledMethodsAfterBlock);
          }
        } else {
          try {
            propagateObligationsToSuccessorBlock(
                obligations,
                currentBlock,
                successorAndExceptionType.first,
                successorAndExceptionType.second,
                visited,
                worklist);
          } catch (InvalidLoopBodyAnalysisException e) {
            return;
          }
        }
      }
    }

    System.out.println("calledmethodsinloop: " + calledMethodsInLoop);
    // now put the loop into the static datastructure if it calls any methods on the element
    if (calledMethodsInLoop != null && calledMethodsInLoop.size() > 0) {
      potentiallyFulfillingLoop.addMethods(calledMethodsInLoop);
      MustCallOnElementsAnnotatedTypeFactory.markFulfillingLoop(potentiallyFulfillingLoop);
    }
  }

  /**
   * Checks the CalledMethods store after the given block and determines the CalledMethods type of
   * the given tree (which corresponds to the collection element iterated over) and returns the
   * union of methods in the CalledMethods type of the collection element and all its resource
   * aliases.
   *
   * @param lastLoopBodyBlock last block of loop body
   * @param potentiallyFulfillingLoop loop wrapper of the loop to analyze
   * @param obligations the set of tracked obligations
   * @return the union of methods in the CalledMethods type of the collection element and all its
   *     resource aliases.
   */
  private Set<String> analyzeTypeOfCollectionElement(
      Block lastLoopBodyBlock,
      PotentiallyFulfillingLoop potentiallyFulfillingLoop,
      Set<Obligation> obligations) {
    AccumulationStore store = null;
    if (lastLoopBodyBlock.getLastNode() == null) {
      // TODO is this really the right store? I think we need to get the then-or else store
      store = cmAtf.getStoreAfterBlock(lastLoopBodyBlock);
    } else {
      store = cmAtf.getStoreAfter(lastLoopBodyBlock.getLastNode());
    }
    Obligation collectionElementObligation =
        getObligationForVar(obligations, potentiallyFulfillingLoop.collectionElementTree);
    if (collectionElementObligation == null) {
      // the loop did something weird. Might have reassigned the collection element.
      // The sound thing to do is return an empty list.
      System.out.println("obligation gone for collection element");
      return new HashSet<>();
      // throw new BugInCF(
      //     "No obligation for collection element "
      //         + potentiallyFulfillingLoop.collectionElementTree);
    }

    Set<String> calledMethodsAfterThisBlock = new HashSet<>();

    // add the called methods of the ICE
    IteratedCollectionElement ice =
        store.getIteratedCollectionElement(
            potentiallyFulfillingLoop.collectionElementNode,
            potentiallyFulfillingLoop.collectionElementTree);
    if (ice != null) {
      AccumulationValue cmValOfIce = store.getValue(ice);
      List<String> calledMethods = getCalledMethods(cmValOfIce);
      if (calledMethods != null && calledMethods.size() > 0) {
        calledMethodsAfterThisBlock.addAll(calledMethods);
      }
    }

    // add the called methods of possible aliases of the collection element
    for (ResourceAlias alias : collectionElementObligation.resourceAliases) {
      AccumulationValue cmValOfAlias = store.getValue(alias.reference);
      if (cmValOfAlias == null) continue;
      List<String> calledMethods = getCalledMethods(cmValOfAlias);
      if (calledMethods != null && calledMethods.size() > 0) {
        calledMethodsAfterThisBlock.addAll(calledMethods);
      }
    }

    return calledMethodsAfterThisBlock;
  }

  /**
   * Returns the set of called methods values given an AccumulationValue.
   *
   * @param cmVal the accumulation value
   * @return the set of called methods of the given value
   */
  private List<String> getCalledMethods(AccumulationValue cmVal) {
    Set<String> calledMethods = cmVal.getAccumulatedValues();
    if (calledMethods != null) {
      return new ArrayList<>(calledMethods);
    } else {
      for (AnnotationMirror anno : cmVal.getAnnotations()) {
        if (AnnotationUtils.areSameByName(
            anno, "org.checkerframework.checker.calledmethods.qual.CalledMethods")) {
          return cmAtf.getCalledMethods(anno);
        }
      }
    }
    return new ArrayList<>();
  }

  /**
   * Iterates the successor blocks of the given block, updates the obligations for the nodes of the
   * given block and then propagates the obligations to each successor.
   *
   * @param cfg the analyzed control flow graph
   * @param incomingObligations the set of incoming obligations
   * @param currentBlock the {@code Block} currently analyzed
   * @param visited the visited set of {@code Block}s
   * @param worklist the set of {@code Block}s scheduled to visit next (frontier)
   */
  private void propagateObligationsToSuccessorBlocks(
      ControlFlowGraph cfg,
      Set<Obligation> incomingObligations,
      Block currentBlock,
      Set<BlockWithObligations> visited,
      Deque<BlockWithObligations> worklist) {
    // For each successor block that isn't caused by an ignored exception type, this loop
    // computes the set of Obligations that should be propagated to it and then adds it to the
    // worklist if any of its resource aliases are still in scope in the successor block. If
    // none are, then the loop performs a consistency check for that Obligation.
    for (IPair<Block, @Nullable TypeMirror> successorAndExceptionType :
        getSuccessorsExceptIgnoredExceptions(currentBlock)) {

      // A *mutable* set that eventually holds the set of dataflow facts to be propagated to
      // successor blocks. The set is initialized to the current dataflow facts and updated by
      // the methods invoked in the for loop below.
      Set<Obligation> obligations = new LinkedHashSet<>(incomingObligations);

      // PERFORMANCE NOTE: The computed changes to `obligations` are mostly the same for each
      // successor block, but can vary slightly depending on the exception type.  There might
      // be some opportunities for optimization in this mostly-redundant work.
      for (Node node : currentBlock.getNodes()) {
        boolean isLocalVariableDeclaration =
            node.getTree() != null
                && node.getTree().getKind() == Tree.Kind.VARIABLE
                && TreeUtils.elementFromTree(node.getTree()).getKind() != ElementKind.FIELD;
        if (isLocalVariableDeclaration) {
          checkVariableDeclaration((VariableTree) node.getTree());
        }

        if (node instanceof AssignmentNode) {
          updateObligationsForAssignment(obligations, cfg, (AssignmentNode) node);
        } else if (node instanceof ReturnNode) {
          updateObligationsForOwningReturn(obligations, cfg, (ReturnNode) node);
          verifyReturnStatement((ReturnNode) node, cfg);
        } else if (node instanceof MethodInvocationNode || node instanceof ObjectCreationNode) {
          updateObligationsForInvocation(obligations, node, successorAndExceptionType.second, cfg);
        }
        // All other types of nodes are ignored. This is safe, because other kinds of
        // nodes cannot create or modify the resource-alias sets that the algorithm is
        // tracking.
      }

      try {
        propagateObligationsToSuccessorBlock(
            obligations,
            currentBlock,
            successorAndExceptionType.first,
            successorAndExceptionType.second,
            visited,
            worklist);
      } catch (InvalidLoopBodyAnalysisException e) {
        // this method is never called in the loop body analysis, therefore this exception can be
        // ignored
        assert !isLoopBodyAnalysis
            : "propagateObligationsToSuccessorBlocks should not be called in loop body analysis";
      }
    }
  }

  /**
   * Checks whether the given {@code ArrayAccessNode} is desugared from an enhanced for loop and
   * calls a loop-body-analysis on the detected loop if it is.
   *
   * <p>If an {@code ArrayAccessNode} is desugared from an enhanced for loop over an array, it
   * corresponds to the node in the synthetic {@code s = array#numX[index#numY]} assignment, where
   * the loop iterator variable is assigned. The AST node corresponding to the loop itself is in
   * this case contained as a field in the {@code ArrayAccessNode}, which is set in the CFG
   * translation phase one.
   *
   * <p>This method now traverses the CFG upwards to find the loop condition and downwards to find
   * the first block of the loop body. With these two blocks, it can then call a loop-body-analysis
   * to find the methods the loop calls on the elements of the iterated collection, as part of the
   * MustCallOnElements checker.
   *
   * @param arrayAccessNode the {@code ArrayAccessNode}, for which it is checked, whether it is
   *     desugared from an enhanced for loop.
   * @param cfg the enclosing cfg of the {@code ArrayAccessNode}
   */
  private void patternMatchEnhancedArrayForLoop(
      ArrayAccessNode arrayAccessNode, ControlFlowGraph cfg) {
    boolean nodeIsDesugaredFromEnhancedForLoop = arrayAccessNode.getArrayExpression() != null;
    if (nodeIsDesugaredFromEnhancedForLoop && cfg != null) {
      // this is the arr[i] access desugared from an enhanced-for-loop (in iter = arr[i];)
      EnhancedForLoopTree loop = arrayAccessNode.getEnhancedForLoop();
      if (loop == null) {
        throw new BugInCF(
            "MethodInvocationNode.iterableExpression should be non-null iff"
                + " MethodInvocationNode.enhancedForLoop is non-null");
      }

      // Find the first block of the loop body.
      SingleSuccessorBlock ssblock = (SingleSuccessorBlock) arrayAccessNode.getBlock();
      Block loopBodyEntryBlock = ssblock.getSuccessor();

      // Find the loop condition
      // Start from the synthetic (desugared) arr[i] node and traverse the cfg
      // backwards until the LessThan node is found.
      // It corresponds to the desugared loop condition (index#numX < array#numX.length).
      Block block = arrayAccessNode.getBlock();
      Iterator<Node> nodeIterator = block.getNodes().iterator();
      Node loopVarNode = null;
      Node node;
      do {
        while (!nodeIterator.hasNext()) {
          Set<Block> predBlocks = block.getPredecessors();
          if (predBlocks.size() == 1) {
            block = predBlocks.iterator().next();
            nodeIterator = block.getNodes().iterator();
          } else {
            throw new BugInCF(
                "Encountered more than one CFG Block predeccessor trying to find the"
                    + " enhanced-for-loop update block.");
          }
        }
        node = nodeIterator.next();
        if (node instanceof VariableDeclarationNode) {
          // variable declaration of public iterator
          loopVarNode = node;
        }
      } while (!(node instanceof LessThanNode));

      // add the blocks into a static datastructure in the calledmethodsatf, such that it can
      // analyze
      // them (call MustCallConsistencyAnalyzer.analyzeFulfillingLoops, which in turn adds the trees
      // to the static datastructure in McoeAtf)
      PotentiallyFulfillingLoop pfLoop =
          new PotentiallyFulfillingLoop(
              loop.getExpression(),
              loopVarNode.getTree(),
              node.getTree(),
              loopBodyEntryBlock,
              block,
              loopVarNode);
      this.analyzeObligationFulfillingLoop(cfg, pfLoop);
    }
  }

  /**
   * Checks whether the given {@code MethodInvocationNode} is desugared from an enhanced for loop
   * and calls a loop-body-analysis on the detected loop if it is.
   *
   * <p>If a {@code MethodInvocationNode} is desugared from an enhanced for loop over a collection
   * it corresponds to the node in the synthetic {@code Iterator.next()} method call, which is the
   * loop update instruction. The AST node corresponding to the loop itself is in this case
   * contained as a field in the {@code MethodInvocationNode}, which is set in the CFG translation
   * phase one.
   *
   * <p>This method now traverses the CFG upwards to find the loop condition and downwards to find
   * the first block of the loop body. With these two blocks, it can then call a loop-body-analysis
   * to find the methods the loop calls on the elements of the iterated collection, as part of the
   * MustCallOnElements checker.
   *
   * @param methodInvocationNode the {@code MethodInvocationNode}, for which it is checked, whether
   *     it is desugared from an enhanced for loop.
   * @param cfg the enclosing cfg of the {@code MethodInvocationNode}
   */
  private void patternMatchEnhancedCollectionForLoop(
      MethodInvocationNode methodInvocationNode, ControlFlowGraph cfg) {
    boolean nodeIsDesugaredFromEnhancedForLoop =
        methodInvocationNode.getIterableExpression() != null;
    if (nodeIsDesugaredFromEnhancedForLoop) {
      // this is the Iterator.next() call desugared from an enhanced-for-loop
      EnhancedForLoopTree loop = methodInvocationNode.getEnhancedForLoop();
      if (loop == null) {
        throw new BugInCF(
            "MethodInvocationNode.iterableExpression should be non-null iff"
                + " MethodInvocationNode.enhancedForLoop is non-null");
      }

      // Find the first block of the loop body.
      // Start from the synthetic (desugared) iterator.next() node and traverse the cfg
      // until the assignment of the loop iterator variable is found, which is the last
      // desugared instruction. The next block is then the start of the loop body.
      VariableTree loopVariable = loop.getVariable();
      SingleSuccessorBlock ssblock = (SingleSuccessorBlock) methodInvocationNode.getBlock();
      Iterator<Node> nodeIterator = ssblock.getNodes().iterator();
      Node loopVarNode = null;
      Node node;
      boolean isAssignmentOfIterVar;
      do {
        while (!nodeIterator.hasNext()) {
          ssblock = (SingleSuccessorBlock) ssblock.getSuccessor();
          nodeIterator = ssblock.getNodes().iterator();
        }
        node = nodeIterator.next();
        isAssignmentOfIterVar = false;
        if ((node instanceof AssignmentNode) && node.getTree().getKind() == Tree.Kind.VARIABLE) {
          loopVarNode = ((AssignmentNode) node).getTarget();
          VariableTree iterVarDecl = (VariableTree) node.getTree();
          isAssignmentOfIterVar = iterVarDecl.getName() == loopVariable.getName();
        }
      } while (!isAssignmentOfIterVar);
      Block loopBodyEntryBlock = ssblock.getSuccessor();

      // Find the loop-body-condition
      // Start from the synthetic (desugared) iterator.next() node and traverse the cfg
      // backwards until the conditional block is found. The previous block is then the block
      // containing the desugared loop condition iterator.hasNext().
      Block block = methodInvocationNode.getBlock();
      nodeIterator = block.getNodes().iterator();
      boolean isLoopCondition;
      do {
        while (!nodeIterator.hasNext()) {
          Set<Block> predBlocks = block.getPredecessors();
          if (predBlocks.size() == 1) {
            block = predBlocks.iterator().next();
            nodeIterator = block.getNodes().iterator();
          } else {
            System.out.println("predecessor: " + predBlocks);
            throw new BugInCF(
                "Encountered more than one CFG Block predeccessor trying to find the"
                    + " enhanced-for-loop update block. Block: ");
            // + block
            // + "\nPredecessors: "
            // + predBlocks);
          }
        }
        node = nodeIterator.next();
        isLoopCondition = false;
        if (node instanceof MethodInvocationNode) {
          MethodInvocationTree mit = ((MethodInvocationNode) node).getTree();
          isLoopCondition = TreeUtils.isHasNextCall(mit);
        }
      } while (!isLoopCondition);

      // add the blocks into a static datastructure in the calledmethodsatf, such that it can
      // analyze
      // them (call MustCallConsistencyAnalyzer.analyzeFulfillingLoops, which in turn adds the trees
      // to the static datastructure in McoeAtf)
      PotentiallyFulfillingLoop pfLoop =
          new PotentiallyFulfillingLoop(
              loop.getExpression(),
              loopVarNode.getTree(),
              node.getTree(),
              loopBodyEntryBlock,
              block,
              loopVarNode);
      this.analyzeObligationFulfillingLoop(cfg, pfLoop);
    }
  }

  /**
   * Verifies all {@link OwningCollection} fields for the enclosing class of the method
   * corresponding to a CFG are final, non-static, private and arrays/collections. Also verifies
   * that there's no {@code @Owning} field that is an array/collection.
   *
   * @param cfg the CFG
   */
  private void checkOwningCollectionFields(ControlFlowGraph cfg) {
    if (cfg.getUnderlyingAST().getKind() == Kind.METHOD) {
      MethodTree method = ((UnderlyingAST.CFGMethod) cfg.getUnderlyingAST()).getMethod();
      TreePath path = cmAtf.getPath(method);
      ClassTree enclosingClass = TreePathUtil.enclosingClass(path);
      for (Tree member : enclosingClass.getMembers()) {
        if (member instanceof VariableTree) {
          VariableTree tree = (VariableTree) member;
          Element memberElm = TreeUtils.elementFromDeclaration(tree);
          boolean isCollection = RLCUtils.isCollection(tree, cmoeTypeFactory);
          boolean isOwningCollection = memberElm != null && cmAtf.hasOwningCollection(memberElm);
          boolean isOwning = memberElm != null && cmAtf.hasOwning(memberElm);
          boolean isField = memberElm != null && memberElm.getKind().isField();
          boolean isArray =
              memberElm != null
                  && memberElm.asType() != null
                  && memberElm.asType().getKind() == TypeKind.ARRAY;
          if (isField) {
            if (isOwningCollection) {
              if (!ElementUtils.isFinal(memberElm)) {
                checker.reportError(member, "owningcollection.field.not.final", tree.getName());
              }
              if (ElementUtils.isStatic(memberElm)) {
                checker.reportError(member, "owningcollection.field.static", tree.getName());
              }
              // if (!ElementUtils.isPrivate(memberElm)) {
              //   checker.reportError(member, "owningcollection.field.not.private",
              // tree.getName());
              // }
              if (!isArray && !isCollection) {
                checker.reportError(member, "owningcollection.noncollection", tree.getName());
              }
            } else if (isOwning) {
              if (isArray || isCollection) {
                checker.reportError(member, "owning.collection", tree.getName());
              }
            }
          }
        }
      }
    }
  }

  /**
   * Checks whether node corresponds to a MethodInvocationNode of a method call on a resource
   * holding Collection or an Iterator over a resource holding Collection. In this case, it updates
   * the obligations based on the method called on the Collection.
   *
   * <p>If the method invocation return type is a Collection, add an {@link IteratorObligation} for
   * it.
   *
   * @param obligations set of currently tracked obligations
   * @param node the node corresponding to a method call
   */
  private void updateObligationsForMethodInvocationOnCollection(
      Set<Obligation> obligations, Node node, ControlFlowGraph cfg) {
    if (node instanceof MethodInvocationNode) {
      MethodInvocationNode min = (MethodInvocationNode) node;
      MethodAccessNode man = min.getTarget();
      Node receiver = man.getReceiver();
      Node receiverTmpVar = removeCastsAndGetTmpVarIfPresent(receiver);
      Node methodCallTmpVar = removeCastsAndGetTmpVarIfPresent(node);
      MethodTree enclosingMethod = cfg.getEnclosingMethod(node.getTree());

      CFStore mcoeStore = mcoeTypeFactory == null ? null : mcoeTypeFactory.getStoreBefore(node);
      CFStore cmoeStore = cmoeTypeFactory == null ? null : cmoeTypeFactory.getStoreBefore(node);

      boolean isCollection =
          receiver.getTree() != null && RLCUtils.isCollection(receiver.getTree(), cmoeTypeFactory);
      boolean isIterator = RLCUtils.isIterator(receiver, cmoeTypeFactory);
      boolean isOwningCollection =
          receiver.getTree() != null
              && TreeUtils.elementFromTree(receiver.getTree()) != null
              && cmAtf.hasOwningCollection(TreeUtils.elementFromTree(receiver.getTree()));
      boolean isRoAlias =
          receiver.getTree() != null
              && mcoeStore != null // ensure store exists
              && mcoeTypeFactory.isMustCallOnElementsUnknown(mcoeStore, receiver.getTree());
      boolean isResourceCollection = isCollection && (isOwningCollection || isRoAlias);
      TypeMirror methodCallReturnType = man.getMethod().getReturnType();
      boolean returnTypeIsIterator = RLCUtils.isIterator(methodCallReturnType);

      BooleanSupplier checkNoOpenMcoeObligations =
          () -> {
            Obligation o = getObligationForVar(obligations, receiver.getTree());
            if (o == null) {
              return false;
            }
            // assert o != null : "No obligation for @OwningCollection " + receiver.getTree();
            return checkMustCallOnElements(
                o, mcoeStore, cmoeStore, false, false, node.getTree(), "");
          };

      Function<Node, List<String>> checkOpenMcObligations =
          (Node nodeToCheck) -> {
            MustCallAnnotatedTypeFactory mcAtf = cmAtf.getMustCallAnnotatedTypeFactory();
            if (nodeToCheck instanceof LocalVariableNode) {
              Obligation o = getObligationForVar(obligations, (LocalVariableNode) nodeToCheck);
              if (o != null) {
                return checkMustCall(
                    o,
                    cmAtf.getStoreBefore(nodeToCheck),
                    mcAtf.getStoreBefore(nodeToCheck),
                    "",
                    false);
              }
            }

            Element elt = TreeUtils.elementFromTree(nodeToCheck.getTree());
            if (elt == null) {
              return Collections.emptyList();
            }
            AnnotatedTypeMirror anno = mcAtf.getAnnotatedType(elt);
            if (anno instanceof AnnotatedExecutableType) {
              // if method/constructor call, take the return type
              anno = ((AnnotatedExecutableType) anno).getReturnType();
            }
            AnnotationMirror mcAnno = anno.getPrimaryAnnotation(MustCall.class);
            if (mcAnno == null) {
              return Collections.emptyList();
            } else {
              List<String> mcValues =
                  AnnotationUtils.getElementValueArray(
                      mcAnno, mcAtf.getMustCallValueElement(), String.class);
              return mcValues;
            }
          };

      Consumer<Node> checkWritingMethodOnOwningCollection =
          (Node assignedExpr) -> {
            List<String> mcoeValuesOfCollection =
                mcoeTypeFactory.getMustCallOnElementsObligations(
                    mcoeStore, (ExpressionTree) receiver.getTree());

            if (isOwningCollection && (receiver instanceof FieldAccessNode)) {
              Node fieldAccessReceiver = ((FieldAccessNode) receiver).getReceiver();
              if (fieldAccessReceiver instanceof ImplicitThisNode) {
                // an @OwningCollection field of the "this" object
                if (assignedExpr != null) {
                  List<String> mcObligations = checkOpenMcObligations.apply(assignedExpr);
                  if (mcObligations != null && !mcObligations.isEmpty()) {
                    checkEnclosingMethodIsCreatesMustCallFor(receiver, enclosingMethod);
                    List<String> difference = new ArrayList<>(mcObligations);
                    difference.removeAll(mcoeValuesOfCollection);
                    if (!difference.isEmpty()) {
                      // report error
                      checker.reportError(
                          min.getTree(),
                          "unsafe.owningcollection.field.modification",
                          receiver.getTree(),
                          formatMissingMustCallMethods(mcoeValuesOfCollection),
                          assignedExpr.getTree(),
                          formatMissingMustCallMethods(mcObligations));
                    }
                  }
                }
              }
            }

            if (isRoAlias || mcoeValuesOfCollection == null) {
              checker.reportError(
                  min.getTree(), "modification.without.ownership", receiver.getTree());
            }
          };

      Consumer<Node> checkOverwritingMethodOnOwningCollection =
          (Node assignedExpr) -> {
            checkWritingMethodOnOwningCollection.accept(assignedExpr);

            // explicitly check for ro alias here, since it might not have an obligation
            if (isRoAlias) {
              return;
            }

            Obligation o = getObligationForVar(obligations, receiver.getTree());
            if (o == null) {
              List<String> mcoeValues =
                  mcoeTypeFactory.getMustCallOnElementsObligations(
                      mcoeStore, (ExpressionTree) receiver.getTree());
              if (mcoeValues == null) {
                checker.reportError(
                    min.getTree(), "modification.without.ownership", receiver.getTree());
              }
              // mcoeValues would be null if receiver was McoeUnknown, but then, the isRoAlias
              // branch
              // would've been taken and the method had returned, so mcoeValues is not null here.
              if (!mcoeValues.isEmpty()) {
                checker.reportError(
                    min.getTree(),
                    "unsafe.owningcollection.modification",
                    formatMissingMustCallMethods(new ArrayList<>(mcoeValues)));
              }
            } else {
              checkMustCallOnElements(o, mcoeStore, cmoeStore, false, true, node.getTree(), "");
            }
          };

      if (isResourceCollection) {
        List<Node> args = min.getArguments();
        MethodSigType methodSigType = CollectionTransfer.getMethodSigType(man.getMethod());
        switch (methodSigType) {
          case SAFE:
            return;
          case UNSAFE:
            checker.reportError(node.getTree(), "unsafe.method", man);
            return;
          case ADD_E:
            Node argExpr = NodeUtils.removeCasts(args.get(0));
            Node argVar = getTempVarOrNode(argExpr);
            removeObligationForVar(obligations, argVar);
            checkWritingMethodOnOwningCollection.accept(argExpr);
            return;
          case ADD_INT_E:
            argExpr = NodeUtils.removeCasts(args.get(1));
            argVar = getTempVarOrNode(argExpr);
            removeObligationForVar(obligations, argVar);
            checkWritingMethodOnOwningCollection.accept(argExpr);
            return;
          case SET:
            argExpr = NodeUtils.removeCasts(args.get(1));
            argVar = getTempVarOrNode(argExpr);
            removeObligationForVar(obligations, argVar);
            checkOverwritingMethodOnOwningCollection.accept(argExpr);
            return;
          case CLEAR:
            checkOverwritingMethodOnOwningCollection.accept(null);
            return;
          case ITERATOR:
            // if receiver collection has no open MustCallOnElements obligations,
            // there is no need to track an obligation for the iterator.

            // if mcoe obligations fulfilled, don't create this obligation.
            // If it's mcoeunknown, give a flag to the
            // IteratorObligation that reports an error if iter.remove()
            // is called.
            if (isRoAlias) {
              System.out.println("Adding iterator for mcoeu: " + methodCallTmpVar);
              IteratorObligation iterObligation =
                  new IteratorObligation(Obligation.fromTree(methodCallTmpVar.getTree()));
              obligations.add(iterObligation);
            } else {
              if (!checkNoOpenMcoeObligations.getAsBoolean()) {
                System.out.println("Adding iterator for: " + methodCallTmpVar);
                IteratorObligation iterObligation =
                    new IteratorObligation(Obligation.fromTree(methodCallTmpVar.getTree()));
                obligations.add(iterObligation);
              }
            }
            return;

            /*
             * The following cases correspond to methods called on an Iterator, not a collection.
             * They are handled in below switch-case statement.
             */
          case ITER_REMOVE:
          case ITER_NEXT:
            return;
        }
      } else if (isIterator) {
        Obligation o;
        if (receiverTmpVar instanceof LocalVariableNode) {
          o = getObligationForVar(obligations, (LocalVariableNode) receiverTmpVar);
        } else {
          o = getObligationForVar(obligations, receiver.getTree());
        }
        if (o == null || !(o instanceof IteratorObligation)) {
          return;
        }
        IteratorObligation iterObligation = (IteratorObligation) o;

        MethodSigType methodSigType = CollectionTransfer.getIteratorMethodSigType(man.getMethod());
        switch (methodSigType) {
          case SAFE:
            return;
          case UNSAFE:
            checker.reportError(node.getTree(), "unsafe.method", man);
            return;
          case ITER_REMOVE:
            iterObligation.handleIterRemoveCall(min);
            return;
          case ITER_NEXT:
            IteratorNextObligation iterNextObligation =
                IteratorNextObligation.fromIterNextCall(
                    methodCallTmpVar, (MethodInvocationTree) node.getTree(), iterObligation);
            iterObligation.handleIterNextCall(iterNextObligation);
            obligations.add(iterNextObligation);
            return;

            /*
             * The following cases correspond to methods called on a collection, not an iterator.
             * They are handled in the above switch-case statement.
             */
          case ADD_E:
          case CLEAR:
          case ADD_INT_E:
          case SET:
          case ITERATOR:
            return;
        }
      } else if (returnTypeIsIterator) {
        // if this is a method invocation with Iterator return type, create an obligation for it.
        // The logic is placed here since the return type of Collection.iterator() is also an
        // iterator, but its special handling above has priority over the following logic, which
        // is meant for user-defined methods that return an Iterator

        if (shouldTrackIterator(methodCallReturnType)) {
          System.out.println("Adding iterator for ret type: " + methodCallTmpVar);
          obligations.add(new IteratorObligation(Obligation.fromTree(methodCallTmpVar.getTree())));
        }
      }
    }
  }

  /**
   * Update a set of Obligations to account for a method or constructor invocation.
   *
   * @param obligations the Obligations to update
   * @param node the method or constructor invocation
   * @param exceptionType a description of the outgoing CFG edge from the node: <code>null</code> to
   *     indicate normal return, or a {@link TypeMirror} to indicate a subclass of the given
   *     throwable class was thrown
   */
  private void updateObligationsForInvocation(
      Set<Obligation> obligations,
      Node node,
      @Nullable TypeMirror exceptionType,
      ControlFlowGraph cfg) {
    removeObligationsAtOwnershipTransferToParameters(obligations, node, exceptionType);

    if (node instanceof MethodInvocationNode
        && cmAtf.canCreateObligations()
        && cmAtf.hasCreatesMustCallFor((MethodInvocationNode) node)) {
      checkCreatesMustCallForInvocation(obligations, (MethodInvocationNode) node);
      // Count calls to @CreatesMustCallFor methods as creating new resources. Doing so could
      // result in slightly over-counting, because @CreatesMustCallFor doesn't guarantee that
      // a new resource is created: it just means that a new resource might have been created.
      incrementNumMustCall(node);
    }

    if (!shouldTrackInvocationResult(obligations, node, false)) {
      updateObligationsForMethodInvocationOnCollection(obligations, node, cfg);
      return;
    }

    if (cmAtf.declaredTypeHasMustCall(node.getTree())) {
      // The incrementNumMustCall call above increments the count for the target of the
      // @CreatesMustCallFor annotation.  By contrast, this call increments the count for the
      // return value of the method (which can't be the target of the annotation, because our
      // syntax doesn't support that).
      incrementNumMustCall(node);
    }
    updateObligationsWithInvocationResult(obligations, node);
    updateObligationsForMethodInvocationOnCollection(obligations, node, cfg);
  }

  /**
   * Checks that an invocation of a CreatesMustCallFor method is valid.
   *
   * <p>Such an invocation is valid if any of the conditions in {@link
   * #isValidCreatesMustCallForExpression(Set, JavaExpression, TreePath)} is true for each
   * expression in the argument to the CreatesMustCallFor annotation. As a special case, the
   * invocation of a CreatesMustCallFor method with "this" as its expression is permitted in the
   * constructor of the relevant class (invoking a constructor already creates an obligation). If
   * none of these conditions are true for any of the expressions, this method issues a
   * reset.not.owning error.
   *
   * <p>For soundness, this method also guarantees that if any of the expressions in the
   * CreatesMustCallFor annotation has a tracked Obligation, any tracked resource aliases of it will
   * be removed (lest the analysis conclude that it is already closed because one of these aliases
   * was closed before the method was invoked). Aliases created after the CreatesMustCallFor method
   * is invoked are still permitted.
   *
   * @param obligations the currently-tracked Obligations; this value is side-effected if there is
   *     an Obligation in it which tracks any expression from the CreatesMustCallFor annotation as
   *     one of its resource aliases
   * @param node a method invocation node, invoking a method with a CreatesMustCallFor annotation
   */
  private void checkCreatesMustCallForInvocation(
      Set<Obligation> obligations, MethodInvocationNode node) {

    TreePath currentPath = cmAtf.getPath(node.getTree());
    List<JavaExpression> cmcfExpressions =
        CreatesMustCallForToJavaExpression.getCreatesMustCallForExpressionsAtInvocation(
            node, cmAtf, cmAtf);
    List<JavaExpression> missing = new ArrayList<>(0);
    for (JavaExpression expression : cmcfExpressions) {
      if (!isValidCreatesMustCallForExpression(obligations, expression, currentPath)) {
        missing.add(expression);
      }
    }

    if (missing.isEmpty()) {
      // All expressions matched one of the rules, so the invocation is valid.
      return;
    }

    // Special case for invocations of CreatesMustCallFor("this") methods in the constructor.
    if (missing.size() == 1) {
      JavaExpression expression = missing.get(0);
      if (expression instanceof ThisReference && TreePathUtil.inConstructor(currentPath)) {
        return;
      }
    }

    StringJoiner missingStrs = new StringJoiner(",");
    for (JavaExpression m : missing) {
      String s = m.toString();
      missingStrs.add(s.equals("this") ? s + " of type " + m.getType() : s);
    }
    checker.reportError(
        node.getTree(),
        "reset.not.owning",
        node.getTarget().getMethod().getSimpleName().toString(),
        missingStrs.toString());
  }

  /**
   * Checks the validity of the given expression from an invoked method's {@link
   * org.checkerframework.checker.mustcall.qual.CreatesMustCallFor} annotation. Helper method for
   * {@link #checkCreatesMustCallForInvocation(Set, MethodInvocationNode)}.
   *
   * <p>An expression is valid if one of the following conditions is true:
   *
   * <ul>
   *   <li>1) the expression is an owning pointer,
   *   <li>2) the expression already has a tracked Obligation (i.e. there is already a resource
   *       alias in some Obligation's resource alias set that refers to the expression), or
   *   <li>3) the method in which the invocation occurs also has an @CreatesMustCallFor annotation,
   *       with the same expression.
   * </ul>
   *
   * @param obligations the currently-tracked Obligations; this value is side-effected if there is
   *     an Obligation in it which tracks {@code expression} as one of its resource aliases
   * @param expression an element of a method's @CreatesMustCallFor annotation
   * @param invocationPath the path to the invocation of the method from whose @CreateMustCallFor
   *     annotation {@code expression} came
   * @return true iff the expression is valid, as defined above
   */
  private boolean isValidCreatesMustCallForExpression(
      Set<Obligation> obligations, JavaExpression expression, TreePath invocationPath) {
    if (expression instanceof FieldAccess) {
      Element elt = ((FieldAccess) expression).getField();
      if (!noLightweightOwnership && cmAtf.hasOwning(elt)) {
        // The expression is an Owning field.  This satisfies case 1.
        return true;
      }
    } else if (expression instanceof LocalVariable) {
      Element elt = ((LocalVariable) expression).getElement();
      if (!noLightweightOwnership && cmAtf.hasOwning(elt)) {
        // The expression is an Owning formal parameter. Note that this cannot actually
        // be a local variable (despite expressions's type being LocalVariable) because
        // the @Owning annotation can only be written on methods, parameters, and fields;
        // formal parameters are also represented by LocalVariable in the bodies of methods.
        // This satisfies case 1.
        return true;
      } else {
        Obligation toRemove = null;
        Obligation toAdd = null;
        for (Obligation obligation : obligations) {
          ResourceAlias alias = obligation.getResourceAlias(expression);
          if (alias != null) {
            // This satisfies case 2 above. Remove all its aliases, then return below.
            if (toRemove != null) {
              throw new TypeSystemError(
                  "tried to remove multiple sets containing a reset expression at once");
            }
            toRemove = obligation;
            toAdd = obligation.getReplacement(ImmutableSet.of(alias), obligation.whenToEnforce);
          }
        }

        if (toRemove != null) {
          obligations.remove(toRemove);
          obligations.add(toAdd);
          // This satisfies case 2.
          return true;
        }
      }
    }

    // TODO: Getting this every time is inefficient if a method has many @CreatesMustCallFor
    // annotations, but that should be rare.
    MethodTree callerMethodTree = TreePathUtil.enclosingMethod(invocationPath);
    if (callerMethodTree == null) {
      return false;
    }
    ExecutableElement callerMethodElt = TreeUtils.elementFromDeclaration(callerMethodTree);
    MustCallAnnotatedTypeFactory mcAtf = cmAtf.getMustCallAnnotatedTypeFactory();
    List<String> callerCmcfValues =
        RLCCalledMethodsVisitor.getCreatesMustCallForValues(callerMethodElt, mcAtf, cmAtf);
    if (callerCmcfValues.isEmpty()) {
      return false;
    }
    for (String callerCmcfValue : callerCmcfValues) {
      JavaExpression callerTarget;
      try {
        callerTarget =
            StringToJavaExpression.atMethodBody(callerCmcfValue, callerMethodTree, checker);
      } catch (JavaExpressionParseException e) {
        // Do not issue an error here, because it would be a duplicate.
        // The error will be issued by the Transfer class of the checker,
        // via the CreatesMustCallForElementSupplier interface.
        callerTarget = null;
      }

      if (areSame(expression, callerTarget)) {
        // This satisfies case 3.
        return true;
      }
    }
    return false;
  }

  /**
   * Checks whether the two JavaExpressions are the same. This is identical to calling equals() on
   * one of them, with two exceptions: the second expression can be null, and {@code this}
   * references are compared using their underlying type. (ThisReference#equals always returns true,
   * which is probably a bug and isn't accurate in the case of nested classes.)
   *
   * @param target a JavaExpression
   * @param enclosingTarget another, possibly null, JavaExpression
   * @return true iff they represent the same program element
   */
  private boolean areSame(JavaExpression target, @Nullable JavaExpression enclosingTarget) {
    if (enclosingTarget == null) {
      return false;
    }
    if (enclosingTarget instanceof ThisReference && target instanceof ThisReference) {
      return enclosingTarget.getType().toString().equals(target.getType().toString());
    } else {
      return enclosingTarget.equals(target);
    }
  }

  /**
   * Given a node representing a method or constructor call, updates the set of Obligations to
   * account for the result, which is treated as a new resource alias. Adds the new resource alias
   * to the set of an Obligation in {@code obligations}: either an existing Obligation if the result
   * is definitely resource-aliased with it, or a new Obligation if not.
   *
   * @param obligations the currently-tracked Obligations. This is always side-effected: either a
   *     new resource alias is added to the resource alias set of an existing Obligation, or a new
   *     Obligation with a single-element resource alias set is created and added.
   * @param node the invocation node whose result is to be tracked; must be {@link
   *     MethodInvocationNode} or {@link ObjectCreationNode}
   */
  /*package-private*/ void updateObligationsWithInvocationResult(
      Set<Obligation> obligations, Node node) {
    Tree tree = node.getTree();
    // Only track the result of the call if there is a temporary variable for the call node
    // (because if there is no temporary, then the invocation must produce an untrackable value,
    // such as a primitive type).
    LocalVariableNode tmpVar = cmAtf.getTempVarForNode(node);
    if (tmpVar == null) {
      return;
    }

    // `mustCallAliases` is a (possibly-empty) list of arguments passed in a MustCallAlias
    // position.
    List<Node> mustCallAliases = getMustCallAliasArgumentNodes(node);
    // If call returns @This, add the receiver to mustCallAliases.
    if (node instanceof MethodInvocationNode && cmAtf.returnsThis((MethodInvocationTree) tree)) {
      mustCallAliases.add(
          removeCastsAndGetTmpVarIfPresent(
              ((MethodInvocationNode) node).getTarget().getReceiver()));
    }

    if (mustCallAliases.isEmpty()) {
      // If mustCallAliases is an empty List, add tmpVarAsResourceAlias to a new set.
      ResourceAlias tmpVarAsResourceAlias = new ResourceAlias(new LocalVariable(tmpVar), tree);
      if (cmAtf.hasOwningCollection(TreeUtils.elementFromTree(node.getTree()))) {
        obligations.add(
            new CollectionObligation(ImmutableSet.of(tmpVarAsResourceAlias), MethodExitKind.ALL));
      } else {
        obligations.add(new Obligation(ImmutableSet.of(tmpVarAsResourceAlias), MethodExitKind.ALL));
      }
    } else {
      for (Node mustCallAlias : mustCallAliases) {
        if (mustCallAlias instanceof FieldAccessNode) {
          // Do not track the call result if the MustCallAlias argument is a field.
          // Handling of @Owning fields is a completely separate check, and there is never
          // a need to track an alias of a non-@Owning field, as by definition such a
          // field does not have must-call obligations!
        } else if (mustCallAlias instanceof LocalVariableNode) {
          // If mustCallAlias is a local variable already being tracked, add
          // tmpVarAsResourceAlias to the set containing mustCallAlias.
          Obligation obligationContainingMustCallAlias =
              getObligationForVar(obligations, (LocalVariableNode) mustCallAlias);
          if (obligationContainingMustCallAlias != null) {
            ResourceAlias tmpVarAsResourceAlias =
                new ResourceAlias(
                    new LocalVariable(tmpVar),
                    tmpVar.getElement(),
                    tree,
                    obligationContainingMustCallAlias.derivedFromMustCallAlias());
            Set<ResourceAlias> newResourceAliasSet =
                FluentIterable.from(obligationContainingMustCallAlias.resourceAliases)
                    .append(tmpVarAsResourceAlias)
                    .toSet();
            obligations.remove(obligationContainingMustCallAlias);
            obligations.add(
                obligationContainingMustCallAlias.getReplacement(
                    newResourceAliasSet, obligationContainingMustCallAlias.whenToEnforce));
            // It is not an error if there is no Obligation containing the must-call
            // alias. In that case, what has usually happened is that no Obligation was
            // created in the first place.
            // For example, when checking the invocation of a "wrapper stream"
            // constructor, if the argument in the must-call alias position is some
            // stream with no must-call obligations like a ByteArrayInputStream, then no
            // Obligation object will have been created for it and therefore
            // obligationContainingMustCallAlias will be null.
          }
        }
      }
    }
  }

  /**
   * Returns true if the result of the given method or constructor invocation node should be tracked
   * in {@code obligations}. In some cases, there is no need to track the result because the
   * must-call obligations are already satisfied in some other way or there cannot possibly be
   * must-call obligations because of the structure of the code.
   *
   * <p>Specifically, an invocation result does NOT need to be tracked if any of the following is
   * true:
   *
   * <ul>
   *   <li>The invocation is a call to a {@code this()} or {@code super()} constructor.
   *   <li>The method's return type is annotated with MustCallAlias and the argument passed in this
   *       invocation in the corresponding position is an owning field.
   *   <li>The method's return type is non-owning, which can either be because the method has no
   *       return type or because the return type is annotated with {@link NotOwning}.
   *   <li>The method's return type is not {@code OwningCollection}.
   * </ul>
   *
   * <p>This method can also side-effect {@code obligations}, if node is a super or this constructor
   * call with MustCallAlias annotations, by removing that Obligation.
   *
   * @param obligations the current set of Obligations, which may be side-effected
   * @param node the invocation node to check; must be {@link MethodInvocationNode} or {@link
   *     ObjectCreationNode}
   * @param isMustCallInference true if this method is invoked as part of a MustCall inference
   * @return true iff the result of {@code node} should be tracked in {@code obligations}
   */
  public boolean shouldTrackInvocationResult(
      Set<Obligation> obligations, Node node, boolean isMustCallInference) {
    Tree callTree = node.getTree();
    if (callTree.getKind() == Tree.Kind.NEW_CLASS) {
      // Constructor results from new expressions are tracked as long as the declared type has
      // a non-empty @MustCall annotation.
      NewClassTree newClassTree = (NewClassTree) callTree;
      ExecutableElement executableElement = TreeUtils.elementFromUse(newClassTree);
      TypeElement typeElt = TypesUtils.getTypeElement(ElementUtils.getType(executableElement));
      return typeElt == null
          || !cmAtf.hasEmptyMustCallValue(typeElt)
          || !cmAtf.hasEmptyMustCallValue(newClassTree);
    }

    // Now callTree.getKind() == Tree.Kind.METHOD_INVOCATION.
    MethodInvocationTree methodInvokeTree = (MethodInvocationTree) callTree;

    // For must call inference, we do not want to bail out on tracking the obligations for
    // 'this()' or 'super()' calls because this tracking is necessary to correctly infer the
    // @MustCallAlias annotation for the constructor and its aliasing parameter.
    if (!isMustCallInference
        && (TreeUtils.isSuperConstructorCall(methodInvokeTree)
            || TreeUtils.isThisConstructorCall(methodInvokeTree))) {
      List<Node> mustCallAliasArguments = getMustCallAliasArgumentNodes(node);
      // If there is a MustCallAlias argument that is also in the set of Obligations, then
      // remove it; its must-call obligation has been fulfilled by being passed on to the
      // MustCallAlias constructor (because a this/super constructor call can only occur in
      // the body of another constructor).
      for (Node mustCallAliasArgument : mustCallAliasArguments) {
        if (mustCallAliasArgument instanceof LocalVariableNode) {
          removeObligationsContainingVar(obligations, (LocalVariableNode) mustCallAliasArgument);
        }
      }
      return false;
    }
    return !returnTypeIsMustCallAliasWithUntrackable((MethodInvocationNode) node)
        && shouldTrackReturnType((MethodInvocationNode) node);
  }

  /**
   * Returns true if this node represents a method invocation of a must-call-alias method, where the
   * argument in the must-call-alias position is untrackable: an owning field or a pointer that is
   * guaranteed to be non-owning, such as {@code "this"} or a non-owning field. Owning fields are
   * handled by the rest of the checker, not by this algorithm, so they are "untrackable".
   * Non-owning fields and this nodes are guaranteed to be non-owning, and are therefore also
   * "untrackable". Because both owning and non-owning fields are untrackable (and there are no
   * other kinds of fields), this method returns true for all field accesses.
   *
   * @param node a method invocation node
   * @return true if this is the invocation of a method whose return type is MCA with an owning
   *     field or a definitely non-owning pointer
   */
  private boolean returnTypeIsMustCallAliasWithUntrackable(MethodInvocationNode node) {
    List<Node> mustCallAliasArguments = getMustCallAliasArgumentNodes(node);
    for (Node mustCallAliasArg : mustCallAliasArguments) {
      if (!(mustCallAliasArg instanceof FieldAccessNode || mustCallAliasArg instanceof ThisNode)) {
        return false;
      }
    }
    return !mustCallAliasArguments.isEmpty();
  }

  /**
   * Transfer ownership of any locals passed as arguments to {@code @Owning} parameters at a method
   * or constructor call by removing the Obligations corresponding to those locals.
   *
   * @param obligations the current set of Obligations, which is side-effected to remove Obligations
   *     for locals that are passed as owning parameters to the method or constructor
   * @param node a method or constructor invocation node
   * @param exceptionType a description of the outgoing CFG edge from the node: <code>null</code> to
   *     indicate normal return, or a {@link TypeMirror} to indicate a subclass of the given
   *     throwable class was thrown
   */
  private void removeObligationsAtOwnershipTransferToParameters(
      Set<Obligation> obligations, Node node, @Nullable TypeMirror exceptionType) {
    if (exceptionType != null) {
      // Do not transfer ownership if the called method throws an exception.
      return;
    }

    if (noLightweightOwnership) {
      // Never transfer ownership to parameters, matching the default in the analysis built
      // into Eclipse.
      return;
    }

    List<Node> arguments = getArgumentsOfInvocation(node);
    List<? extends VariableElement> parameters = getParametersOfInvocation(node);

    if (arguments.size() != parameters.size()) {
      // This could happen, e.g., with varargs, or with strange cases like generated Enum
      // constructors. In the varargs case (i.e. if the varargs parameter is owning),
      // only the first of the varargs arguments will actually get transferred: the second
      // and later varargs arguments will continue to be tracked at the call-site.
      // For now, just skip this case - the worst that will happen is a false positive in
      // cases like the varargs one described above.
      // TODO allow for ownership transfer here if needed in future
      return;
    }
    for (int i = 0; i < arguments.size(); i++) {
      Node n = removeCastsAndGetTmpVarIfPresent(arguments.get(i));
      if (n instanceof LocalVariableNode) {
        LocalVariableNode local = (LocalVariableNode) n;
        if (varTrackedInObligations(obligations, local)) {

          // check if parameter has an @Owning annotation
          VariableElement parameter = parameters.get(i);
          if (cmAtf.hasOwning(parameter)) {
            Obligation localObligation = getObligationForVar(obligations, local);
            // Passing to an owning parameter is not sufficient to resolve the
            // obligation created from a MustCallAlias parameter, because the
            // enclosing method must actually return the value.
            if (!localObligation.derivedFromMustCallAlias()) {
              // Transfer ownership!
              obligations.remove(localObligation);
            }
          } else if (cmAtf.hasOwningCollection(parameter)) {
            // remove obligation for @OwningCollection argument
            Obligation localObligation = getObligationForVar(obligations, local);
            obligations.remove(localObligation);
          }
        }
      }
    }
  }

  /**
   * If the return type of the enclosing method is {@code @Owning}, treat the must-call obligations
   * of the return expression as satisfied by removing all references to them from {@code
   * obligations}.
   *
   * @param obligations the current set of tracked Obligations. If ownership is transferred, it is
   *     side-effected to remove any Obligations that are resource-aliased to the return node.
   * @param cfg the CFG of the enclosing method
   * @param node a return node
   */
  private void updateObligationsForOwningReturn(
      Set<Obligation> obligations, ControlFlowGraph cfg, ReturnNode node) {
    if (isTransferOwnershipAtReturn(cfg)) {
      Node returnExpr = node.getResult();
      returnExpr = getTempVarOrNode(returnExpr);
      if (returnExpr instanceof LocalVariableNode) {
        removeObligationsContainingVar(obligations, (LocalVariableNode) returnExpr);
      }
    }
  }

  /**
   * Helper method that gets the temporary node corresponding to {@code node}, if one exists. If
   * not, this method returns its input.
   *
   * @param node a node
   * @return the temporary for node, or node if no temporary exists
   */
  /*package-private*/ Node getTempVarOrNode(Node node) {
    Node temp = cmAtf.getTempVarForNode(node);
    if (temp != null) {
      return temp;
    }
    return node;
  }

  /**
   * Should ownership be transferred to the return type of the method corresponding to a CFG?
   * Returns true when there is no {@link NotOwning} annotation on the return type.
   *
   * @param cfg the CFG of the method
   * @return true iff ownership should be transferred to the return type of the method corresponding
   *     to a CFG
   */
  private boolean isTransferOwnershipAtReturn(ControlFlowGraph cfg) {
    if (noLightweightOwnership) {
      // If not using LO, default to always transfer at return, just like Eclipse does.
      return true;
    }

    UnderlyingAST underlyingAST = cfg.getUnderlyingAST();
    if (underlyingAST instanceof UnderlyingAST.CFGMethod) {
      // TODO: lambdas? In that case false is returned below, which means that ownership will
      //  not be transferred.
      MethodTree method = ((UnderlyingAST.CFGMethod) underlyingAST).getMethod();
      ExecutableElement executableElement = TreeUtils.elementFromDeclaration(method);
      return !cmAtf.hasNotOwning(executableElement);
    }
    return false;
  }

  /**
   * Removes all obligations containing the specified variable.
   *
   * @param obligations the set of currently tracked obligations
   * @param node the local variable node
   */
  private void removeObligationForNode(Set<Obligation> obligations, LocalVariableNode node) {
    LocalVariableNode rhsVar = node;
    Set<MethodExitKind> toClear = MethodExitKind.ALL;
    removeObligationsContainingVar(
        obligations, rhsVar, MustCallAliasHandling.NO_SPECIAL_HANDLING, toClear);
  }

  /**
   * Removes all obligations containing the specified variable, handling nodes beyond just {@code
   * LocalVariableNode}.
   *
   * @param obligations the set of currently tracked obligations
   * @param node the node to remove obligations of
   */
  private void removeObligationForVar(Set<Obligation> obligations, Node node) {
    // node = removeCastsAndGetTmpVarIfPresent(node); // TODO is this correct? idempotent function?
    if (node instanceof LocalVariableNode) {
      removeObligationForNode(obligations, (LocalVariableNode) node);
      return;
    } else if (node instanceof NullLiteralNode) {
      // no obligation to remove
      return;
    } else if (node instanceof ArrayCreationNode) {
      // array doesn't have mustcall obligations
      return;
    } else {
      throw new BugInCF(
          "Unhandled Node type when removing obligation for a variable: "
              + node.getClass().getSimpleName());
    }
  }

  /**
   * Updates a set of Obligations to account for an assignment and enforces the assignment rules for
   * the MustCallOnElements checker.
   *
   * <p>Obligation creation rules for {@code @OwningCollection}s.
   *
   * <ul>
   *   <li>1. A declaration assignment where the lhs is {@code @OwningCollection} always creates an
   *       obligation for the array/collection annotated {@code @OwningCollection} and removes the
   *       obligation for the rhs.
   *   <li>2. An assignment where lhs is {@code @OwningCollection} and
   *       {@code @MustCallOnElementsUnknown} creates an obligation for the lhs array/collection and
   *       removes the obligation for the rhs.
   * </ul>
   *
   * <p>Assignment rules:
   *
   * <ul>
   *   <li>1. An {@code @OwningCollection} field and its elements may not be assigned outside of a
   *       constructor (except at declaration site). The elements of such a field may only be
   *       assigned once per constructor in a pattern-matched assignment loop.
   *   <li>2. An overwriting assignment to the elements of an {@code OwningCollection} is only
   *       allowed if the collection has no open calling obligations.
   *   <li>3. Any collection/array reference with type {@code MustCallOnElementsUnknown} cannot
   *       write to its elements.
   *   <li>4. An {@code @OwningCollection} field cannot transfer its ownership. In particular can it
   *       not be on the rhs of an assignment where the lhs is also {@code @OwningCollection}.
   * </ul>
   *
   * @param lhs node of the lhs of the assignment
   * @param rhs node of the rhs of the assignment - after conversion to temp-var
   * @param rhsExpr node of the rhs of the assignment - before conversion to temp-var
   * @param assignmentNode node of assignment to check
   * @param cfg the ControlFlowGraph of the enclosing method
   * @param obligations the set of tracked obligations
   */
  private void updateObligationsForAssignmentToOwningCollection(
      Node lhs,
      Node rhs,
      Node rhsExpr,
      AssignmentNode assignmentNode,
      ControlFlowGraph cfg,
      Set<Obligation> obligations) {
    Element lhsElement = TreeUtils.elementFromTree(lhs.getTree());
    Element rhsElement = TreeUtils.elementFromTree(rhs.getTree());
    CFStore mcoeStore =
        mcoeTypeFactory == null ? null : mcoeTypeFactory.getStoreBefore(assignmentNode.getTree());
    CFStore cmoeStore =
        cmoeTypeFactory == null ? null : cmoeTypeFactory.getStoreBefore(assignmentNode.getTree());
    boolean lhsIsOwningCollection = !isLoopBodyAnalysis && cmAtf.hasOwningCollection(lhsElement);
    boolean rhsIsOwningCollection =
        rhsElement != null && !isLoopBodyAnalysis && cmAtf.hasOwningCollection(rhsElement);
    boolean lhsIsField = lhsElement.getKind() == ElementKind.FIELD;
    boolean lhsIsMcoeUnknown =
        mcoeStore != null && mcoeTypeFactory.isMustCallOnElementsUnknown(mcoeStore, lhs.getTree());
    boolean rhsIsMcoeUnknown =
        mcoeStore != null && mcoeTypeFactory.isMustCallOnElementsUnknown(mcoeStore, rhs.getTree());
    MethodTree enclosingMethod = cfg.getEnclosingMethod(assignmentNode.getTree());
    boolean inConstructor = enclosingMethod != null && TreeUtils.isConstructor(enclosingMethod);
    boolean rhsIsField = rhsElement != null && rhsElement.getKind() == ElementKind.FIELD;

    if (!lhsIsOwningCollection && rhsIsOwningCollection && !(rhsExpr instanceof ArrayAccessNode)) {
      // declaration of a read-only alias for the RHS @OwningCollection
      // Ownership remains with the RHS collection. LHS gets type @MustCallOnElementsUnknown.
      // This is not an error.
    }
    if (lhsIsOwningCollection) {
      if (rhsIsField
          && rhsIsOwningCollection
          && lhs.getTree().getKind() != Tree.Kind.ARRAY_ACCESS) {
        checker.reportError(
            assignmentNode.getTree(),
            "illegal.ownership.transfer",
            "Cannot transfer ownership from an @OwningCollection field.");
        return;
      }
      if (enclosingMethod == null) {
        // this is a declaration-site-assignment of an @OwningCollection field. nothing to check.
      } else if (inConstructor && lhsIsField) {
        Tree lhsTree = lhs.getTree();

        // possibly an assignment within allocating for-loop for the field
        if (lhsTree instanceof ArrayAccessTree) {
          if (MustCallOnElementsAnnotatedTypeFactory.doesAssignmentCreateArrayObligation(
              (AssignmentTree) assignmentNode.getTree())) {
            // allocating for-loop: remove obligation of RHS
            assert rhs instanceof LocalVariableNode
                : "rhs of pattern-matched assignment assumed to be LocalVariableNode,"
                    + " but its tree is "
                    + rhs.getTree().getKind();
            removeObligationForVar(obligations, rhs);

            // check whether elements of field have been assigned previously in the constructor
            ExpressionTree arrayTree = ((ArrayAccessTree) lhsTree).getExpression();
            assert arrayTree instanceof IdentifierTree
                : "LHS of pattern-matched assignment-loop assumed to be IdentifierTree, but is: "
                    + arrayTree
                    + " of kind "
                    + arrayTree.getKind();
            Name arrayName = ((IdentifierTree) arrayTree).getName();
            // enforces 1. assignment rule:
            // elements of @OwningCollection field may only be assigned once in constructor
            if (this.alreadyAllocatedArrays.contains(arrayName)) {
              checker.reportError(
                  assignmentNode.getTree(),
                  "owningcollection.field.elements.assigned.multiple.times");
              return;
            } else {
              this.alreadyAllocatedArrays.add(arrayName);
            }
          } else {
            // enforces 1. assignment rule:
            // illegal assignment to elements of @OwningCollection field in constructor
            checker.reportError(
                assignmentNode.getTree(), "illegal.owningcollection.field.elements.assignment");
            return;
          }
        } else {
          if (rhsIsMcoeUnknown) {
            // cannot assign OwningCollection field to a write-disabled alias.
            checker.reportError(
                assignmentNode.getTree(), "illegal.owningcollection.field.assignment");
          } else {
            // normal assignment to field. final keyword ensures it's the only one.
            // remove the obligation of the rhs.
            removeObligationForVar(obligations, rhs);
          }
        }
      } else {
        if (lhsIsField) {
          // enforce 1. assignment rule:
          // @OwningCollection field may not be assigned (neither its elements) outside of
          // constructor
          checker.reportError(
              assignmentNode.getTree(), "owningcollection.field.assigned.outside.constructor");
          return;
        } else if (lhs.getTree() instanceof VariableTree) {
          // declaration of local @OwningCollection. Can't be field since we're in the else clause.
          // add obligation for the collection and remove for the initializer.
          ExpressionTree declarationRhs = ((VariableTree) lhs.getTree()).getInitializer();
          if (declarationRhs != null) {
            removeObligationForVar(obligations, rhs);
          }
          Obligation newObligation = CollectionObligation.fromTree(lhs.getTree());
          obligations.add(newObligation);
        } else if (lhs.getTree() instanceof IdentifierTree) {
          // definition of local @OwningCollection. If the array was previously assigned, the old
          // (in-memory) array goes out of scope and must be checked.
          // The new one needs an obligation, add it.
          Obligation obligation = getObligationForVar(obligations, lhs.getTree());
          if (obligation != null) {
            checkMustCallOnElements(
                obligation,
                mcoeStore,
                cmoeStore,
                true,
                true,
                lhs.getTree(),
                "array is reassigned at " + assignmentNode.getTree());
            obligations.remove(obligation);
          }
          removeObligationForVar(obligations, rhs);
          Obligation newObligation = CollectionObligation.fromTree(lhs.getTree());
          obligations.add(newObligation);
        } else if (lhs.getTree() instanceof ArrayAccessTree) {
          // Assignment to an element of an @OwningCollection array, which may or may not be
          // in a pattern-matched assignment loop.
          // Remove Obligations from local variables, now that the @OwningCollection is
          // responsible.
          // Report an error of the receiver collection is read-only or has unfulfilled
          // MustCallOnElements obligations. Enforces 2. assignment rule.
          ExpressionTree receiverArray = ((ArrayAccessTree) lhs.getTree()).getExpression();
          if (lhsIsMcoeUnknown) {
            // enforces 3. assignment rule: no assignment without ownership
            checker.reportError(
                assignmentNode.getTree(), "modification.without.ownership", receiverArray);
          } else {
            Obligation o = getObligationForVar(obligations, receiverArray);
            assert o != null : "No obligation for @OwningCollection " + receiverArray;
            checkMustCallOnElements(o, mcoeStore, cmoeStore, false, true, receiverArray, "");
          }
          removeObligationForVar(obligations, rhs);
        } else {
          throw new BugInCF(
              "uncovered case in MCConsistencyAnalyzer#updateObligationsForAssignment(): lhs "
                  + lhs.getTree()
                  + " of kind "
                  + lhs.getTree().getKind());
        }
      }
    } else if (lhsIsMcoeUnknown && (lhs.getTree().getKind() == Tree.Kind.ARRAY_ACCESS)) {
      // enforces 3. assignment rule: no assignment without ownership
      // lhs is non-@OwningCollection read-only reference and tries to assign its elements.
      checker.reportError(
          assignmentNode.getTree(), "modification.without.ownership", lhs.getTree());
    }
  }

  /**
   * Updates a set of Obligations to account for an assignment and enforces the assignment rules for
   * the MustCallOnElements checker.
   *
   * <p>Assigning to an owning field might remove Obligations, assigning to a resource variable
   * might remove obligations, assigning to a new local variable might modify an Obligation (by
   * increasing the size of its resource alias set), etc.
   *
   * @param obligations the set of Obligations to update
   * @param cfg the control flow graph that contains {@code assignmentNode}
   * @param assignmentNode the assignment
   */
  private void updateObligationsForAssignment(
      Set<Obligation> obligations, ControlFlowGraph cfg, AssignmentNode assignmentNode) {
    Node lhs = assignmentNode.getTarget();
    Element lhsElement = TreeUtils.elementFromTree(lhs.getTree());
    if (lhsElement == null) {
      return;
    }
    // Use the temporary variable for the rhs if it exists.
    Node rhsExpr = NodeUtils.removeCasts(assignmentNode.getExpression());
    Node rhs = getTempVarOrNode(rhsExpr);
    MethodTree enclosingMethod = cfg.getEnclosingMethod(assignmentNode.getTree());
    boolean inConstructor = enclosingMethod != null && TreeUtils.isConstructor(enclosingMethod);

    // update obligations for assignments to @OwningCollection array
    if (!isLoopBodyAnalysis) {
      updateObligationsForAssignmentToOwningCollection(
          lhs, rhs, rhsExpr, assignmentNode, cfg, obligations);
    }

    // Ownership transfer to @Owning field.
    if (lhsElement.getKind() == ElementKind.FIELD && !isLoopBodyAnalysis) {
      boolean isOwningField = !noLightweightOwnership && cmAtf.hasOwning(lhsElement);
      // Check that the must-call obligations of the lhs have been satisfied, if the field is
      // non-final and owning.
      if (isOwningField && cmAtf.canCreateObligations() && !ElementUtils.isFinal(lhsElement)) {
        checkReassignmentToField(obligations, assignmentNode);
      }

      // Remove Obligations from local variables, now that the owning field is responsible.
      // (When obligation creation is turned off, non-final fields cannot take ownership.)
      if (isOwningField
          && rhs instanceof LocalVariableNode
          && (cmAtf.canCreateObligations() || ElementUtils.isFinal(lhsElement))) {

        LocalVariableNode rhsVar = (LocalVariableNode) rhs;

        // Determine which obligations this field assignment can clear.  In a constructor,
        // assignments to `this.field` only clears obligations on normal return, since
        // on exception `this` becomes inaccessible.
        Set<MethodExitKind> toClear;
        if (inConstructor
            && lhs instanceof FieldAccessNode
            && ((FieldAccessNode) lhs).getReceiver() instanceof ThisNode) {
          toClear = Collections.singleton(MethodExitKind.NORMAL_RETURN);
        } else {
          toClear = MethodExitKind.ALL;
        }

        @Nullable Element enclosingElem = lhsElement.getEnclosingElement();
        @Nullable TypeElement enclosingType =
            enclosingElem != null ? ElementUtils.enclosingTypeElement(enclosingElem) : null;

        // Assigning to an owning field is sufficient to clear a must-call alias obligation
        // in a constructor, if the enclosing class has at most one @Owning field. If the
        // class had multiple owning fields, then a soundness bug would occur: the must call
        // alias relationship would allow the whole class' obligation to be fulfilled by
        // closing only one of the parameters passed to the constructor (but the other
        // owning fields might not actually have had their obligations fulfilled). See test
        // case checker/tests/resourceleak/TwoOwningMCATest.java for an example.
        if (hasAtMostOneOwningField(enclosingType)) {
          removeObligationsContainingVar(
              obligations, rhsVar, MustCallAliasHandling.NO_SPECIAL_HANDLING, toClear);
        } else {
          removeObligationsContainingVar(
              obligations,
              rhsVar,
              MustCallAliasHandling.RETAIN_OBLIGATIONS_DERIVED_FROM_A_MUST_CALL_ALIAS_PARAMETER,
              toClear);
        }

        // Finally, if any obligations containing this var remain, then closing the field
        // will satisfy them.  Here we are overly cautious and only track final fields.  In
        // the future we could perhaps relax this guard with careful handling for field
        // reassignments.
        if (ElementUtils.isFinal(lhsElement)) {
          addAliasToObligationsContainingVar(
              obligations,
              rhsVar,
              new ResourceAlias(JavaExpression.fromNode(lhs), lhsElement, lhs.getTree()));
        }
      }
    } else if (lhs instanceof LocalVariableNode) {
      LocalVariableNode lhsVar = (LocalVariableNode) lhs;
      boolean isOwningCollection =
          !isLoopBodyAnalysis && !noLightweightOwnership && cmAtf.hasOwningCollection(lhsElement);
      if (!isOwningCollection) {
        updateObligationsForPseudoAssignment(obligations, assignmentNode, lhsVar, rhs);
      }
      if (isLoopBodyAnalysis) {
        handleAssignmentToCollectionElementVariable(obligations, assignmentNode, lhsVar, rhsExpr);
      }
    }
  }

  /**
   * In the case of a loop body analysis, this method checks whether the rhs of the assignment
   * corresponds to the collection element iterated over, by comparing the AST-trees of the
   * collection element and the rhs for structural equality (as defined by
   * TreeUtils#sameTree(ExpressionTree, ExpressionTree)).
   *
   * <p>If they are determined equal, the lhs variable is added as a resource alias to the
   * obligation of the collection element.
   *
   * @param obligations the set of tracked obligations
   * @param node the assignment node to handle
   * @param lhsVar the left-hand side for the pseudo-assignment
   * @param rhsExpr the right-hand side for the pseudo-assignment, without conversion to a
   *     temp-variable
   */
  private void handleAssignmentToCollectionElementVariable(
      Set<Obligation> obligations, AssignmentNode node, LocalVariableNode lhsVar, Node rhsExpr) {
    if (!isLoopBodyAnalysis) {
      return;
    }
    Obligation oldObligation = null, newObligation = null;
    for (Obligation o : obligations) {
      if (oldObligation != null && newObligation != null) break;
      for (ResourceAlias alias : o.resourceAliases) {
        if ((alias.tree instanceof ExpressionTree)
            && (rhsExpr.getTree() instanceof ExpressionTree)
            && TreeUtils.sameTree(
                (ExpressionTree) alias.tree, (ExpressionTree) rhsExpr.getTree())) {
          Set<ResourceAlias> newResourceAliasesForObligation =
              new LinkedHashSet<>(o.resourceAliases);
          // It is possible to observe assignments to temporary variables, e.g.,
          // synthetic assignments to ternary expression variables in the CFG.  For such
          // cases, use the tree associated with the temp var for the resource alias,
          // as that is the tree where errors should be reported.
          Tree treeForAlias =
              // I don't think we need a tempVar here, since the only case where we're interested
              // in such an assignment in the loopBodyAnalysis is if the lhs is an actual variable
              // to be used as an alias, so we don't care about the case where lhs is a temp-var.
              // typeFactory.isTempVar(lhsVar)
              //     ? typeFactory.getTreeForTempVar(lhsVar)
              //     : node.getTree();
              node.getTree();
          ResourceAlias aliasForAssignment =
              new ResourceAlias(new LocalVariable(lhsVar), treeForAlias);
          newResourceAliasesForObligation.add(aliasForAssignment);
          oldObligation = o;
          newObligation = new Obligation(newResourceAliasesForObligation, o.whenToEnforce);
          break;
        }
      }
    }
    if (oldObligation != null && newObligation != null) {
      obligations.remove(oldObligation);
      obligations.add(newObligation);
      // System.out.println("added obligation (loop-body-analysis): " + newObligation);
    }
  }

  /**
   * Returns true iff the given type element has 0 or 1 @Owning fields.
   *
   * @param element an element for a class
   * @return true iff element has no more than 1 owning field
   */
  private boolean hasAtMostOneOwningField(TypeElement element) {
    List<VariableElement> fields = ElementUtils.getAllFieldsIn(element, cmAtf.getElementUtils());
    // Has an owning field already been encountered?
    boolean hasOwningField = false;
    for (VariableElement field : fields) {
      if (cmAtf.hasOwning(field)) {
        if (hasOwningField) {
          return false;
        } else {
          hasOwningField = true;
        }
      }
    }
    // We haven't seen two owning fields, so there must be 1 or 0.
    return true;
  }

  /**
   * Add a new alias to all Obligations that have {@code var} in their resource-alias set. This
   * method should be used when {@code var} and {@code newAlias} definitively point to the same
   * object in memory.
   *
   * @param obligations the set of Obligations to modify
   * @param var a variable
   * @param newAlias a new {@link ResourceAlias} to add
   */
  private void addAliasToObligationsContainingVar(
      Set<Obligation> obligations, LocalVariableNode var, ResourceAlias newAlias) {
    Iterator<Obligation> it = obligations.iterator();
    List<Obligation> newObligations = new ArrayList<>();

    while (it.hasNext()) {
      Obligation obligation = it.next();
      if (obligation.canBeSatisfiedThrough(var)) {
        it.remove();
        Set<ResourceAlias> newAliases = new LinkedHashSet<>(obligation.resourceAliases);
        newAliases.add(newAlias);
        newObligations.add(obligation.getReplacement(newAliases, obligation.whenToEnforce));
      }
    }

    obligations.addAll(newObligations);
  }

  /**
   * Remove any Obligations that contain {@code var} in their resource-alias set.
   *
   * @param obligations the set of Obligations to modify
   * @param var a variable
   */
  /*package-private*/ void removeObligationsContainingVar(
      Set<Obligation> obligations, LocalVariableNode var) {
    removeObligationsContainingVar(
        obligations, var, MustCallAliasHandling.NO_SPECIAL_HANDLING, MethodExitKind.ALL);
  }

  /**
   * Helper type for {@link #removeObligationsContainingVar(Set, LocalVariableNode,
   * MustCallAliasHandling, Set)}
   */
  private enum MustCallAliasHandling {
    /**
     * Obligations derived from {@link MustCallAlias} parameters do not require special handling,
     * and they should be removed like any other obligation.
     */
    NO_SPECIAL_HANDLING,

    /**
     * Obligations derived from {@link MustCallAlias} parameters are not satisfied and should be
     * retained.
     */
    RETAIN_OBLIGATIONS_DERIVED_FROM_A_MUST_CALL_ALIAS_PARAMETER,
  }

  /**
   * Remove Obligations that contain {@code var} in their resource-alias set.
   *
   * <p>Some operations do not satisfy all Obligations. For instance, assigning to a field in a
   * constructor only satisfies Obligations when the constructor exits normally (i.e. without
   * throwing an exception). The last two arguments to this method can be used to retain some
   * Obligations in special circumstances.
   *
   * @param obligations the set of Obligations to modify
   * @param var a variable
   * @param mustCallAliasHandling how to treat Obligations derived from {@link MustCallAlias}
   *     parameters
   * @param whatToClear the kind of Obligations to remove
   */
  private void removeObligationsContainingVar(
      Set<Obligation> obligations,
      LocalVariableNode var,
      MustCallAliasHandling mustCallAliasHandling,
      Set<MethodExitKind> whatToClear) {
    List<Obligation> newObligations = new ArrayList<>();

    Iterator<Obligation> it = obligations.iterator();
    while (it.hasNext()) {
      Obligation obligation = it.next();

      if (obligation.canBeSatisfiedThrough(var)
          && (mustCallAliasHandling == MustCallAliasHandling.NO_SPECIAL_HANDLING
              || !obligation.derivedFromMustCallAlias())) {
        it.remove();

        Set<MethodExitKind> whenToEnforce = new HashSet<>(obligation.whenToEnforce);
        whenToEnforce.removeAll(whatToClear);

        if (!whenToEnforce.isEmpty()) {
          newObligations.add(obligation.getReplacement(obligation.resourceAliases, whenToEnforce));
        }
      }
    }

    obligations.addAll(newObligations);
  }

  /**
   * Update a set of tracked Obligations to account for a (pseudo-)assignment to some variable, as
   * in a gen-kill dataflow analysis problem. That is, add ("gen") and remove ("kill") resource
   * aliases from Obligations in the {@code obligations} set as appropriate based on the
   * (pseudo-)assignment performed by {@code node}. This method may also remove an Obligation
   * entirely if the analysis concludes that its resource alias set is empty because the last
   * tracked alias to it has been overwritten (including checking that the must-call obligations
   * were satisfied before the assignment).
   *
   * <p>Pseudo-assignments may include operations that "assign" to a temporary variable, exposing
   * the possible value flow into the variable. E.g., for a ternary expression {@code b ? x : y}
   * whose temporary variable is {@code t}, this method may process "assignments" {@code t = x} and
   * {@code t = y}, thereby capturing the two possible values of {@code t}.
   *
   * @param obligations the tracked Obligations, which will be side-effected
   * @param node the node performing the pseudo-assignment; it is not necessarily an assignment node
   * @param lhsVar the left-hand side variable for the pseudo-assignment
   * @param rhs the right-hand side for the pseudo-assignment, which must have been converted to a
   *     temporary variable (via a call to {@link
   *     RLCCalledMethodsAnnotatedTypeFactory#getTempVarForNode})
   */
  /*package-private*/ void updateObligationsForPseudoAssignment(
      Set<Obligation> obligations, Node node, LocalVariableNode lhsVar, Node rhs) {
    // Replacements to eventually perform in Obligations.  This map is kept to avoid a
    // ConcurrentModificationException in the loop below.
    Map<Obligation, Obligation> replacements = new LinkedHashMap<>();
    // Cache to re-use on subsequent iterations.
    ResourceAlias aliasForAssignment = null;
    for (Obligation obligation : obligations) {
      // This is a non-null value iff the resource alias set for obligation needs to
      // change because of the pseudo-assignment. The value of this variable is the new
      // alias set for `obligation` if it is non-null.
      Set<ResourceAlias> newResourceAliasesForObligation = null;

      // Always kill the lhs var if it is present in the resource alias set for this
      // Obligation by removing it from the resource alias set.
      ResourceAlias aliasForLhs = obligation.getResourceAlias(lhsVar);
      if (aliasForLhs != null) {
        newResourceAliasesForObligation = new LinkedHashSet<>(obligation.resourceAliases);
        newResourceAliasesForObligation.remove(aliasForLhs);
      }
      // If rhs is a variable tracked in the Obligation's resource alias set, gen the lhs
      // by adding it to the resource alias set.
      if (rhs instanceof LocalVariableNode
          && obligation.canBeSatisfiedThrough((LocalVariableNode) rhs)) {
        LocalVariableNode rhsVar = (LocalVariableNode) rhs;
        if (newResourceAliasesForObligation == null) {
          newResourceAliasesForObligation = new LinkedHashSet<>(obligation.resourceAliases);
        }
        if (aliasForAssignment == null) {
          // It is possible to observe assignments to temporary variables, e.g.,
          // synthetic assignments to ternary expression variables in the CFG.  For such
          // cases, use the tree associated with the temp var for the resource alias,
          // as that is the tree where errors should be reported.
          Tree treeForAlias =
              cmAtf.isTempVar(lhsVar) ? cmAtf.getTreeForTempVar(lhsVar) : node.getTree();
          aliasForAssignment = new ResourceAlias(new LocalVariable(lhsVar), treeForAlias);
        }
        newResourceAliasesForObligation.add(aliasForAssignment);
        // Remove temp vars from tracking once they are assigned to another location.
        if (cmAtf.isTempVar(rhsVar)) {
          ResourceAlias aliasForRhs = obligation.getResourceAlias(rhsVar);
          if (aliasForRhs != null) {
            newResourceAliasesForObligation.remove(aliasForRhs);
          }
        }
      }

      // If no changes were made to the resource alias set, there is no need to update the
      // Obligation.
      if (newResourceAliasesForObligation == null) {
        continue;
      }

      if (!isLoopBodyAnalysis && newResourceAliasesForObligation.isEmpty()) {
        // Because the last reference to the resource has been overwritten, check the
        // must-call obligation.
        MustCallAnnotatedTypeFactory mcAtf = cmAtf.getMustCallAnnotatedTypeFactory();
        checkMustCall(
            obligation,
            cmAtf.getStoreBefore(node),
            mcAtf.getStoreBefore(node),
            "variable overwritten by assignment " + node.getTree(),
            true);
        replacements.put(obligation, null);
      } else {
        replacements.put(
            obligation,
            obligation.getReplacement(newResourceAliasesForObligation, obligation.whenToEnforce));
      }
    }

    // Finally, update the set of Obligations according to the replacements.
    for (Map.Entry<Obligation, Obligation> entry : replacements.entrySet()) {
      obligations.remove(entry.getKey());
      if (entry.getValue() != null && !entry.getValue().resourceAliases.isEmpty()) {
        obligations.add(entry.getValue());
      }
    }
  }

  /**
   * Issues an error if the given re-assignment to a non-final, owning field is not valid. A
   * re-assignment is valid if the called methods type of the lhs before the assignment satisfies
   * the must-call obligations of the field.
   *
   * <p>Despite the name of this method, the argument {@code node} might be the first and only
   * assignment to a field.
   *
   * @param obligations current tracked Obligations
   * @param node an assignment to a non-final, owning field
   */
  private void checkReassignmentToField(Set<Obligation> obligations, AssignmentNode node) {
    Node lhsNode = node.getTarget();

    if (!(lhsNode instanceof FieldAccessNode)) {
      throw new TypeSystemError(
          "checkReassignmentToField: non-field node " + node + " of class " + node.getClass());
    }

    FieldAccessNode lhs = (FieldAccessNode) lhsNode;
    Node receiver = lhs.getReceiver();

    if (permitStaticOwning && receiver instanceof ClassNameNode) {
      return;
    }

    // TODO: it would be better to defer getting the path until after checking
    // for a CreatesMustCallFor annotation, because getting the path can be expensive.
    // It might be possible to exploit the CFG structure to find the enclosing
    // method (rather than using the path, as below), because if a method is being
    // analyzed then it should be the root of the CFG (I think).
    TreePath currentPath = cmAtf.getPath(node.getTree());
    MethodTree enclosingMethodTree = TreePathUtil.enclosingMethod(currentPath);

    if (enclosingMethodTree == null) {
      // The assignment is taking place outside of a method:  in a variable declaration's
      // initializer or in an initializer block.
      // The Resource Leak Checker issues no error if the assignment is a field initializer.
      if (node.getTree().getKind() == Tree.Kind.VARIABLE) {
        // An assignment to a field that is also a declaration must be a field initializer
        // (VARIABLE Trees are only used for declarations).  Assignment in a field
        // initializer is always permitted.
        return;
      } else if (permitInitializationLeak
          && TreePathUtil.isTopLevelAssignmentInInitializerBlock(currentPath)) {
        // This is likely not reassignment; if reassignment, the number of assignments that
        // were not warned about is limited to other initializations (is not unbounded).
        // This behavior is unsound; see InstanceInitializer.java test case.
        return;
      } else {
        // Issue an error if the field has a non-empty must-call type.
        MustCallAnnotatedTypeFactory mcTypeFactory = cmAtf.getMustCallAnnotatedTypeFactory();
        AnnotationMirror mcAnno =
            mcTypeFactory.getAnnotatedType(lhs.getElement()).getPrimaryAnnotation(MustCall.class);
        List<String> mcValues =
            AnnotationUtils.getElementValueArray(
                mcAnno, mcTypeFactory.getMustCallValueElement(), String.class);
        if (mcValues.isEmpty()) {
          return;
        }
        VariableElement lhsElement = TreeUtils.variableElementFromTree(lhs.getTree());
        checker.reportError(
            node.getTree(),
            "required.method.not.called",
            formatMissingMustCallMethods(mcValues),
            "field " + lhsElement.getSimpleName().toString(),
            lhsElement.asType().toString(),
            "Field assignment outside method or declaration might overwrite field's current value");
        return;
      }
    } else if (permitInitializationLeak && TreeUtils.isConstructor(enclosingMethodTree)) {
      Element enclosingClassElement =
          TreeUtils.elementFromDeclaration(enclosingMethodTree).getEnclosingElement();
      if (ElementUtils.isTypeElement(enclosingClassElement)) {
        Element receiverElement = TypesUtils.getTypeElement(receiver.getType());
        if (Objects.equals(enclosingClassElement, receiverElement)) {
          return;
        }
      }
    }

    // Check that there is a corresponding CreatesMustCallFor annotation, unless this is
    // 1) an assignment to a field of a newly-declared local variable whose scope does not
    // extend beyond the method's body (and which therefore could not be targeted by an
    // annotation on the method declaration), or 2) the rhs is a null literal (so there's
    // nothing to reset).
    if (!(receiver instanceof LocalVariableNode
            && varTrackedInObligations(obligations, (LocalVariableNode) receiver))
        && !(node.getExpression() instanceof NullLiteralNode)) {
      checkEnclosingMethodIsCreatesMustCallFor(node.getTarget(), enclosingMethodTree);
    }

    // The following code handles a special case where the field being assigned is itself
    // getting passed in an owning position to another method on the RHS of the assignment.
    // For example, if the field's type is a class whose constructor takes another instance
    // of itself (such as a node in a linked list) in an owning position, re-assigning the
    // field to a new instance that takes the field's value as an owning parameter is safe
    // (the new value has taken responsibility for closing the old value). In such a case,
    // it is not required that the must-call obligation of the field be satisfied via method
    // calls before the assignment, since the invoked method will take ownership of the
    // object previously referenced by the field and handle the obligation. This fixes the
    // false positive in https://github.com/typetools/checker-framework/issues/5971.
    Node rhs = node.getExpression();
    if (!noLightweightOwnership
        && (rhs instanceof ObjectCreationNode || rhs instanceof MethodInvocationNode)) {

      List<Node> arguments = getArgumentsOfInvocation(rhs);
      List<? extends VariableElement> parameters = getParametersOfInvocation(rhs);

      if (arguments.size() == parameters.size()) {
        for (int i = 0; i < arguments.size(); i++) {
          VariableElement param = parameters.get(i);
          if (!isLoopBodyAnalysis && cmAtf.hasOwning(param)) {
            Node argument = arguments.get(i);
            if (argument.equals(lhs)) {
              return;
            }
          }
        }
      } else {
        // This could happen, e.g., with varargs, or with strange cases like generated Enum
        // constructors. In the varargs case (i.e. if the varargs parameter is owning),
        // only the first of the varargs arguments will actually get transferred: the second
        // and later varargs arguments will continue to be tracked at the call-site.
        // For now, just skip this case - the worst that will happen is a false positive in
        // cases like the varargs one described above.
        // TODO allow for ownership transfer here if needed in future, but for now do
        // nothing
      }
    }

    MustCallAnnotatedTypeFactory mcTypeFactory = cmAtf.getMustCallAnnotatedTypeFactory();

    // Get the Must Call type for the field. If there's info about this field in the store, use
    // that. Otherwise, use the declared type of the field
    CFStore mcStore = mcTypeFactory.getStoreBefore(lhs);
    CFValue mcValue = mcStore.getValue(lhs);
    AnnotationMirror mcAnno = null;
    if (mcValue != null) {
      mcAnno = AnnotationUtils.getAnnotationByClass(mcValue.getAnnotations(), MustCall.class);
    }
    if (mcAnno == null) {
      // No stored value (or the stored value is Poly/top), so use the declared type.
      mcAnno =
          mcTypeFactory.getAnnotatedType(lhs.getElement()).getPrimaryAnnotation(MustCall.class);
    }
    // if mcAnno is still null, then the declared type must be something other than
    // @MustCall (probably @MustCallUnknown). Do nothing in this case: a warning
    // about the field will be issued elsewhere (it will be impossible to satisfy its
    // obligations!).
    if (mcAnno == null) {
      return;
    }
    List<String> mcValues =
        AnnotationUtils.getElementValueArray(
            mcAnno, mcTypeFactory.getMustCallValueElement(), String.class);

    if (mcValues.isEmpty()) {
      return;
    }

    // Get the store before the RHS rather than the assignment node, because the CFG always has
    // the RHS first. If the RHS has side-effects, then the assignment node's store will have
    // had its inferred types erased.
    AccumulationStore cmStoreBefore = cmAtf.getStoreBefore(rhs);
    AccumulationValue cmValue = cmStoreBefore == null ? null : cmStoreBefore.getValue(lhs);
    AnnotationMirror cmAnno = null;
    if (cmValue != null) { // When store contains the lhs
      Set<String> accumulatedValues = cmValue.getAccumulatedValues();
      if (accumulatedValues != null) { // type variable or wildcard type
        cmAnno = cmAtf.createCalledMethods(accumulatedValues.toArray(new String[0]));
      } else {
        for (AnnotationMirror anno : cmValue.getAnnotations()) {
          if (AnnotationUtils.areSameByName(
              anno, "org.checkerframework.checker.calledmethods.qual.CalledMethods")) {
            cmAnno = anno;
          }
        }
      }
    }
    if (cmAnno == null) {
      cmAnno = cmAtf.top;
    }
    if (!calledMethodsSatisfyMustCall(mcValues, cmAnno)) {
      VariableElement lhsElement = TreeUtils.variableElementFromTree(lhs.getTree());
      if (!checker.shouldSkipUses(lhsElement)) {
        checker.reportError(
            node.getTree(),
            "required.method.not.called",
            formatMissingMustCallMethods(mcValues),
            "field " + lhsElement.getSimpleName().toString(),
            lhsElement.asType().toString(),
            " Non-final owning field might be overwritten");
      }
    }
  }

  /**
   * Checks that the method that encloses a write to a field is marked with @CreatesMustCallFor
   * annotation whose target is the object whose field is being written to.
   *
   * @param receiver the receiver node of the write, which is a field with calling obligations
   * @param enclosingMethod the MethodTree in which the write takes place
   */
  private void checkEnclosingMethodIsCreatesMustCallFor(Node receiver, MethodTree enclosingMethod) {
    if (!(receiver instanceof FieldAccessNode)) {
      return;
    }
    if (permitStaticOwning && ((FieldAccessNode) receiver).getReceiver() instanceof ClassNameNode) {
      return;
    }

    String receiverString = receiverAsString((FieldAccessNode) receiver);
    if ("this".equals(receiverString) && TreeUtils.isConstructor(enclosingMethod)) {
      // Constructors always create must-call obligations, so there is no need for them to
      // be annotated.
      return;
    }
    ExecutableElement enclosingMethodElt = TreeUtils.elementFromDeclaration(enclosingMethod);
    MustCallAnnotatedTypeFactory mcAtf = cmAtf.getMustCallAnnotatedTypeFactory();

    List<String> cmcfValues =
        RLCCalledMethodsVisitor.getCreatesMustCallForValues(enclosingMethodElt, mcAtf, cmAtf);

    if (cmcfValues.isEmpty()) {
      checker.reportError(
          enclosingMethod,
          "missing.creates.mustcall.for",
          enclosingMethodElt.getSimpleName().toString(),
          receiverString,
          ((FieldAccessNode) receiver).getFieldName());
      return;
    }

    List<String> checked = new ArrayList<>();
    for (String targetStrWithoutAdaptation : cmcfValues) {
      String targetStr;
      try {
        targetStr =
            StringToJavaExpression.atMethodBody(
                    targetStrWithoutAdaptation, enclosingMethod, checker)
                .toString();
      } catch (JavaExpressionParseException e) {
        targetStr = targetStrWithoutAdaptation;
      }
      if (targetStr.equals(receiverString)) {
        // This @CreatesMustCallFor annotation matches.
        return;
      }
      checked.add(targetStr);
    }
    checker.reportError(
        enclosingMethod,
        "incompatible.creates.mustcall.for",
        enclosingMethodElt.getSimpleName().toString(),
        receiverString,
        ((FieldAccessNode) receiver).getFieldName(),
        String.join(", ", checked));
  }

  /**
   * Gets a standardized name for an object whose field is being re-assigned.
   *
   * @param fieldAccessNode a field access node
   * @return the name of the object whose field is being accessed (the receiver), as a string
   */
  private String receiverAsString(FieldAccessNode fieldAccessNode) {
    Node receiver = fieldAccessNode.getReceiver();
    if (receiver instanceof ThisNode) {
      return "this";
    }
    if (receiver instanceof LocalVariableNode) {
      return ((LocalVariableNode) receiver).getName();
    }
    if (receiver instanceof ClassNameNode) {
      return ((ClassNameNode) receiver).getElement().toString();
    }
    if (receiver instanceof SuperNode) {
      return "super";
    }
    throw new TypeSystemError(
        "unexpected receiver of field assignment: " + receiver + " of type " + receiver.getClass());
  }

  /**
   * Finds the arguments passed in the {@code @MustCallAlias} positions for a call.
   *
   * @param callNode callNode representing the call; must be {@link MethodInvocationNode} or {@link
   *     ObjectCreationNode}
   * @return if {@code callNode} invokes a method with a {@code @MustCallAlias} annotation on some
   *     formal parameter(s) (or the receiver), returns the result of calling {@link
   *     #removeCastsAndGetTmpVarIfPresent(Node)} on the argument(s) passed in corresponding
   *     position(s). Otherwise, returns an empty List.
   */
  private List<Node> getMustCallAliasArgumentNodes(Node callNode) {
    Preconditions.checkArgument(
        callNode instanceof MethodInvocationNode || callNode instanceof ObjectCreationNode);
    List<Node> result = new ArrayList<>();
    if (!cmAtf.hasMustCallAlias(callNode.getTree())) {
      return result;
    }

    List<Node> args = getArgumentsOfInvocation(callNode);
    List<? extends VariableElement> parameters = getParametersOfInvocation(callNode);
    for (int i = 0; i < args.size(); i++) {
      if (cmAtf.hasMustCallAlias(parameters.get(i))) {
        result.add(removeCastsAndGetTmpVarIfPresent(args.get(i)));
      }
    }

    // If none of the parameters were @MustCallAlias, it must be the receiver
    if (result.isEmpty() && callNode instanceof MethodInvocationNode) {
      result.add(
          removeCastsAndGetTmpVarIfPresent(
              ((MethodInvocationNode) callNode).getTarget().getReceiver()));
    }

    return result;
  }

  /**
   * If a temporary variable exists for node after typecasts have been removed, return it.
   * Otherwise, return node.
   *
   * @param node a node
   * @return either a tempvar for node's content sans typecasts, or node
   */
  /*package-private*/ Node removeCastsAndGetTmpVarIfPresent(Node node) {
    // TODO: Create temp vars for TypeCastNodes as well, so there is no need to explicitly
    // remove casts here.
    node = NodeUtils.removeCasts(node);
    return getTempVarOrNode(node);
  }

  /**
   * Get the nodes representing the arguments of a method or constructor invocation from the
   * invocation node.
   *
   * @param node a MethodInvocation or ObjectCreation node
   * @return the arguments, in order
   */
  /*package-private*/ List<Node> getArgumentsOfInvocation(Node node) {
    if (node instanceof MethodInvocationNode) {
      MethodInvocationNode invocationNode = (MethodInvocationNode) node;
      return invocationNode.getArguments();
    } else if (node instanceof ObjectCreationNode) {
      return ((ObjectCreationNode) node).getArguments();
    } else {
      throw new TypeSystemError("unexpected node type " + node.getClass());
    }
  }

  /**
   * Get the elements representing the formal parameters of a method or constructor, from an
   * invocation of that method or constructor.
   *
   * @param node a method invocation or object creation node
   * @return a list of the declarations of the formal parameters of the method or constructor being
   *     invoked
   */
  /*package-private*/ List<? extends VariableElement> getParametersOfInvocation(Node node) {
    ExecutableElement executableElement;
    if (node instanceof MethodInvocationNode) {
      MethodInvocationNode invocationNode = (MethodInvocationNode) node;
      executableElement = TreeUtils.elementFromUse(invocationNode.getTree());
    } else if (node instanceof ObjectCreationNode) {
      executableElement = TreeUtils.elementFromUse(((ObjectCreationNode) node).getTree());
    } else {
      throw new TypeSystemError("unexpected node type " + node.getClass());
    }

    return executableElement.getParameters();
  }

  /**
   * Is the return type of the invoked method one that should be tracked?
   *
   * @param node a method invocation
   * @return true iff the checker is in no-lightweight-ownership mode, or the method has a
   *     {@code @MustCallAlias} annotation, or (1) the method has a return type that needs to be
   *     tracked (i.e., it has a non-empty {@code @MustCall} obligation and (2) the method
   *     declaration does not have a {@code @NotOwning} annotation
   */
  private boolean shouldTrackReturnType(MethodInvocationNode node) {
    if (noLightweightOwnership) {
      // Default to always transferring at return if not using LO, just like Eclipse does.
      return true;
    }
    MethodInvocationTree methodInvocationTree = node.getTree();
    ExecutableElement executableElement = TreeUtils.elementFromUse(methodInvocationTree);
    if (cmAtf.hasMustCallAlias(executableElement)) {
      // assume tracking is required
      return true;
    }
    TypeMirror type = ElementUtils.getType(executableElement);
    // void or primitive-returning methods are "not owning" by construction
    if (type.getKind() == TypeKind.VOID || type.getKind().isPrimitive()) {
      return false;
    }
    List<String> mcoeValues = RLCUtils.getMcoeValuesInManualAnno(type);
    TypeElement typeElt = TypesUtils.getTypeElement(type);
    // track if there's an @OwningCollection or non-empty @MustCallOnElements annotation
    if ((typeElt != null && cmAtf.hasOwningCollection(typeElt))
        || (mcoeValues != null && mcoeValues.size() > 0)) {
      return true;
    }
    // no need to track if type has no possible @MustCall obligation
    if (typeElt != null
        && cmAtf.hasEmptyMustCallValue(typeElt)
        && cmAtf.hasEmptyMustCallValue(methodInvocationTree)) {
      return false;
    }
    // check for absence of @NotOwning annotation
    return !cmAtf.hasNotOwning(executableElement);
  }

  /**
   * Get all successor blocks for some block, except for those corresponding to ignored exception
   * types. See {@link RLCCalledMethodsAnalysis#isIgnoredExceptionType(TypeMirror)}. Each
   * exceptional successor is paired with the type of exception that leads to it, for use in error
   * messages.
   *
   * @param block input block
   * @return set of pairs (b, t), where b is a successor block, and t is the type of exception for
   *     the CFG edge from block to b, or {@code null} if b is a non-exceptional successor
   */
  private Set<IPair<Block, @Nullable TypeMirror>> getSuccessorsExceptIgnoredExceptions(
      Block block) {
    if (block.getType() == Block.BlockType.EXCEPTION_BLOCK) {
      ExceptionBlock excBlock = (ExceptionBlock) block;
      Set<IPair<Block, @Nullable TypeMirror>> result = new LinkedHashSet<>();
      // regular successor
      Block regularSucc = excBlock.getSuccessor();
      if (regularSucc != null) {
        result.add(IPair.of(regularSucc, null));
      }
      // non-ignored exception successors
      Map<TypeMirror, Set<Block>> exceptionalSuccessors = excBlock.getExceptionalSuccessors();
      for (Map.Entry<TypeMirror, Set<Block>> entry : exceptionalSuccessors.entrySet()) {
        TypeMirror exceptionType = entry.getKey();
        if (!cmAtf.isIgnoredExceptionType(exceptionType)) {
          for (Block exSucc : entry.getValue()) {
            result.add(IPair.of(exSucc, exceptionType));
          }
        }
      }
      return result;
    } else {
      Set<IPair<Block, @Nullable TypeMirror>> result = new LinkedHashSet<>();
      for (Block b : block.getSuccessors()) {
        result.add(IPair.of(b, null));
      }
      return result;
    }
  }

  /**
   * Checks whether the given return statement returns an {@code @OwningCollection} if and only if
   * the return type is annotated {@code @OwningCollection} and it is not a field. Violations of
   * this rule result in a reported error.
   *
   * @param node the return node
   * @param cfg the control flow graph to get the enclosing method
   */
  private void verifyReturnStatement(ReturnNode node, ControlFlowGraph cfg) {
    Node result = node.getResult();
    Tree tree = result == null ? null : result.getTree();
    Element elt = tree == null ? null : TreeUtils.elementFromTree(tree);

    boolean isOwningCollection = elt != null && cmAtf.hasOwningCollection(elt);
    boolean isField = elt != null && elt.getKind().isField();
    CFStore mcoeStore =
        mcoeTypeFactory == null ? null : mcoeTypeFactory.getStoreBefore(node.getTree());
    boolean isMcoeUnknown =
        mcoeStore != null
            && elt != null
            && mcoeTypeFactory != null
            && mcoeTypeFactory.isMustCallOnElementsUnknown(mcoeStore, tree);

    MethodTree enclosingMethod = cfg.getEnclosingMethod(node.getTree());
    if (enclosingMethod == null) {
      return;
    }

    ModifiersTree modifiers = enclosingMethod.getModifiers();
    boolean returnTypeIsOwningCollection = false;
    boolean returnTypeIsCollectionAlias = false;
    for (AnnotationTree annoTree : modifiers.getAnnotations()) {
      AnnotationMirror anno = TreeUtils.annotationFromAnnotationTree(annoTree);
      if (AnnotationUtils.areSameByName(anno, OwningCollection.class.getCanonicalName())) {
        returnTypeIsOwningCollection = true;
      } else if (AnnotationUtils.areSameByName(anno, CollectionAlias.class.getCanonicalName())) {
        returnTypeIsCollectionAlias = true;
      }
    }

    if (returnTypeIsOwningCollection) {
      if (isField) {
        checker.reportError(node.getTree(), "owningcollection.field.returned");
      } else if (isMcoeUnknown) {
        checker.reportError(node.getTree(), "return.without.ownership", tree);
      } else if (!isOwningCollection) {
        checker.reportError(node.getTree(), "non.owningcollection.return.value", tree);
      } else {
        // OwningCollection, non-write disabled reference. Exactly as intended.
      }
    } else if (returnTypeIsCollectionAlias) {
      if (isMcoeUnknown) {
        // intended - return an alias
      } else if (isOwningCollection) {
        // this is an owning reference. Returning a read-only alias is only safe if it is
        // an @OwningCollection field (because the obligation certainly stays within the class).
        // If it is not a field, the obligation is dismissed, because the collection does not leave
        // the scope, but a new obligation is not created at call-site, since the call-site does not
        // take ownership.
        if (!isField) {
          checker.reportError(
              node.getTree(),
              "illegal.ownership.transfer",
              "Cannot transfer ownership to a CollectionAlias return type. Did you mean to return @OwningCollection?");
        }
      } else {
        // not a resource collection.
        // report warning that the return type annotation is unnecessary.
        checker.reportWarning(
            node.getTree(), "unnecessary.collectionalias.return.type", node.getTree());
      }
    } else {
      // no return type annotation
      if (isMcoeUnknown) {
        checker.reportError(node.getTree(), "returning.unannotated.owningcollection.alias", tree);
      } else if (isOwningCollection) {
        checker.reportError(node.getTree(), "owningcollection.return.value", tree);
      }
    }
  }

  /**
   * Ensures for the declaration given by the {@code VariableTree} that it is not:
   *
   * <ul>
   *   <li>{@code @OwningCollection} and NOT a 1D-array/collection
   *   <li>{@code @Owning} and an array/collection
   * </ul>
   *
   * If either of those are true about the declaration, an error is reported.
   *
   * @param tree the declaration
   */
  private void checkVariableDeclaration(VariableTree tree) {
    Element elt = tree == null ? null : TreeUtils.elementFromDeclaration(tree);
    boolean isOwningCollection = elt != null && cmAtf.hasOwningCollection(elt);
    boolean isOwning = elt != null && cmAtf.hasOwning(elt);
    boolean isArray =
        elt != null && elt.asType() != null && elt.asType().getKind() == TypeKind.ARRAY;
    boolean is1dArray =
        isArray
            && ((ArrayType) elt.asType()).getComponentType() != null
            && ((ArrayType) elt.asType()).getComponentType().getKind() != TypeKind.ARRAY;
    boolean isCollection = elt != null && RLCUtils.isCollection(tree, mcoeTypeFactory);
    if (isOwningCollection && !(is1dArray || isCollection)) {
      checker.reportError(tree, "owningcollection.noncollection", tree);
    } else if (isOwning && (isArray || isCollection)) {
      checker.reportError(tree, "owning.collection", tree);
    }
  }

  /**
   * If this exception is thrown, it indicates to the caller of the method that the loop body
   * analysis should be aborted and immediately return. This happens if a {@code Block} is
   * encountered, which does not have an incoming store, meaning the analysis is not supposed to
   * reach it. However, in the loop body analysis, such a store may be explicitly explored (if a
   * potentially fulfilling loop is in an unreachable {@code Block}). It is then impossible to
   * proceed with the analysis, since stores for these {@code Block}s don't exist. Hence, the
   * analysis should abort.
   */
  @SuppressWarnings("serial")
  private static class InvalidLoopBodyAnalysisException extends Exception {

    /**
     * Construct an InvalidLoopBodyAnalysisException
     *
     * @param message the error message
     */
    public InvalidLoopBodyAnalysisException(String message) {
      super(message);
    }
  }

  /**
   * Helper for {@link #propagateObligationsToSuccessorBlocks(ControlFlowGraph, Set, Block, Set,
   * Deque)} that propagates obligations along a single edge.
   *
   * @param obligations the Obligations for the current block
   * @param currentBlock the current block
   * @param successor a successor of the current block
   * @param exceptionType the type of edge from <code>currentBlock</code> to <code>successor
   *     </code>: <code>null</code> for normal control flow, or a throwable type for exceptional
   *     control flow
   * @param visited block-Obligations pairs already analyzed or already on the worklist
   * @param worklist current worklist
   */
  private void propagateObligationsToSuccessorBlock(
      Set<Obligation> obligations,
      Block currentBlock,
      Block successor,
      @Nullable TypeMirror exceptionType,
      Set<BlockWithObligations> visited,
      Deque<BlockWithObligations> worklist)
      throws InvalidLoopBodyAnalysisException {
    List<Node> currentBlockNodes = currentBlock.getNodes();
    // successorObligations eventually contains the Obligations to propagate to successor.
    // The loop below mutates it.
    Set<Obligation> successorObligations = new LinkedHashSet<>();
    // A detailed reason to give in the case that the last resource alias of an Obligation
    // goes out of scope without a called-methods type that satisfies the corresponding
    // must-call obligation along the current control-flow edge. Computed here for
    // efficiency; used in the loop over the Obligations, below.
    String exitReasonForErrorMessage =
        exceptionType == null
            ?
            // Technically the variable may be going out of scope before the method
            // exit, but that doesn't seem to provide additional helpful
            // information.
            "regular method exit"
            : "possible exceptional exit due to "
                + ((ExceptionBlock) currentBlock).getNode().getTree()
                + " with exception type "
                + exceptionType;
    MustCallAnnotatedTypeFactory mcAtf =
        isLoopBodyAnalysis ? null : cmAtf.getMustCallAnnotatedTypeFactory();
    CFStore mcCFStore = isLoopBodyAnalysis ? null : mcAtf.getStoreBefore(successor);
    final CFStore mcoeStore =
        isLoopBodyAnalysis ? null : mcoeTypeFactory.getStoreForBlock(true, successor, null);
    // Computed outside the Obligation loop for efficiency.
    if (isLoopBodyAnalysis && cmAtf.getInput(successor) == null) {
      // there are CFG nodes that have no incoming store. In the consistency analysis,
      // these are not explored. However, in the loop body analysis, such a node may be explicitly
      // explored
      // (if a potentially fulfilling loop is in an unreachable block). Hence it is safe to return
      // here and
      // not propagate obligations, since the state is not reached by the analysis anyways.
      // Not just that, but note that if this state is reached, the entire loop is in an state
      // unreachable
      // by the analysis. If the beginning of the loop was reachable, the analysis wouldn't have
      // taken a path that is
      // unreachable for it. Hence, the entire loop body analysis can be aborted here.
      // The thrown exception is caught in the caller and the loop body analysis aborts, i.e.
      // returns
      // immediately.
      throw new InvalidLoopBodyAnalysisException("Block with no incoming store.");
    }
    if (cmAtf.getInput(successor) == null) {
      throw new BugInCF("block with no outgoing incoming store: " + successor);
    }
    AccumulationStore regularStoreOfSuccessor = cmAtf.getInput(successor).getRegularStore();
    for (Obligation obligation : obligations) {
      // This boolean is true if there is no evidence that the Obligation does not go out
      // of scope - that is, if there is definitely a resource alias that is in scope in
      // the successor.
      boolean obligationGoesOutOfScopeBeforeSuccessor = true;
      for (ResourceAlias resourceAlias : obligation.resourceAliases) {
        if (aliasInScopeInSuccessor(
            regularStoreOfSuccessor, mcoeStore, mcCFStore, resourceAlias, obligation)) {
          obligationGoesOutOfScopeBeforeSuccessor = false;
          break;
        }
      }
      // This check is to determine if this Obligation's resource aliases are definitely
      // going out of scope: if this is an exit block or there is no information about any
      // of them in the successor store, all aliases must be going out of scope and a
      // consistency check should occur.
      if ((successor.getType() == BlockType.SPECIAL_BLOCK /* special blocks are exit blocks */
              || obligationGoesOutOfScopeBeforeSuccessor)
          && !isLoopBodyAnalysis) {
        // this checks whether we exit and if yes, whether MustCall and CalledMethods are
        // consistent.

        // If successor is an exceptional successor, and Obligation represents the
        // temporary variable for currentBlock's node, do not propagate or do a
        // consistency check, as in the exceptional case the "assignment" to the
        // temporary variable does not succeed.
        //
        // Note that this test cannot be "successor.getType() ==
        // BlockType.EXCEPTIONAL_BLOCK", because not every exceptional successor is an
        // exceptional block. For example, the successor might be a regular block
        // (containing a catch clause, for example), or a special block indicating an
        // exceptional exit. Nor can this test be "currentBlock.getType() ==
        // BlockType.EXCEPTIONAL_BLOCK", because some exception types are ignored.
        // Whether exceptionType is null captures the logic of both of these cases.
        if (exceptionType != null) {
          Node exceptionalNode = NodeUtils.removeCasts(((ExceptionBlock) currentBlock).getNode());
          LocalVariableNode tmpVarForExcNode = cmAtf.getTempVarForNode(exceptionalNode);
          if (tmpVarForExcNode != null
              && obligation.resourceAliases.size() == 1
              && obligation.canBeSatisfiedThrough(tmpVarForExcNode)) {
            continue;
          }
        }

        // At this point, a consistency check will definitely occur, unless the
        // obligation was derived from a MustCallAlias parameter. If it was, an error is
        // immediately issued, because such a parameter should not go out of scope
        // without its obligation being resolved some other way.
        if (obligation.derivedFromMustCallAlias()) {
          // MustCallAlias annotations only have meaning if the method returns
          // normally, so issue an error if and only if this exit is happening on a
          // normal exit path.
          if (exceptionType == null
              && obligation.whenToEnforce.contains(MethodExitKind.NORMAL_RETURN)) {
            checker.reportError(
                obligation.resourceAliases.asList().get(0).tree,
                "mustcallalias.out.of.scope",
                exitReasonForErrorMessage);
          }
          // Whether or not an error is issued, the check is now complete - there is
          // no further checking to do on a must-call-alias-derived obligation along
          // an exceptional path.
          continue;
        }

        // Which stores from the called-methods and must-call checkers are used in the
        // consistency check varies depending on the context.  Generally speaking, we would
        // like to use the store propagated along the CFG edge from currentBlock to
        // successor.  But, there are special cases to consider.  The rules are:
        // 1. if the current block has no nodes, it is either a ConditionalBlock or a
        //    SpecialBlock.
        //    For the called-methods store, we obtain the exact CFG edge store that we need
        //    (see getStoreForEdgeFromEmptyBlock()).  For the must-call store, due to API
        //    limitations, we use the following heuristics:
        //    1a. if there is information about any alias in the resource alias set
        //        in the successor store, use the successor's MC store, which
        //        contains whatever information is true after this block finishes.
        //    1b. if there is not any information about any alias in the resource alias
        //        set in the successor store, use the current block's MC store,
        //        which contain whatever information is true before this (empty) block.
        // 2. if the current block has one or more nodes, always use the CM store after
        //    the last node. To decide which MC store to use:
        //    2a. if the last node in the block is the invocation of an
        //        @CreatesMustCallFor method that might throw an exception, and the
        //        consistency check is for an exceptional path, use the MC store
        //        immediately before the method invocation, because the method threw an
        //        exception rather than finishing and therefore did not actually create
        //        any must-call obligation, so the MC store after might contain
        //        must-call obligations that do not need to be fulfilled along this
        //        path.
        //    2b. in all other cases, use the MC store from after the last node in
        //        the block.
        CFStore mcStore;
        AccumulationStore cmStore;
        CFStore mcoeStoreCurrent =
            mcoeTypeFactory == null
                ? null
                : mcoeTypeFactory.getStoreForBlock(
                    obligationGoesOutOfScopeBeforeSuccessor,
                    currentBlock, // 1a. (MC)
                    successor); // 1b. (MC)
        CFStore cmoeStoreCurrent =
            cmoeTypeFactory == null
                ? null
                : cmoeTypeFactory.getStoreForBlock(
                    obligationGoesOutOfScopeBeforeSuccessor,
                    currentBlock, // 1a. (MC)
                    successor); // 1b. (MC)
        if (currentBlockNodes.size() == 0 /* currentBlock is special or conditional */) {
          cmStore = getStoreForEdgeFromEmptyBlock(currentBlock, successor); // 1. (CM)
          // For the Must Call Checker, we currently apply a less precise handling and do
          // not get the store for the specific CFG edge from currentBlock to successor.
          // We do not believe this will impact precision except in convoluted and
          // uncommon cases.  If we find that we need more precision, we can revisit this,
          // but it will require additional API support in the AnalysisResult type to get
          // the information that we need.
          mcStore =
              mcAtf.hasFlowResult()
                  ? mcAtf.getStoreForBlock(
                      obligationGoesOutOfScopeBeforeSuccessor,
                      currentBlock, // 1a. (MC)
                      successor) // 1b. (MC)
                  : null;
        } else {
          // In this case, current block has at least one node.
          // Use the called-methods store immediately after the last node in
          // currentBlock.
          Node last = currentBlockNodes.get(currentBlockNodes.size() - 1); // 2. (CM)

          if (cmStoreAfter.containsKey(last)) {
            cmStore = cmStoreAfter.get(last);
          } else {
            cmStore = cmAtf.getStoreAfter(last);
            cmStoreAfter.put(last, cmStore);
          }
          // If this is an exceptional block, check the MC store beforehand to avoid
          // issuing an error about a call to a CreatesMustCallFor method that might
          // throw an exception. Otherwise, use the store after.
          if (exceptionType != null && isInvocationOfCreatesMustCallForMethod(last)) {
            mcStore = mcAtf.getStoreBefore(last); // 2a. (MC)
          } else {
            if (mcStoreAfter.containsKey(last)) {
              mcStore = mcStoreAfter.get(last);
            } else {
              mcStore = mcAtf.getStoreAfter(last); // 2b. (MC)
              mcStoreAfter.put(last, mcStore);
            }
          }
        }

        MethodExitKind exitKind =
            exceptionType == null ? MethodExitKind.NORMAL_RETURN : MethodExitKind.EXCEPTIONAL_EXIT;
        if (obligation.whenToEnforce.contains(exitKind) && !isLoopBodyAnalysis) {
          if (obligation instanceof CollectionObligation) {
            checkMustCallOnElements(
                obligation,
                mcoeStoreCurrent,
                cmoeStoreCurrent,
                true,
                true,
                null,
                exitReasonForErrorMessage);
          } else {
            checkMustCall(obligation, cmStore, mcStore, exitReasonForErrorMessage, true);
          }
        }
      } else {
        // In this case, there is info in the successor store about some alias in the
        // Obligation.
        // Handles the possibility that some resource in the Obligation may go out of
        // scope.
        //
        Set<ResourceAlias> copyOfResourceAliases = new LinkedHashSet<>(obligation.resourceAliases);
        copyOfResourceAliases.removeIf(
            alias ->
                !aliasInScopeInSuccessor(
                        regularStoreOfSuccessor, mcoeStore, mcCFStore, alias, obligation)
                    && !isLoopBodyAnalysis);
        successorObligations.add(
            obligation.getReplacement(copyOfResourceAliases, obligation.whenToEnforce));
      }
    }

    propagate(new BlockWithObligations(successor, successorObligations), visited, worklist);
  }

  /**
   * Gets the store propagated by the {@link RLCCalledMethodsAnalysis} (containing called methods
   * information) along a particular CFG edge during local type inference. The source {@link Block}
   * of the edge must contain no {@link Node}s.
   *
   * @param currentBlock source block of the CFG edge. Must contain no {@link Node}s.
   * @param successor target block of the CFG edge.
   * @return store propagated by the {@link RLCCalledMethodsAnalysis} along the CFG edge.
   */
  private AccumulationStore getStoreForEdgeFromEmptyBlock(Block currentBlock, Block successor) {
    switch (currentBlock.getType()) {
      case CONDITIONAL_BLOCK:
        ConditionalBlock condBlock = (ConditionalBlock) currentBlock;
        if (condBlock.getThenSuccessor().equals(successor)) {
          return cmAtf.getInput(currentBlock).getThenStore();
        } else if (condBlock.getElseSuccessor().equals(successor)) {
          return cmAtf.getInput(currentBlock).getElseStore();
        } else {
          throw new BugInCF("successor not found");
        }
      case SPECIAL_BLOCK:
        return cmAtf.getInput(successor).getRegularStore();
      default:
        throw new BugInCF("unexpected block type " + currentBlock.getType());
    }
  }

  /**
   * Returns true if {@code alias.reference} is definitely in-scope in the successor store: that is,
   * there is a value for it in a successor store.
   *
   * @param successorStore the regular store of the successor block
   * @param mcoeStore the mcoeStore of the successor block
   * @param mcStore the mcStore of the successor block
   * @param alias the resource alias to check
   * @return true if the variable is definitely in scope for the purposes of the consistency
   *     checking algorithm in the successor block from which the store came
   */
  private boolean aliasInScopeInSuccessor(
      AccumulationStore successorStore,
      CFStore mcoeStore,
      CFStore mcStore,
      ResourceAlias alias,
      Obligation o) {
    if (alias.element.getKind() == ElementKind.FIELD && o instanceof IteratorObligation) {
      return true;
    }
    return (successorStore.getValue(alias.reference) != null)
        || (mcoeStore != null && mcoeStore.getValue(alias.reference) != null)
        || (mcStore != null && mcStore.getValue(alias.reference) != null);
  }

  /**
   * Returns true if node is a MethodInvocationNode of a method with a CreatesMustCallFor
   * annotation.
   *
   * @param node a node
   * @return true if node is a MethodInvocationNode of a method with a CreatesMustCallFor annotation
   */
  private boolean isInvocationOfCreatesMustCallForMethod(Node node) {
    if (!(node instanceof MethodInvocationNode)) {
      return false;
    }
    MethodInvocationNode miNode = (MethodInvocationNode) node;
    return cmAtf.hasCreatesMustCallFor(miNode);
  }

  /**
   * Finds {@link Owning} formal parameters for the method corresponding to a CFG and iterator
   * fields and parameters whose type variable has non-empty must call type.
   *
   * @param cfg the CFG
   * @return the owning formal parameters of the method that corresponds to the given cfg, iterator
   *     fields and parameters with potential must call obligaitons, or an empty set if the given
   *     CFG doesn't correspond to a method body
   */
  private Set<Obligation> initialTrackedObligations(ControlFlowGraph cfg) {
    // TODO what about lambdas?
    if (cfg.getUnderlyingAST().getKind() == Kind.METHOD) {
      MethodTree method = ((UnderlyingAST.CFGMethod) cfg.getUnderlyingAST()).getMethod();
      Set<Obligation> result = new LinkedHashSet<>(1);
      for (VariableTree param : method.getParameters()) {
        VariableElement paramElement = TreeUtils.elementFromDeclaration(param);
        boolean hasMustCallAlias = cmAtf.hasMustCallAlias(paramElement);
        if (hasMustCallAlias
            || (cmAtf.declaredTypeHasMustCall(param)
                && !noLightweightOwnership
                && (paramElement.getAnnotation(Owning.class) != null))) {
          result.add(
              new Obligation(
                  ImmutableSet.of(
                      new ResourceAlias(
                          new LocalVariable(paramElement), paramElement, param, hasMustCallAlias)),
                  Collections.singleton(MethodExitKind.NORMAL_RETURN)));
          // Increment numMustCall for each @Owning parameter tracked by the enclosing
          // method.
          incrementNumMustCall(paramElement);
        }
        if (paramElement.getAnnotation(OwningCollection.class) != null) {
          result.add(
              new CollectionObligation(
                  ImmutableSet.of(
                      new ResourceAlias(
                          JavaExpression.fromVariableTree(param), paramElement, param)),
                  Collections.singleton(MethodExitKind.NORMAL_RETURN)));
        }
        boolean paramIsIterator = RLCUtils.isIterator(paramElement, cmAtf);
        if (paramIsIterator) {
          if (shouldTrackIterator(paramElement.asType())) {
            System.out.println("Adding iterator for param: " + param);
            result.add(new IteratorObligation(Obligation.fromTree(param)));
          }
        }
      }

      TreePath path = cmAtf.getPath(method);
      ClassTree enclosingClass = TreePathUtil.enclosingClass(path);
      for (Tree member : enclosingClass.getMembers()) {
        if (member instanceof VariableTree) {
          VariableTree declaration = (VariableTree) member;
          Element memberElm = TreeUtils.elementFromDeclaration(declaration);
          boolean isIterator = RLCUtils.isIterator(declaration, cmoeTypeFactory);
          boolean isField = memberElm != null && memberElm.getKind().isField();

          if (isField && isIterator) {
            if (shouldTrackIterator(memberElm.asType())) {
              System.out.println("Adding iterator for field: " + declaration);
              result.add(new IteratorObligation(Obligation.fromTree(declaration)));
            }
          }
        }
      }
      return result;
    }
    return Collections.emptySet();
  }

  /**
   * Returns whether the given iterator type should be tracked by an {@link IteratorObligation}. If
   * the type variable upper bound (either the type variable itself if it is concrete or the upper
   * bound if its a wildcard or generic) has MustCall values, the method returns true.
   *
   * <p>If the type variable has no upper bound, for instance if it is a wildcard with no extends
   * clause, the method returns false. The type variable upper bound rules ensure that such an
   * Iterator can never hold values with MustCall obligations.
   *
   * @param iteratorType the declared type of the iterator
   * @return whether the iterator should be tracked by an {@link IteratorObligation}
   * @throws BugInCF if the iterator is not a DeclaredType or does not have exactly one type
   *     argument.
   */
  private boolean shouldTrackIterator(TypeMirror iteratorType) {
    if (!(iteratorType instanceof DeclaredType)) {
      throw new BugInCF(
          "Type of iterator expecteced to be DeclaredType, but is "
              + iteratorType.getClass().getSimpleName());
    }
    List<? extends TypeMirror> typeArgs = ((DeclaredType) iteratorType).getTypeArguments();
    if (typeArgs.size() != 1) {
      throw new BugInCF(
          "Iterator expecteced to have only one type argument, but has "
              + typeArgs.size()
              + " "
              + typeArgs);
    }
    MustCallAnnotatedTypeFactory mcAtf = cmAtf.getMustCallAnnotatedTypeFactory();
    TypeMirror typeArg = typeArgs.get(0);
    List<String> mcValuesOfTypeArgUpperBound = RLCUtils.getMcValues(typeArg, mcAtf);
    if (mcValuesOfTypeArgUpperBound == null) {
      // wildcard or generic with Object or non-existing upper bound. Tracking makes no sense,
      // since the elements statically have no obligation anyways.
      return false;
    } else {
      return mcValuesOfTypeArgUpperBound.size() > 0;
    }
  }

  /**
   * Checks whether there is some resource alias set <em>R</em> in {@code obligations} such that
   * <em>R</em> contains a {@link ResourceAlias} whose local variable is {@code node}.
   *
   * @param obligations the set of Obligations to search
   * @param var the local variable to look for
   * @return true iff there is a resource alias set in {@code obligations} that contains node
   */
  private static boolean varTrackedInObligations(
      Set<Obligation> obligations, LocalVariableNode var) {
    for (Obligation obligation : obligations) {
      if (obligation.canBeSatisfiedThrough(var)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Gets the Obligation whose resource alias set contains the given local variable, if one exists
   * in {@code obligations}.
   *
   * @param obligations a set of Obligations
   * @param node variable of interest
   * @return the Obligation in {@code obligations} whose resource alias set contains {@code node},
   *     or {@code null} if there is no such Obligation
   */
  /*package-private*/ static @Nullable Obligation getObligationForVar(
      Set<Obligation> obligations, LocalVariableNode node) {
    for (Obligation obligation : obligations) {
      if (obligation.canBeSatisfiedThrough(node)) {
        return obligation;
      }
    }
    return null;
  }

  /**
   * Gets the Obligation whose resource alias set contains the given tree, if one exists in {@code
   * obligations}.
   *
   * @param obligations a set of Obligations
   * @param tree variable tree of interest
   * @return the Obligation in {@code obligations} whose resource alias set contains {@code tree},
   *     or {@code null} if there is no such Obligation
   */
  /*package-private*/ static @Nullable Obligation getObligationForVar(
      Set<Obligation> obligations, Tree tree) {
    for (Obligation obligation : obligations) {
      if (obligation.canBeSatisfiedThrough(tree)) {
        return obligation;
      }
    }
    return null;
  }

  /**
   * For the given Obligation, computes the union of {@code @MustCallOnElements} values and
   * {@code @CalledMethodsOnElements} values over all aliases of the obligation. If the union over
   * the {@code CalledMethodsOnElements} values is not a superset, an error is issued. The error
   * issued depends on whether the check occurs at an exit or at a reassignment. This information is
   * passed with the {@code @isExit} flag. Returns true if the check is successful, i.e. either the
   * obligation is not a {@code @CollectionObligation} anyways or the {@code MustCallOnElements}
   * values are a subset of the {@code @CalledMethodsOnElements} values.
   *
   * <p>This is now overkill, since we don't allow aliasing of {@code @OwningCollection} anymore.
   * However, it it sound and doesn't throw additional errors if code does end up aliasing.
   *
   * @param obligation the Obligation
   * @param mcoeStore the mustCallOnElements store
   * @param cmoeStore the calledMethodsOnElements store
   * @param occurence tree where the check is called from (for logging purposes)
   * @param isExit whether the check occurs for an exit
   * @param reportErrors whether to report errors
   * @param exitReasonForErrorMessage if the {@code @MustCallOnElements} obligation is not
   *     satisfied, a useful explanation to include in the error message
   * @return true if the check is successful, i.e. either the obligation is not a
   *     {@code @CollectionObligation} anyways or the {@code MustCallOnElements} values are a subset
   *     of the {@code @CalledMethodsOnElements} values.
   */
  private boolean checkMustCallOnElements(
      Obligation obligation,
      CFStore mcoeStore,
      CFStore cmoeStore,
      boolean isExit,
      boolean reportErrors,
      Tree occurence,
      String exitReasonForErrorMessage) {
    if (!(obligation instanceof CollectionObligation)) {
      return true;
    }
    if (mcoeStore == null || cmoeStore == null) {
      // this occurs if there is no mcoe/cmoe store, i.e. when called after the CalledMethods
      // checker,
      // but before the cmoe/mcoe checker (see analyzeObligationFulfillingLoop)
      return true;
    }
    Set<String> mcoeValues = new HashSet<>();
    Set<String> cmoeValues = new HashSet<>();
    for (ResourceAlias alias : obligation.resourceAliases) {
      List<String> mcoeValuesOfAlias; // = new ArrayList<String>();
      // try {
      mcoeValuesOfAlias =
          mcoeTypeFactory.getMustCallOnElementsObligations(
              mcoeStore, alias.reference, alias.element);
      // } catch (BugInCF e) {
      //   // no mcoe annotation for the checked element

      // }
      boolean isOwningCollection =
          alias.element != null && cmAtf.hasOwningCollection(alias.element);
      boolean hasRevokedOwnership = mcoeValuesOfAlias == null && isOwningCollection;
      boolean isReadOnlyAlias = mcoeValuesOfAlias == null && !isOwningCollection;
      if (hasRevokedOwnership) {
        if (isExit) {
          // since the obligation is revoked in the case of an exit, this has to be a manual
          // mcoeUnknown
          // annotation to mask obligations. report an error for unfulfilled obligations.
          if (reportErrors) {
            checker.reportError(
                alias.tree,
                "unfulfilled.mustcallonelements.obligations",
                "unknown",
                alias.tree.toString(),
                exitReasonForErrorMessage);
          }
          return false;
        } else {
          // this is not an exit, but verification of modifiying operation.
          // Cannot remove access to collection elements with revoked ownership.
          if (reportErrors) {
            checker.reportError(
                alias.tree, "modification.without.ownership", alias.tree.toString());
          }
          return false;
        }
      }
      // mcoeValuesOfAlias is null for read-only aliases
      if (!isReadOnlyAlias) {
        mcoeValues.addAll(mcoeValuesOfAlias);
        cmoeValues.addAll(
            cmoeTypeFactory.getCalledMethodsOnElements(cmoeStore, alias.reference, alias.tree));
      }
    }
    ResourceAlias firstAlias = obligation.resourceAliases.iterator().next();
    if (isExit) {
      // System.out.println(
      //     "verifying exit "
      //         + obligation.hashCode()
      //         + ": "
      //         + obligation
      //         + " "
      //         + mcoeValues
      //         + "\n        -> "
      //         + cmoeValues);
    } else {
      // System.out.println(
      //     "verifying modification "
      //         + obligation.hashCode()
      //         + ": "
      //         + obligation
      //         + " "
      //         + mcoeValues
      //         + "\n        -> "
      //         + cmoeValues);
    }
    mcoeValues.removeAll(cmoeValues);
    if (!mcoeValues.isEmpty()) {
      if (isExit) {
        if (reportErrors) {
          checker.reportError(
              firstAlias.tree,
              "unfulfilled.mustcallonelements.obligations",
              formatMissingMustCallMethods(new ArrayList<>(mcoeValues)),
              firstAlias.tree,
              exitReasonForErrorMessage);
        }
        return false;
      } else {
        if (reportErrors) {
          checker.reportError(
              occurence,
              "unsafe.owningcollection.modification",
              formatMissingMustCallMethods(new ArrayList<>(mcoeValues)));
        }
        return false;
      }
    }
    return true;
  }

  /**
   * For the given Obligation, checks that at least one of its variables has its {@code @MustCall}
   * obligation satisfied, based on {@code @CalledMethods} and {@code @MustCall} types in the given
   * stores.
   *
   * @param obligation the Obligation
   * @param cmStore the called-methods store
   * @param mcStore the must-call store
   * @param outOfScopeReason if the {@code @MustCall} obligation is not satisfied, a useful
   *     explanation to include in the error message
   * @param reportErrors whether an error should be reported
   * @return the list of unfulfilled must-call obligations
   */
  private List<String> checkMustCall(
      Obligation obligation,
      AccumulationStore cmStore,
      CFStore mcStore,
      String outOfScopeReason,
      boolean reportErrors) {

    if (obligation instanceof IteratorNextObligation) {
      // cache the cmStore and mcStore for the obligation corresponding to the value returned by
      // an Iterator.next() in case it went out of scope before a call to Iterator.remove(),
      // where we have to check whether the MustCall values of the preceding
      // Iterator.next() call have been fulfilled.
      // The return value is ignored.
      IteratorNextObligation iterNextOb = (IteratorNextObligation) obligation;
      if (!iterNextOb.checkObligation) {
        ((IteratorNextObligation) obligation).leaveScope(cmStore, mcStore);
        return Collections.emptyList();
      }
    }

    Map<ResourceAlias, List<String>> mustCallValues = obligation.getMustCallMethods(cmAtf, mcStore);

    // Optimization: if mustCallValues is null, always issue a warning (there is no way to
    // satisfy the check). A null mustCallValue occurs when the type is top
    // (@MustCallUnknown).
    if (mustCallValues == null) {
      // Report the error at the first alias' definition. This choice is arbitrary but
      // consistent.
      ResourceAlias firstAlias = obligation.resourceAliases.iterator().next();
      if (!reportedErrorAliases.contains(firstAlias)) {
        if (!checker.shouldSkipUses(TreeUtils.elementFromTree(firstAlias.tree))) {
          reportedErrorAliases.add(firstAlias);
          checker.reportError(
              firstAlias.tree,
              "required.method.not.known",
              firstAlias.stringForErrorMessage(),
              firstAlias.reference.getType().toString(),
              outOfScopeReason);
        }
      }
      return null;
    }
    if (mustCallValues.isEmpty()) {
      throw new TypeSystemError("unexpected empty must-call values for obligation " + obligation);
    }

    boolean mustCallSatisfied = false;
    for (ResourceAlias alias : obligation.resourceAliases) {

      List<String> mustCallValuesForAlias = mustCallValues.get(alias);
      // optimization when there are no methods to call
      if (mustCallValuesForAlias.isEmpty()) {
        mustCallSatisfied = true;
        break;
      }

      // sometimes the store is null!  this looks like a bug in checker dataflow.
      // TODO track down and report the root-cause bug
      AccumulationValue cmValue = cmStore != null ? cmStore.getValue(alias.reference) : null;
      AnnotationMirror cmAnno = null;

      if (cmValue != null) { // When store contains the lhs
        Set<String> accumulatedValues = cmValue.getAccumulatedValues();
        if (accumulatedValues != null) { // type variable or wildcard type
          cmAnno = cmAtf.createCalledMethods(accumulatedValues.toArray(new String[0]));
        } else {
          for (AnnotationMirror anno : cmValue.getAnnotations()) {
            if (AnnotationUtils.areSameByName(
                    anno, "org.checkerframework.checker.calledmethods.qual.CalledMethods")
                || AnnotationUtils.areSameByName(
                    anno, "org.checkerframework.checker.calledmethods.qual.CalledMethodsBottom")) {
              cmAnno = anno;
            }
          }
        }
      }
      if (cmAnno == null) {
        cmAnno = cmAtf.getAnnotatedType(alias.element).getEffectiveAnnotationInHierarchy(cmAtf.top);
      }

      if (calledMethodsSatisfyMustCall(mustCallValuesForAlias, cmAnno)) {
        mustCallSatisfied = true;
        break;
      }
    }

    if (!mustCallSatisfied) {
      // Report the error at the first alias' definition. This choice is arbitrary but
      // consistent.
      ResourceAlias firstAlias = obligation.resourceAliases.iterator().next();
      if (reportErrors) {
        if (!reportedErrorAliases.contains(firstAlias)) {
          if (!checker.shouldSkipUses(TreeUtils.elementFromTree(firstAlias.tree))) {
            reportedErrorAliases.add(firstAlias);
            checker.reportError(
                firstAlias.tree,
                "required.method.not.called",
                formatMissingMustCallMethods(mustCallValues.get(firstAlias)),
                firstAlias.stringForErrorMessage(),
                firstAlias.reference.getType().toString(),
                outOfScopeReason);
          }
        }
      }
      return mustCallValues.get(firstAlias);
    }
    return Collections.emptyList();
  }

  /**
   * Increment the -AcountMustCall counter.
   *
   * @param node the node being counted, to extract the type
   */
  private void incrementNumMustCall(Node node) {
    if (countMustCall) {
      TypeMirror type = node.getType();
      incrementMustCallImpl(type);
    }
  }

  /**
   * Increment the -AcountMustCall counter.
   *
   * @param elt the elt being counted, to extract the type
   */
  private void incrementNumMustCall(Element elt) {
    if (countMustCall) {
      TypeMirror type = elt.asType();
      incrementMustCallImpl(type);
    }
  }

  /**
   * Shared implementation for the two version of countMustCall. Don't call this directly.
   *
   * @param type the type of the object that has a must-call obligation
   */
  private void incrementMustCallImpl(TypeMirror type) {
    // only count uses of JDK classes, since that's what the paper reported
    if (!isJdkClass(TypesUtils.getTypeElement(type).getQualifiedName().toString())) {
      return;
    }
    checker.numMustCall++;
  }

  /**
   * Is the given class a java* class? This is a heuristic for whether the class was defined in the
   * JDK.
   *
   * @param qualifiedName a fully qualified name of a class
   * @return true iff the type's fully-qualified name starts with "java", indicating that it is from
   *     a java.* or javax.* package (probably)
   */
  /*package-private*/ static boolean isJdkClass(String qualifiedName) {
    return qualifiedName.startsWith("java");
  }

  /**
   * Do the called methods represented by the {@link CalledMethods} type {@code cmAnno} include all
   * the methods in {@code mustCallValues}?
   *
   * @param mustCallValues the strings representing the must-call obligations
   * @param cmAnno an annotation from the called-methods type hierarchy
   * @return true iff cmAnno is a subtype of a called-methods annotation with the same values as
   *     mustCallValues
   */
  /*package-private*/ boolean calledMethodsSatisfyMustCall(
      List<String> mustCallValues, AnnotationMirror cmAnno) {
    // Create this annotation and use a subtype test because there's no guarantee that
    // cmAnno is actually an instance of CalledMethods: it could be CMBottom or CMPredicate.
    AnnotationMirror cmAnnoForMustCallMethods =
        cmAtf.createCalledMethods(mustCallValues.toArray(new String[0]));
    return cmAtf.getQualifierHierarchy().isSubtypeQualifiersOnly(cmAnno, cmAnnoForMustCallMethods);
  }

  /**
   * If the input {@code state} has not been visited yet, add it to {@code visited} and {@code
   * worklist}.
   *
   * @param state the current state
   * @param visited the states that have been analyzed or are already on the worklist
   * @param worklist the states that will be analyzed
   */
  private static void propagate(
      BlockWithObligations state,
      Set<BlockWithObligations> visited,
      Deque<BlockWithObligations> worklist) {

    if (visited.add(state)) {
      worklist.add(state);
    }
  }

  /**
   * Formats a list of must-call method names to be printed in an error message.
   *
   * @param mustCallVal the list of must-call strings
   * @return a formatted string
   */
  public static String formatMissingMustCallMethods(List<String> mustCallVal) {
    int size = mustCallVal.size();
    if (size == 0) {
      return "None";
    } else if (size == 1) {
      return "method " + mustCallVal.get(0);
    } else {
      return "methods " + String.join(", ", mustCallVal);
    }
  }

  /**
   * A pair of a {@link Block} and a set of dataflow facts on entry to the block. Each dataflow fact
   * represents a set of resource aliases for some tracked resource. The analyzer's worklist
   * consists of BlockWithObligations objects, each representing the need to handle the set of
   * dataflow facts reaching the block during analysis.
   */
  /*package-private*/ static class BlockWithObligations {

    /** The block. */
    public final Block block;

    /** The dataflow facts. */
    public final ImmutableSet<Obligation> obligations;

    /**
     * Create a new BlockWithObligations from a block and a set of dataflow facts.
     *
     * @param b the block
     * @param obligations the set of incoming Obligations at the start of the block (may be the
     *     empty set)
     */
    public BlockWithObligations(Block b, Set<Obligation> obligations) {
      this.block = b;
      this.obligations = ImmutableSet.copyOf(obligations);
    }

    @Override
    public boolean equals(@Nullable Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      BlockWithObligations that = (BlockWithObligations) o;
      return block.equals(that.block) && obligations.equals(that.obligations);
    }

    @Override
    public int hashCode() {
      return Objects.hash(block, obligations);
    }

    @Override
    public String toString() {
      return String.format(
          "BWO{%s %d, %d obligations %d}",
          block.getType(), block.getUid(), obligations.size(), obligations.hashCode());
    }

    /**
     * Returns a printed representation of a collection of BlockWithObligations. If a
     * BlockWithObligations appears multiple times in the collection, it is printed more succinctly
     * after the first time.
     *
     * @param bwos a collection of BlockWithObligations, to format
     * @return a printed representation of a collection of BlockWithObligations
     */
    public static String collectionToString(Collection<BlockWithObligations> bwos) {
      List<Block> blocksWithDuplicates = new ArrayList<>();
      for (BlockWithObligations bwo : bwos) {
        blocksWithDuplicates.add(bwo.block);
      }
      Collection<Block> duplicateBlocks = CollectionsPlume.duplicates(blocksWithDuplicates);
      StringJoiner result = new StringJoiner(", ", "BWOs[", "]");
      for (BlockWithObligations bwo : bwos) {
        ImmutableSet<Obligation> obligations = bwo.obligations;
        if (duplicateBlocks.contains(bwo.block)) {
          result.add(
              String.format(
                  "BWO{%s %d, %d obligations %s}",
                  bwo.block.getType(), bwo.block.getUid(), obligations.size(), obligations));
        } else {
          result.add(
              String.format(
                  "BWO{%s %d, %d obligations}",
                  bwo.block.getType(), bwo.block.getUid(), obligations.size()));
        }
      }
      return result.toString();
    }
  }
}
