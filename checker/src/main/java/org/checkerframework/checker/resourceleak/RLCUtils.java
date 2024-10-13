package org.checkerframework.checker.resourceleak;

import com.sun.source.tree.Tree;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.WildcardType;
import org.checkerframework.checker.calledmethodsonelements.CalledMethodsOnElementsChecker;
import org.checkerframework.checker.mustcall.MustCallAnnotatedTypeFactory;
import org.checkerframework.checker.mustcall.MustCallChecker;
import org.checkerframework.checker.mustcall.MustCallNoCreatesMustCallForChecker;
import org.checkerframework.checker.mustcall.qual.InheritableMustCall;
import org.checkerframework.checker.mustcall.qual.MustCall;
import org.checkerframework.checker.mustcallonelements.MustCallOnElementsChecker;
import org.checkerframework.checker.mustcallonelements.qual.MustCallOnElements;
import org.checkerframework.checker.mustcallonelements.qual.MustCallOnElementsUnknown;
import org.checkerframework.checker.mustcallonelements.qual.OwningCollection;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.checker.rlccalledmethods.RLCCalledMethodsChecker;
import org.checkerframework.common.basetype.BaseTypeChecker;
import org.checkerframework.framework.source.SourceChecker;
import org.checkerframework.framework.type.AnnotatedTypeFactory;
import org.checkerframework.framework.type.AnnotatedTypeMirror;
import org.checkerframework.javacutil.AnnotationUtils;
import org.checkerframework.javacutil.BugInCF;
import org.checkerframework.javacutil.TreeUtils;
import org.checkerframework.javacutil.TypesUtils;
import org.plumelib.util.CollectionsPlume;

/**
 * Collection of static utility functions related to the various checkers within the
 * ResourceLeakChecker.
 */
public class RLCUtils {

  /** List of checker names associated with the Resource Leak Checker. */
  public static List<String> rlcCheckers =
      new ArrayList<>(
          Arrays.asList(
              ResourceLeakChecker.class.getCanonicalName(),
              RLCCalledMethodsChecker.class.getCanonicalName(),
              MustCallChecker.class.getCanonicalName(),
              MustCallNoCreatesMustCallForChecker.class.getCanonicalName(),
              MustCallOnElementsChecker.class.getCanonicalName(),
              CalledMethodsOnElementsChecker.class.getCanonicalName()));

  /**
   * Returns the type factory corresponding to the desired checker class within the
   * ResourceLeakChecker given a checker part of the ResourceLeakChecker.
   *
   * @param targetClass the desired checker class
   * @param referenceChecker the current checker
   * @return the type factory of the desired class
   */
  public static @NonNull AnnotatedTypeFactory getTypeFactory(
      Class<? extends SourceChecker> targetClass, SourceChecker referenceChecker) {
    BaseTypeChecker targetChecker = (BaseTypeChecker) getChecker(targetClass, referenceChecker);
    return targetChecker.getTypeFactory();
  }

  /**
   * Returns the checker of the desired class within the ResourceLeakChecker given a type factory
   * part of the ResourceLeakChecker.
   *
   * @param targetClass the desired checker class
   * @param referenceAtf the current atf
   * @return the checker of the desired class
   */
  public static @NonNull SourceChecker getChecker(
      Class<? extends SourceChecker> targetClass, AnnotatedTypeFactory referenceAtf) {
    if (!rlcCheckers.contains(targetClass.getCanonicalName())) {
      throw new IllegalArgumentException(
          "Argument targetClass to RLCUtils#getChecker(targetClass, referenceChecker) expected to be an RLC checker but is "
              + targetClass.getCanonicalName());
    }
    return getChecker(targetClass, referenceAtf.getChecker());
  }

  /**
   * Returns the type factory corresponding to the desired checker class within the
   * ResourceLeakChecker given a type factory part of the ResourceLeakChecker.
   *
   * @param targetClass the desired checker class
   * @param referenceAtf the current atf
   * @return the type factory of the desired class
   */
  public static @NonNull AnnotatedTypeFactory getTypeFactory(
      Class<? extends SourceChecker> targetClass, AnnotatedTypeFactory referenceAtf) {
    if (!rlcCheckers.contains(targetClass.getCanonicalName())) {
      throw new IllegalArgumentException(
          "Argument targetClass to RLCUtils#getChecker(targetClass, referenceChecker) expected to be an RLC checker but is "
              + targetClass.getCanonicalName());
    }
    return ((BaseTypeChecker) getChecker(targetClass, referenceAtf.getChecker())).getTypeFactory();
  }

