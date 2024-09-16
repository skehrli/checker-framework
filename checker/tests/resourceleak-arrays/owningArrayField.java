import org.checkerframework.checker.calledmethods.qual.*;
import org.checkerframework.checker.calledmethodsonelements.qual.*;
import org.checkerframework.checker.calledmethodsonelements.qual.EnsuresCalledMethodsOnElements;
import org.checkerframework.checker.mustcall.qual.*;
import org.checkerframework.checker.mustcallonelements.qual.*;

@InheritableMustCall({"close", "foo"})
class Resource {
  public void foo() {}

  public void close() {}
}

class illegalOwningCollectionField {
  // non-final owningcollection field is illegal
  // :: error: owningcollection.field.not.final
  @OwningCollection Resource[] arr;
  // static owningcollection field is illegal
  // :: error: owningcollection.field.static
  // :: error: unfulfilled.mustcallonelements.obligations
  static final @OwningCollection Resource[] arr2 = new Resource[1];
}

@InheritableMustCall("close")
class multipleOwningCollectionFieldAssignment {
  final @OwningCollection Resource[] arr;

  @EnsuresCalledMethodsOnElements(
      value = "arr",
      methods = {"close", "foo"})
  public void close() {
    // :: error: owningcollection.field.assigned.outside.constructor
    arr[0] = null;
    for (int i = 0; i < arr.length; i++) {
      arr[i].close();
    }
    for (int i = 0; i < arr.length; i++) {
      arr[i].foo();
    }
  }

  public multipleOwningCollectionFieldAssignment(int n) {
    arr = new Resource[n];
    // illegal assignment - only in pattern-matched loop allowed
    // :: error: illegal.owningcollection.field.elements.assignment
    arr[0] = null;
    for (int i = 0; i < n; i++) {
      arr[i] = new Resource();
    }
    for (int i = 0; i < n; i++) {
      arr[i].close();
    }
    for (int i = 0; i < n; i++) {
      arr[i].foo();
    }
    for (int i = 0; i < n; i++) {
      // :: error: owningcollection.field.elements.assigned.multiple.times
      arr[i] = new Resource();
    }
  }
}

class NoDestructorMethodForOwningCollectionField {
  // no mustcall annotation on class (for destructor method)
  // :: error: unfulfilled.mustcallonelements.obligations
  final @OwningCollection Resource[] arr = new Resource[10];
}

@InheritableMustCall("close")
class DestructorMethodWithoutEnsuresCmoeForOwningCollectionField {
  // mustcall annotation on class doesn't have a EnsuresCmoe annotation
  // :: error: unfulfilled.mustcallonelements.obligations
  final @OwningCollection Resource[] arr = new Resource[10];

  public void close() {}
}

@InheritableMustCall("close")
class DestructorMethodWithInsufficientEnsuresCmoeForOwningCollectionField {
  // destructor method doesn't cover all calling obligations
  // :: error: unfulfilled.mustcallonelements.obligations
  final @OwningCollection Resource[] arr = new Resource[10];

  @EnsuresCalledMethodsOnElements(value = "arr", methods = "close")
  public void close() {
    for (int i = 0; i < arr.length; i++) {
      arr[i].close();
    }
  }
}

@InheritableMustCall("close")
class DestructorMethodWithInvalidEnsuresCmoeForOwningCollectionField {
  final @OwningCollection Resource[] arr = new Resource[10];

  @EnsuresCalledMethodsOnElements(
      value = "arr",
      methods = {"foo", "close"})
  // destructor method doesn't fulfill post-condition
  // :: error: contracts.postcondition
  public void close() {}
}

@InheritableMustCall({"destruct", "close"})
class ValidOwningCollectionField {
  final @OwningCollection Resource[] arr;

  public Resource[] getField() {
    // :: error: return.owningcollection
    return arr;
  }

  public ValidOwningCollectionField(@OwningCollection Resource[] arr) {
    this.arr = arr;
  }

