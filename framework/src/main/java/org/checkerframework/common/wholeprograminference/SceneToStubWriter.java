package org.checkerframework.common.wholeprograminference;

import com.google.common.collect.ComparisonChain;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.regex.Pattern;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import org.checkerframework.afu.scenelib.Annotation;
import org.checkerframework.afu.scenelib.el.AClass;
import org.checkerframework.afu.scenelib.el.AField;
import org.checkerframework.afu.scenelib.el.AMethod;
import org.checkerframework.afu.scenelib.el.AScene;
import org.checkerframework.afu.scenelib.el.ATypeElement;
import org.checkerframework.afu.scenelib.el.AnnotationDef;
import org.checkerframework.afu.scenelib.el.DefCollector;
import org.checkerframework.afu.scenelib.el.DefException;
import org.checkerframework.afu.scenelib.el.TypePathEntry;
import org.checkerframework.afu.scenelib.field.AnnotationFieldType;
import org.checkerframework.checker.index.qual.SameLen;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.checker.signature.qual.BinaryName;
import org.checkerframework.checker.signature.qual.DotSeparatedIdentifiers;
import org.checkerframework.common.basetype.BaseTypeChecker;
import org.checkerframework.common.value.qual.MinLen;
import org.checkerframework.common.wholeprograminference.scenelib.ASceneWrapper;
import org.checkerframework.framework.type.GenericAnnotatedTypeFactory;
import org.checkerframework.javacutil.AnnotationUtils;
import org.checkerframework.javacutil.BugInCF;

// In this file, "base name" means "type without its package part in binary name format".
// For example, "Outer$Inner" is a base name.

/**
 * Static method {@link #write} writes an {@link AScene} to a file in stub file format. This class
 * is the equivalent of {@code IndexFileWriter} from the Annotation File Utilities, but outputs the
 * results in the stub file format instead of jaif format. This class is not part of the Annotation
 * File Utilities, a library for manipulating .jaif files, because it has nothing to do with .jaif
 * files.
 *
 * <p>This class works by taking as input a scene-lib representation of a type augmented with
 * additional information, stored in javac's format (e.g. as TypeMirrors or Elements). {@link
 * ASceneWrapper} stores this additional information. This class walks the scene-lib representation
 * structurally and outputs the stub file as a string, by combining the information scene-lib stores
 * with the information gathered elsewhere.
 *
 * <p>The additional information is necessary because the scene-lib representation of a type does
 * not have enough information to print full types.
 *
 * <p>This writer is used instead of {@code IndexFileWriter} if the {@code -Ainfer=stubs}
 * command-line argument is present.
 */
public final class SceneToStubWriter {

  /**
   * The entry point to this class is {@link #write}.
   *
   * <p>This is a utility class with only static methods. It is not instantiable.
   */
  private SceneToStubWriter() {
    throw new BugInCF("Do not instantiate");
  }

  /**
   * A pattern matching the name of an anonymous inner class, a local class, or a class nested
   * within one of these types of classes. An anonymous inner class has a basename like Outer$1 and
   * a local class has a basename like Outer$1Inner. See <a
   * href="https://docs.oracle.com/javase/specs/jls/se17/html/jls-13.html#jls-13.1">Java Language
   * Specification, section 13.1</a>.
   */
  private static final Pattern anonymousInnerClassOrLocalClassPattern = Pattern.compile("\\$\\d+");

  /** How far to indent when writing members of a stub file. */
  private static final String INDENT = "  ";

  /**
   * Writes the annotations in {@code scene} to {@code out} in stub file format.
   *
   * @param scene the scene to write out
   * @param filename the name of the file to write (must end with .astub)
   * @param checker the checker, for computing preconditions and postconditions
   */
  public static void write(ASceneWrapper scene, String filename, BaseTypeChecker checker) {
    writeImpl(scene, filename, checker);
  }

  /**
   * Returns the part of a binary name that specifies the package.
   *
   * @param className the binary name of a class
   * @return the part of the name referring to the package, or null if there is no package name
   */
  @SuppressWarnings("signature") // a valid non-empty package name is a dot separated identifier
  private static @Nullable @DotSeparatedIdentifiers String packagePart(
      @BinaryName String className) {
    int lastdot = className.lastIndexOf('.');
    return (lastdot == -1) ? null : className.substring(0, lastdot);
  }

