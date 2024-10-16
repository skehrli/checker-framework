package org.checkerframework.checker.calledmethodsonelements;

import com.sun.source.tree.ExpressionTree;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.ExecutableElement;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.checker.resourceleak.CollectionTransfer;
import org.checkerframework.checker.resourceleak.ResourceLeakChecker;
import org.checkerframework.checker.rlccalledmethods.RLCCalledMethodsAnnotatedTypeFactory.PotentiallyAssigningLoop;
import org.checkerframework.checker.rlccalledmethods.RLCCalledMethodsAnnotatedTypeFactory.PotentiallyFulfillingLoop;
import org.checkerframework.dataflow.analysis.ConditionalTransferResult;
import org.checkerframework.dataflow.analysis.RegularTransferResult;
import org.checkerframework.dataflow.analysis.TransferResult;
import org.checkerframework.dataflow.cfg.node.MethodInvocationNode;
import org.checkerframework.dataflow.expression.JavaExpression;
import org.checkerframework.framework.flow.CFStore;
import org.checkerframework.framework.flow.CFValue;
import org.checkerframework.javacutil.AnnotationBuilder;
import org.checkerframework.javacutil.AnnotationUtils;
import org.plumelib.util.CollectionsPlume;

/** A transfer function that accumulates the names of methods called. */
public class CalledMethodsOnElementsTransfer extends CollectionTransfer {
  /**
   * The element for the CalledMethodsOnElements annotation's value element. Stored in a field in
   * this class to prevent the need to cast to CalledMethodsOnElements ATF every time it's used.
   */
  private final ExecutableElement calledMethodsOnElementsValueElement;

  /** The type factory. */
  private final CalledMethodsOnElementsAnnotatedTypeFactory atypeFactory;

  /** The processing environment. */
  private final ProcessingEnvironment env;

  /**
   * True if -AenableWpiForRlc was passed on the command line. See {@link
   * ResourceLeakChecker#ENABLE_WPI_FOR_RLC}.
   */
  private final boolean enableWpiForRlc;

  /**
   * Create a new CalledMethodsOnElementsTransfer.
   *
   * @param analysis the analysis
   */
  public CalledMethodsOnElementsTransfer(CalledMethodsOnElementsAnalysis analysis) {
    super(analysis, analysis.getTypeFactory());
    if (analysis.getTypeFactory() instanceof CalledMethodsOnElementsAnnotatedTypeFactory) {
      atypeFactory = (CalledMethodsOnElementsAnnotatedTypeFactory) analysis.getTypeFactory();
    } else {
      atypeFactory =
          new CalledMethodsOnElementsAnnotatedTypeFactory(analysis.getTypeFactory().getChecker());
    }
    calledMethodsOnElementsValueElement = atypeFactory.calledMethodsOnElementsValueElement;
    enableWpiForRlc = atypeFactory.getChecker().hasOption(ResourceLeakChecker.ENABLE_WPI_FOR_RLC);
    env = atypeFactory.getProcessingEnv();
  }

  /**
   * Abstract transformer for when an {@code @OwningColletion} variable is passed to an
   * {@code @OwningCollection} method parameter (constructor or method invocation).
   *
   * <p>Resets the type of the passed argument to {@code CalledMethodsOnElements()}.
   *
   * @param store the store to update
   * @param collectionArg the {@code @OwningCollection} argument
   */
  @Override
  protected void transformOwningCollectionArg(CFStore store, JavaExpression collectionArg) {
    resetCmoeValue(store, collectionArg);
  }

  /**
   * Sets the {@code CalledMethodsOnElements} type of the given JavaExpression to {@code
   * CalledMethodsOnElementsBottom}.
   *
   * @param store the store, in which the type is changed
   * @param element the JavaExpression for the element, whose type is reset
   */
  private void resetCmoeValue(CFStore store, JavaExpression element) {
    AnnotationMirror newAnno =
        createAccumulatorAnnotation(Collections.emptyList(), atypeFactory.TOP);
    store.clearValue(element);
    store.insertValue(element, newAnno);
  }