  /**
   * Returns the checker of the desired class given a checker part of the RLC. Both the targetClass
   * and reference checker must be checkers from the RLC ecosystem, as defined by {@code
   * this.rlcCheckers}.
   *
   * @param targetClass the desired checker class
   * @param referenceChecker the current checker
   * @return the checker of the desired class
   * @throws IllegalArgumentException when either of the arguments is not one of the RLC checkers
   */
  public static @NonNull SourceChecker getChecker(
      Class<? extends SourceChecker> targetClass, SourceChecker referenceChecker) {
    if (!rlcCheckers.contains(targetClass.getCanonicalName())) {
      throw new IllegalArgumentException(
          "Argument targetClass to RLCUtils#getChecker(targetClass, referenceChecker) expected to be an RLC checker but is "
              + targetClass.getCanonicalName());
    }
    Class<?> refClass = referenceChecker.getClass();
    if (refClass == targetClass) {
      // base case - we found the desired checker
      return referenceChecker;
    } else if (refClass == MustCallChecker.class) {
      return getChecker(targetClass, referenceChecker.getParentChecker());
    } else if (refClass == CalledMethodsOnElementsChecker.class) {
      return getChecker(targetClass, referenceChecker.getParentChecker());
    } else if (refClass == ResourceLeakChecker.class) {
      return getChecker(
          targetClass, referenceChecker.getSubchecker(MustCallOnElementsChecker.class));
    } else if (refClass == RLCCalledMethodsChecker.class) {
      if (targetClass == MustCallChecker.class) {
        MustCallChecker mcc = referenceChecker.getSubchecker(MustCallChecker.class);
        return mcc != null
            ? mcc
            : referenceChecker.getSubchecker(MustCallNoCreatesMustCallForChecker.class);
      } else {
        return getChecker(targetClass, referenceChecker.getParentChecker());
      }
    } else if (refClass == MustCallOnElementsChecker.class) {
      if (targetClass == ResourceLeakChecker.class) {
        return referenceChecker.getParentChecker();
      } else if (targetClass == CalledMethodsOnElementsChecker.class) {
        return referenceChecker.getSubchecker(CalledMethodsOnElementsChecker.class);
      } else {
        return getChecker(
            targetClass, referenceChecker.getSubchecker(RLCCalledMethodsChecker.class));
      }
    } else {
      throw new IllegalArgumentException(
          "Argument referenceChecker to RLCUtils#getChecker(targetClass, referenceChecker) expected to be an RLC checker but is "
              + refClass.getCanonicalName());
    }
  }