  /**
   * Returns the part of a binary name that specifies the basename of the class.
   *
   * @param className a binary name
   * @return the part of the name representing the class's name without its package
   */
  @SuppressWarnings("signature:return") // A binary name without its package is still a binary name
  private static @BinaryName String basenamePart(@BinaryName String className) {
    int lastdot = className.lastIndexOf('.');
    return className.substring(lastdot + 1);
  }

  /**
   * Returns the String representation of an annotation in Java source format.
   *
   * @param a the annotation to print
   * @return the formatted annotation
   */
  public static String formatAnnotation(Annotation a) {
    StringBuilder sb = new StringBuilder();
    formatAnnotation(sb, a);
    return sb.toString();
  }

  /**
   * Formats an annotation in Java source format.
   *
   * @param sb where to format the annotation to
   * @param a the annotation to print
   */
  public static void formatAnnotation(StringBuilder sb, Annotation a) {
    String fullAnnoName = a.def().name;
    String simpleAnnoName = fullAnnoName.substring(fullAnnoName.lastIndexOf('.') + 1);
    sb.append("@");
    sb.append(simpleAnnoName);
    if (a.fieldValues.isEmpty()) {
      return;
    } else {
      sb.append("(");
      if (a.fieldValues.size() == 1 && a.fieldValues.containsKey("value")) {
        AnnotationFieldType aft = a.def().fieldTypes.get("value");
        aft.format(sb, a.fieldValues.get("value"));
      } else {
        // This simulates: new StringJoiner(", ", "@" + simpleAnnoName + "(", ")")
        for (Map.Entry<String, Object> f : a.fieldValues.entrySet()) {
          AnnotationFieldType aft = a.def().fieldTypes.get(f.getKey());
          sb.append(f.getKey());
          sb.append("=");
          aft.format(sb, f.getValue());
          sb.append(", ");
        }
        sb.delete(sb.length() - 2, sb.length());
      }
      sb.append(")");
    }
  }

  /**
   * Returns all annotations in {@code annos} in a form suitable to be printed as Java source code.
   *
   * <p>Each annotation is followed by a space, to separate it from following Java code.
   *
   * @param annos the annotations to format
   * @return all annotations in {@code annos}, each followed by a space, in a form suitable to be
   *     printed as Java source code
   */
  private static String formatAnnotations(Collection<? extends Annotation> annos) {
    StringBuilder sb = new StringBuilder();
    formatAnnotations(sb, annos);
    return sb.toString();
  }

  /**
   * Prints all annotations in {@code annos} to {@code sb} in a form suitable to be printed as Java
   * source code.
   *
   * <p>Each annotation is followed by a space, to separate it from following Java code.
   *
   * @param sb where to write the formatted annotations
   * @param annos the annotations to format
   */
  private static void formatAnnotations(StringBuilder sb, Collection<? extends Annotation> annos) {
    for (Annotation tla : annos) {
      if (!isInternalJDKAnnotation(tla.def.name)) {
        formatAnnotation(sb, tla);
        sb.append(" ");
      }
    }
  }

  /**
   * Formats the type of an array so that it is printable in Java source code, with the annotations
   * from the scenelib representation added in appropriate places. The result includes a trailing
   * space.
   *
   * @param sb where to format the array type to
   * @param scenelibRep the array's scenelib type element
   * @param javacRep the representation of the array's type used by javac
   */
  private static void formatArrayType(
      StringBuilder sb, ATypeElement scenelibRep, ArrayType javacRep) {
    TypeMirror componentType = javacRep.getComponentType();
    ATypeElement scenelibComponent = getNextArrayLevel(scenelibRep);
    while (componentType.getKind() == TypeKind.ARRAY) {
      componentType = ((ArrayType) componentType).getComponentType();
      scenelibComponent = getNextArrayLevel(scenelibComponent);
    }
    formatType(sb, scenelibComponent, componentType);
    formatArrayTypeImpl(sb, scenelibRep, javacRep);
  }

