package org.checkerframework.common.value;

import com.sun.source.tree.Tree;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.type.TypeMirror;
import org.checkerframework.checker.mustcall.qual.MustCallUnknown;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.common.value.qual.IntRange;
import org.checkerframework.common.value.qual.IntVal;
import org.checkerframework.common.value.qual.StringVal;
import org.checkerframework.common.value.util.NumberUtils;
import org.checkerframework.common.value.util.Range;
import org.checkerframework.dataflow.expression.JavaExpression;
import org.checkerframework.framework.type.AnnotatedTypeFactory;
import org.checkerframework.framework.type.AnnotatedTypeMirror;
import org.checkerframework.framework.type.GenericAnnotatedTypeFactory;
import org.checkerframework.javacutil.AnnotationUtils;
import org.checkerframework.javacutil.TypeSystemError;
import org.checkerframework.javacutil.TypesUtils;
import org.plumelib.util.CollectionsPlume;

/** Utility methods for the Value Checker. */
public class ValueCheckerUtils {

  /** Do not instantiate. */
  private ValueCheckerUtils() {
    throw new TypeSystemError("do not instantiate");
  }

  /**
   * Returns a list of the values of an annotation, and then cast the values to a given type.
   *
   * @param anno the annotation that contains values
   * @param castTo the type that is cast to
   * @param atypeFactory the type factory
   * @return a list of values after the casting
   */
  public static List<?> getValuesCastedToType(
      AnnotationMirror anno, TypeMirror castTo, ValueAnnotatedTypeFactory atypeFactory) {
    return getValuesCastedToType(anno, castTo, false, atypeFactory);
  }

  /**
   * Returns a list of the values of an annotation, and then cast the values to a given type.
   *
   * @param anno the annotation that contains values
   * @param castTo the unannotated type that is casted to
   * @param isUnsigned true if the type being casted to is unsigned
   * @param atypeFactory the type factory
   * @return a list of values after the casting
   */
  public static List<?> getValuesCastedToType(
      AnnotationMirror anno,
      TypeMirror castTo,
      boolean isUnsigned,
      ValueAnnotatedTypeFactory atypeFactory) {
    Class<?> castType = TypesUtils.getClassFromType(castTo);
    List<?> values;
    switch (AnnotationUtils.annotationName(anno)) {
      case ValueAnnotatedTypeFactory.DOUBLEVAL_NAME:
        values = convertDoubleVal(anno, castType, castTo, atypeFactory);
        break;
      case ValueAnnotatedTypeFactory.INTVAL_NAME:
        List<Long> longs = atypeFactory.getIntValues(anno);
        values = convertIntVal(longs, castType, castTo, isUnsigned);
        break;
      case ValueAnnotatedTypeFactory.INTRANGE_NAME:
        Range range = atypeFactory.getRange(anno);
        List<Long> rangeValues = getValuesFromRange(range, Long.class);
        values = convertIntVal(rangeValues, castType, castTo, isUnsigned);
        break;
      case ValueAnnotatedTypeFactory.STRINGVAL_NAME:
        values = convertStringVal(anno, castType, atypeFactory);
        break;
      case ValueAnnotatedTypeFactory.BOOLVAL_NAME:
        values = convertBoolVal(anno, castType, atypeFactory);
        break;
      case ValueAnnotatedTypeFactory.BOTTOMVAL_NAME:
      case ValueAnnotatedTypeFactory.ARRAYLEN_NAME:
        values = Collections.emptyList();
        break;
      default:
        values = null;
    }
    return values;
  }

  /** Get the minimum and maximum of a list and return a range bounded by them. */
  public static @Nullable Range getRangeFromValues(List<? extends Number> values) {
    if (values == null) {
      return null;
    } else if (values.isEmpty()) {
      return Range.NOTHING;
    }
    return Range.create(values);
  }

