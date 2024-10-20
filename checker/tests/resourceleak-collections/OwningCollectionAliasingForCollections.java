import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import org.checkerframework.checker.calledmethods.qual.*;
import org.checkerframework.checker.calledmethodsonelements.qual.*;
import org.checkerframework.checker.mustcall.qual.*;
import org.checkerframework.checker.mustcallonelements.qual.*;

class OwningCollectionAliasingForCollectionsTest {
  private final int n = 10;
  private final String myHost = "myHost";
  private final int myPort = 42;

  void nonOwningCollectionAliasing() {
    @OwningCollection List<Socket> sockets = new ArrayList<Socket>();

    // create non-@OwningCollection read-only alias
    List<Socket> nonOwningCollectionAlias = sockets;
    // this assignment is thus illegal
    // :: error: modification.without.ownership
    nonOwningCollectionAlias.set(0, createSocket());
    // :: error: modification.without.ownership
    nonOwningCollectionAlias.set(0, null);
    // method calls should also not work
    // :: error: missing.collection.ownership.annotation
    // :: error: argument
    doSomethingWeird(nonOwningCollectionAlias);
    // however, I can call methods on the array
    try {
      nonOwningCollectionAlias.get(0).close();
    } catch (Exception e) {
    }

    // create an alias of the read-only alias
    // it will also be a read-only alias
    List<Socket> nonOwningCollectionAlias2 = nonOwningCollectionAlias;
    // this assignment is thus illegal. The ownership of the socket is also
    // not transferred into the collection and throws an error of its own.
    // :: error: modification.without.ownership
    nonOwningCollectionAlias2.set(0, createSocket());
    // check that null-assignments also don't work
    // :: error: modification.without.ownership
    nonOwningCollectionAlias2.set(0, null);
    // method calls should also not work
    // :: error: missing.collection.ownership.annotation
    // :: error: argument
    doSomethingWeird(nonOwningCollectionAlias2);
    // however, I can call methods on the array
    try {
      nonOwningCollectionAlias2.get(0).close();
    } catch (Exception e) {
    }
  }

  // public void owningCollectionAliasing() {
  //   @OwningCollection Socket[] sockets = new Socket[n];

  //   // create an @OwningCollection read-only alias
  //   @OwningCollection Socket[] owningCollectionAlias = sockets;
  //   // this assignment is thus illegal. Since the reference is @OwningCollection array,
  //   // assignments outside an assigning loop are not permitted anyways.
  //   // :: error: required.method.not.called
  //   // :: error: modification.without.ownership
  //   // :: error: illegal.owningcollection.element.assignment
  //   owningCollectionAlias[0] = createSocket();
  //   // method calls should also not work
  //   // :: error: argument.with.revoked.ownership
  //   // :: error: argument
  //   doSomethingWeird(owningCollectionAlias);

  //   // however, I can call methods on the array
  //   try {
  //     owningCollectionAlias[0].close();
  //   } catch (Exception e) {
  //   }

  //   // create a second-degree @OwningCollection read-only alias
  //   @OwningCollection Socket[] owningCollectionAlias2 = owningCollectionAlias;
  //   // this assignment is thus illegal
  //   // :: error: required.method.not.called
  //   // :: error: modification.without.ownership
  //   // :: error: illegal.owningcollection.element.assignment
  //   owningCollectionAlias2[0] = createSocket();
  //   // method calls should also not work
  //   // :: error: argument.with.revoked.ownership
  //   // :: error: argument
  //   doSomethingWeird(owningCollectionAlias2);
  //   // however, I can call methods on the array
  //   try {
  //     owningCollectionAlias2[0].close();
  //   } catch (Exception e) {
  //   }

  //   // I can reassign the ro alias to its own collection
  //   owningCollectionAlias2 = new Socket[n];
  //   // now, its not ro anymore
  //   for (int i = 0; i < n; i++) {
  //     // owningCollectionAlias2[i] = createSocket();
  //     try {
  //       owningCollectionAlias2[i] = new Socket(myHost, myPort);
  //     } catch (Exception e) {
  //     }
  //   }
  //   for (Socket s : owningCollectionAlias2) {
  //     close(s);
  //   }
  // }

  Socket createSocket() {
    Socket s = null;
    try {
      s = new Socket(myHost, myPort);
    } catch (Exception e) {
    }
    return s;
  }

  @EnsuresCalledMethods(
      value = "#1",
      methods = {"close"})
  void close(Socket s) {
    try {
      s.close();
    } catch (Exception e) {
    }
  }

  void doSomethingWeird(List<Socket> sockets) {
    // a simple attempt to assign a resource into a collection that is not @OwningCollection
    // :: error: required.method.not.called
    sockets.set(0, createSocket());
  }

  // /*
  //  * Tries to take on obligation of read-only aliases with a manual
  //  * Mcoe Unknown annotation, which is forbidden however.
  //  */
  // // :: error: manual.mcoeunknown.annotation
  // void tryToCheat(Socket @MustCallOnElementsUnknown [] sockets) {
  //   // the following error doesn't make much sense, as it assumes that
  //   // McoeUnknown means no ownership. Here, it was annotated manually.
  //   // Of course, we forbid the annotation, but the following errors are
  //   // still reported.
  //   // :: error: modification.without.ownership
  //   // :: error: required.method.not.called
  //   sockets[0] = createSocket();
  // }
}