  /**
   * Formats the type of an array to be printable in Java source code, with the annotations from the
   * scenelib representation added. This method formats only the "array" parts of an array type; it
   * does not format (or attempt to format) the ultimate component type (that is, the non-array part
   * of the array type).
   *
   * @param sb where to format the array type to
   * @param scenelibRep the scene-lib representation
   * @param javacRep the javac representation of the array type
   */
  private static void formatArrayTypeImpl(
      StringBuilder sb, ATypeElement scenelibRep, ArrayType javacRep) {
    TypeMirror javacComponent = javacRep.getComponentType();
    ATypeElement scenelibComponent = getNextArrayLevel(scenelibRep);
    List<? extends AnnotationMirror> explicitAnnos = javacRep.getAnnotationMirrors();
    for (AnnotationMirror explicitAnno : explicitAnnos) {
      sb.append(explicitAnno.toString());
      sb.append(" ");
    }
    if (explicitAnnos.isEmpty() && scenelibRep != null) {
      formatAnnotations(sb, scenelibRep.tlAnnotationsHere);
    }
    sb.append("[] ");
    if (javacComponent.getKind() == TypeKind.ARRAY) {
      formatArrayTypeImpl(sb, scenelibComponent, (ArrayType) javacComponent);
    }
  }

  /** Static mutable variable to improve performance of getNextArrayLevel. */
  private static List<TypePathEntry> location;

  /**
   * Gets the outermost array level (or the component if not an array) from the given type element,
   * or null if scene-lib is not storing any more information about this array (for example, when
   * the component type is unannotated).
   *
   * @param e the array type element; can be null
   * @return the next level of the array, if scene-lib stores information on it. null if the input
   *     is null or scene-lib is not storing more information.
   */
  private static @Nullable ATypeElement getNextArrayLevel(@Nullable ATypeElement e) {
    if (e == null) {
      return null;
    }

    for (Map.Entry<List<TypePathEntry>, ATypeElement> ite : e.innerTypes.entrySet()) {
      location = ite.getKey();
      if (location.contains(TypePathEntry.ARRAY_ELEMENT)) {
        return ite.getValue();
      }
    }
    return null;
  }

  /**
   * Formats a single formal parameter declaration.
   *
   * @param param the AField that represents the parameter
   * @param parameterName the name of the parameter to display in the stub file. Stub files
   *     disregard formal parameter names, so this is aesthetic in almost all cases. The exception
   *     is the receiver parameter, whose name must be "this".
   * @param basename the type name to use for the receiver parameter. Only used when the previous
   *     argument is exactly the String "this".
   * @return the formatted formal parameter, as if it were written in Java source code
   */
  private static String formatParameter(AField param, String parameterName, String basename) {
    StringBuilder sb = new StringBuilder();
    formatParameter(sb, param, parameterName, basename);
    return sb.toString();
  }

  /**
   * Formats a single formal parameter declaration, as if it were written in Java source code.
   *
   * @param sb where to format the formal parameter to
   * @param param the AField that represents the parameter
   * @param parameterName the name of the parameter to display in the stub file. Stub files
   *     disregard formal parameter names, so this is aesthetic in almost all cases. The exception
   *     is the receiver parameter, whose name must be "this".
   * @param basename the type name to use for the receiver parameter. Only used when the previous
   *     argument is exactly the String "this".
   */
  private static void formatParameter(
      StringBuilder sb, AField param, String parameterName, String basename) {
    if (!param.tlAnnotationsHere.isEmpty()) {
      for (Annotation declAnno : param.tlAnnotationsHere) {
        formatAnnotation(sb, declAnno);
        sb.append(" ");
      }
      sb.delete(sb.length() - 1, sb.length());
    }
    formatAFieldImpl(sb, param, parameterName, basename);
  }

