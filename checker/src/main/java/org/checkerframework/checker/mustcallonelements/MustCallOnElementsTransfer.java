package org.checkerframework.checker.mustcallonelements;

import com.sun.source.tree.ArrayAccessTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.Tree;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import org.checkerframework.checker.mustcall.MustCallAnnotatedTypeFactory;
import org.checkerframework.checker.mustcall.qual.InheritableMustCall;
import org.checkerframework.checker.mustcall.qual.MustCall;
import org.checkerframework.checker.mustcallonelements.qual.OwningCollection;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.checker.resourceleak.CollectionTransfer;
import org.checkerframework.checker.resourceleak.ResourceLeakChecker;
import org.checkerframework.checker.rlccalledmethods.RLCCalledMethodsAnnotatedTypeFactory.PotentiallyAssigningLoop;
import org.checkerframework.checker.rlccalledmethods.RLCCalledMethodsAnnotatedTypeFactory.PotentiallyFulfillingLoop;
import org.checkerframework.dataflow.analysis.ConditionalTransferResult;
import org.checkerframework.dataflow.analysis.RegularTransferResult;
import org.checkerframework.dataflow.analysis.TransferInput;
import org.checkerframework.dataflow.analysis.TransferResult;
import org.checkerframework.dataflow.cfg.node.AssignmentNode;
import org.checkerframework.dataflow.cfg.node.MethodInvocationNode;
import org.checkerframework.dataflow.cfg.node.Node;
import org.checkerframework.dataflow.expression.JavaExpression;
import org.checkerframework.framework.flow.CFAnalysis;
import org.checkerframework.framework.flow.CFStore;
import org.checkerframework.framework.flow.CFValue;
import org.checkerframework.javacutil.AnnotationBuilder;
import org.checkerframework.javacutil.AnnotationUtils;
import org.checkerframework.javacutil.TreeUtils;
import org.checkerframework.javacutil.TypesUtils;
import org.plumelib.util.CollectionsPlume;

/** Transfer function for the MustCallOnElements type system. */
public class MustCallOnElementsTransfer extends CollectionTransfer {

  /** The type factory. */
  private final MustCallOnElementsAnnotatedTypeFactory atypeFactory;

  /** True if -AenableWpiForRlc was passed on the command line. */
  private final boolean enableWpiForRlc;

  /** The processing environment. */
  private final ProcessingEnvironment env;

  /**
   * Create a MustCallOnElementsTransfer.
   *
   * @param analysis the analysis
   */
  public MustCallOnElementsTransfer(CFAnalysis analysis) {
    super(analysis, analysis.getTypeFactory());
    if (analysis.getTypeFactory() instanceof MustCallOnElementsAnnotatedTypeFactory) {
      atypeFactory = (MustCallOnElementsAnnotatedTypeFactory) analysis.getTypeFactory();
    } else {
      atypeFactory =
          new MustCallOnElementsAnnotatedTypeFactory(
              ((MustCallAnnotatedTypeFactory) analysis.getTypeFactory()).getChecker());
    }
    enableWpiForRlc = atypeFactory.getChecker().hasOption(ResourceLeakChecker.ENABLE_WPI_FOR_RLC);
    this.env = atypeFactory.getChecker().getProcessingEnvironment();
  }

  // @Override
  // public TransferResult<CFValue, CFStore> visitVariableDeclaration(
  //     VariableDeclarationNode node, TransferInput<CFValue, CFStore> input) {
  //   TransferResult<CFValue, CFStore> res = super.visitVariableDeclaration(node, input);
  //   // since @OwningCollection is enforced to be array, the following cast is guaranteed to
  // succeed
  //   VariableElement elmnt = TreeUtils.elementFromDeclaration(node.getTree());
  //   if (atypeFactory.getDeclAnnotation(elmnt, OwningCollection.class) != null
  //       && elmnt.getKind() == ElementKind.FIELD) {
  //     TypeMirror componentType = ((ArrayType) elmnt.asType()).getComponentType();
  //     List<String> mcoeObligationsOfOwningField = getMustCallValuesForType(componentType);
  //     AnnotationMirror newType = getMustCallOnElementsType(mcoeObligationsOfOwningField);
  //     JavaExpression field = JavaExpression.fromVariableTree(node.getTree());
  //     res.getRegularStore().clearValue(field);
  //     res.getRegularStore().insertValue(field, newType);
  //   }
  //   return res;
  // }

