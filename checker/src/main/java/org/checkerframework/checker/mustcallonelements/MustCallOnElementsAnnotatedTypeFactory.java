package org.checkerframework.checker.mustcallonelements;

import com.sun.source.tree.ArrayAccessTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.TypeMirror;
import org.checkerframework.checker.calledmethods.CalledMethodsAnnotatedTypeFactory.McoeObligationAlteringLoop;
import org.checkerframework.checker.calledmethods.CalledMethodsAnnotatedTypeFactory.PotentiallyAssigningLoop;
import org.checkerframework.checker.calledmethods.CalledMethodsAnnotatedTypeFactory.PotentiallyFulfillingLoop;
import org.checkerframework.checker.mustcall.MustCallAnnotatedTypeFactory;
import org.checkerframework.checker.mustcall.qual.InheritableMustCall;
import org.checkerframework.checker.mustcall.qual.MustCall;
import org.checkerframework.checker.mustcallonelements.qual.MustCallOnElements;
import org.checkerframework.checker.mustcallonelements.qual.MustCallOnElementsUnknown;
import org.checkerframework.checker.mustcallonelements.qual.OwningArray;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.checker.resourceleak.ResourceLeakChecker;
import org.checkerframework.common.basetype.BaseAnnotatedTypeFactory;
import org.checkerframework.common.basetype.BaseTypeChecker;
import org.checkerframework.dataflow.cfg.block.Block;
import org.checkerframework.dataflow.cfg.node.LocalVariableNode;
import org.checkerframework.dataflow.cfg.node.Node;
import org.checkerframework.dataflow.expression.JavaExpression;
import org.checkerframework.framework.flow.CFStore;
import org.checkerframework.framework.flow.CFValue;
import org.checkerframework.framework.type.AnnotatedTypeFactory;
import org.checkerframework.framework.type.AnnotatedTypeMirror;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedExecutableType;
import org.checkerframework.framework.type.GenericAnnotatedTypeFactory;
import org.checkerframework.framework.type.QualifierHierarchy;
import org.checkerframework.framework.type.SubtypeIsSubsetQualifierHierarchy;
import org.checkerframework.framework.type.treeannotator.ListTreeAnnotator;
import org.checkerframework.framework.type.treeannotator.TreeAnnotator;
import org.checkerframework.framework.type.typeannotator.ListTypeAnnotator;
import org.checkerframework.framework.type.typeannotator.TypeAnnotator;
import org.checkerframework.javacutil.AnnotationBuilder;
import org.checkerframework.javacutil.AnnotationUtils;
import org.checkerframework.javacutil.BugInCF;
import org.checkerframework.javacutil.ElementUtils;
import org.checkerframework.javacutil.TreeUtils;
import org.checkerframework.javacutil.TypesUtils;
import org.plumelib.util.CollectionsPlume;

/**
 * The annotated type factory for the Must Call Checker. Primarily responsible for the subtyping
 * rules between @MustCallOnElements annotations. Additionally holds some static datastructures used
 * for pattern-matching loops that create/fulfill MustCallOnElements obligations. These are in the
 * MustCall checker, since it runs before the MustCallOnElements checker and the pattern- match must
 * be finished by the time the MustCallOnElements checker runs.
 */
public class MustCallOnElementsAnnotatedTypeFactory extends BaseAnnotatedTypeFactory {

  /** The {@code @}{@link MustCallOnElementsUnknown} annotation. */
  public final AnnotationMirror TOP;

  /** The {@code @}{@link MustCallOnElements} annotation. It is the default in unannotated code. */
  public final AnnotationMirror BOTTOM;

  /**
   * Map from trees representing expressions to the temporary variables that represent them in the
   * store.
   *
   * <p>Consider the following code, adapted from Apache Zookeeper:
   *
   * <pre>
   *   sock = SocketChannel.open();
   *   sock.socket().setSoLinger(false, -1);
   * </pre>
   *
   * This code is safe from resource leaks: sock is an unconnected socket and therefore has no
   * must-call obligation. The expression sock.socket() similarly has no must-call obligation
   * because it is a resource alias, but without a temporary variable that represents that
   * expression in the store, the resource leak checker wouldn't be able to determine that.
   *
   * <p>These temporary variables are only created once---here---but are used by all three parts of
   * the resource leak checker by calling {@link #getTempVar(Node)}. The temporary variables are
   * shared in the same way that subcheckers share CFG structure; see {@link
   * #getSharedCFGForTree(Tree)}.
   */
  /*package-private*/ final IdentityHashMap<Tree, LocalVariableNode> tempVars =
      new IdentityHashMap<>(100);