  /**
   * The abstract transformer for {@code Collection.add(int, E)}. Resets the {@code
   * CalledMethodsOnElements} type of the recipient {@code Collection} to {@code
   * CalledMethodsOnElementsBottom}.
   *
   * @param node the {@code MethodInvocationNode}
   * @param res the {@code TransferResult} containing the store to be edited
   * @param receiver JavaExpression of the collection, whose type should be changed
   * @return updated {@code TransferResult}
   */
  @Override
  protected TransferResult<CFValue, CFStore> transformListSet(
      MethodInvocationNode node, TransferResult<CFValue, CFStore> res, JavaExpression receiver) {
    CFStore store = res.getRegularStore();
    resetCmoeValue(store, receiver);
    return new RegularTransferResult<CFValue, CFStore>(res.getResultValue(), store);
  }

  /**
   * The abstract transformer for {@code Collection.clear()}
   *
   * <p>The method does not remove the called methods values, since disposing method often clear the
   * collection after closing the obligations, which would cause the postcondition assertion that
   * the method was called on elements fail. If some elements are added after a clear, these will
   * reset the cmoe value, so this transformer is sound.
   *
   * @param node the {@code MethodInvocationNode}
   * @param res the {@code TransferResult} containing the store to be edited
   * @param receiver JavaExpression of the collection, whose type should be changed
   * @return updated {@code TransferResult}
   */
  @Override
  protected TransferResult<CFValue, CFStore> transformCollectionClear(
      MethodInvocationNode node, TransferResult<CFValue, CFStore> res, JavaExpression receiver) {
    return res;
  }

  /**
   * The abstract transformer for {@code Collection.add(int, E)}. Resets the {@code
   * CalledMethodsOnElements} type of the recipient {@code Collection} to {@code
   * CalledMethodsOnElementsBottom}.
   *
   * @param node the {@code MethodInvocationNode}
   * @param res the {@code TransferResult} containing the store to be edited
   * @param receiver JavaExpression of the collection, whose type should be changed
   * @return updated {@code TransferResult}
   */
  @Override
  protected TransferResult<CFValue, CFStore> transformCollectionAddWithIdx(
      MethodInvocationNode node, TransferResult<CFValue, CFStore> res, JavaExpression receiver) {
    CFStore store = res.getRegularStore();
    resetCmoeValue(store, receiver);
    return new RegularTransferResult<CFValue, CFStore>(res.getResultValue(), store);
  }

  /**
   * The abstract transformer for {@code Collection.add(E)}. Resets the {@code
   * CalledMethodsOnElements} type of the recipient {@code Collection} to {@code
   * CalledMethodsOnElementsBottom}.
   *
   * @param node the {@code MethodInvocationNode}
   * @param res the {@code TransferResult} containing the store to be edited
   * @param receiver JavaExpression of the collection, whose type should be changed
   * @return updated {@code TransferResult}
   */
  @Override
  protected TransferResult<CFValue, CFStore> transformCollectionAdd(
      MethodInvocationNode node, TransferResult<CFValue, CFStore> res, JavaExpression receiver) {
    CFStore store = res.getRegularStore();
    resetCmoeValue(store, receiver);
    return new RegularTransferResult<CFValue, CFStore>(res.getResultValue(), store);
  }

  /**
   * Resets the {@code @CalledMethodsOnElements} type of the collection specified in the passed loop
   * to TOP in the else store of the {@code TransferResult}. This is required for soundness, since
   * when the collection is reassigned, the previously called methods are no longer considered
   * called on the new elements.
   *
   * @param loop the {@code PotentiallyAssigningLoop}
   * @param res the transfer result to update
   * @return the updated {@code TransferResult}
   */
  @Override
  protected TransferResult<CFValue, CFStore> transformAssigningLoop(
      PotentiallyAssigningLoop loop, TransferResult<CFValue, CFStore> res) {
    ExpressionTree arrayTree = loop.collectionTree;
    JavaExpression target = JavaExpression.fromTree(arrayTree);
    AnnotationMirror newAnno =
        createAccumulatorAnnotation(Collections.emptyList(), atypeFactory.TOP);
    CFStore elseStore = res.getElseStore();
    elseStore.clearValue(target);
    elseStore.insertValue(target, newAnno);
    return new ConditionalTransferResult<>(res.getResultValue(), res.getThenStore(), elseStore);
  }