  /**
   * Converts a long value to a boxed numeric type.
   *
   * @param value a long value
   * @param expectedType the boxed numeric type of the result
   * @return {@code value} converted to {@code expectedType} using standard conversion rules
   */
  private static <T> T convertLongToType(long value, Class<T> expectedType) {
    Object convertedValue;
    if (expectedType == Integer.class) {
      convertedValue = (int) value;
    } else if (expectedType == Short.class) {
      convertedValue = (short) value;
    } else if (expectedType == Byte.class) {
      convertedValue = (byte) value;
    } else if (expectedType == Long.class) {
      convertedValue = value;
    } else if (expectedType == Double.class) {
      convertedValue = (double) value;
    } else if (expectedType == Float.class) {
      convertedValue = (float) value;
    } else if (expectedType == Character.class) {
      convertedValue = (char) value;
    } else {
      throw new UnsupportedOperationException(
          "ValueCheckerUtils: unexpected class: " + expectedType);
    }
    return expectedType.cast(convertedValue);
  }

  /**
   * Returns all possible values from the given type and cast them into a boxed primitive type.
   * Returns null if the list would have length greater than {@link
   * ValueAnnotatedTypeFactory#MAX_VALUES}.
   *
   * <p>{@code expectedType} must be a boxed type, not a primitive type, because primitive types
   * cannot be stored in a list.
   *
   * @param <T> the type of the values to obtain
   * @param range the given range
   * @param expectedType the expected type
   * @return a list of all the values in the range, or null if there would be more than {@link
   *     ValueAnnotatedTypeFactory#MAX_VALUES}
   */
  public static <T> @Nullable List<T> getValuesFromRange(
      @Nullable Range range, Class<T> expectedType) {
    if (range == null || range.isWiderThan(ValueAnnotatedTypeFactory.MAX_VALUES)) {
      return null;
    }
    if (range.isNothing()) {
      return Collections.emptyList();
    }

    // The subtraction does not overflow, because the width has already been checked, so the
    // bound difference is less than ValueAnnotatedTypeFactory.MAX_VALUES.
    long boundDifference = range.to - range.from;

    // Each value is computed as a sum of the first value and an offset within the range,
    // to avoid having range.to as an upper bound of the loop. range.to can be Long.MAX_VALUE,
    // in which case a comparison value <= range.to would be always true.
    // boundDifference is always much smaller than Long.MAX_VALUE
    List<T> values = new ArrayList<>((int) boundDifference + 1);
    for (long offset = 0; offset <= boundDifference; offset++) {
      long value = range.from + offset;
      values.add(convertLongToType(value, expectedType));
    }
    return values;
  }

  /**
   * Converts a list of objects to a list of their string representations.
   *
   * @param origValues the objects to format
   * @return a list of the formatted objects
   */
  private static @Nullable List<?> convertToStringVal(
      List<? extends @MustCallUnknown Object> origValues) {
    if (origValues == null) {
      return null;
    }
    return CollectionsPlume.mapList(Object::toString, origValues);
  }

  /**
   * Convert the {@code value} argument/element of a @BoolVal annotation into a list.
   *
   * @param anno a @BoolVal annotation
   * @param newClass if String.class, the returned list is a {@code List<String>}
   * @param atypeFactory the type factory, used for obtaining fields/elements from annotations
   * @return the {@code value} of a @BoolVal annotation, as a {@code List<Boolean>} or a {@code
   *     List<String>}
   */
  private static List<?> convertBoolVal(
      AnnotationMirror anno, Class<?> newClass, ValueAnnotatedTypeFactory atypeFactory) {
    List<Boolean> bools =
        AnnotationUtils.getElementValueArray(anno, atypeFactory.boolValValueElement, Boolean.class);

    if (newClass == String.class) {
      return convertToStringVal(bools);
    }
    return bools;
  }

  /**
   * Convert the {@code value} argument/element of a {@code @StringVal} annotation into a list.
   *
   * @param anno a {@code @StringVal} annotation
   * @param newClass if char[].class, the returned list is a {@code List<char[]>}
   * @param atypeFactory the type factory, used for obtaining fields/elements from annotations
   * @return the {@code value} of a {@code @StringVal} annotation, as a {@code List<String>} or a
   *     {@code List<char[]>}
   */
  private static List<?> convertStringVal(
      AnnotationMirror anno, Class<?> newClass, ValueAnnotatedTypeFactory atypeFactory) {
    List<String> strings = atypeFactory.getStringValues(anno);
    if (newClass == char[].class) {
      return CollectionsPlume.mapList(String::toCharArray, strings);
    }
    return strings;
  }

