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
class CollectionAliasParameterTest {
  private final int n = 10;
  private final String myHost = "myHost";
  private final int myPort = 42;

  /*
   * This method accepts a @CollectionAlias. At call-site, ownership does not get transferred.
   */
  void acceptOwningCollectionAlias(@CollectionAlias List<Socket> list) {
    // test that the alias is not write-enabled
    try {
      // :: error: modification.without.ownership
      list.set(0, new Socket(myHost, myPort));
    } catch (Exception e) {
    }
  }

  void testCallSiteOwnershipBehavior() {
    // pass a write-disabled alias to the @OwningCollection.
    // check that ownership remains at call-site and write is allowed.
    @OwningCollection List<Socket> list = new ArrayList<Socket>();
    acceptOwningCollectionAlias(list);
    try {
      list.set(0, new Socket(myHost, myPort));
    } catch (Exception e) {
    }

    List<Socket> alias = list; // alias has no ownership. it can also be passed
    // to the method
    acceptOwningCollectionAlias(alias);
    // test that alias is still not write-enabled
    try {
      // :: error: unsafe.owningcollection.modification
      list.set(0, new Socket(myHost, myPort));
    } catch (Exception e) {
    }

    // finally, close obligations of list
    for (Socket s : list) {
      try {
        s.close();
      } catch (Exception e) {
      }
    }
  }

  /*
   * Pass a read-only alias to a method.
   */
  void partialFulfillment() {
    @OwningCollection List<Socket> sockets = new ArrayList<Socket>(); // declare owning socket list
    fulfillPartially(sockets);
  }

  @EnsuresCalledMethodsOnElements(value = "#1", methods = "close")
  void fulfillPartially(@CollectionAlias List<Socket> sockets) {
    for (Socket s : sockets) {
      try {
        s.close();
      } catch (Exception e) {
      }
    }
  }
}