  /** The MustCallOnElements.value field/element. */
  private final ExecutableElement mustCallOnElementsValueElement =
      TreeUtils.getMethod(MustCallOnElements.class, "value", 0, processingEnv);

  /**
   * Maps the AST-tree corresponding to the loop condition of an Mcoe-Obligation-altering loop to
   * the loop wrapper class.
   */
  private static Map<Tree, McoeObligationAlteringLoop> conditionToLoopMap = new HashMap<>();

  /**
   * Maps the AST-tree corresponding to an assignment within an Mcoe-Obligation-creating loop to the
   * loop wrapper class.
   */
  private static Map<AssignmentTree, PotentiallyAssigningLoop> assignmentToLoopMap =
      new HashMap<>();

  /**
   * Fetches the store from the results of dataflow for {@code first}. If {@code afterFirstStore} is
   * true, then the store after {@code first} is returned; if {@code afterFirstStore} is false, the
   * store before {@code succ} is returned.
   *
   * @param afterFirstStore whether to use the store after the first block or the store before its
   *     successor, succ
   * @param first a block
   * @param succ first's successor
   * @return the appropriate CFStore, populated with MustCall annotations, from the results of
   *     running dataflow
   */
  public CFStore getStoreForBlock(boolean afterFirstStore, Block first, Block succ) {
    return afterFirstStore ? flowResult.getStoreAfter(first) : flowResult.getStoreBefore(succ);
  }

  /**
   * Returns the store immediately before the specified tree.
   *
   * @param tree an AST node
   * @return the mcoe store immediately before the tree
   */
  public CFStore getStoreForTree(Tree tree) {
    return flowResult.getStoreBefore(tree);
  }

  /**
   * Returns the store immediately after the specified tree.
   *
   * @param tree an AST node
   * @return the mcoe store immediately after the tree
   */
  public CFStore getStoreAfterTree(Tree tree) {
    return flowResult.getStoreAfter(tree);
  }

  /** True if -AnoLightweightOwnership was passed on the command line. */
  // private final boolean noLightweightOwnership;

  /**
   * True if -AenableWpiForRlc (see {@link ResourceLeakChecker#ENABLE_WPI_FOR_RLC}) was passed on
   * the command line.
   */
  private final boolean enableWpiForRlc;

  /**
   * Creates a MustCallOnElementsAnnotatedTypeFactory.
   *
   * @param checker the checker associated with this type factory
   */
  public MustCallOnElementsAnnotatedTypeFactory(BaseTypeChecker checker) {
    super(checker);
    TOP = AnnotationBuilder.fromClass(elements, MustCallOnElementsUnknown.class);
    BOTTOM = createMustCallOnElements(Collections.emptyList());
    // noLightweightOwnership =
    // checker.hasOption(MustCallOnElementsChecker.NO_LIGHTWEIGHT_OWNERSHIP);
    enableWpiForRlc = checker.hasOption(ResourceLeakChecker.ENABLE_WPI_FOR_RLC);
    if (checker instanceof MustCallOnElementsChecker) {
      this.postInit();
    }
  }

  /**
   * Cache of the MustCallOnElements annotations that have actually been created. Most programs
   * require few distinct MustCallOnElements annotations (e.g. MustCallOnElements() and
   * MustCallOnElements("close")).
   */
  private final Map<List<String>, AnnotationMirror> mustCallOnElementsAnnotations =
      new HashMap<>(10);

  /**
   * Checks if WPI is enabled for the Resource Leak Checker inference. See {@link
   * ResourceLeakChecker#ENABLE_WPI_FOR_RLC}.
   *
   * @return returns true if WPI is enabled for the Resource Leak Checker
   */
  protected boolean isWpiEnabledForRLC() {
    return enableWpiForRlc;
  }

  /**
   * Return the temporary variable for node, if it exists. See {@code #tempVars}.
   *
   * @param node a CFG node
   * @return the corresponding temporary variable, or null if there is not one
   */
  public @Nullable LocalVariableNode getTempVar(Node node) {
    return tempVars.get(node.getTree());
  }

  @Override
  protected TreeAnnotator createTreeAnnotator() {
    return new ListTreeAnnotator(
        super.createTreeAnnotator(), new MustCallOnElementsTreeAnnotator(this));
  }