  // /**
  //  * Returns the list of mustcall obligations for a type.
  //  *
  //  * @param type the type
  //  * @return the list of mustcall obligations for the type
  //  */
  // private List<String> getMustCallValuesForType(TypeMirror type) {
  //   InheritableMustCall imcAnnotation =
  //       TypesUtils.getClassFromType(type).getAnnotation(InheritableMustCall.class);
  //   MustCall mcAnnotation = TypesUtils.getClassFromType(type).getAnnotation(MustCall.class);
  //   Set<String> mcValues = new HashSet<>();
  //   if (mcAnnotation != null) {
  //     mcValues.addAll(Arrays.asList(mcAnnotation.value()));
  //   }
  //   if (imcAnnotation != null) {
  //     mcValues.addAll(Arrays.asList(imcAnnotation.value()));
  //   }
  //   return new ArrayList<>(mcValues);
  // }

  // @Override
  // public TransferResult<CFValue, CFStore> visitFieldAccess(
  //     FieldAccessNode node, TransferInput<CFValue, CFStore> input) {
  //   TransferResult<CFValue, CFStore> res = super.visitFieldAccess(node, input);
  //   Element elmnt = TreeUtils.elementFromTree(node.getTree());
  //   if (atypeFactory.getDeclAnnotation(elmnt, OwningCollection.class) != null
  //       && elmnt.getKind() == ElementKind.FIELD) {
  //     // since @OwningCollection is enforced to be array, the following cast is guaranteed to
  // succeed
  //     TypeMirror componentType = ((ArrayType) elmnt.asType()).getComponentType();
  //     List<String> mcoeObligationsOfOwningField = getMustCallValuesForType(componentType);
  //     AnnotationMirror newType = getMustCallOnElementsType(mcoeObligationsOfOwningField);
  //     JavaExpression field = JavaExpression.fromTree((ExpressionTree) node.getTree());
  //     res.getRegularStore().clearValue(field);
  //     res.getRegularStore().insertValue(field, newType);
  //     System.out.println("changed type of: " + node);
  //   }
  //   return res;
  // }