  /**
   * Formats a field declaration or formal parameter so that it can be printed in a stub.
   *
   * <p>This method does not add a trailing semicolon or comma.
   *
   * <p>Usually, {@link #formatParameter(AField, String, String)} should be called to format method
   * parameters, and {@link #printField(AField, String, PrintWriter, String)} should be called to
   * print field declarations. Both use this method as their underlying implementation.
   *
   * @param aField the field declaration or formal parameter declaration to format; should not
   *     represent a local variable
   * @param fieldName the name to use for the declaration in the stub file. This doesn't matter for
   *     parameters (except the "this" receiver parameter), but must be correct for fields.
   * @param className the simple name of the enclosing class. This is only used for printing the
   *     type of an explicit receiver parameter (i.e., a parameter named "this").
   * @return a String suitable to print in a stub file
   */
  private static String formatAFieldImpl(AField aField, String fieldName, String className) {
    StringBuilder sb = new StringBuilder();
    formatAFieldImpl(sb, aField, fieldName, className);
    return sb.toString();
  }

  /**
   * Formats a field declaration or formal parameter so that it can be printed in a stub file.
   *
   * <p>This method does not add a trailing semicolon or comma.
   *
   * <p>Usually, {@link #formatParameter(AField, String, String)} should be called to format method
   * parameters, and {@link #printField(AField, String, PrintWriter, String)} should be called to
   * print field declarations. Both use this method as their underlying implementation.
   *
   * @param sb where to write the formatted declaration to
   * @param aField the field declaration or formal parameter declaration to format; should not
   *     represent a local variable
   * @param fieldName the name to use for the declaration in the stub file. This doesn't matter for
   *     parameters (except the "this" receiver parameter), but must be correct for fields.
   * @param className the simple name of the enclosing class. This is only used for printing the
   *     type of an explicit receiver parameter (i.e., a parameter named "this").
   */
  private static void formatAFieldImpl(
      StringBuilder sb, AField aField, String fieldName, String className) {
    if ("this".equals(fieldName)) {
      formatType(sb, aField.type, null, className);
    } else {
      formatType(sb, aField.type, aField.getTypeMirror());
    }
    sb.append(fieldName);
  }

  /**
   * Formats the given type for printing in Java source code.
   *
   * @param aType the scene-lib representation of the type, or null if only the unannotated type is
   *     to be printed
   * @param javacType the javac representation of the type
   * @return the type as it would appear in Java source code, followed by a trailing space
   */
  private static String formatType(@Nullable ATypeElement aType, TypeMirror javacType) {
    StringBuilder sb = new StringBuilder();
    formatType(sb, aType, javacType);
    return sb.toString();
  }

  /**
   * Formats the given type as it would appear in Java source code, followed by a trailing space.
   *
   * @param sb where to format the type to
   * @param aType the scene-lib representation of the type, or null if only the unannotated type is
   *     to be printed
   * @param javacType the javac representation of the type
   */
  private static void formatType(
      StringBuilder sb, @Nullable ATypeElement aType, TypeMirror javacType) {
    // TypeMirror#toString prints multiple annotations on a single type
    // separated by commas rather than by whitespace, as is required in source code.
    String basetypeToPrint = javacType.toString().replaceAll(",@", " @");

    // We must not print annotations in the default package that conflict with
    // imported annotation names.
    for (AnnotationMirror anm : javacType.getAnnotationMirrors()) {
      String annotationName = AnnotationUtils.annotationName(anm);
      String simpleName = annotationName.substring(annotationName.lastIndexOf('.') + 1);
      // This checks if it is in the default package.
      if (simpleName.equals(annotationName)) {
        // In that case, do not print any annotations with the type, to
        // avoid needing to parse an annotation string to remove it.
        // TypeMirror does not provide any methods to remove annotations.
        // This code relies on unannotated Java types not including spaces.
        basetypeToPrint = basetypeToPrint.substring(basetypeToPrint.lastIndexOf(' ') + 1);
      }
    }
    formatType(sb, aType, javacType, basetypeToPrint);
  }