  @Override
  protected TypeAnnotator createTypeAnnotator() {
    return new ListTypeAnnotator(
        super.createTypeAnnotator(), new MustCallOnElementsTypeAnnotator(this));
  }

  /**
   * returns whether the specified assignment AST node is in a pattern-matched allocating for-loop.
   *
   * @param assgn the assignment AST node
   * @return whether the specified node is in an allocating for-loop for an {@code @OwningArray}.
   */
  public static boolean doesAssignmentCreateArrayObligation(AssignmentTree assgn) {
    return assignmentToLoopMap.containsKey(assgn);
  }

  /**
   * Marks the specified loop as fulfilling an mcoe obligation for an {@code @OwningArray} array.
   *
   * @param loop the wrapper class of the loop
   */
  public static void markFulfillingLoop(PotentiallyFulfillingLoop loop) {
    conditionToLoopMap.put(loop.condition, loop);
  }

  /**
   * Marks the specified loop as creating an mcoe obligation for an {@code @OwningArray} array.
   *
   * @param loop the wrapper class of the loop
   */
  public static void markAssigningLoop(PotentiallyAssigningLoop loop) {
    assert (loop.condition.getKind() == Tree.Kind.LESS_THAN)
        : "Trying to associate Tree as condition of a method calling for-loop, but is not a LESS_THAN tree";
    assignmentToLoopMap.put(loop.assignment, loop);
    conditionToLoopMap.put(loop.condition, loop);
  }

  /**
   * Returns the {@link
   * org.checkerframework.checker.calledmethods.CalledMethodsAnnotatedTypeFactory.McoeObligationAlteringLoop}
   * for which the given tree is the loop condition or null if there is no such loop.
   *
   * @param tree the loop condition tree
   * @return the {@link
   *     org.checkerframework.checker.calledmethods.CalledMethodsAnnotatedTypeFactory.McoeObligationAlteringLoop}
   *     for which the given tree is the loop condition or null if there is no such loop.
   */
  public static McoeObligationAlteringLoop getLoopForCondition(Tree tree) {
    return conditionToLoopMap.get(tree);
  }

  /**
   * Returns the list of MustCallOnElements obligations created in this loop, specified through the
   * less-than AST-node, which is the loop condition.
   *
   * @param condition the condition of a pattern-matched loop that creates a MustCallOnElements
   *     obligation
   * @return list of the methods that are the MustCallOnElements obligations created in this loop
   */
  public static Set<String> whichObligationsDoesLoopWithThisConditionCreate(Tree condition) {
    assert (condition.getKind() == Tree.Kind.LESS_THAN)
        : "Trying to associate Tree as condition of a method calling for-loop, but is not a LESS_THAN tree";
    McoeObligationAlteringLoop loop = conditionToLoopMap.get(condition);
    boolean isAssigningLoop =
        loop != null && loop.loopKind == McoeObligationAlteringLoop.LoopKind.ASSIGNING;
    if (isAssigningLoop) {
      return loop.getMethods();
    }
    return null;
  }

  /**
   * Returns the names of the methods called in the pattern-matched, MustCallOnElements-fulfilling
   * loop specified by the given Tree, which is expected to be the condition of said loop.
   *
   * @param condition the condition of a pattern-matched loop that closes a MustCallOnElements
   *     obligation
   * @return names of the methods called on the elements of the array in the loop
   */
  public static Set<String> whichMethodsDoesLoopWithThisConditionCall(Tree condition) {
    assert (condition.getKind() == Tree.Kind.LESS_THAN)
        : "Trying to associate Tree as condition of a method calling for-loop, but is not a LESS_THAN tree";
    McoeObligationAlteringLoop loop = conditionToLoopMap.get(condition);
    boolean isFulfillingLoop =
        loop != null && loop.loopKind == McoeObligationAlteringLoop.LoopKind.FULFILLING;
    if (isFulfillingLoop) {
      return new HashSet<String>(loop.getMethods());
    }
    return null;
  }

