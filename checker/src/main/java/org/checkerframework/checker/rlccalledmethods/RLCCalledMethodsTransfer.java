package org.checkerframework.checker.rlccalledmethods;

import com.sun.source.tree.MethodInvocationTree;
import java.util.List;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.type.TypeKind;
import org.checkerframework.checker.calledmethods.CalledMethodsTransfer;
import org.checkerframework.checker.mustcall.CreatesMustCallForToJavaExpression;
import org.checkerframework.checker.mustcall.MustCallAnnotatedTypeFactory;
import org.checkerframework.checker.resourceleak.MustCallConsistencyAnalyzer;
import org.checkerframework.common.accumulation.AccumulationStore;
import org.checkerframework.common.accumulation.AccumulationValue;
import org.checkerframework.dataflow.analysis.TransferInput;
import org.checkerframework.dataflow.analysis.TransferResult;
import org.checkerframework.dataflow.cfg.node.LocalVariableNode;
import org.checkerframework.dataflow.cfg.node.MethodInvocationNode;
import org.checkerframework.dataflow.cfg.node.Node;
import org.checkerframework.dataflow.cfg.node.ObjectCreationNode;
import org.checkerframework.dataflow.cfg.node.SwitchExpressionNode;
import org.checkerframework.dataflow.cfg.node.TernaryExpressionNode;
import org.checkerframework.dataflow.expression.JavaExpression;
import org.checkerframework.javacutil.ElementUtils;
import org.checkerframework.javacutil.TreeUtils;
import org.checkerframework.javacutil.TypesUtils;

/** The transfer function for the resource-leak extension to the called-methods type system. */
public class RLCCalledMethodsTransfer extends CalledMethodsTransfer {

  /**
   * Shadowed because we must dispatch to the RLCCm Checker's version of getTypefactoryOfSubchecker
   * to get the correct MustCallAnnotatedTypeFactory.
   */
  private final RLCCalledMethodsAnnotatedTypeFactory cmAtf;

  /**
   * Create a new RLCCalledMethodsTransfer.
   *
   * @param analysis the analysis
   */
  public RLCCalledMethodsTransfer(RLCCalledMethodsAnalysis analysis) {
    super(analysis);
    this.cmAtf = (RLCCalledMethodsAnnotatedTypeFactory) analysis.getTypeFactory();
  }

  @Override
  public void accumulate(
      Node node, TransferResult<AccumulationValue, AccumulationStore> result, String... values) {
    super.accumulate(node, result, values);
    updateStoreForIteratedCollectionElement(Arrays.asList(values), result, node);
  }

  /**
   * Add the collection elements iterated over in potentially Mcoe-obligation-fulfilling loops to
   * the store.
   */
  @Override
  public AccumulationStore initialStore(
      UnderlyingAST underlyingAST, List<LocalVariableNode> parameters) {
    AccumulationStore store = super.initialStore(underlyingAST, parameters);
    CalledMethodsAnnotatedTypeFactory cmAtf =
        (CalledMethodsAnnotatedTypeFactory) this.analysis.getTypeFactory();
    for (CalledMethodsAnnotatedTypeFactory.PotentiallyFulfillingLoop loop :
        CalledMethodsAnnotatedTypeFactory.getPotentiallyFulfillingLoops()) {
      IteratedCollectionElement collectionElementJx =
          new IteratedCollectionElement(loop.collectionElementNode, loop.collectionElementTree);
      store.insertValue(collectionElementJx, cmAtf.top);
    }
    return store;
  }

