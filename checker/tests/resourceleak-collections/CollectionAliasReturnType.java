import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import org.checkerframework.checker.calledmethods.qual.*;
import org.checkerframework.checker.calledmethodsonelements.qual.*;
import org.checkerframework.checker.mustcall.qual.*;
import org.checkerframework.checker.mustcallonelements.qual.*;

/*
 * Test the @CollectionAlias annotation
 */
class CollectionAliasReturnTypeTest {
  private final int n = 10;
  private final String myHost = "myHost";
  private final int myPort = 42;

  /*
   * Try to return an OwningCollection reference. Although it would be safe to
   * return an alias if the obligations are fulfilled, it still violates the
   * invariant that there is always exactly one owning reference to a resource
   * collection.
   * We do not allow it.
   */
  @CollectionAlias
  List<Socket> tryToReturnAliasToOwningCollection() {
    @OwningCollection List<Socket> list = new ArrayList<Socket>();
    // :: error: illegal.ownership.transfer
    return list;
  }

  /*
   * Return a write-disabled reference, which is the intended usage of the
   * @CollectionAlias return type annotation. Everything's good.
   */
  @CollectionAlias
  List<Socket> returnAliasToOwningCollection() {
    @OwningCollection List<Socket> list = new ArrayList<Socket>();
    List<Socket> nonOwningList = list;
    return nonOwningList;
  }

  /*
   * Return a write-disabled reference that used to be owning, which is
   * also the intended usage of the @CollectionAlias return type annotation.
   * Everything's good.
   */
  @CollectionAlias
  List<Socket> returnAliasToOwningCollection2() {
    @OwningCollection List<Socket> list = new ArrayList<Socket>();
    @OwningCollection List<Socket> list2 = new ArrayList<Socket>();
    list2 = list; // list becomes a write-disabled alias. Return is fine.
    return list;
  }

  /*
   * Return a list that has no connection to any OwningCollection at all.
   * This is safe, but we report a warning due to the imprecision introduced
   * by this unnecessary return type annotation. At call-site, the returned
   * List will be restricted (write-disabled alias).
   */
  @CollectionAlias
  List<Socket> returnNonResourceCollection() {
    List<Socket> list = new ArrayList<Socket>();
    // :: warning: unnecessary.collectionalias.return.type
    return list;
  }

  void testCollectionAliasReturnTypeBehavior() {
    // declare a non-owning collection
    List<Socket> list = returnAliasToOwningCollection();
    try {
      // :: error: modification.without.ownership
      list.add(new Socket(myHost, myPort));
    } catch (Exception e) {
    }

    // declare an @OwningCollection, but it becomes a write-disabled alias as soon as it
    // is assigned to the write-disabled return value of the method.
    // :: error: unfulfilled.mustcallonelements.obligations
    @OwningCollection List<Socket> owningList = returnAliasToOwningCollection();
    try {
      // :: error: modification.without.ownership
      owningList.add(new Socket(myHost, myPort));
    } catch (Exception e) {
    }
  }
}