  /**
   * Returns the collection AST-node, for which a MustCallOnElements obligation is opened/closed in
   * the loop, for which the given lessThan AST-node is the condition.
   *
   * @param condition the less-than AST-node
   * @return the array AST-node in the loop body
   */
  public static ExpressionTree getCollectionTreeForLoopWithThisCondition(Tree condition) {
    assert (condition.getKind() == Tree.Kind.LESS_THAN)
        : "Trying to associate Tree as condition of an obligation changing for-loop, but is not a LESS_THAN tree";
    McoeObligationAlteringLoop loop = conditionToLoopMap.get(condition);
    ExpressionTree collectionTree = loop == null ? null : loop.collectionTree;
    return collectionTree;
  }

  /**
   * Returns the collection access AST-tree, for which a MustCallOnElements obligation is
   * opened/closed in the loop, for which the given lessThan AST-node is the condition.
   *
   * @param condition the less-than AST-node
   * @return the collection access AST-tree in the loop body
   */
  public static ExpressionTree getCollectionAccessTreeForLoopWithThisCondition(Tree condition) {
    assert (condition.getKind() == Tree.Kind.LESS_THAN)
        : "Trying to associate Tree as condition of an obligation changing for-loop, but is not a LESS_THAN tree";
    McoeObligationAlteringLoop loop = conditionToLoopMap.get(condition);
    ExpressionTree accessTree = loop == null ? null : loop.collectionElementTree;
    return accessTree;
  }

  /**
   * Returns whether the given Tree is a java.util.Collection type by checking whether the raw type
   * of the element is assignable from java.util.Collection. Returns false if tree is null, or has
   * no valid type.
   *
   * @param tree the tree
   * @param atf an AnnotatedTypeFactory to get the annotated type of the element
   * @return whether the given Tree is a Java.util.Collection type
   */
  public static boolean isCollection(Tree tree, AnnotatedTypeFactory atf) {
    if (tree == null) return false;
    Element element = TreeUtils.elementFromTree(tree);
    if (element == null) return false;
    AnnotatedTypeMirror elementTypeMirror = atf.getAnnotatedType(element);
    if (elementTypeMirror == null || elementTypeMirror.getUnderlyingType() == null) return false;
    Class<?> elementRawType = TypesUtils.getClassFromType(elementTypeMirror.getUnderlyingType());
    if (elementRawType == null) return false;
    return Collection.class.isAssignableFrom(elementRawType);
  }

  /**
   * Returns whether the given Element is a java.util.Collection type by checking whether the raw
   * type of the element is assignable from java.util.Collection. Returns false if element is null,
   * or has no valid type.
   *
   * @param element the element
   * @param atf an AnnotatedTypeFactory to get the annotated type of the element
   * @return whether the given element is a Java.util.Collection type
   */
  public static boolean isCollection(Element element, AnnotatedTypeFactory atf) {
    if (element == null) return false;
    AnnotatedTypeMirror elementTypeMirror = atf.getAnnotatedType(element);
    if (elementTypeMirror == null || elementTypeMirror.getUnderlyingType() == null) return false;
    Class<?> elementRawType = TypesUtils.getClassFromType(elementTypeMirror.getUnderlyingType());
    if (elementRawType == null) return false;
    return Collection.class.isAssignableFrom(elementRawType);
  }

  /**
   * Returns whether the given tree has a {@code MustCallOnElementsUnknown} annotation in the given
   * store. Assumes the arguments are non-null.
   *
   * @param mcoeStore store containing MustCallOnElements type annotation information
   * @param arrTree the array variable/identifier tree
   * @return list of the MustCallOnElements obligations of the given array
   */
  public boolean isMustCallOnElementsUnknown(CFStore mcoeStore, Tree arrTree) {
    if (arrTree instanceof AssignmentTree) {
      arrTree = ((AssignmentTree) arrTree).getVariable();
    }
    if (arrTree instanceof ArrayAccessTree) {
      arrTree = ((ArrayAccessTree) arrTree).getExpression();
    }
    if (!(arrTree instanceof VariableTree) && !(arrTree instanceof IdentifierTree)) {
      return false;
    }
    JavaExpression arrayJX =
        (arrTree instanceof VariableTree)
            ? JavaExpression.fromVariableTree((VariableTree) arrTree)
            : JavaExpression.fromTree((IdentifierTree) arrTree);
    CFValue cfval = getValueFromStoreSafely(mcoeStore, arrayJX);
    if (cfval == null) return false;
    Element arrElm =
        (arrTree instanceof VariableTree)
            ? TreeUtils.elementFromDeclaration((VariableTree) arrTree)
            : TreeUtils.elementFromTree((IdentifierTree) arrTree);
    if (arrElm.getKind() == ElementKind.FIELD && arrElm.getAnnotation(OwningArray.class) != null) {
      if (ElementUtils.isFinal(arrElm)) {
        if (cfval == null) {
          // entry block doesn't have final field in store yet
          return false;
        }
      } else {
        // nonfinal OwningArray field is illegal. An error was already issued.
        // Prevent program crash and return here.
        return false;
      }
    }
    AnnotationMirror mcoeAnnoUnknown =
        AnnotationUtils.getAnnotationByClass(
            cfval.getAnnotations(), MustCallOnElementsUnknown.class);
    return mcoeAnnoUnknown != null;
  }