  /**
   * Checks whether the given node is the element of a collection iterated over in a
   * potentially-Mcoe-obligation-fulfilling loop. If it is, accumulates the called methods to this
   * collection element.
   *
   * @param valuesAsList the list of called methods
   * @param result the transfer result
   * @param node a cfg node
   */
  private void updateStoreForIteratedCollectionElement(
      List<String> valuesAsList,
      TransferResult<AccumulationValue, AccumulationStore> result,
      Node node) {
    IteratedCollectionElement collectionElement =
        result.getRegularStore().getIteratedCollectionElement(node, node.getTree());
    if (collectionElement != null) {
      AccumulationValue flowValue = result.getRegularStore().getValue(collectionElement);
      if (flowValue != null) {
        // Dataflow has already recorded information about the target.  Integrate it into
        // the list of values in the new annotation.
        AnnotationMirrorSet flowAnnos = flowValue.getAnnotations();
        assert flowAnnos.size() <= 1;
        for (AnnotationMirror anno : flowAnnos) {
          if (atypeFactory.isAccumulatorAnnotation(anno)) {
            List<String> oldFlowValues =
                AnnotationUtils.getElementValueArray(anno, calledMethodsValueElement, String.class);
            // valuesAsList cannot have its length changed -- it is backed by an
            // array.  getElementValueArray returns a new, modifiable list.
            oldFlowValues.addAll(valuesAsList);
            valuesAsList = oldFlowValues;
          }
        }
      }
      AnnotationMirror newAnno = atypeFactory.createAccumulatorAnnotation(valuesAsList);
      if (result.containsTwoStores()) {
        updateValueAndInsertIntoStore(result.getThenStore(), collectionElement, valuesAsList);
        updateValueAndInsertIntoStore(result.getElseStore(), collectionElement, valuesAsList);
      } else {
        updateValueAndInsertIntoStore(result.getRegularStore(), collectionElement, valuesAsList);
      }
      exceptionalStores.forEach(
          (tm, s) ->
              s.replaceValue(
                  collectionElement,
                  analysis.createSingleAnnotationValue(newAnno, collectionElement.getType())));
    }
  }

  // private LocalVariableNode createLocalVariableNode(Node node) {
  //   LocalVariableNode localVariableNode = null;
  //   VariableTree temp = createTemporaryVar(node);
  //   if (temp != null) {
  //     IdentifierTree identifierTree = treeBuilder.buildVariableUse(temp);
  //     localVariableNode = new LocalVariableNode(identifierTree);
  //     localVariableNode.setInSource(true);
  //   }
  //   return localVariableNode;
  // }

  // /**
  //  * Creates a variable declaration for the given expression node, if possible.
  //  *
  //  * <p>Note that error reporting code assumes that the names of temporary variables are not
  // legal
  //  * Java identifiers (see <a
  //  * href="https://docs.oracle.com/javase/specs/jls/se17/html/jls-3.html#jls-3.8">JLS 3.8</a>).
  // The
  //  * temporary variable names generated here include an {@code '-'} character to make the names
  //  * invalid.
  //  *
  //  * @param node an expression node
  //  * @return a variable tree for the node, or null if an appropriate containing element cannot be
  //  *     located
  //  */
  // private @Nullable VariableTree createTemporaryVar(Node node) {
  //   ExpressionTree tree = (ExpressionTree) node.getTree();
  //   TypeMirror treeType = TreeUtils.typeOf(tree);
  //   Element enclosingElement;
  //   TreePath path = atypeFactory.getPath(tree);
  //   if (path == null) {
  //     enclosingElement = TreeUtils.elementFromUse(tree).getEnclosingElement();
  //   } else {
  //     // Issue 6473
  //     // Adjusts handling of nearest enclosing element for temporary variables.
  //     // This approach ensures the correct enclosing element (method or class) is determined.
  //     enclosingElement = TreePathUtil.findNearestEnclosingElement(path);
  //   }
  //   if (enclosingElement == null) {
  //     return null;
  //   }
  //   // Declare and initialize a new, unique variable
  //   VariableTree tmpVarTree =
  //       treeBuilder.buildVariableDecl(treeType, uniqueName("temp-var"), enclosingElement, tree);
  //   return tmpVarTree;
  // }

  @Override
  public TransferResult<AccumulationValue, AccumulationStore> visitTernaryExpression(
      TernaryExpressionNode node, TransferInput<AccumulationValue, AccumulationStore> input) {
    TransferResult<AccumulationValue, AccumulationStore> result =
        super.visitTernaryExpression(node, input);
    if (!TypesUtils.isPrimitiveOrBoxed(node.getType())) {
      // Add the synthetic variable created during CFG construction to the temporary
      // variable map (rather than creating a redundant temp var)
      cmAtf.addTempVar(node.getTernaryExpressionVar(), node.getTree());
    }
    return result;
  }

  @Override
  public TransferResult<AccumulationValue, AccumulationStore> visitSwitchExpressionNode(
      SwitchExpressionNode node, TransferInput<AccumulationValue, AccumulationStore> input) {
    TransferResult<AccumulationValue, AccumulationStore> result =
        super.visitSwitchExpressionNode(node, input);
    if (!TypesUtils.isPrimitiveOrBoxed(node.getType())) {
      // Add the synthetic variable created during CFG construction to the temporary
      // variable map (rather than creating a redundant temp var)
      cmAtf.addTempVar(node.getSwitchExpressionVar(), node.getTree());
    }
    return result;
  }

