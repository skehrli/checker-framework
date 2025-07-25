package org.checkerframework.common.returnsreceiver;

import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import org.checkerframework.checker.signature.qual.CanonicalName;
import org.checkerframework.framework.type.AnnotatedTypeMirror;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedExecutableType;
import org.checkerframework.javacutil.AnnotationUtils;
import org.checkerframework.javacutil.TypeSystemError;
import org.checkerframework.javacutil.TypesUtils;

/**
 * A utility class to support fluent API generators so the checker can add {@code @This} annotations
 * on method return types when a generator has been used. To check whether a method is created by
 * any of the generators and returns {@code this}, simply call the {@link FluentAPIGenerator#check}
 * on the annotated type of the method signature.
 */
public class FluentAPIGenerator {

  /**
   * Check if a method was generated by a known fluent API generator and returns its receiver.
   *
   * @param t the annotated type of the method signature
   * @return {@code true} if the method was created by a generator and returns {@code this}
   */
  public static boolean check(AnnotatedExecutableType t) {
    for (FluentAPIGenerators fluentAPIGenerator : FluentAPIGenerators.values()) {
      if (fluentAPIGenerator.returnsThis(t)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Enum of supported fluent API generators. For such generators, the checker can automatically
   * add @This annotations on method return types in the generated code.
   */
  private enum FluentAPIGenerators {
    /**
     * The <a
     * href="https://github.com/google/auto/blob/master/value/userguide/builders.md">AutoValue</a>
     * framework.
     */
    AUTO_VALUE {

      /**
       * The qualified name of the AutoValue Builder annotation. This needs to be constructed
       * dynamically due to a side effect of the shadow plugin. See {@link
       * getAutoValueBuilderCanonicalName()} for more information.
       */
      private final String AUTO_VALUE_BUILDER = getAutoValueBuilderCanonicalName();

      @Override
      public boolean returnsThis(AnnotatedExecutableType t) {
        ExecutableElement element = t.getElement();
        TypeElement enclosingElement = (TypeElement) element.getEnclosingElement();
        boolean inAutoValueBuilder =
            AnnotationUtils.containsSameByName(
                enclosingElement.getAnnotationMirrors(), AUTO_VALUE_BUILDER);

        if (!inAutoValueBuilder) {
          // see if superclass is an AutoValue Builder, to handle generated code
          TypeMirror superclass = enclosingElement.getSuperclass();
          // if enclosingElement is an interface, the superclass has TypeKind NONE
          if (superclass.getKind() != TypeKind.NONE) {
            // update enclosingElement to be for the superclass for this case
            enclosingElement = TypesUtils.getTypeElement(superclass);
            inAutoValueBuilder =
                AnnotationUtils.containsSameByName(
                    enclosingElement.getAnnotationMirrors(), AUTO_VALUE_BUILDER);
          }
        }

        if (inAutoValueBuilder) {
          AnnotatedTypeMirror returnType = t.getReturnType();
          if (returnType == null) {
            throw new TypeSystemError("Return type cannot be null: " + t);
          }
          return enclosingElement.equals(TypesUtils.getTypeElement(returnType.getUnderlyingType()));
        }
        return false;
      }

      /**
       * Returns the qualified name of the AutoValue Builder annotation. This method constructs the
       * String dynamically, to ensure it does not get rewritten due to relocation of the {@code
       * "com.google"} package during the build process.
       *
       * @return {@code "com.google.auto.value.AutoValue.Builder"}
       */
      private @CanonicalName String getAutoValueBuilderCanonicalName() {
        String com = "com";
        @SuppressWarnings("signature:assignment") // string concatenation
        @CanonicalName String result = com + "." + "google.auto.value.AutoValue.Builder";
        return result;
      }
    },
    /** <a href="https://projectlombok.org/features/Builder">Project Lombok</a>. */
    LOMBOK {
      @Override
      public boolean returnsThis(AnnotatedExecutableType t) {
        ExecutableElement element = t.getElement();
        Element enclosingElement = element.getEnclosingElement();
        boolean inLombokBuilder =
            (AnnotationUtils.containsSameByName(
                        enclosingElement.getAnnotationMirrors(), "lombok.Generated")
                    || AnnotationUtils.containsSameByName(
                        element.getAnnotationMirrors(), "lombok.Generated"))
                && enclosingElement.getSimpleName().toString().endsWith("Builder");

        if (inLombokBuilder) {
          AnnotatedTypeMirror returnType = t.getReturnType();
          if (returnType == null) {
            throw new TypeSystemError("Return type cannot be null: " + t);
          }
          return enclosingElement.equals(TypesUtils.getTypeElement(returnType.getUnderlyingType()));
        }
        return false;
      }
    };

    /**
     * Returns {@code true} if the method was created by this generator and returns {@code this}.
     *
     * @param t the annotated type of the method signature
     * @return {@code true} if the method was created by this generator and returns {@code this}
     */
    protected abstract boolean returnsThis(AnnotatedExecutableType t);
  }
}