  /**
   * Wrapper to accessing a store, which throws a BugInCF when receiving an unexpected
   * JavaExpression.
   *
   * @param store the store to access
   * @param jx the JavaExpression value that is queried
   * @return the CFValue of jx in store or null if it doesn't exist
   */
  private CFValue getValueFromStoreSafely(CFStore store, JavaExpression jx) {
    try {
      return store.getValue(jx);
    } catch (BugInCF e) {
      return null;
    }
  }

  /**
   * Returns a list of methods that have to be "called on elements" on the {@code @OwningArray}
   * array specified by the given tree (expected to be an array identifier) or null if there is a
   * {@code @MustCallOnElementsUnknown} annotation. The list is extracted from the store passed as
   * an argument.
   *
   * @param mcoeStore store containing MustCallOnElements type annotation information
   * @param arrTree the array variable/identifier tree
   * @return list of the MustCallOnElements obligations of the given array
   */
  public List<String> getMustCallOnElementsObligations(CFStore mcoeStore, Tree arrTree) {
    if (arrTree instanceof AssignmentTree) {
      arrTree = ((AssignmentTree) arrTree).getVariable();
    }
    if (arrTree instanceof ArrayAccessTree) {
      arrTree = ((ArrayAccessTree) arrTree).getExpression();
    }
    if (!(arrTree instanceof VariableTree) && !(arrTree instanceof IdentifierTree)) {
      throw new BugInCF(
          "MCOE obligation %s must be either from definition or declaration, but is %s",
          arrTree, arrTree.getClass().getCanonicalName());
    }
    JavaExpression arrayJX =
        (arrTree instanceof VariableTree)
            ? JavaExpression.fromVariableTree((VariableTree) arrTree)
            : JavaExpression.fromTree((IdentifierTree) arrTree);
    CFValue cfval = mcoeStore.getValue(arrayJX);
    Element arrElm =
        (arrTree instanceof VariableTree)
            ? TreeUtils.elementFromDeclaration((VariableTree) arrTree)
            : TreeUtils.elementFromTree((IdentifierTree) arrTree);
    if (arrElm.getKind() == ElementKind.FIELD && arrElm.getAnnotation(OwningArray.class) != null) {
      if (ElementUtils.isFinal(arrElm)) {
        if (cfval == null) {
          // entry block doesn't have final field in store yet
          return Collections.emptyList();
        }
      } else {
        // nonfinal OwningArray field is illegal. An error was already issued.
        // Prevent program crash and return here.
        return Collections.emptyList();
      }
    }
    assert cfval != null : "No mcoe annotation for " + arrTree + " in store.";
    AnnotationMirror mcoeAnno =
        AnnotationUtils.getAnnotationByClass(cfval.getAnnotations(), MustCallOnElements.class);
    AnnotationMirror mcoeAnnoUnknown =
        AnnotationUtils.getAnnotationByClass(
            cfval.getAnnotations(), MustCallOnElementsUnknown.class);
    assert mcoeAnno != null || mcoeAnnoUnknown != null
        : "No mcoe annotation for " + arrTree + " in store.";
    if (mcoeAnnoUnknown != null) {
      return null;
    } else {
      AnnotationValue av =
          mcoeAnno.getElementValues().get(this.getMustCallOnElementsValueElement());
      return av == null
          ? Collections.emptyList()
          : AnnotationUtils.annotationValueToList(av, String.class);
    }
  }