  /**
   * Return true if the passed {@code TypeMirror} has a manual {@code MustCallOnElementsUnknown}
   * annotation.
   *
   * @param typeMirror the {@code TypeMirror}
   * @return true if the passed {@code TypeMirror} has a manual {@code MustCallOnElementsUnknown}
   *     annotation
   */
  public static boolean hasManualMcoeUnknownAnno(TypeMirror typeMirror) {
    for (AnnotationMirror paramAnno : typeMirror.getAnnotationMirrors()) {
      if (AnnotationUtils.areSameByName(
          paramAnno, MustCallOnElementsUnknown.class.getCanonicalName())) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns the {@code MustCallOnElements} type values in the manual annotation of the given {@code
   * TypeMirror} or null if there is no such annotation.
   *
   * @param typeMirror the {@code TypeMirror} of an @OwningCollection method parameter
   * @return the {@code MustCallOnElements} type values in the manual annotation of the given {@code
   *     TypeMirror} or null if there is no such annotation.
   */
  public static @Nullable List<String> getMcoeValuesInManualAnno(TypeMirror typeMirror) {
    // extract the @MustCallOnElement values of the typeMirror
    List<String> mcoeValues = Collections.emptyList();
    boolean hasMcoeAnno = false;
    for (AnnotationMirror paramAnno : typeMirror.getAnnotationMirrors()) {
      if (AnnotationUtils.areSameByName(paramAnno, MustCallOnElements.class.getCanonicalName())) {
        // is @MustCallOnElements annotation
        hasMcoeAnno = true;
        for (ExecutableElement key : paramAnno.getElementValues().keySet()) {
          AnnotationValue value = paramAnno.getElementValues().get(key);
          if (value != null) {
            mcoeValues =
                CollectionsPlume.concatenate(
                    mcoeValues, AnnotationUtils.annotationValueToList(value, String.class));
          }
        }
      }
      if (AnnotationUtils.areSameByName(
          paramAnno, MustCallOnElementsUnknown.class.getCanonicalName())) {
        // if mcoeUnknown annotation, no methods are guaranteed to be called
        hasMcoeAnno = true;
      }
    }
    return hasMcoeAnno ? mcoeValues : null;
  }

  /**
   * Assuming that the passed {@code TypeMirror} belongs to an {@code @OwningArray} method parameter
   * or field, returns the values in its {@code MustCallOnElements} type and the empty list if the
   * type is {@code MustCallOnElementsUnknown}. This is because for {@code @OwningArray} method
   * parameters, the default {@code MustCallOnElements} type contains the {@code MustCall} type
   * values of its component type, but a manual annotation overrides this default.
   *
   * <p>The returned list of method names is decided by the following rules:
   *
   * <ol>
   *   <li>If the {@code TypeMirror} contains a manual {@code MustCallOnElementsUnkown} annotation,
   *       the empty list is returned, since no {@code MustCallOnElements} obligations are known to
   *       be fulfilled by the enclosing method in that case (in reality, this annotation will throw
   *       an error, since it is illegal).
   *   <li>If the {@code TypeMirror} contains any other manual {@code MustCallOnElements}
   *       annotation, the type values of the annotation are returned, since these methods must be
   *       called on the elements of the argument by the time the enclosing method returns.
   *   <li>If the {@code TypeMirror} contains no manual annotation, the {@code MustCall} type values
   *       of the component type are returned, since this is the default {@code MustCallOnElements}
   *       type of the method parameter in this case and thus the enclosing method guarantees to
   *       fulfill them by the time it returns.
   * </ol>
   *
   * @param elt the {@code Element} of an @OwningCollection method parameter or field
   * @param mcAtf the {@code MustCallAnnotatedTypeFactory} to get the {@code MustCall} type
   * @return the values in the {@code MustCallOnElements} type of the given {@code TypeMirror} and
   *     the empty list if the type is {@code MustCallOnElementsUnknown}
   */
  public static @NonNull List<String> getMcoeValuesOfOwningCollection(
      Element elt, MustCallAnnotatedTypeFactory mcAtf) {
    // Check arguments, since this is critical for the soundness of the defaulting rule.
    ElementKind eltKind = elt.getKind();
    boolean eltIsOwningCollection = elt.getAnnotation(OwningCollection.class) != null;
    if (eltKind != ElementKind.FIELD && eltKind != ElementKind.PARAMETER) {
      throw new IllegalArgumentException(
          "RLCUtils#getMcoeValuesOfOwningCollection() expected a FIELD or PARAMETER element, but got: "
              + eltKind);
    }
    if (!eltIsOwningCollection) {
      throw new IllegalArgumentException(
          "RLCUtils#getMcoeValuesOfOwningCollection() expected an @OwningCollection argument, but "
              + elt
              + " is not @OwningCollection.");
    }

    TypeMirror typeMirror = elt.asType();
    List<String> manualMcoeAnnoValues = getMcoeValuesInManualAnno(typeMirror);
    if (manualMcoeAnnoValues != null) {
      return manualMcoeAnnoValues;
    } else {
      // if no mcoe anno, the mcoe type defaults to all obligations of the component
      List<String> mcoeValues = Collections.emptyList();
      boolean isArray = typeMirror instanceof ArrayType;
      boolean isCollection = isCollection(typeMirror);
      if (isArray) {
        TypeMirror componentType = ((ArrayType) typeMirror).getComponentType();
        mcoeValues = getMcValues(componentType, mcAtf);
      } else if (isCollection) {
        assert typeMirror instanceof DeclaredType
            : "Collection TypeMirror assumed to be DeclaredType, but is: "
                + typeMirror.getClass().getCanonicalName();
        DeclaredType declType = (DeclaredType) typeMirror;
        List<? extends TypeMirror> typeArgs = declType.getTypeArguments();
        assert typeArgs.size() == 1 : "Collections are expected to have only one type variable.";
        TypeMirror typeArg = typeArgs.get(0);
        mcoeValues = getMcValues(typeArg, mcAtf);
      } else {
        throw new BugInCF(
            "Argument for @OwningCollection parameter is neither ArrayType, nor Collection but "
                + typeMirror.getClass().getCanonicalName());
      }
      return mcoeValues;
    }
  }

  /**
   * Returns the list of mustcall obligations for the given {@code TypeMirror}.
   *
   * @param type the {@code TypeMirror}
   * @param mcAtf the {@code MustCallAnnotatedTypeFactory} to get the {@code MustCall} type
   * @return the list of mustcall obligations for {@code type}
   */
  public static List<String> getMcValues(TypeMirror type, MustCallAnnotatedTypeFactory mcAtf) {
    if (type instanceof TypeVariable) {
      // a generic - replace with upper bound
      type = ((TypeVariable) type).getUpperBound();
    } else if (type instanceof WildcardType) {
      // a wildcard - replace with upper bound
      type = ((WildcardType) type).getExtendsBound();
    }
    TypeElement typeElement = TypesUtils.getTypeElement(type);
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

  /**
   * Returns whether the given {@link TypeMirror} is an instance of a collection (subclass). This is
   * determined by getting the class of the TypeMirror and checking whether it is assignable from
   * Collection.
   *
   * @param type the TypeMirror
   * @return whether type is an instance of a collection (subclass)
   */
  public static boolean isCollection(TypeMirror type) {
    if (type == null) return false;
    Class<?> elementRawType = TypesUtils.getClassFromType(type);
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
    return isCollection(elementTypeMirror.getUnderlyingType());
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
    return isCollection(element, atf);
  }

  /**
   * Returns whether the given {@link TypeMirror} is an instance of Iterator (subtype). This is
   * determined by getting the class of the TypeMirror and checking whether it is assignable from
   * Iterator.
   *
   * @param type the TypeMirror
   * @return whether type is an instance of Iterator
   */
  public static boolean isIterator(TypeMirror type) {
    if (type == null) return false;
    Class<?> elementRawType = TypesUtils.getClassFromType(type);
    if (elementRawType == null) return false;
    return Iterator.class.isAssignableFrom(elementRawType);
  }

  /**
   * Returns whether the given Element is a java.util.Iterator type by checking whether the raw type
   * of the element is assignable from java.util.Iterator. Returns false if element is null, or has
   * no valid type.
   *
   * @param element the element
   * @param atf an AnnotatedTypeFactory to get the annotated type of the element
   * @return whether the given element is a Java.util.Iterator type
   */
  public static boolean isIterator(Element element, AnnotatedTypeFactory atf) {
    if (element == null) return false;
    AnnotatedTypeMirror elementTypeMirror = atf.getAnnotatedType(element);
    if (elementTypeMirror == null || elementTypeMirror.getUnderlyingType() == null) return false;
    return isIterator(elementTypeMirror.getUnderlyingType());
  }

  /**
   * Returns whether the given Tree is a java.util.Iterator type by checking whether the raw type of
   * the element is assignable from java.util.Iterator. Returns false if tree is null, or has no
   * valid type.
   *
   * @param tree the tree
   * @param atf an AnnotatedTypeFactory to get the annotated type of the element
   * @return whether the given Tree is a Java.util.Iterator type
   */
  public static boolean isIterator(Tree tree, AnnotatedTypeFactory atf) {
    if (tree == null) return false;
    Element element = TreeUtils.elementFromTree(tree);
    return isIterator(element, atf);
  }
}