  /**
   * Convert a list of longs to a given type.
   *
   * @param longs the integral values to convert
   * @param newClass determines the type of the result
   * @param newType the type to which to cast, if newClass is numeric
   * @param isUnsigned if true, treat {@code newType} as unsigned
   * @return the {@code value} of a {@code @IntVal} annotation, as a {@code List<Integer>} or a
   *     {@code List<char[]>}
   */
  private static @Nullable List<?> convertIntVal(
      List<Long> longs, Class<?> newClass, TypeMirror newType, boolean isUnsigned) {
    if (longs == null) {
      return null;
    }
    if (newClass == String.class) {
      return convertToStringVal(longs);
    } else if (newClass == Character.class || newClass == char.class) {
      return CollectionsPlume.mapList((Long l) -> (char) l.longValue(), longs);
    } else if (newClass == Boolean.class) {
      throw new UnsupportedOperationException(
          "ValueAnnotatedTypeFactory: can't convert integral type to boolean");
    }
    return NumberUtils.castNumbers(newType, isUnsigned, longs);
  }

  /**
   * Convert the {@code value} argument/element of a @StringVal annotation into a list.
   *
   * @param anno a {@code @DoubleVal} annotation
   * @param newClass the component type for the returned list
   * @param newType the component type for the returned list
   * @param atypeFactory the type factory, used for obtaining fields/elements from annotations
   * @return the {@code value} of a {@code @DoubleVal} annotation
   */
  private static @Nullable List<?> convertDoubleVal(
      AnnotationMirror anno,
      Class<?> newClass,
      TypeMirror newType,
      ValueAnnotatedTypeFactory atypeFactory) {
    List<Double> doubles = atypeFactory.getDoubleValues(anno);
    if (doubles == null) {
      return null;
    }
    if (newClass == String.class) {
      return convertToStringVal(doubles);
    } else if (newClass == Character.class || newClass == char.class) {
      return CollectionsPlume.mapList((Double l) -> (char) l.doubleValue(), doubles);
    } else if (newClass == Boolean.class) {
      throw new UnsupportedOperationException(
          "ValueAnnotatedTypeFactory: can't convert double to boolean");
    }
    return NumberUtils.castNumbers(newType, doubles);
  }

  /**
   * Gets a list of lengths for a list of string values.
   *
   * @param values list of string values
   * @return list of unique lengths of strings in {@code values}
   */
  public static List<Integer> getLengthsForStringValues(List<String> values) {
    List<Integer> lengths = CollectionsPlume.mapList(String::length, values);
    return CollectionsPlume.withoutDuplicatesSorted(lengths);
  }

  /**
   * Returns a range representing the possible integral values represented by the passed {@code
   * AnnotatedTypeMirror}. If the passed {@code AnnotatedTypeMirror} does not contain an {@code
   * IntRange} annotation or an {@code IntVal} annotation, returns null.
   */
  public static @Nullable Range getPossibleValues(
      AnnotatedTypeMirror valueType, ValueAnnotatedTypeFactory valueAnnotatedTypeFactory) {
    if (valueAnnotatedTypeFactory.isIntRange(valueType.getPrimaryAnnotations())) {
      return valueAnnotatedTypeFactory.getRange(valueType.getPrimaryAnnotation(IntRange.class));
    } else {
      List<Long> values =
          valueAnnotatedTypeFactory.getIntValues(valueType.getPrimaryAnnotation(IntVal.class));
      if (values != null) {
        return Range.create(values);
      } else {
        return null;
      }
    }
  }

  /**
   * Either returns the exact value of the given tree according to the Constant Value Checker, or
   * null if the exact value is not known. This method should only be used by clients who need
   * exactly one value -- such as the LBC's binary operator rules -- and not by those that need to
   * know whether a valueType belongs to a particular qualifier.
   */
  public static @Nullable Long getExactValue(Tree tree, ValueAnnotatedTypeFactory factory) {
    AnnotatedTypeMirror valueType = factory.getAnnotatedType(tree);
    Range possibleValues = getPossibleValues(valueType, factory);
    if (possibleValues != null && possibleValues.from == possibleValues.to) {
      return possibleValues.from;
    } else {
      return null;
    }
  }