  /**
   * Formats the given type as it would appear in Java source code, followed by a trailing space.
   *
   * <p>This overloaded version of this method exists only for receiver parameters, which are
   * printed using the name of the class as {@code basetypeToPrint} instead of the javac type. The
   * other version of this method should be preferred in every other case.
   *
   * @param sb where to formate the type to
   * @param aType the scene-lib representation of the type, or null if only the unannotated type is
   *     to be printed
   * @param javacType the javac representation of the type, or null if this is a receiver parameter
   * @param basetypeToPrint the string representation of the type
   */
  private static void formatType(
      StringBuilder sb,
      @Nullable ATypeElement aType,
      @Nullable TypeMirror javacType,
      String basetypeToPrint) {
    // anonymous static classes shouldn't be printed with the "anonymous" tag that the AScene
    // library uses
    if (basetypeToPrint.startsWith("<anonymous ")) {
      basetypeToPrint =
          basetypeToPrint.substring("<anonymous ".length(), basetypeToPrint.length() - 1);
    }

    // fields don't need their generic types, and sometimes they are wrong. Just don't print
    // them.
    while (basetypeToPrint.contains("<")) {
      int openCount = 1;
      int pos = basetypeToPrint.indexOf('<');
      while (openCount > 0) {
        pos++;
        if (basetypeToPrint.charAt(pos) == '<') {
          openCount++;
        }
        if (basetypeToPrint.charAt(pos) == '>') {
          openCount--;
        }
      }
      basetypeToPrint =
          basetypeToPrint.substring(0, basetypeToPrint.indexOf('<'))
              + basetypeToPrint.substring(pos + 1);
    }

    // An array is not a receiver, so using the javacType to check for arrays is safe.
    if (javacType != null && javacType.getKind() == TypeKind.ARRAY) {
      formatArrayType(sb, aType, (ArrayType) javacType);
      return;
    }

    if (aType != null) {
      formatAnnotations(sb, aType.tlAnnotationsHere);
    }
    sb.append(basetypeToPrint);
    sb.append(" ");
  }

  /** Writes an import statement for each annotation used in an {@link AScene}. */
  private static class ImportDefWriter extends DefCollector {

    /** The writer onto which to write the import statements. */
    private final PrintWriter printWriter;

    /**
     * Constructs a new ImportDefWriter, which will run on the given AScene when its {@code visit}
     * method is called.
     *
     * @param scene the scene whose imported annotations should be printed
     * @param printWriter the writer onto which to write the import statements
     * @throws DefException if the DefCollector does not succeed
     */
    ImportDefWriter(ASceneWrapper scene, PrintWriter printWriter) throws DefException {
      super(scene.getAScene());
      this.printWriter = printWriter;
    }

    /**
     * Write an import statement for a given AnnotationDef. This is only called once per annotation
     * used in the scene.
     *
     * @param d the annotation definition to print an import for
     */
    @Override
    protected void visitAnnotationDef(AnnotationDef d) {
      if (!isInternalJDKAnnotation(d.name)) {
        printWriter.println("import " + d.name + ";");
      }
    }
  }

  /**
   * Returns true if the given annotation is an internal JDK annotation, whose name includes '+'.
   *
   * @param annotationName the name of the annotation
   * @return true iff this is an internal JDK annotation
   */
  private static boolean isInternalJDKAnnotation(String annotationName) {
    return annotationName.contains("+");
  }

  /**
   * Print the hierarchy of outer classes up to and including the given class, and return the number
   * of curly braces to close with. The classes are printed with appropriate opening curly braces,
   * in standard Java style.
   *
   * <p>In an AScene, an inner class name is a binary name like "Outer$Inner". In a stub file, inner
   * classes must be nested, as in Java source code.
   *
   * @param basename the binary name of the class without the package part
   * @param aClass the AClass for {@code basename}
   * @param printWriter the writer where the class definition should be printed
   * @param checker the type-checker whose annotations are being written
   * @return the number of outer classes within which this class is nested
   */
  private static int printClassDefinitions(
      String basename, AClass aClass, PrintWriter printWriter, BaseTypeChecker checker) {
    String[] classNames = basename.split("\\$");
    TypeElement innermostTypeElt = aClass.getTypeElement();
    if (innermostTypeElt == null) {
      throw new BugInCF("typeElement was unexpectedly null in this aClass: " + aClass);
    }
    TypeElement[] typeElements = getTypeElementsForClasses(innermostTypeElt, classNames);

    for (int i = 0; i < classNames.length; i++) {
      String nameToPrint = classNames[i];
      if (i == classNames.length - 1) {
        printWriter.print(indents(i));
        printWriter.println("@AnnotatedFor(\"" + checker.getClass().getCanonicalName() + "\")");
      }
      printWriter.print(indents(i));
      if (i == classNames.length - 1) {
        // Only print class annotations on the innermost class, which corresponds to aClass.
        // If there should be class annotations on another class, it will have its own stub
        // file, which will eventually be merged with this one.
        printWriter.print(formatAnnotations(aClass.getAnnotations()));
      }
      if (aClass.isAnnotation(nameToPrint)) {
        printWriter.print("@interface ");
      } else if (aClass.isEnum(nameToPrint)) {
        printWriter.print("enum ");
      } else if (aClass.isInterface(nameToPrint)) {
        printWriter.print("interface ");
      } else if (aClass.isRecord(nameToPrint)) {
        printWriter.print("record ");
      } else {
        printWriter.print("class ");
      }
      printWriter.print(nameToPrint);
      printTypeParameters(typeElements[i], printWriter);
      printWriter.println(" {");
      if (aClass.isEnum(nameToPrint) && i != classNames.length - 1) {
        // Print a blank set of enum constants if this is an outer enum.
        printWriter.println(indents(i + 1) + "/* omitted enum constants */ ;");
      }
      printWriter.println();
    }
    return classNames.length;
  }

