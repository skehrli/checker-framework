package org.checkerframework.checker.calledmethodsonelements;

import com.sun.source.tree.Tree;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import org.checkerframework.checker.calledmethodsonelements.qual.CalledMethodsOnElements;
import org.checkerframework.checker.calledmethodsonelements.qual.CalledMethodsOnElementsBottom;
import org.checkerframework.checker.mustcallonelements.qual.OwningCollection;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.common.basetype.BaseAnnotatedTypeFactory;
import org.checkerframework.common.basetype.BaseTypeChecker;
import org.checkerframework.dataflow.cfg.block.Block;
import org.checkerframework.dataflow.expression.JavaExpression;
import org.checkerframework.framework.flow.CFStore;
import org.checkerframework.framework.flow.CFValue;
import org.checkerframework.framework.type.GenericAnnotatedTypeFactory;
import org.checkerframework.framework.type.QualifierHierarchy;
import org.checkerframework.framework.type.SubtypeIsSupersetQualifierHierarchy;
import org.checkerframework.javacutil.AnnotationBuilder;
import org.checkerframework.javacutil.AnnotationUtils;
import org.checkerframework.javacutil.BugInCF;
import org.checkerframework.javacutil.ElementUtils;
import org.checkerframework.javacutil.TreeUtils;
import org.checkerframework.javacutil.TypesUtils;

/** The annotated type factory for the Called Methods On Elements Checker. */
public class CalledMethodsOnElementsAnnotatedTypeFactory extends BaseAnnotatedTypeFactory {

  /** The {@code @CalledMethodsOnElements()} annotation. */
  public final AnnotationMirror TOP;

  /** The {@code CalledMethodsOnElements()} annotation. */
  public final AnnotationMirror BOTTOM;

  /** The {@link CalledMethodsOnElements#value} element/argument. */
  /*package-private*/ final ExecutableElement calledMethodsOnElementsValueElement =
      TreeUtils.getMethod(CalledMethodsOnElements.class, "value", 0, processingEnv);

  /**
   * Fetches the store from the results of dataflow for {@code first}. If {@code afterFirstStore} is
   * true, then the store after {@code first} is returned; if {@code afterFirstStore} is false, the
   * store before {@code succ} is returned.
   *
   * @param afterFirstStore whether to use the store after the first block or the store before its
   *     successor, succ
   * @param first a block
   * @param succ first's successor
   * @return the appropriate CFStore, populated with CalledMethodsOnElements annotations, from the
   *     results of running dataflow
   */
  public CFStore getStoreForBlock(boolean afterFirstStore, Block first, Block succ) {
    return afterFirstStore ? flowResult.getStoreAfter(first) : flowResult.getStoreBefore(succ);
  }

  /**
   * Fetches the store from the results of dataflow for before {@code block}.
   *
   * @param block a block
   * @return the appropriate CFStore, populated with CalledMethodsOnElements annotations, from the
   *     results of running dataflow
   */
  public CFStore getStoreForBlock(Block block) {
    return flowResult.getStoreBefore(block);
  }

  /**
   * Fetches the store from the results of dataflow for before {@code tree}.
   *
   * @param tree a tree
   * @return the appropriate CFStore, populated with CalledMethodsOnElements annotations, from the
   *     results of running dataflow
   */
  public CFStore getStoreForTree(Tree tree) {
    return flowResult.getStoreBefore(tree);
  }

  /**
   * Create a new CalledMethodsOnElementsAnnotatedTypeFactory.
   *
   * @param checker the checker
   */
  public CalledMethodsOnElementsAnnotatedTypeFactory(BaseTypeChecker checker) {
    super(checker);
    TOP = createCMOEAnnotation(Collections.emptyList());
    BOTTOM = AnnotationBuilder.fromClass(elements, CalledMethodsOnElementsBottom.class);
    // Don't call postInit() for subclasses.
    if (this.getClass() == CalledMethodsOnElementsAnnotatedTypeFactory.class) {
      this.postInit();
    }
  }

  /**
   * Creates a {@code @CalledMethodsOnElements} annotation with the passed methods as value.
   *
   * @param methodList list of methods as strings
   * @return a {@code @CalledMethodsOnElements} annotation with the passed methods as value
   */
  private AnnotationMirror createCMOEAnnotation(List<String> methodList) {
    AnnotationBuilder builder = new AnnotationBuilder(processingEnv, CalledMethodsOnElements.class);
    String[] methodArray = methodList.toArray(new String[methodList.size()]);
    Arrays.sort(methodArray);
    builder.setValue("value", methodArray);
    return builder.build();
  }

  @Override
  protected CalledMethodsOnElementsAnalysis createFlowAnalysis() {
    return new CalledMethodsOnElementsAnalysis((CalledMethodsOnElementsChecker) checker, this);
  }

  /**
   * Returns the cmoe value element.
   *
   * @return the cmoe value element.
   */
  public ExecutableElement getCalledMethodsOnElementsValueElement() {
    return calledMethodsOnElementsValueElement;
  }

