package org.checkerframework.checker.mustcallonelements;

import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedArrayType;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedDeclaredType;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedPrimitiveType;
import org.checkerframework.framework.type.typeannotator.TypeAnnotator;

/** Primitive types always have no must-call obligations. */
public class MustCallOnElementsTypeAnnotator extends TypeAnnotator {

  /**
   * Create a MustCallOnElementsTypeAnnotator.
   *
   * @param typeFactory the type factory
   */
  protected MustCallOnElementsTypeAnnotator(MustCallOnElementsAnnotatedTypeFactory typeFactory) {
    super(typeFactory);
  }

  @Override
  public Void visitArray(AnnotatedArrayType type, Void aVoid) {
    type.replaceAnnotation(((MustCallOnElementsAnnotatedTypeFactory) atypeFactory).BOTTOM);
    return super.visitArray(type, aVoid);
  }

  @Override
  public Void visitDeclared(AnnotatedDeclaredType type, Void aVoid) {
    type.replaceAnnotation(((MustCallOnElementsAnnotatedTypeFactory) atypeFactory).BOTTOM);
    return super.visitDeclared(type, aVoid);
  }

  @Override
  public Void visitPrimitive(AnnotatedPrimitiveType type, Void aVoid) {
    type.replaceAnnotation(((MustCallOnElementsAnnotatedTypeFactory) atypeFactory).BOTTOM);
    return super.visitPrimitive(type, aVoid);
  }
}