  /**
   * Constructs an array of TypeElements corresponding to the list of classes.
   *
   * @param innermostTypeElt the innermost type element: either an inner class or an outer class
   *     without any inner classes that should be printed
   * @param classNames the names of the enclosing classes, from outer to inner
   * @return an array of TypeElements whose entry at a given index represents the type named at that
   *     index in {@code classNames}
   */
  private static TypeElement @SameLen("#2") [] getTypeElementsForClasses(
      TypeElement innermostTypeElt, String @MinLen(1) [] classNames) {
    TypeElement[] result = new TypeElement[classNames.length];
    result[classNames.length - 1] = innermostTypeElt;
    Element elt = innermostTypeElt;
    for (int i = classNames.length - 2; i >= 0; i--) {
      elt = elt.getEnclosingElement();
      result[i] = (TypeElement) elt;
    }
    return result;
  }

  /**
   * Prints all the fields of a given class.
   *
   * @param aClass the class whose fields should be printed
   * @param printWriter the writer on which to print the fields
   * @param indentLevel the indent string
   */
  private static void printFields(AClass aClass, PrintWriter printWriter, String indentLevel) {

    if (aClass.getFields().isEmpty()) {
      return;
    }

    printWriter.println(indentLevel + "// fields:");
    printWriter.println();
    for (Map.Entry<String, AField> fieldEntry : aClass.getFields().entrySet()) {
      String fieldName = fieldEntry.getKey();
      AField aField = fieldEntry.getValue();
      printField(aField, fieldName, printWriter, indentLevel);
    }
  }

  /**
   * Prints a field declaration, including a trailing semicolon and a newline.
   *
   * @param aField the field declaration
   * @param fieldName the name of the field
   * @param printWriter the writer on which to print
   * @param indentLevel the indent string
   */
  private static void printField(
      AField aField, String fieldName, PrintWriter printWriter, String indentLevel) {
    if (aField.getTypeMirror() == null) {
      // aField has no type mirror, so there are no inferred annotations and the field need
      // not be printed.
      return;
    }

    for (Annotation declAnno : aField.tlAnnotationsHere) {
      printWriter.print(indentLevel);
      printWriter.println(formatAnnotation(declAnno));
    }

    printWriter.print(indentLevel);
    printWriter.print(formatAFieldImpl(aField, fieldName, /*enclosing class=*/ null));
    printWriter.println(";");
    printWriter.println();
  }

