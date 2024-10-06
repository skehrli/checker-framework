package org.checkerframework.checker.resourceleak;

import com.sun.source.tree.Tree;
import java.util.List;
import java.util.stream.Collectors;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.VariableElement;
import org.checkerframework.checker.mustcallonelements.MustCallOnElementsAnnotatedTypeFactory;
import org.checkerframework.checker.mustcallonelements.MustCallOnElementsChecker;
import org.checkerframework.checker.mustcallonelements.qual.OwningCollection;
import org.checkerframework.checker.nullness.qual.NonNull;
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
 * corresponding abstract transformer when a method is called on an {@code @OwningCollection}, so
 * that the implementing transfer functions only have to implement these transformers.
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
   * Defines the different classifications of method signatures with respect to their safety in the
   * context of them being called on an {@code OwningCollection} receiver.
   */
  public static enum MethodSigType {
    SAFE, /* Methods that are handled and cosidered safe. */
    UNSAFE, /* Methods that are either not handled or handled and cosidered unsafe. */
    ADD_E, /* All other method signatures require special handling. */
    ADD_INT_E,
    SET
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
      case "add(int,E)":
        return MethodSigType.ADD_INT_E;
      case "set(int,E)":
        return MethodSigType.SET;
      case "isEmpty()":
      case "iterator()":
      case "size()":
      case "get(int)":
        return MethodSigType.SAFE;
      default:
        System.out.println("unhandled method " + methodSignature);
        return MethodSigType.UNSAFE;
    }
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
  protected abstract TransferResult<CFValue, CFStore> transformCollectionAddWithIdx(
      MethodInvocationNode node, TransferResult<CFValue, CFStore> res, JavaExpression receiver);

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
   * The abstract transformer for {@code Collection.set(int, E)}
   *
   * @param node the {@code MethodInvocationNode}
   * @param res the {@code TransferResult} containing the store to be edited
   * @param receiver JavaExpression of the collection, whose type should be changed
   * @return updated {@code TransferResult}
   */
  protected abstract TransferResult<CFValue, CFStore> transformListSet(
      MethodInvocationNode node, TransferResult<CFValue, CFStore> res, JavaExpression receiver);

  /**
   * Responsible for:
   *
   * <ol>
   *   <li>Checking whether the {@code MethodInvocationNode} corresponds to a method being called on
   *       a receiver collection and then call the corresponding abstract transformer if this is the
   *       case.
   *   <li>Checking whether the {@code MethodInvocationNode} is the condition of an {@code
   *       MustCallOnElements} obligation fulfilling or assigning for loop and then call the
   *       corresponding abstract transformer if this is the case.
   * </ol>
   *
   * @param node a {@code MethodInvocationNode}
   * @param input the {@code TransferInput}
   * @return updated {@code TransferResult}
   */
  @Override
  public TransferResult<CFValue, CFStore> visitMethodInvocation(
      MethodInvocationNode node, TransferInput<CFValue, CFStore> input) {
    TransferResult<CFValue, CFStore> res = super.visitMethodInvocation(node, input);

    McoeObligationAlteringLoop loop =
        MustCallOnElementsAnnotatedTypeFactory.getLoopForCondition(node);
    if (loop != null) {
      if (loop.loopKind == LoopKind.ASSIGNING) {
        res = transformAssigningLoop((PotentiallyAssigningLoop) loop, res);
      } else if (loop.loopKind == LoopKind.FULFILLING) {
        res = transformFulfillingLoop((PotentiallyFulfillingLoop) loop, res);
      }
    }

    MethodAccessNode methodAccessNode = node.getTarget();
    Node receiver = methodAccessNode.getReceiver();
    Tree receiverTree = receiver.getTree();
    boolean isCollection =
        receiverTree != null && RLCUtils.isCollection(receiverTree, atypeFactory);
    boolean isOwningCollection =
        receiverTree != null
            && TreeUtils.elementFromTree(receiverTree) != null
            && TreeUtils.elementFromTree(receiverTree).getAnnotation(OwningCollection.class)
                != null;
    JavaExpression receiverJx = JavaExpression.fromNode(receiver);
    boolean isRoAlias =
        receiverTree != null
            && res.getRegularStore() != null // ensure store exists
            && ((MustCallOnElementsAnnotatedTypeFactory)
                    RLCUtils.getTypeFactory(MustCallOnElementsChecker.class, atypeFactory))
                .isMustCallOnElementsUnknown(res.getRegularStore(), receiverTree);
    if (isCollection && (isOwningCollection || isRoAlias)) {
      MethodSigType methodSigType = getMethodSigType(methodAccessNode.getMethod());
      switch (methodSigType) {
        case SAFE:
          break;
        case UNSAFE:
          break;
        case ADD_E:
          res = transformCollectionAdd(node, res, receiverJx);
          break;
        case ADD_INT_E:
          res = transformCollectionAddWithIdx(node, res, receiverJx);
          break;
        case SET:
          res = transformListSet(node, res, receiverJx);
          break;
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
        MustCallOnElementsAnnotatedTypeFactory.getLoopForCondition(node);
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
