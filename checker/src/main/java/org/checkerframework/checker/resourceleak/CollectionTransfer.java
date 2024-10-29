package org.checkerframework.checker.resourceleak;

import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.Tree;
import java.util.List;
import java.util.stream.Collectors;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import org.checkerframework.checker.mustcall.MustCallAnnotatedTypeFactory;
import org.checkerframework.checker.mustcall.MustCallChecker;
import org.checkerframework.checker.mustcallonelements.MustCallOnElementsAnnotatedTypeFactory;
import org.checkerframework.checker.mustcallonelements.MustCallOnElementsChecker;
import org.checkerframework.checker.mustcallonelements.MustCallOnElementsTransfer;
import org.checkerframework.checker.mustcallonelements.qual.CollectionAlias;
import org.checkerframework.checker.mustcallonelements.qual.OwningCollection;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.rlccalledmethods.RLCCalledMethodsAnnotatedTypeFactory;
import org.checkerframework.checker.rlccalledmethods.RLCCalledMethodsAnnotatedTypeFactory.McoeObligationAlteringLoop;
import org.checkerframework.checker.rlccalledmethods.RLCCalledMethodsAnnotatedTypeFactory.McoeObligationAlteringLoop.LoopKind;
import org.checkerframework.checker.rlccalledmethods.RLCCalledMethodsAnnotatedTypeFactory.PotentiallyAssigningLoop;
import org.checkerframework.checker.rlccalledmethods.RLCCalledMethodsAnnotatedTypeFactory.PotentiallyFulfillingLoop;
import org.checkerframework.checker.rlccalledmethods.RLCCalledMethodsChecker;
import org.checkerframework.dataflow.analysis.RegularTransferResult;
import org.checkerframework.dataflow.analysis.TransferInput;
import org.checkerframework.dataflow.analysis.TransferResult;
import org.checkerframework.dataflow.cfg.node.LessThanNode;
import org.checkerframework.dataflow.cfg.node.LocalVariableNode;
import org.checkerframework.dataflow.cfg.node.MethodAccessNode;
import org.checkerframework.dataflow.cfg.node.MethodInvocationNode;
import org.checkerframework.dataflow.cfg.node.Node;
import org.checkerframework.dataflow.cfg.node.ObjectCreationNode;
import org.checkerframework.dataflow.expression.JavaExpression;
import org.checkerframework.dataflow.util.NodeUtils;
import org.checkerframework.framework.flow.CFAbstractAnalysis;
import org.checkerframework.framework.flow.CFStore;
import org.checkerframework.framework.flow.CFTransfer;
import org.checkerframework.framework.flow.CFValue;
import org.checkerframework.framework.type.AnnotatedTypeFactory;
import org.checkerframework.javacutil.ElementUtils;
import org.checkerframework.javacutil.TreeUtils;
import org.checkerframework.javacutil.TypesUtils;

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

    /* method signatures that require special handling. */
    CLEAR,
    ADD_E,
    ADD_INT_E,
    SET,
    ITERATOR,

    /* Iterator methods. */
    ITER_REMOVE,
    ITER_NEXT
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
      case "clear()":
        return MethodSigType.CLEAR;
      case "add(E)":
        return MethodSigType.ADD_E;
      case "add(int,E)":
        return MethodSigType.ADD_INT_E;
      case "set(int,E)":
        return MethodSigType.SET;
      case "iterator()":
        return MethodSigType.ITERATOR;
      case "isEmpty()":
      case "size()":
      case "get(int)":
      case "contains(Object)":
      case "containsAll(Collection<?>)":
      case "equals(Object)":
      case "hashCode()":
      case "indexOf(Object)":
      case "lastIndexOf(Object)":
      case "remove(int)":
      case "sort(Comparator<? super E>)":
        return MethodSigType.SAFE;
      default:
        System.out.println("unhandled method " + methodSignature);
        return MethodSigType.UNSAFE;
    }
  }

  /**
   * Returns the {@code MethodSigType} of the passed {@code method} called on an {@code Iterator}.
   * {@code MethodSigType} classifies method signatures by their safety in the context of this
   * method being called on an {@code Iterator}.
   *
   * <p>This method exists since multiple code locations must have a consistent method
   * classification, such as the consistency analyzer to handle the change of obligation through the
   * method call and the {@code MustCallOnElements} transfer function to decide the type change of
   * the associated collection.
   *
   * @param method the method to consider
   * @return the {@code MethodSigType} of the passed {@code method}
   */
  public static @NonNull MethodSigType getIteratorMethodSigType(ExecutableElement method) {
    List<? extends VariableElement> parameters = method.getParameters();
    String methodSignature =
        method.getSimpleName().toString()
            + parameters.stream()
                .map(param -> param.asType().toString())
                .collect(Collectors.joining(",", "(", ")"));
    switch (methodSignature) {
      case "next()":
        return MethodSigType.ITER_NEXT;
      case "remove()":
        return MethodSigType.ITER_REMOVE;
      case "hasNext()":
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
   * The abstract transformer for {@code Collection.clear()}
   *
   * @param node the {@code MethodInvocationNode}
   * @param res the {@code TransferResult} containing the store to be edited
   * @param receiver JavaExpression of the collection, whose type should be changed
   * @return updated {@code TransferResult}
   */
  protected abstract TransferResult<CFValue, CFStore> transformCollectionClear(
      MethodInvocationNode node, TransferResult<CFValue, CFStore> res, JavaExpression receiver);

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
   * Abstract transformer for when an {@code @OwningColletion} variable is passed to an
   * {@code @OwningCollection} method parameter (constructor or method invocation).
   *
   * @param store the store to update
   * @param collectionArg the {@code @OwningCollection} argument
   */
  protected abstract void transformOwningCollectionArg(CFStore store, JavaExpression collectionArg);

  /**
   * Responsible for:
   *
   * <ol>
   *   <li>Updating store with method invocation temp-var.
   *   <li>Finding {@code @OwningCollection} arguments and calling the respective abstract
   *       transformer on them.
   *   <li>Enforcing rules regarding {@code @OwningCollection} parameter/argument annotations.
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

    updateStoreWithTempVar(res, node);

    ExecutableElement method = node.getTarget().getMethod();
    List<Node> args = node.getArguments();
    res = checkOwningCollectionArgs(method, args, res);

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
    boolean isResourceCollection = isCollection && (isOwningCollection || isRoAlias);
    if (isResourceCollection) {
      MethodSigType methodSigType = getMethodSigType(methodAccessNode.getMethod());
      switch (methodSigType) {
        case ADD_E:
          res = transformCollectionAdd(node, res, receiverJx);
          break;
        case ADD_INT_E:
          res = transformCollectionAddWithIdx(node, res, receiverJx);
          break;
        case SET:
          res = transformListSet(node, res, receiverJx);
          break;
        case CLEAR:
          res = transformCollectionClear(node, res, receiverJx);
          break;
        case ITERATOR:

          /*
           * Unsafe methods are handled in the consistency analyzer. No handling required here.
           */
        case SAFE:
        case UNSAFE:

          /*
           * The following are methods called on an Iterator (and not a Collection)
           * and need no handling here. They are included so that we don't need a default
           * case.
           */
        case ITER_NEXT:
        case ITER_REMOVE:
          break;
      }
    }

    boolean isIterator = receiverTree != null && RLCUtils.isIterator(receiverTree, atypeFactory);
    if (isIterator) {
      MethodSigType methodSigType = getIteratorMethodSigType(methodAccessNode.getMethod());
      switch (methodSigType) {
        case SAFE:
          break;
        case UNSAFE:
          break;
        case ITER_NEXT:
          break;
        case ITER_REMOVE:
          break;

          /*
           * The following are methods called on a Collection (and not an Iterator)
           * and need no handling here. They are included so that we don't need a default
           * case.
           */
        case ADD_E:
        case CLEAR:
        case ADD_INT_E:
        case SET:
        case ITERATOR:
          break;
      }
    }
    return res;
  }

  /**
   * Responsible for:
   *
   * <ol>
   *   <li>Updating store with constructor invocation temp-var.
   *   <li>Finding {@code @OwningCollection} arguments and calling the respective abstract
   *       transformer on them.
   *   <li>Enforcing rules regarding {@code @OwningCollection} parameter/argument annotations.
   * </ol>
   *
   * @param node an {@code ObjectCreationNode}
   * @param input the {@code TransferInput}
   * @return updated {@code TransferResult}
   */
  @Override
  public TransferResult<CFValue, CFStore> visitObjectCreation(
      ObjectCreationNode node, TransferInput<CFValue, CFStore> input) {
    TransferResult<CFValue, CFStore> res = super.visitObjectCreation(node, input);

    updateStoreWithTempVar(res, node);

    ExecutableElement constructor = TreeUtils.elementFromUse(node.getTree());
    List<Node> args = node.getArguments();
    res = checkOwningCollectionArgs(constructor, args, res);

    return res;
  }

  /**
   * Checks that every argument is {@code @OwningCollection} if and only if its corresponding
   * parameter is and reports an error if this is violated. Calls the dedicated abstract transformer
   * for {@code @OwningCollection} arguments.
   *
   * @param method the method whose arguments/parameters are checked
   * @param args the list of method arguments
   * @param res the transfer result so far
   * @return the updated transfer result
   */
  private TransferResult<CFValue, CFStore> checkOwningCollectionArgs(
      ExecutableElement method, List<Node> args, TransferResult<CFValue, CFStore> res) {
    // ensure method call args respects ownership consistency
    // also, empty mcoe type of @OwningCollection args that are passed as @OwningCollection params
    List<? extends VariableElement> params = method.getParameters();

    CFStore store = res.getRegularStore();
    for (int i = 0; i < Math.min(args.size(), params.size()); i++) {
      VariableElement param = params.get(i);
      Node arg = args.get(i);
      arg = getNodeOrTempVar(arg);
      Element argElt = arg.getTree() != null ? TreeUtils.elementFromTree(arg.getTree()) : null;

      boolean argIsOwningCollection =
          argElt != null && argElt.getAnnotation(OwningCollection.class) != null;
      boolean paramIsOwningCollection =
          param != null && param.getAnnotation(OwningCollection.class) != null;
      boolean paramIsCollectionAlias =
          param != null && param.getAnnotation(CollectionAlias.class) != null;
      boolean argIsMcoeUnknown =
          ((MustCallOnElementsAnnotatedTypeFactory)
                  RLCUtils.getTypeFactory(MustCallOnElementsChecker.class, atypeFactory))
              .isMustCallOnElementsUnknown(res.getRegularStore(), arg.getTree());
      boolean argIsField = argElt != null && argElt.getKind() == ElementKind.FIELD;

      if (argIsOwningCollection && argIsField) {
        reportError(
            arg.getTree(),
            "illegal.ownership.transfer",
            "Cannot transfer ownership from an @OwningCollection field.");
      } else {
        if (paramIsOwningCollection) {
          if (argIsMcoeUnknown) {
            reportError(arg.getTree(), "missing.argument.ownership", param, arg.getTree());
          } else if (argIsOwningCollection) {
            JavaExpression argCollection = JavaExpression.fromNode(arg);
            transformOwningCollectionArg(store, argCollection);
          } else {
            reportError(arg.getTree(), "missing.argument.ownership", param, arg.getTree());
          }
        } else if (paramIsCollectionAlias) {
          if (argIsMcoeUnknown) {
            // write-disabled alias is able to be passed to CollectionAlias
          } else if (argIsOwningCollection) {
            // ownership stays at call-site, no transfer
          } else {
            reportWarning(
                arg.getTree(), "unnecessary.collectionalias.annotation", param, arg.getTree());
          }
        } else {
          // in this case, param is not allowed hold a resource collection,
          // but arg is a resource collection
          if (argIsMcoeUnknown || argIsOwningCollection) {
            reportError(
                arg.getTree(), "missing.collection.ownership.annotation", arg.getTree(), param);
          }
        }
      }
    }
    return new RegularTransferResult<CFValue, CFStore>(res.getResultValue(), store);
  }

  /**
   * Wrapper for reporting errors, such that only one of both subclasses (MustCallOnElementsTransfer
   * and CalledMethodsOnElementsTransfer) reports an error.
   */
  private void reportError(Tree location, String code, Object... args) {
    if (this instanceof MustCallOnElementsTransfer) {
      atypeFactory.getChecker().reportError(location, code, args);
    }
  }

  /**
   * Wrapper for reporting warnings, such that only one of both subclasses
   * (MustCallOnElementsTransfer and CalledMethodsOnElementsTransfer) reports a warning.
   */
  private void reportWarning(Tree location, String code, Object... args) {
    if (this instanceof MustCallOnElementsTransfer) {
      atypeFactory.getChecker().reportWarning(location, code, args);
    }
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

  /**
   * This method either creates or looks up the temp var t for node, and then updates the store to
   * give t the same type as node. Temporary variables are supported for expressions throughout this
   * checker (and the Must Call Checker) to enable refinement of their types. See the documentation
   * of {@link MustCallConsistencyAnalyzer} for more details.
   *
   * @param node the node to be assigned to a temporary variable
   * @param result the transfer result containing the store to be modified
   */
  public void updateStoreWithTempVar(TransferResult<CFValue, CFStore> result, Node node) {
    // If the node is a void method invocation then do not create temp vars for it.
    if (node instanceof MethodInvocationNode) {
      MethodInvocationTree methodInvocationTree = (MethodInvocationTree) node.getTree();
      ExecutableElement executableElement = TreeUtils.elementFromUse(methodInvocationTree);
      if (ElementUtils.getType(executableElement).getKind() == TypeKind.VOID) {
        return;
      }
    }
    // mcoe obligations on primitives are not supported.
    if (!TypesUtils.isPrimitiveOrBoxed(node.getType())) {
      MustCallAnnotatedTypeFactory mcAtf =
          (MustCallAnnotatedTypeFactory)
              RLCUtils.getTypeFactory(MustCallChecker.class, atypeFactory);
      LocalVariableNode temp = mcAtf.getTempVar(node);
      if (temp != null) {
        // cmAtf.addTempVar(temp, node.getTree());
        JavaExpression localExp = JavaExpression.fromNode(temp);
        AnnotationMirror top =
            atypeFactory.getQualifierHierarchy().getTopAnnotations().iterator().next();
        AnnotationMirror anm =
            atypeFactory.getAnnotatedType(node.getTree()).getPrimaryAnnotationInHierarchy(top);
        if (anm == null) {
          anm = top;
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