  /**
   * Prints a method declaration in stub file format (i.e., without a method body).
   *
   * @param className the class that contains the method, for diagnostics only
   * @param aMethod the method to print
   * @param simplename the simple name of the enclosing class, for receiver parameters and
   *     constructor names
   * @param printWriter where to print the method signature
   * @param atf the type factory, for computing preconditions and postconditions
   * @param indentLevel the indent string
   */
  @SuppressWarnings("UnusedVariable")
  private static void printMethodDeclaration(
      String className,
      AMethod aMethod,
      String simplename,
      PrintWriter printWriter,
      String indentLevel,
      GenericAnnotatedTypeFactory<?, ?, ?, ?> atf) {

    if (aMethod.getTypeParameters() == null) {
      // aMethod.setFieldsFromMethodElement has not been called
      return;
    }

    for (Annotation declAnno : aMethod.tlAnnotationsHere) {
      printWriter.print(indentLevel);
      printWriter.println(formatAnnotation(declAnno));
    }

    for (AnnotationMirror contractAnno : atf.getContractAnnotations(aMethod)) {
      printWriter.print(indentLevel);
      printWriter.println(contractAnno);
    }

    printWriter.print(indentLevel);

    printTypeParameters(aMethod.getTypeParameters(), printWriter);

    String methodName = aMethod.getMethodName();
    // Use Java syntax for constructors.
    if ("<init>".equals(methodName)) {
      // Set methodName, but don't output a return type.
      methodName = simplename;
    } else {
      printWriter.print(formatType(aMethod.returnType, aMethod.getReturnTypeMirror()));
    }
    printWriter.print(methodName);
    printWriter.print("(");

    StringJoiner parameters = new StringJoiner(", ");
    if (!aMethod.receiver.type.tlAnnotationsHere.isEmpty()) {
      // Only output the receiver if it has an annotation.
      parameters.add(formatParameter(aMethod.receiver, "this", simplename));
    }
    for (Integer index : aMethod.getParameters().keySet()) {
      AField param = aMethod.getParameters().get(index);
      parameters.add(formatParameter(param, param.getName(), simplename));
    }
    printWriter.print(parameters.toString());
    printWriter.println(");");
    printWriter.println();
  }

  /**
   * The implementation of {@link #write}. Prints imports, classes, method signatures, and fields in
   * stub file format, all with appropriate annotations.
   *
   * @param scene the scene to write
   * @param filename the name of the file to write (must end in .astub)
   * @param checker the checker, for computing preconditions
   */
  private static void writeImpl(ASceneWrapper scene, String filename, BaseTypeChecker checker) {
    // Sort by package name first so that output is deterministic and default package
    // comes first; within package sort by class name.
    @SuppressWarnings("signature") // scene-lib bytecode lacks signature annotations
    List<@BinaryName String> classes = new ArrayList<>(scene.getAScene().getClasses().keySet());
    Collections.sort(
        classes,
        (o1, o2) ->
            ComparisonChain.start()
                .compare(
                    packagePart(o1),
                    packagePart(o2),
                    Comparator.nullsFirst(Comparator.naturalOrder()))
                .compare(basenamePart(o1), basenamePart(o2))
                .result());

    boolean anyClassPrintable = false;

    // The writer is not initialized until it is certain that at
    // least one class can be written, to avoid empty stub files.
    // An alternate approach would be to delete the file after it is closed, if the file is
    // empty.
    // It's not worth rewriting this code, since .stub files are obsolescent.

    FileWriter fileWriter = null;
    PrintWriter printWriter = null;
    try {

      // For each class
      for (String clazz : classes) {
        if (isPrintable(clazz, scene.getAScene().getClasses().get(clazz))) {
          if (!anyClassPrintable) {
            try {
              if (fileWriter != null || printWriter != null) {
                throw new Error("This can't happen");
              }
              fileWriter = new FileWriter(filename, StandardCharsets.UTF_8);
              printWriter = new PrintWriter(fileWriter);
            } catch (IOException e) {
              throw new BugInCF("error writing file during WPI: " + filename);
            }

            // Write out all imports
            ImportDefWriter importDefWriter;
            try {
              importDefWriter = new ImportDefWriter(scene, printWriter);
            } catch (DefException e) {
              throw new BugInCF(e);
            }
            importDefWriter.visit();
            printWriter.println("import org.checkerframework.framework.qual.AnnotatedFor;");
            printWriter.println();
            anyClassPrintable = true;
          }
          printClass(clazz, scene.getAScene().getClasses().get(clazz), checker, printWriter);
        }
      }
    } finally {
      if (printWriter != null) {
        printWriter.close(); // does not throw IOException
      }
      try {
        if (fileWriter != null) {
          fileWriter.close();
        }
      } catch (IOException e) {
        // Nothing to do since exceptions thrown from a finally block have no effect.
      }
    }
  }