  /*
   * Change the default @MustCallOnElements type value of @OwningArray fields to contain the @MustCall
   * methods of the component, if no manual annotation is present.
   * For example the type of: final @OwningArray Socket[] s is changed to @MustCallOnElements("close").
   */
  // @Override
  // public void addComputedTypeAnnotations(Tree tree, AnnotatedTypeMirror type, boolean useFlow) {
  //   super.addComputedTypeAnnotations(tree, type, useFlow);
  //   if (tree instanceof VariableTree) {
  //     VariableTree varTree = (VariableTree) tree;
  //     Element elt = TreeUtils.elementFromDeclaration(varTree);
  //     boolean noMcoeAnno = true;
  //     for (AnnotationMirror paramAnno : elt.asType().getAnnotationMirrors()) {
  //       if (AnnotationUtils.areSameByName(paramAnno,
  // MustCallOnElements.class.getCanonicalName())) {
  //         // is @MustCallOnElements annotation
  //         noMcoeAnno = false;
  //         break;
  //       }
  //     }
  //     if (noMcoeAnno) { // don't override an existing manual annotation
  //       if ((elt.getKind() == ElementKind.FIELD || elt.getKind() == ElementKind.PARAMETER)
  //           && getDeclAnnotation(elt, OwningArray.class) != null) {
  //         TypeMirror componentType = ((ArrayType) elt.asType()).getComponentType();
  //         List<String> mcoeObligationsOfOwningField = getMustCallValuesForType(componentType);
  //         AnnotationMirror newType = getMustCallOnElementsType(mcoeObligationsOfOwningField);
  //         type.replaceAnnotation(newType);
  //       }
  //     }
  //   }
  // }

  /*
   * Change the default @MustCallOnElements type value of @OwningArray fields and @OwningArray method parameters
   * to contain the @MustCall methods of the component, if no manual annotation is present.
   * For example the type of: final @OwningArray Socket[] s is changed to @MustCallOnElements("close").
   */
  /* TODO could add: if (elt instanceof VariableElement) {} to ensure it's a declaration? */
  @Override
  public void addComputedTypeAnnotations(Element elt, AnnotatedTypeMirror type) {
    super.addComputedTypeAnnotations(elt, type);

    if (elt.getKind() == ElementKind.METHOD || elt.getKind() == ElementKind.CONSTRUCTOR) {
      // is a param @OwningArray?
      // change the type of that param
      ExecutableElement method = (ExecutableElement) elt;
      AnnotatedExecutableType methodType = (AnnotatedExecutableType) type;
      Iterator<? extends VariableElement> paramIterator = method.getParameters().iterator();
      Iterator<AnnotatedTypeMirror> paramTypeIterator = methodType.getParameterTypes().iterator();
      while (paramIterator.hasNext() && paramTypeIterator.hasNext()) {
        VariableElement param = paramIterator.next();
        AnnotatedTypeMirror paramType = paramTypeIterator.next();
        // if (getDeclAnnotation(param, OwningArray.class) != null) {
        // @OwningArray parameter
        boolean noMcoeAnno = true;
        for (AnnotationMirror paramAnno : param.asType().getAnnotationMirrors()) {
          if (AnnotationUtils.areSameByName(
              paramAnno, MustCallOnElements.class.getCanonicalName())) {
            // is @MustCallOnElements annotation
            noMcoeAnno = false;
            break;
          }
          if (AnnotationUtils.areSameByName(
              paramAnno, MustCallOnElementsUnknown.class.getCanonicalName())) {
            noMcoeAnno = false;
            break;
          }
        }
        if (noMcoeAnno) { // don't override an existing manual annotation
          if (param.asType() instanceof ArrayType) {
            TypeMirror componentType = ((ArrayType) param.asType()).getComponentType();
            List<String> mcoeObligationsOfOwningField = getMustCallValuesForType(componentType);
            AnnotationMirror newType = getMustCallOnElementsType(mcoeObligationsOfOwningField);
            paramType.replaceAnnotation(newType);
          }
        }
      }
    } else if (elt.asType() instanceof ArrayType) {
      if (!(elt instanceof VariableElement)) {
        // not a declaration
        return;
      }
      boolean noMcoeAnno = true;
      for (AnnotationMirror paramAnno : elt.asType().getAnnotationMirrors()) {
        if (AnnotationUtils.areSameByName(paramAnno, MustCallOnElements.class.getCanonicalName())) {
          // is @MustCallOnElements annotation
          noMcoeAnno = false;
          break;
        }
        if (AnnotationUtils.areSameByName(
            paramAnno, MustCallOnElementsUnknown.class.getCanonicalName())) {
          noMcoeAnno = false;
          break;
        }
      }
      if (noMcoeAnno) { // don't override an existing manual annotation
        if ((elt.getKind() == ElementKind.FIELD
                && getDeclAnnotation(elt, OwningArray.class) != null)
            || elt.getKind() == ElementKind.PARAMETER) {
          if (elt.asType() instanceof ArrayType) {
            TypeMirror componentType = ((ArrayType) elt.asType()).getComponentType();
            List<String> mcoeObligationsOfOwningField = getMustCallValuesForType(componentType);
            AnnotationMirror newType = getMustCallOnElementsType(mcoeObligationsOfOwningField);
            type.replaceAnnotation(newType);
          }
        }
      }
    }
  }