  /**
   * Returns the exact value of an annotated element according to the Constant Value Checker, or
   * null if the exact value is not known.
   *
   * @param element the element to get the exact value from
   * @param factory a ValueAnnotatedTypeFactory used for annotation accessing
   * @return the exact value of the element if it is constant, or null otherwise
   */
  public static @Nullable Long getExactValue(Element element, ValueAnnotatedTypeFactory factory) {
    AnnotatedTypeMirror valueType = factory.getAnnotatedType(element);
    Range possibleValues = getPossibleValues(valueType, factory);
    if (possibleValues != null && possibleValues.from == possibleValues.to) {
      return possibleValues.from;
    } else {
      return null;
    }
  }

  /**
   * Either returns the exact string value of the given tree according to the Constant Value
   * Checker, or null if the exact value is not known. This method should only be used by clients
   * who need exactly one value and not by those that need to know whether a valueType belongs to a
   * particular qualifier.
   */
  public static @Nullable String getExactStringValue(Tree tree, ValueAnnotatedTypeFactory factory) {
    AnnotatedTypeMirror valueType = factory.getAnnotatedType(tree);
    if (valueType.hasPrimaryAnnotation(StringVal.class)) {
      AnnotationMirror valueAnno = valueType.getPrimaryAnnotation(StringVal.class);
      List<String> possibleValues =
          AnnotationUtils.getElementValueArray(
              valueAnno, factory.stringValValueElement, String.class);
      if (possibleValues.size() == 1) {
        return possibleValues.get(0);
      }
    }
    return null;
  }

  /**
   * Finds the minimum value in a Value Checker type. If there is no information (such as when the
   * list of possible values is empty or null), returns null. Otherwise, returns the smallest value
   * in the list of possible values.
   */
  public static @Nullable Long getMinValue(Tree tree, ValueAnnotatedTypeFactory factory) {
    AnnotatedTypeMirror valueType = factory.getAnnotatedType(tree);
    Range possibleValues = getPossibleValues(valueType, factory);
    if (possibleValues != null) {
      return possibleValues.from;
    } else {
      return null;
    }
  }

  /**
   * Finds the maximum value in a Value Checker type. If there is no information (such as when the
   * list of possible values is empty or null), returns null. Otherwise, returns the smallest value
   * in the list of possible values.
   */
  public static @Nullable Long getMaxValue(Tree tree, ValueAnnotatedTypeFactory factory) {
    AnnotatedTypeMirror valueType = factory.getAnnotatedType(tree);
    Range possibleValues = getPossibleValues(valueType, factory);
    if (possibleValues != null) {
      return possibleValues.to;
    } else {
      return null;
    }
  }

  /**
   * Looks up the minlen of a member select tree. The tree must be an access to a sequence length.
   */
  public static @Nullable Integer getMinLenFromTree(Tree tree, ValueAnnotatedTypeFactory valueATF) {
    AnnotatedTypeMirror minLenType = valueATF.getAnnotatedType(tree);
    Long min = valueATF.getMinimumIntegralValue(minLenType);
    if (min == null) {
      return null;
    }
    if (min < 0 || min > Integer.MAX_VALUE) {
      min = 0L;
    }
    return min.intValue();
  }

  /**
   * Queries the Value Checker to determine if there is a known minimum length for the array
   * represented by {@code tree}. If not, returns 0.
   */
  public static int getMinLen(Tree tree, ValueAnnotatedTypeFactory valueAnnotatedTypeFactory) {
    AnnotatedTypeMirror minLenType = valueAnnotatedTypeFactory.getAnnotatedType(tree);
    return valueAnnotatedTypeFactory.getMinLenValue(minLenType);
  }

  /**
   * Optimize the given JavaExpression. See {@link JavaExpressionOptimizer} for more details.
   *
   * @param je the expression to optimize
   * @param factory the annotated type factory
   * @return an optimized version of the argument
   */
  public static JavaExpression optimize(JavaExpression je, AnnotatedTypeFactory factory) {
    ValueAnnotatedTypeFactory vatf =
        ((GenericAnnotatedTypeFactory<?, ?, ?, ?>) factory)
            .getTypeFactoryOfSubcheckerOrNull(ValueChecker.class);
    return new JavaExpressionOptimizer(vatf == null ? factory : vatf).convert(je);
  }
}