  /**
   * Returns true if the class is printable in a stub file. A printable class is a class or enum
   * (not a package or module) and is not anonymous.
   *
   * @param classname the class name
   * @param aClass the representation of the class
   * @return true if the class is printable, by the definition above
   */
  private static boolean isPrintable(@BinaryName String classname, AClass aClass) {
    String basename = basenamePart(classname);

    if ("package-info".equals(basename) || "module-info".equals(basename)) {
      return false;
    }

    // Do not attempt to print stubs for anonymous inner classes, local classes, or their inner
    // classes, because the stub parser cannot read them.
    if (anonymousInnerClassOrLocalClassPattern.matcher(basename).find()) {
      return false;
    }

    if (aClass.getTypeElement() == null) {
      throw new BugInCF(
          "Tried printing an unprintable class to a stub file during WPI: " + aClass.className);
    }

    return true;
  }

  /**
   * Print the class body, or nothing if this is an anonymous inner class. Call {@link
   * #isPrintable(String, AClass)} and check that it returns true before calling this method.
   *
   * @param classname the class name
   * @param aClass the representation of the class
   * @param checker the checker, for computing preconditions
   * @param printWriter the writer on which to print
   */
  private static void printClass(
      @BinaryName String classname,
      AClass aClass,
      BaseTypeChecker checker,
      PrintWriter printWriter) {

    String basename = basenamePart(classname);
    String innermostClassname =
        basename.contains("$") ? basename.substring(basename.lastIndexOf('$') + 1) : basename;
    String pkg = packagePart(classname);

    if (pkg != null) {
      printWriter.println("package " + pkg + ";");
    }

    int curlyCount = printClassDefinitions(basename, aClass, printWriter, checker);

    String indentLevel = indents(curlyCount);

    List<VariableElement> enumConstants = aClass.getEnumConstants();
    if (enumConstants != null) {
      StringJoiner sj = new StringJoiner(", ");
      for (VariableElement enumConstant : enumConstants) {
        sj.add(enumConstant.getSimpleName());
      }

      printWriter.println(indentLevel + "// enum constants:");
      printWriter.println();
      printWriter.println(indentLevel + sj.toString() + ";");
      printWriter.println();
    }

    printFields(aClass, printWriter, indentLevel);

    if (!aClass.getMethods().isEmpty()) {
      // print method signatures
      printWriter.println(indentLevel + "// methods:");
      printWriter.println();
      for (Map.Entry<String, AMethod> methodEntry : aClass.getMethods().entrySet()) {
        printMethodDeclaration(
            aClass.className,
            methodEntry.getValue(),
            innermostClassname,
            printWriter,
            indentLevel,
            checker.getTypeFactory());
      }
    }
    for (int i = 0; i < curlyCount; i++) {
      printWriter.println(indents(curlyCount - i - 1) + "}");
    }
  }

  /**
   * Returns a string containing n indents.
   *
   * @param n the number of indents
   * @return a string containing that many indents
   */
  private static String indents(int n) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < n; i++) {
      sb.append(INDENT);
    }
    return sb.toString();
  }

  /**
   * Prints the type parameters of the given class, enclosed in {@code <...>}.
   *
   * @param type the TypeElement representing the class whose type parameters should be printed
   * @param printWriter where to print the type parameters
   */
  private static void printTypeParameters(TypeElement type, PrintWriter printWriter) {
    List<? extends TypeParameterElement> typeParameters = type.getTypeParameters();
    printTypeParameters(typeParameters, printWriter);
  }

  /**
   * Prints the given type parameters.
   *
   * @param typeParameters the type element to print
   * @param printWriter where to print the type parameters
   */
  private static void printTypeParameters(
      List<? extends TypeParameterElement> typeParameters, PrintWriter printWriter) {
    if (typeParameters.isEmpty()) {
      return;
    }
    StringJoiner sj = new StringJoiner(", ", "<", ">");
    for (TypeParameterElement t : typeParameters) {
      sj.add(t.getSimpleName().toString());
    }
    printWriter.print(sj.toString());
  }
}