  /**
   * Generate an annotation from a list of method names.
   *
   * @param methodNames the names of the methods to add to the type
   * @return the annotation with the given methods as value
   */
  private @Nullable AnnotationMirror getMustCallOnElementsType(List<String> methodNames) {
    AnnotationBuilder builder = new AnnotationBuilder(processingEnv, BOTTOM);
    builder.setValue("value", CollectionsPlume.withoutDuplicatesSorted(methodNames));
    return builder.build();
  }

  /**
   * Returns the list of mustcall obligations for a type.
   *
   * @param type the type
   * @return the list of mustcall obligations for the type
   */
  private List<String> getMustCallValuesForType(TypeMirror type) {
    MustCallAnnotatedTypeFactory mcAtf = new MustCallAnnotatedTypeFactory(checker);
    TypeElement typeElement = TypesUtils.getTypeElement(type);
    if (typeElement == null) return new ArrayList<>();
    AnnotationMirror imcAnnotation =
        mcAtf.getDeclAnnotation(typeElement, InheritableMustCall.class);
    AnnotationMirror mcAnnotation = mcAtf.getDeclAnnotation(typeElement, MustCall.class);
    Set<String> mcValues = new HashSet<>();
    if (mcAnnotation != null) {
      mcValues.addAll(
          AnnotationUtils.getElementValueArray(
              mcAnnotation, mcAtf.getMustCallValueElement(), String.class));
    }
    if (imcAnnotation != null) {
      mcValues.addAll(
          AnnotationUtils.getElementValueArray(
              imcAnnotation, mcAtf.getInheritableMustCallValueElement(), String.class));
    }
    return new ArrayList<>(mcValues);
  }

  @Override
  public void setRoot(@Nullable CompilationUnitTree root) {
    super.setRoot(root);
    // TODO: This should probably be guarded by isSafeToClearSharedCFG from
    // GenericAnnotatedTypeFactory, but this works here because we know the Must Call Checker is
    // always the first subchecker that's sharing tempvars.
    tempVars.clear();
  }

  @Override
  protected Set<Class<? extends Annotation>> createSupportedTypeQualifiers() {
    return new LinkedHashSet<>(
        Arrays.asList(MustCallOnElements.class, MustCallOnElementsUnknown.class));
  }

  /**
   * Creates a {@link MustCallOnElements} annotation whose values are the given strings.
   *
   * @param val the methods that should be called
   * @return an annotation indicating that the given methods should be called
   */
  public AnnotationMirror createMustCallOnElements(List<String> val) {
    return mustCallOnElementsAnnotations.computeIfAbsent(val, this::createMustCallOnElementsImpl);
  }

  /**
   * Creates a {@link MustCallOnElements} annotation whose values are the given strings.
   *
   * <p>This internal version bypasses the cache, and is only used for new annotations.
   *
   * @param methodList the methods that should be called
   * @return an annotation indicating that the given methods should be called
   */
  private AnnotationMirror createMustCallOnElementsImpl(List<String> methodList) {
    AnnotationBuilder builder = new AnnotationBuilder(processingEnv, MustCallOnElements.class);
    String[] methodArray = methodList.toArray(new String[methodList.size()]);
    Arrays.sort(methodArray);
    builder.setValue("value", methodArray);
    return builder.build();
  }

  /**
   * Returns the {@link MustCallOnElements#value} element.
   *
   * @return the {@link MustCallOnElements#value} element
   */
  public ExecutableElement getMustCallOnElementsValueElement() {
    return mustCallOnElementsValueElement;
  }

  /**
   * Returns true if the given type should never have a must-call-on-elements obligation.
   *
   * @param type the type to check
   * @return true if the given type should never have a must-call-on-elements obligation
   */
  public boolean shouldHaveNoMustCallOnElementsObligation(TypeMirror type) {
    return type.getKind().isPrimitive() || TypesUtils.isClass(type) || TypesUtils.isString(type);
  }