  @Override
  public TransferResult<AccumulationValue, AccumulationStore> visitMethodInvocation(
      MethodInvocationNode node, TransferInput<AccumulationValue, AccumulationStore> input) {

    TransferResult<AccumulationValue, AccumulationStore> result =
        super.visitMethodInvocation(node, input);

    handleCreatesMustCallFor(node, result);
    updateStoreWithTempVar(result, node);

    // If there is a temporary variable for the receiver, update its type.
    Node receiver = node.getTarget().getReceiver();
    MustCallAnnotatedTypeFactory mcAtf = cmAtf.getMustCallAnnotatedTypeFactory();
    Node accumulationTarget = mcAtf.getTempVar(receiver);
    if (accumulationTarget != null) {
      String methodName = node.getTarget().getMethod().getSimpleName().toString();
      methodName = cmAtf.adjustMethodNameUsingValueChecker(methodName, node.getTree());
      accumulate(accumulationTarget, result, methodName);
    }

    return result;
  }

  /**
   * Clears the called-methods store of all information about the target if an @CreatesMustCallFor
   * method is invoked and the type factory can create obligations. Otherwise, does nothing.
   *
   * @param n a method invocation
   * @param result the transfer result whose stores should be cleared of information
   */
  private void handleCreatesMustCallFor(
      MethodInvocationNode n, TransferResult<AccumulationValue, AccumulationStore> result) {
    if (!cmAtf.canCreateObligations()) {
      return;
    }

    List<JavaExpression> targetExprs =
        CreatesMustCallForToJavaExpression.getCreatesMustCallForExpressionsAtInvocation(
            n, cmAtf, cmAtf);
    AnnotationMirror defaultType = cmAtf.top;
    for (JavaExpression targetExpr : targetExprs) {
      AccumulationValue defaultTypeValue =
          analysis.createSingleAnnotationValue(defaultType, targetExpr.getType());
      if (result.containsTwoStores()) {
        result.getThenStore().replaceValue(targetExpr, defaultTypeValue);
        result.getElseStore().replaceValue(targetExpr, defaultTypeValue);
      } else {
        result.getRegularStore().replaceValue(targetExpr, defaultTypeValue);
      }
    }
  }

  @Override
  public TransferResult<AccumulationValue, AccumulationStore> visitObjectCreation(
      ObjectCreationNode node, TransferInput<AccumulationValue, AccumulationStore> input) {
    TransferResult<AccumulationValue, AccumulationStore> result =
        super.visitObjectCreation(node, input);
    updateStoreWithTempVar(result, node);
    return result;
  }

  /**
   * This method either creates or looks up the temp var t for node, and then updates the store to
   * give t the same type as node. Temporary variables are supported for expressions throughout this
   * checker (and the Must Call Checker) to enable refinement of their types. See the documentation
   * of {@link MustCallConsistencyAnalyzer} for more details.
   *
   * @param node the node to be assigned to a temporary variable
   * @param result the transfer result containing the store to be modified
   */
  public void updateStoreWithTempVar(
      TransferResult<AccumulationValue, AccumulationStore> result, Node node) {
    // If the node is a void method invocation then do not create temp vars for it.
    if (node instanceof MethodInvocationNode) {
      MethodInvocationTree methodInvocationTree = (MethodInvocationTree) node.getTree();
      ExecutableElement executableElement = TreeUtils.elementFromUse(methodInvocationTree);
      if (ElementUtils.getType(executableElement).getKind() == TypeKind.VOID) {
        return;
      }
    }
    // Must-call obligations on primitives are not supported.
    if (!TypesUtils.isPrimitiveOrBoxed(node.getType())) {
      MustCallAnnotatedTypeFactory mcAtf = cmAtf.getMustCallAnnotatedTypeFactory();
      LocalVariableNode temp = mcAtf.getTempVar(node);
      if (temp != null) {
        cmAtf.addTempVar(temp, node.getTree());
        JavaExpression localExp = JavaExpression.fromNode(temp);
        AnnotationMirror anm =
            cmAtf.getAnnotatedType(node.getTree()).getPrimaryAnnotationInHierarchy(cmAtf.top);
        if (anm == null) {
          anm = cmAtf.top;
        }
        if (result.containsTwoStores()) {
          result.getThenStore().insertValue(localExp, anm);
          result.getElseStore().insertValue(localExp, anm);
        } else {
          result.getRegularStore().insertValue(localExp, anm);
        }
      }
    }
  }
}