  /**
   * Updates the {@code @CalledMethodsOnElements} type of the corresponding collection in the else
   * store of the {@code TransferResult} based on the information in the loop wrapper.
   *
   * @param loop the {@code PotentiallyFulfillingLoop}
   * @param res the transfer result to update
   * @return the updated transfer result
   */
  @Override
  protected TransferResult<CFValue, CFStore> transformFulfillingLoop(
      PotentiallyFulfillingLoop loop, TransferResult<CFValue, CFStore> res) {
    ExpressionTree arrayTree = loop.collectionTree;
    Set<String> calledMethods = loop.getMethods();
    // System.out.println("calledmethods: (cmoe transfer) " + calledMethods);
    if (calledMethods != null && calledMethods.size() > 0) {
      CFStore elseStore = res.getElseStore();
      JavaExpression target = JavaExpression.fromTree(arrayTree);
      CFValue oldTypeValue = elseStore.getValue(target);
      AnnotationMirror oldType =
          oldTypeValue == null ? atypeFactory.TOP : oldTypeValue.getAnnotations().first();
      AnnotationMirror newType =
          getUpdatedCalledMethodsOnElementsType(oldType, new ArrayList<>(calledMethods));
      elseStore.clearValue(target);
      elseStore.insertValue(target, newType);
      return new ConditionalTransferResult<>(res.getResultValue(), res.getThenStore(), elseStore);
    }
    return res;
  }

  /**
   * Extract the current called-methods type from {@code currentType}, and then add {@code
   * methodName} to it, and return the result. This method is similar to GLB, but should be used
   * when the new methods come from a source other than an {@code CalledMethodsOnElements}
   * annotation.
   *
   * @param type the current type in the called-methods hierarchy
   * @param methodNames list of names of the new methods to add to the type
   * @return the new annotation to be added to the type, or null if the current type cannot be
   *     converted to an accumulator annotation
   */
  private @Nullable AnnotationMirror getUpdatedCalledMethodsOnElementsType(
      AnnotationMirror type, List<String> methodNames) {
    List<String> currentMethods =
        AnnotationUtils.getElementValueArray(
            type, calledMethodsOnElementsValueElement, String.class);
    List<String> newList = CollectionsPlume.concatenate(currentMethods, methodNames);
    return createAccumulatorAnnotation(newList, type);
  }

  /**
   * Creates a new instance of the accumulator annotation that contains the elements of {@code
   * values}.
   *
   * @param values the arguments to the annotation. The values can contain duplicates and can be in
   *     any order.
   * @param type the {@code AnnotationMirror} to build upon
   * @return an annotation mirror representing the accumulator annotation with {@code values}'s
   *     arguments; this is top if {@code values} is empty
   */
  public AnnotationMirror createAccumulatorAnnotation(List<String> values, AnnotationMirror type) {
    AnnotationBuilder builder = new AnnotationBuilder(this.env, type);
    builder.setValue("value", CollectionsPlume.withoutDuplicatesSorted(values));
    return builder.build();
  }

  /**
   * Creates a new instance of the accumulator annotation that contains exactly one value.
   *
   * @param value the argument to the annotation
   * @param type the {@code AnnotationMirror} to build upon
   * @return an annotation mirror representing the accumulator annotation with {@code value} as its
   *     argument
   */
  public AnnotationMirror createAccumulatorAnnotation(String value, AnnotationMirror type) {
    return createAccumulatorAnnotation(Collections.singletonList(value), type);
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