  @Override
  public TransferResult<CFValue, CFStore> visitAssignment(
      AssignmentNode node, TransferInput<CFValue, CFStore> input) {
    TransferResult<CFValue, CFStore> res = super.visitAssignment(node, input);
    CFStore store = res.getRegularStore();
    Node lhs = node.getTarget();
    lhs = getNodeOrTempVar(lhs);
    Node rhs = node.getExpression();
    rhs = getNodeOrTempVar(rhs);
    boolean lhsIsOwningCollection =
        lhs != null
            && lhs.getTree() != null
            && TreeUtils.elementFromTree(lhs.getTree()) != null
            && TreeUtils.elementFromTree(lhs.getTree()).getAnnotation(OwningCollection.class)
                != null;
    boolean rhsIsOwningCollection =
        rhs != null
            && rhs.getTree() != null
            && TreeUtils.elementFromTree(rhs.getTree()) != null
            && TreeUtils.elementFromTree(rhs.getTree()).getAnnotation(OwningCollection.class)
                != null;

    boolean assignmentOfOwningCollectionArrayElement =
        lhsIsOwningCollection && lhs.getTree().getKind() == Tree.Kind.ARRAY_ACCESS;

    if (assignmentOfOwningCollectionArrayElement) {
      ExpressionTree arrayExpression = ((ArrayAccessTree) lhs.getTree()).getExpression();
      JavaExpression arrayJx = JavaExpression.fromTree(arrayExpression);

      boolean inAssigningLoop =
          MustCallOnElementsAnnotatedTypeFactory.doesAssignmentCreateArrayObligation(
              (AssignmentTree) node.getTree());

      // transformation of assigning loop is handled at the loop condition node,
      // not the assignment node. So, only transform if not in an assigning loop.
      if (!inAssigningLoop) {
        store =
            transformWriteToOwningCollection(arrayJx, arrayExpression, node.getExpression(), store);
      }
    } else if (lhsIsOwningCollection && rhsIsOwningCollection) {
      // this transfers ownership from rhs to lhs. If the rhs has mcoeunknown (i.e. revoked
      // ownership),
      // this doesn't require special handling. The lhs will automatically also get this type, since
      // it
      // is top of the hierarchy.
      JavaExpression rhsJavaExpression = JavaExpression.fromNode(rhs);
      store.clearValue(rhsJavaExpression);
      store.insertValue(rhsJavaExpression, getMustCallOnElementsUnknown());
    } else if (rhsIsOwningCollection && !(rhs.getTree() instanceof ArrayAccessTree)) {
      JavaExpression lhsJavaExpression = JavaExpression.fromNode(lhs);
      store.clearValue(lhsJavaExpression);
      store.insertValue(lhsJavaExpression, getMustCallOnElementsUnknown());
    }
    return new RegularTransferResult<CFValue, CFStore>(res.getResultValue(), store);
  }

  /**
   * Transforms a generic single write to an {@code OwningCollection}.
   *
   * <p>If the collection is read-only, don't perform transformation.
   *
   * <p>Set the {@code MustCallOnElements} type of the collection to the upper bound of its previous
   * type and the type generated by the {@code MustCall} values of the RHS in the given store, for
   * example {@code @MustCallOnElements("close")} if the RHS is a {@code Socket}.
   *
   * @param receiverCollection the {@code OwningCollection} written to
   * @param receiverTree tree of the {@code OwningCollection} written to
   * @param rhs the rhs of the assignment/write
   * @param store the store to modify
   * @return the modified store
   */
  private CFStore transformWriteToOwningCollection(
      JavaExpression receiverCollection, Tree receiverTree, Node rhs, CFStore store) {
    List<String> previousMcoeMethods =
        atypeFactory.getMustCallOnElementsObligations(
            store, receiverCollection, TreeUtils.elementFromTree(receiverTree));
    if (previousMcoeMethods == null) {
      // previous value is @MustCallOnElementsUnknown - i.e. no write permission. Consistency
      // analyzer throws error. Don't perform transformation.
    } else {
      Node rhsVar = getNodeOrTempVar(rhs);
      List<String> mcValuesOfRhs = getMustCallValues(rhsVar);
      AnnotationMirror newType = getMustCallOnElementsType(new HashSet<>(mcValuesOfRhs));

      CFValue oldCFVal = store.getValue(receiverCollection);
      CFValue newCFVal =
          analysis.createSingleAnnotationValue(newType, receiverCollection.getType());
      newCFVal =
          oldCFVal == null
              ? newCFVal
              : oldCFVal.leastUpperBound(newCFVal, receiverCollection.getType());
      store.replaceValue(receiverCollection, newCFVal);
    }
    return store;
  }