  @Override
  protected QualifierHierarchy createQualifierHierarchy() {
    return new MustCallOnElementsQualifierHierarchy(
        this.getSupportedTypeQualifiers(), this.getProcessingEnv(), this);
  }

  /** Qualifier hierarchy for the MustCallOnElements Checker. */
  class MustCallOnElementsQualifierHierarchy extends SubtypeIsSubsetQualifierHierarchy {

    /**
     * Creates a SubtypeIsSubsetQualifierHierarchy from the given classes.
     *
     * @param qualifierClasses classes of annotations that are the qualifiers for this hierarchy
     * @param processingEnv processing environment
     * @param atypeFactory the associated type factory
     */
    public MustCallOnElementsQualifierHierarchy(
        Collection<Class<? extends Annotation>> qualifierClasses,
        ProcessingEnvironment processingEnv,
        GenericAnnotatedTypeFactory<?, ?, ?, ?> atypeFactory) {
      super(qualifierClasses, processingEnv, atypeFactory);
    }

    @Override
    public boolean isSubtypeShallow(
        AnnotationMirror subQualifier,
        TypeMirror subType,
        AnnotationMirror superQualifier,
        TypeMirror superType) {
      if (shouldHaveNoMustCallOnElementsObligation(subType)
          || shouldHaveNoMustCallOnElementsObligation(superType)) {
        return true;
      }
      return super.isSubtypeShallow(subQualifier, subType, superQualifier, superType);
    }

    @Override
    public @Nullable AnnotationMirror leastUpperBoundShallow(
        AnnotationMirror qualifier1, TypeMirror tm1, AnnotationMirror qualifier2, TypeMirror tm2) {
      boolean tm1NoMustCallOnElements = shouldHaveNoMustCallOnElementsObligation(tm1);
      boolean tm2NoMustCallOnElements = shouldHaveNoMustCallOnElementsObligation(tm2);
      if (tm1NoMustCallOnElements == tm2NoMustCallOnElements) {
        return super.leastUpperBoundShallow(qualifier1, tm1, qualifier2, tm2);
      } else if (tm1NoMustCallOnElements) {
        return qualifier1;
      } else { // if (tm2NoMustCallOnElements) {
        return qualifier2;
      }
    }
  }

  /**
   * The TreeAnnotator for the MustCallOnElements type system.
   *
   * <p>This tree annotator treats non-owning method parameters as bottom, regardless of their
   * declared type, when they appear in the body of the method. Doing so is safe because being
   * non-owning means, by definition, that their must-call obligations are only relevant in the
   * callee. (This behavior is disabled if the {@code -AnoLightweightOwnership} option is passed to
   *
   * <p>The tree annotator also changes the type of resource variables to remove "close" from their
   * must-call types, because the try-with-resources statement guarantees that close() is called on
   * all such variables.
   */
  private class MustCallOnElementsTreeAnnotator extends TreeAnnotator {
    /**
     * Create a MustCallOnElementsTreeAnnotator.
     *
     * @param mustCallOnElementsAnnotatedTypeFactory the type factory
     */
    public MustCallOnElementsTreeAnnotator(
        MustCallOnElementsAnnotatedTypeFactory mustCallOnElementsAnnotatedTypeFactory) {
      super(mustCallOnElementsAnnotatedTypeFactory);
    }

    @Override
    public Void visitIdentifier(IdentifierTree tree, AnnotatedTypeMirror type) {
      Element elt = TreeUtils.elementFromUse(tree);
      // The following changes are not desired for RLC _inference_ in unannotated programs,
      // where a goal is to infer and add @Owning annotations to formal parameters.
      // Therefore, if WPI is enabled, they should not be executed.
      if (getWholeProgramInference() == null
          && elt.getKind() == ElementKind.PARAMETER
          && getDeclAnnotation(elt, OwningArray.class) == null) {
        // Parameters that are not annotated with @Owning should be treated as bottom
        // (to suppress warnings about them). An exception is polymorphic parameters,
        // which might be @MustCallOnElementsAlias (and so wouldn't be annotated with @Owning):
        // these are not modified, to support verification of @MustCallOnElementsAlias
        // annotations.
        type.replaceAnnotation(BOTTOM);
      }
      return super.visitIdentifier(tree, type);
    }
  }
}