  public ValidOwningCollectionField(Resource[] arr, int k) {
    // k is here simply because without it, we would have a duplicate constructor.
    // assignment is illegal since the arr parameter is not @OwningCollection
    // :: error: illegal.owningcollection.field.assignment
    this.arr = arr;
  }

  @EnsuresCalledMethodsOnElements(
      value = "arr",
      methods = {"close"})
  public void close() {
    for (int i = 0; i < arr.length; i++) {
      arr[i].close();
    }
  }

  @EnsuresCalledMethodsOnElements(
      value = "arr",
      methods = {"foo"})
  public void destruct() {
    for (int i = 0; i < arr.length; i++) {
      arr[i].foo();
    }
  }
}

class EvilOwningCollectionWrapperClient {
  public EvilOwningCollectionWrapperClient() {
    int n = 10;
    @OwningCollection Resource[] localarr = new Resource[n];
    for (int i = 0; i < n; i++) {
      localarr[i] = new Resource();
    }
    // give up ownership to constructor
    ValidOwningCollectionField d = new ValidOwningCollectionField(localarr);
    // this loop doesn't pattern-match, simply because d.arr is not accepted
    // as an identifier (it is a MemberSelectTree). A warning is issued to inform
    // the programmer that d.arr is an unexpected array expression.
    for (int i = 0; i < d.arr.length; i++) {
      // :: error: required.method.not.called
      // :: error: illegal.owningcollection.field.elements.assignment
      // :: warning: unexpected.array.expression
      d.arr[i] = new Resource();
    }
    // fulfill the obligations of 'd'
    d.destruct();
    d.close();
  }

  public void tryCapturingOwningCollectionField() {
    int n = 10;
    @OwningCollection Resource[] localarr = new Resource[n];
    for (int i = 0; i < n; i++) {
      localarr[i] = new Resource();
    }
    // give up ownership to constructor
    ValidOwningCollectionField d = new ValidOwningCollectionField(localarr);
    // try reassigning the elements of array, despite lost ownership
    for (int i = 0; i < n; i++) {
      // :: error: assignment.without.ownership
      localarr[i] = new Resource();
    }
    // this method call is not allowed either, due to the missing ownership over localarr
    // :: error: argument
    // :: error: argument.with.revoked.ownership
    methodWithOwningCollectionParameter(localarr);

    // reassign localarr to a new array, which is legal.
    // However, after its elements have been assigned, they
    // are never closed, which throws an error.
    // :: error: unfulfilled.mustcallonelements.obligations
    localarr = new Resource[n];
    // assigning its elements is now allowed
    for (int i = 0; i < n; i++) {
      localarr[i] = new Resource();
    }

    // try to capture the @OwningCollection field of 'd'
    // since the RHS of the assignment is @OwningCollection, that is illegal aliasing.
    // :: error: illegal.aliasing
    Resource[] capture = d.arr;
    // this illegal assignment counts as a field assignment
    // and since the field is @OwningCollection, it is forbidden
    // :: error: owningcollection.field.assigned.outside.constructor
    d.arr[0] = null;

    // fulfill the obligations of 'd'
    d.destruct();
    d.close();
  }

  private void methodWithOwningCollectionParameter(@OwningCollection Resource[] arr) {
    for (int i = 0; i < arr.length; i++) {
      arr[i].close();
    }
    for (int i = 0; i < arr.length; i++) {
      arr[i].foo();
    }
  }
}

class OwningCollectionFieldClient {
  public void m() {
    int n = 10;
    @OwningCollection Resource[] localarr = new Resource[n];
    for (int i = 0; i < n; i++) {
      localarr[i] = new Resource();
    }
    // d.close() would also have to be called
    // :: error: required.method.not.called
    ValidOwningCollectionField d = new ValidOwningCollectionField(localarr);
    d.destruct();
  }
}