  /**
   * Returns a list of {@code @MustCall} values of the given node. Returns the empty list if the
   * node has no {@code @MustCall} values or is null.
   *
   * @param node the node
   * @return a list of {@code @MustCall} values of the given node
   */
  private List<String> getMustCallValues(Node node) {
    if (node.getTree() == null || TreeUtils.elementFromTree(node.getTree()) == null) {
      return new ArrayList<>();
    }
    Element elt = TreeUtils.elementFromTree(node.getTree());
    elt = TypesUtils.getTypeElement(elt.asType());
    if (elt == null) {
      return new ArrayList<>();
    }
    MustCallAnnotatedTypeFactory mcAtf =
        new MustCallAnnotatedTypeFactory(atypeFactory.getChecker());
    AnnotationMirror imcAnnotation = mcAtf.getDeclAnnotation(elt, InheritableMustCall.class);
    AnnotationMirror mcAnnotation = mcAtf.getDeclAnnotation(elt, MustCall.class);
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

  /**
   * The abstract transformer for {@code List.set(int, E)}.
   *
   * <p>Sets the Mcoe type of the receiver collection to the join of previous type and new type,
   * which is given by the MustCall values of the new element (second method parameter).
   *
   * <p>If the receiver list has type {@code MustCallOnElementsUnknown}, the transformer is not
   * executed.
   *
   * @param node the {@code MethodInvocationNode}
   * @param res the {@code TransferResult} containing the store to be edited
   * @param receiver JavaExpression of the collection, whose type should be changed
   * @return updated {@code TransferResult}
   */
  @Override
  protected TransferResult<CFValue, CFStore> transformListSet(
      MethodInvocationNode node, TransferResult<CFValue, CFStore> res, JavaExpression receiver) {
    List<Node> args = node.getArguments();
    assert args.size() == 2
        : "calling abstract transformer for List.Set(int,E), but params are: " + args;
    CFStore store =
        transformWriteToOwningCollection(
            receiver, node.getTarget().getReceiver().getTree(), args.get(1), res.getRegularStore());
    return new RegularTransferResult<CFValue, CFStore>(res.getResultValue(), store);
  }

  /**
   * The abstract transformer for {@code Collection.add(int, E)}.
   *
   * @param node the {@code MethodInvocationNode}
   * @param res the {@code TransferResult} containing the store to be edited
   * @param receiver JavaExpression of the collection, whose type should be changed
   * @return updated {@code TransferResult}
   */
  @Override
  protected TransferResult<CFValue, CFStore> transformCollectionAddWithIdx(
      MethodInvocationNode node, TransferResult<CFValue, CFStore> res, JavaExpression receiver) {
    List<Node> args = node.getArguments();
    assert args.size() == 2
        : "calling abstract transformer for Collection.add(int,E), but params are: " + args;
    CFStore store =
        transformWriteToOwningCollection(
            receiver, node.getTarget().getReceiver().getTree(), args.get(1), res.getRegularStore());
    return new RegularTransferResult<CFValue, CFStore>(res.getResultValue(), store);
  }

  /**
   * The abstract transformer for {@code Collection.clear()}
   *
   * @param node the {@code MethodInvocationNode}
   * @param res the {@code TransferResult} containing the store to be edited
   * @param receiver JavaExpression of the collection, whose type should be changed
   * @return updated {@code TransferResult}
   */
  @Override
  protected TransferResult<CFValue, CFStore> transformCollectionClear(
      MethodInvocationNode node, TransferResult<CFValue, CFStore> res, JavaExpression receiver) {
    List<Node> args = node.getArguments();
    assert args.size() == 0
        : "calling abstract transformer for Collection.clear(), but params are: " + args;
    CFStore store = res.getRegularStore();
    List<String> previousMcoeMethods =
        atypeFactory.getMustCallOnElementsObligations(
            store, receiver, TreeUtils.elementFromTree(node.getTarget().getReceiver().getTree()));
    if (previousMcoeMethods == null) {
      // previous value is @MustCallOnElementsUnknown - i.e. clearing illegal. Consistency
      // analyzer throws error. Don't perform transformation.
    } else {
      AnnotationMirror newType = getMustCallOnElementsType(new HashSet<>());
      CFValue newCFVal = analysis.createSingleAnnotationValue(newType, receiver.getType());
      store.replaceValue(receiver, newCFVal);
    }
    return new RegularTransferResult<CFValue, CFStore>(res.getResultValue(), store);
  }

  /**
   * The abstract transformer for {@code Collection.add(E)}.
   *
   * @param node the {@code MethodInvocationNode}
   * @param res the {@code TransferResult} containing the store to be edited
   * @param receiver JavaExpression of the collection, whose type should be changed
   * @return updated {@code TransferResult}
   */
  @Override
  protected TransferResult<CFValue, CFStore> transformCollectionAdd(
      MethodInvocationNode node, TransferResult<CFValue, CFStore> res, JavaExpression receiver) {
    List<Node> args = node.getArguments();
    assert args.size() == 1
        : "calling abstract transformer for Collection.add(E), but params are: " + args;
    CFStore store =
        transformWriteToOwningCollection(
            receiver, node.getTarget().getReceiver().getTree(), args.get(0), res.getRegularStore());
    return new RegularTransferResult<CFValue, CFStore>(res.getResultValue(), store);
  }

  /**
   * Abstract transformer for when an {@code @OwningColletion} variable is passed to an
   * {@code @OwningCollection} method parameter (constructor or method invocation).
   *
   * <p>Sets the type of the passed argument to {@code MustCallOnElementsUnknown} to indicate it
   * becomes a read-only alias.
   *
   * @param store the store to update
   * @param collectionArg the {@code @OwningCollection} argument
   */
  @Override
  protected void transformOwningCollectionArg(CFStore store, JavaExpression collectionArg) {
    store.clearValue(collectionArg);
    store.insertValue(collectionArg, getMustCallOnElementsUnknown());
  }

  /**
   * Updates the type of the corresponding collection in the else store of the {@code
   * TransferResult}.
   *
   * <p>An assigning loop is of the form {@code for (int i = 0; i < n; i++) collection[i] = new
   * Resource();}. This transformer now sets the {@code MustCallOnElements} type of {@code
   * collection} to the {@code MustCall} type of {@code Resource} upper bounded with the previous
   * {@code MustCallOnElements} type of {@code collection}.
   *
   * <p>The reason for that is that as a pre-condition for the loop, the {@code MustCallOnElements}
   * type of {@code collection} must be empty. Thus, if the pre-condition is fulfilled, this upper
   * bounding has no effect. However, {@code collection} could have revoked ownership (encoded as
   * having {@code MustCallOnElementsUnknown}, the top type). Then, the type would stay the same and
   * the revoked ownership is correctly preserved through the loop. In this case, the loop will be
   * flagged illegal and have an error reported later in the consistency analyzer.
   *
   * @param loop the {@code PotentiallyAssigningLoop}
   * @param res the transfer result to update
   * @return the updated transfer result
   */
  @Override
  protected TransferResult<CFValue, CFStore> transformAssigningLoop(
      PotentiallyAssigningLoop loop, TransferResult<CFValue, CFStore> res) {
    ExpressionTree arrayTree = loop.collectionTree;
    JavaExpression arrayJx = JavaExpression.fromTree(arrayTree);
    AnnotationMirror newType = getMustCallOnElementsType(loop.getMethods());
    CFStore elseStore = res.getElseStore();
    CFValue oldCFVal = elseStore.getValue(arrayJx);
    CFValue newCFVal = analysis.createSingleAnnotationValue(newType, arrayJx.getType());
    newCFVal = oldCFVal == null ? newCFVal : oldCFVal.leastUpperBound(newCFVal, arrayJx.getType());
    elseStore.replaceValue(arrayJx, newCFVal);
    return new ConditionalTransferResult<>(res.getResultValue(), res.getThenStore(), elseStore);
  }

  /**
   * Removes the methods that {@code loop} calls on the elements of the collection corresponding to
   * {@code loop.collectionTree} from the collections Mcoe type.
   *
   * @param loop the {@code PotentiallyFulfillingLoop}
   * @param res the transfer result to update
   * @return the updated transfer result
   */
  @Override
  protected TransferResult<CFValue, CFStore> transformFulfillingLoop(
      PotentiallyFulfillingLoop loop, TransferResult<CFValue, CFStore> res) {
    ExpressionTree collectionTree = loop.collectionTree;
    JavaExpression collectionJx = JavaExpression.fromTree(collectionTree);

    // this loop fulfills an obligation - remove that methodname from
    // the MustCallOnElements type of the collection
    CFStore elseStore = res.getElseStore();
    CFValue oldTypeValue = elseStore.getValue(collectionJx);
    if (oldTypeValue == null) {
      // collection is not in store - thus it cannot have mustcallonelements obligations
      return res;
    }
    AnnotationMirror oldType = oldTypeValue.getAnnotations().first();
    List<String> mcoeMethods = new ArrayList<>();
    if (oldType.getElementValues().get(atypeFactory.getMustCallOnElementsValueElement()) != null) {
      mcoeMethods =
          AnnotationUtils.getElementValueArray(
              oldType, atypeFactory.getMustCallOnElementsValueElement(), String.class);
    }
    mcoeMethods.removeAll(loop.getMethods());
    AnnotationMirror newType = getMustCallOnElementsType(new HashSet<>(mcoeMethods));
    elseStore.clearValue(collectionJx);
    elseStore.insertValue(collectionJx, newType);
    return new ConditionalTransferResult<>(res.getResultValue(), res.getThenStore(), elseStore);
  }

  /**
   * Generate an annotation from a list of method names.
   *
   * @param methodNames the names of the methods to add to the type
   * @return the annotation with the given methods as value
   */
  private @Nullable AnnotationMirror getMustCallOnElementsType(Set<String> methodNames) {
    AnnotationBuilder builder = new AnnotationBuilder(this.env, atypeFactory.BOTTOM);
    builder.setValue(
        "value", CollectionsPlume.withoutDuplicatesSorted(new ArrayList<>(methodNames)));
    return builder.build();
  }

  /**
   * Return a {@code @MustCallOnElementsUnknown} annotation.
   *
   * @return a {@code @MustCallOnElementsUnknown} AnnotationMirror.
   */
  private AnnotationMirror getMustCallOnElementsUnknown() {
    AnnotationBuilder builder = new AnnotationBuilder(this.env, atypeFactory.TOP);
    return builder.build();
  }

  /**
   * @param tree a tree
   * @return false if Resource Leak Checker is running as one of the upstream checkers and the
   *     -AenableWpiForRlc flag is not passed as a command line argument, otherwise returns the
   *     result of the super call
   */
  @Override
  protected boolean shouldPerformWholeProgramInference(Tree tree) {
    if (!isWpiEnabledForRLC()
        && atypeFactory.getCheckerNames().contains(ResourceLeakChecker.class.getCanonicalName())) {
      return false;
    }
    return super.shouldPerformWholeProgramInference(tree);
  }

  /**
   * @param expressionTree a tree
   * @param lhsTree its element
   * @return false if Resource Leak Checker is running as one of the upstream checkers and the
   *     -AenableWpiForRlc flag is not passed as a command line argument, otherwise returns the
   *     result of the super call
   */
  @Override
  protected boolean shouldPerformWholeProgramInference(Tree expressionTree, Tree lhsTree) {
    if (!isWpiEnabledForRLC()
        && atypeFactory.getCheckerNames().contains(ResourceLeakChecker.class.getCanonicalName())) {
      return false;
    }
    return super.shouldPerformWholeProgramInference(expressionTree, lhsTree);
  }

  /**
   * Checks if WPI is enabled for the Resource Leak Checker inference. See {@link
   * ResourceLeakChecker#ENABLE_WPI_FOR_RLC}.
   *
   * @return returns true if WPI is enabled for the Resource Leak Checker
   */
  protected boolean isWpiEnabledForRLC() {
    return enableWpiForRlc;
  }
}
