package org.checkerframework.checker.resourceleak;

import com.sun.source.tree.Tree;
import org.checkerframework.checker.mustcallonelements.MustCallOnElementsAnnotatedTypeFactory;
import org.checkerframework.checker.mustcallonelements.MustCallOnElementsChecker;
import org.checkerframework.checker.mustcallonelements.qual.OwningArray;
import org.checkerframework.checker.resourceleak.RLCUtils.MethodSigType;
import org.checkerframework.checker.rlccalledmethods.RLCCalledMethodsAnnotatedTypeFactory;
import org.checkerframework.checker.rlccalledmethods.RLCCalledMethodsAnnotatedTypeFactory.McoeObligationAlteringLoop;
import org.checkerframework.checker.rlccalledmethods.RLCCalledMethodsAnnotatedTypeFactory.McoeObligationAlteringLoop.LoopKind;
import org.checkerframework.checker.rlccalledmethods.RLCCalledMethodsAnnotatedTypeFactory.PotentiallyAssigningLoop;
import org.checkerframework.checker.rlccalledmethods.RLCCalledMethodsAnnotatedTypeFactory.PotentiallyFulfillingLoop;
import org.checkerframework.checker.rlccalledmethods.RLCCalledMethodsChecker;
import org.checkerframework.dataflow.analysis.TransferInput;
import org.checkerframework.dataflow.analysis.TransferResult;
import org.checkerframework.dataflow.cfg.node.LessThanNode;
import org.checkerframework.dataflow.cfg.node.MethodAccessNode;
import org.checkerframework.dataflow.cfg.node.MethodInvocationNode;
import org.checkerframework.dataflow.cfg.node.Node;
import org.checkerframework.dataflow.expression.JavaExpression;
import org.checkerframework.dataflow.util.NodeUtils;
import org.checkerframework.framework.flow.CFAbstractAnalysis;
import org.checkerframework.framework.flow.CFStore;
import org.checkerframework.framework.flow.CFTransfer;
import org.checkerframework.framework.flow.CFValue;
import org.checkerframework.framework.type.AnnotatedTypeFactory;
import org.checkerframework.javacutil.BugInCF;
import org.checkerframework.javacutil.TreeUtils;

/**
 * An abstract transfer function that is implemented by the transfer functions of the {@code
 * MustCallOnElementsChecker} and {@code CalledMethodsOnElementsChecker}.
 *
 * <p>The class aggregates shared logic between the implementing transfer functions to call the
 * corresponding abstract transformer when a method is called on an {@code @OwningArray}, so that
 * the implementing transfer functions only have to implement these transformers.
 */
public abstract class CollectionTransfer extends CFTransfer {

  /** The type factory of the implementing subclass passed in the constructor. */
  private final AnnotatedTypeFactory atypeFactory;

  /**
   * Constructs a CollectionTransfer.
   *
   * @param analysis the analysis of the implementing subclass
   * @param atf the type factory of the implementing subclass
   */
  public CollectionTransfer(
      CFAbstractAnalysis<CFValue, CFStore, CFTransfer> analysis, AnnotatedTypeFactory atf) {
    super(analysis);
    this.atypeFactory = atf;
  }

  /**
   * Updates the type of the corresponding collection in the else store of the {@code
   * TransferResult} based on the information in the loop wrapper.
   *
   * @param loop the {@code PotentiallyAssigningLoop}
   * @param res the transfer result to update
   * @return the updated transfer result
   */
  protected abstract TransferResult<CFValue, CFStore> transformAssigningLoop(
      PotentiallyAssigningLoop loop, TransferResult<CFValue, CFStore> res);

  /**
   * Updates the type of the corresponding collection in the else store of the {@code
   * TransferResult} based on the information in the loop wrapper.
   *
   * @param loop the {@code PotentiallyFulfillingLoop}
   * @param res the transfer result to update
   * @return the updated transfer result
   */
  protected abstract TransferResult<CFValue, CFStore> transformFulfillingLoop(
      PotentiallyFulfillingLoop loop, TransferResult<CFValue, CFStore> res);