  /**
   * Returns a list of methods considered "called on elements" of the given tree (expected to be an
   * array identifier) The list is extracted from the store passed as an argument.
   *
   * @param cmoeStore the store holding cmoe type annotation information
   * @param reference the java expression
   * @param tree the expression tree
   * @return list of the CalledMethodsOnElements values of the given expression in the given store
   */
  public List<String> getCalledMethodsOnElements(
      CFStore cmoeStore, JavaExpression reference, Tree tree) {
    CFValue cfval = cmoeStore.getValue(reference);
    Element collectionElm = TreeUtils.elementFromTree(tree);
    if (collectionElm.getKind() == ElementKind.FIELD
        && collectionElm.getAnnotation(OwningCollection.class) != null) {
      if (ElementUtils.isFinal(collectionElm)) {
        if (cfval == null) {
          // entry block doesn't have final field in store yet
          return Collections.emptyList();
        }
      } else {
        // nonfinal OwningCollection field is illegal. An error was already issued.
        // Prevent program crash and return here.
        return Collections.emptyList();
      }
    }
    if (cfval == null) {
      return Collections.emptyList();
    }
    // assert cfval != null : "No cmoe annotation for " + reference + " in store.";
    AnnotationMirror cmoeAnno =
        AnnotationUtils.getAnnotationByClass(cfval.getAnnotations(), CalledMethodsOnElements.class);
    AnnotationMirror cmoeAnnoBottom =
        AnnotationUtils.getAnnotationByClass(
            cfval.getAnnotations(), CalledMethodsOnElementsBottom.class);
    if (cmoeAnno != null) {
      AnnotationValue av =
          cmoeAnno.getElementValues().get(getCalledMethodsOnElementsValueElement());
      if (av == null) {
        return new ArrayList<String>();
      } else {
        return AnnotationUtils.annotationValueToList(av, String.class);
      }
    } else if (cmoeAnnoBottom != null) {
      return Collections.emptyList();
    } else {
      AnnotationMirror result =
          getAnnotatedType(collectionElm).getEffectiveAnnotationInHierarchy(BOTTOM);
      if (result != null && !AnnotationUtils.areSame(result, BOTTOM)) {
        AnnotationValue av =
            result.getElementValues().get(getCalledMethodsOnElementsValueElement());
        if (av == null) {
          return new ArrayList<String>();
        } else {
          return AnnotationUtils.annotationValueToList(av, String.class);
        }
      }
      // There wasn't a @CalledMethodsOnElements annotation for it in the store and the type
      // factory has no information, so fall back to the default must-call type for the class.
      TypeElement typeElt = TypesUtils.getTypeElement(reference.getType());
      if (typeElt == null) {
        // typeElt is null if reference.getType() was not a class, interface, annotation
        // type, or enum -- that is, was not an annotatable type.
        // That happens rarely, such as when it is a wildcard type.
        // The safe default here is bottom.
        return new ArrayList<>();
      }
      if (typeElt.asType().getKind() == TypeKind.VOID) {
        // Void types can't have methods called on them, so returning bottom is safe.
        return new ArrayList<>();
      }
      throw new BugInCF("Cannot find any Cmoe type for " + reference);
    }
  }

  @Override
  protected QualifierHierarchy createQualifierHierarchy() {
    return new CalledMethodsOnElementsQualifierHierarchy(
        this.getSupportedTypeQualifiers(), this.getProcessingEnv(), this);
  }

  /** Qualifier hierarchy for the Must Call Checker. */
  class CalledMethodsOnElementsQualifierHierarchy extends SubtypeIsSupersetQualifierHierarchy {

    /**
     * Creates a SubtypeIsSuperSetQualifierHierarchy from the given classes.
     *
     * @param qualifierClasses classes of annotations that are the qualifiers for this hierarchy
     * @param processingEnv processing environment
     * @param atypeFactory the associated type factory
     */
    public CalledMethodsOnElementsQualifierHierarchy(
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
      if (shouldHaveNoCalledMethodsOnElementsObligation(subType)
          || shouldHaveNoCalledMethodsOnElementsObligation(superType)) {
        return true;
      }
      return super.isSubtypeShallow(subQualifier, subType, superQualifier, superType);
    }

    @Override
    public @Nullable AnnotationMirror leastUpperBoundShallow(
        AnnotationMirror qualifier1, TypeMirror tm1, AnnotationMirror qualifier2, TypeMirror tm2) {
      boolean tm1NoCalledMethodsOnElements = shouldHaveNoCalledMethodsOnElementsObligation(tm1);
      boolean tm2NoCalledMethodsOnElements = shouldHaveNoCalledMethodsOnElementsObligation(tm2);
      if (tm1NoCalledMethodsOnElements == tm2NoCalledMethodsOnElements) {
        return super.leastUpperBoundShallow(qualifier1, tm1, qualifier2, tm2);
      } else if (tm1NoCalledMethodsOnElements) {
        return qualifier1;
      } else { // if (tm2NoCalledMethodsOnElements) {
        return qualifier2;
      }
    }
  }

  /**
   * Returns true if the given type should never have a called-methods-on-elements obligation.
   *
   * @param type the type to check
   * @return true if the given type should never have a called-methods-on-elements obligation
   */
  public boolean shouldHaveNoCalledMethodsOnElementsObligation(TypeMirror type) {
    return type.getKind().isPrimitive() || TypesUtils.isClass(type) || TypesUtils.isString(type);
  }
}