  /**
   * The abstract transformer for {@code Collection.add(E)}
   *
   * @param node the {@code MethodInvocationNode}
   * @param res the {@code TransferResult} containing the store to be edited
   * @param receiver JavaExpression of the collection, whose type should be changed
   * @return updated {@code TransferResult}
   */
  protected abstract TransferResult<CFValue, CFStore> transformCollectionAdd(
      MethodInvocationNode node, TransferResult<CFValue, CFStore> res, JavaExpression receiver);

  /**
   * Responsible for calling abstract transformers of all methods called on a collection.
   *
   * @param node a {@code MethodInvocationNode}
   * @param input the {@code TransferInput}
   * @return updated {@code TransferResult}
   */
  @Override
  public TransferResult<CFValue, CFStore> visitMethodInvocation(
      MethodInvocationNode node, TransferInput<CFValue, CFStore> input) {
    TransferResult<CFValue, CFStore> res = super.visitMethodInvocation(node, input);

    MethodAccessNode methodAccessNode = node.getTarget();
    Node receiver = methodAccessNode.getReceiver();
    Tree receiverTree = receiver.getTree();
    boolean isCollection =
        receiverTree != null
            && MustCallOnElementsAnnotatedTypeFactory.isCollection(receiverTree, atypeFactory);
    boolean isOwningArray =
        receiverTree != null
            && TreeUtils.elementFromTree(receiverTree) != null
            && TreeUtils.elementFromTree(receiverTree).getAnnotation(OwningArray.class) != null;
    JavaExpression receiverJx = JavaExpression.fromNode(receiver);
    boolean isRoAlias =
        receiverTree != null
            && res.getRegularStore() != null // ensure store exists
            && ((MustCallOnElementsAnnotatedTypeFactory)
                    RLCUtils.getTypeFactory(MustCallOnElementsChecker.class, atypeFactory))
                .isMustCallOnElementsUnknown(res.getRegularStore(), receiverTree);
    if (isCollection && (isOwningArray || isRoAlias)) {
      MethodSigType methodSigType = RLCUtils.getMethodSigType(methodAccessNode.getMethod());
      switch (methodSigType) {
        case SAFE:
          break;
        case UNSAFE:
          break;
        case ADD_E:
          return transformCollectionAdd(node, res, receiverJx);
          // case "add(int,E)":
          //   return transformCollectionAddWithIdx(node, res, parameters);
        default:
          throw new BugInCF("unhandled MethodSigType " + methodSigType);
      }
    }
    return res;
  }

  /**
   * Checks whether the {@code LessThanNode} is the condition of a loop that was pattern-matched in
   * a prior pass and calls the corresponding abstract transformer if that is the case.
   */
  @Override
  public TransferResult<CFValue, CFStore> visitLessThan(
      LessThanNode node, TransferInput<CFValue, CFStore> input) {
    TransferResult<CFValue, CFStore> res = super.visitLessThan(node, input);
    McoeObligationAlteringLoop loop =
        MustCallOnElementsAnnotatedTypeFactory.getLoopForCondition(node.getTree());
    if (loop != null) {
      if (loop.loopKind == LoopKind.ASSIGNING) {
        res = transformAssigningLoop((PotentiallyAssigningLoop) loop, res);
      } else if (loop.loopKind == LoopKind.FULFILLING) {
        res = transformFulfillingLoop((PotentiallyFulfillingLoop) loop, res);
      }
    }
    return res;
  }

  /**
   * Removes casts from {@code node} and returns the temp-var corresponding to it if it exists or
   * else {@code node} with removed casts.
   *
   * @param node the node
   * @return the temp-var corresponding to {@code node} with casts removed if it exists or else
   *     {@code node} with casts removed
   */
  protected Node getNodeOrTempVar(Node node) {
    node = NodeUtils.removeCasts(node);
    Node tempVarForNode =
        ((RLCCalledMethodsAnnotatedTypeFactory)
                RLCUtils.getTypeFactory(RLCCalledMethodsChecker.class, atypeFactory))
            .getTempVarForNode(node);
    if (tempVarForNode != null) {
      return tempVarForNode;
    }
    return node;
  }
}
