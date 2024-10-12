import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import org.checkerframework.checker.calledmethods.qual.*;
import org.checkerframework.checker.calledmethodsonelements.qual.*;
import org.checkerframework.checker.mustcall.qual.*;
import org.checkerframework.checker.mustcallonelements.qual.*;

class OwningCollectionReturnTest {
  private final int n = 10;
  private final String myHost = "myHost";
  private final int myPort = 42;

  /** Check that return type at call-site is not ignored. */
  void ignoreOwningReturn() {
    // :: error: unfulfilled.mustcallonelements.obligations
    getOwningSocketList();
  }

  /*
   * The following method assigns an OwningCollection to the
   * return value of a method. Ensure this works as expected.
   */
  void assignEmptyList() {
    @OwningCollection List<Socket> sockets = new ArrayList<Socket>(); // declare owning socket list
    // :: error: unfulfilled.mustcallonelements.obligations
    sockets = getOwningSocketList(); // works since lhs has no open obligations
  }

  void checkTernary(boolean b) {
    @OwningCollection List<Socket> sockets = new ArrayList<Socket>(); // declare owning socket list
    // sockets is allowed to be reassigned, since it doesn't have open obligations.
    // it possibly takes ownership of a list returned by getOwningSocketList, whose
    // obligations it does not fulfill. This reports an error.
    // :: error: unfulfilled.mustcallonelements.obligations
    sockets = b ? null : getOwningSocketList(); // works since lhs has no open obligations
  }

  /*
   * The following method assigns an OwningCollection with open calling obligations to the
   * return value of a method. Ensure this reports an error for unfulfilled calling obligations.
   */
  void assignNonEmptyList() {
    // :: error: unfulfilled.mustcallonelements.obligations
    @OwningCollection List<Socket> sockets = new ArrayList<Socket>(); // declare owning socket list

    try {
      sockets.add(new Socket(myHost, myPort));
    } catch (Exception e) {
    }

    // the previously owned elements of socket go out of scope without having their
    // obligations fulfilled, which reports an error at the declaration point of sockets.
    // The reference is now used to own the list returned by getOwningSocketList().
    // Since these obligations are also not fulfilled, there is another error for unfulfilled
    // obligations of the list returned by getOwningSocketList.
    // :: error: unfulfilled.mustcallonelements.obligations
    sockets = getOwningSocketList();
  }

  /*
   * The following method assigns an OwningCollection with open calling obligations to the
   * return value of a method. Ensure this reports an error for unfulfilled calling obligations.
   */
  void assignNonEmptyList2() {
    // :: error: unfulfilled.mustcallonelements.obligations
    @OwningCollection List<Socket> sockets = new ArrayList<Socket>(); // declare owning socket list

    try {
      sockets.add(new Socket(myHost, myPort));
    } catch (Exception e) {
    }

    // the previously owned elements of socket go out of scope without having their
    // obligations fulfilled, which reports an error at the declaration point of sockets.
    // The reference is now used to own the list returned by getOwningSocketList().
    // It fulfills the calling obligations for that list in a loop below.
    sockets = getOwningSocketList();

    for (Socket s : sockets) {
      try {
        s.close();
      } catch (Exception e) {
      }
    }
  }

  /*
   * Reassign list to the parameter is passed (and was returned again).
   */
  void partialFulfillment() {
    @OwningCollection List<Socket> sockets = new ArrayList<Socket>(); // declare owning socket list
    sockets = fulfillPartially(sockets);
  }

  @OwningCollection
  List<Socket> fulfillPartially(@OwningCollection List<Socket> sockets) {
    for (Socket s : sockets) {
      try {
        s.close();
      } catch (Exception e) {
      }
    }
    return sockets;
  }

  @OwningCollection
  @MustCallOnElements("close")
  List<Socket> getOwningSocketList() {
    @OwningCollection List<Socket> sockets = new ArrayList<Socket>(); // declare owning socket list
    return sockets; // return the socket and ownership over it
  }

  /*
   * The following method tries to return a not-@OwningCollection list, even though the
   * return value is annotated @OwningCollection, which is forbidden. It is not directly
   * unsound, but it is not consistent with the invariants of the checker. A collection
   * without ownership should not be able to pass it on.
   */
  @OwningCollection
  @MustCallOnElements("close")
  List<Socket> tryToReturnOwning() {
    List<Socket> sockets = new ArrayList<Socket>(); // declare non-owning socket list
    // :: error: non.owningcollection.return.value
    return sockets;
  }

  /*
   * The following method tries to return an @OwningCollection list, even though the
   * return value is not annotated @OwningCollection, which is forbidden.
   * This would result in no obligations at call-site.
   *
   * TODO We could allow this case in the future and treat a non-@OwningCollection return
   * of an @OwningCollection as leaving scope, i.e. an error is reported iff the collection
   * has not completed all its calling obligations at the time of method return.
   */
  @MustCallOnElements("close")
  List<Socket> tryToReturnNotOwning() {
    @OwningCollection List<Socket> sockets = new ArrayList<Socket>(); // declare owning socket list
    // :: error: owningcollection.return.value
    return sockets;
  }
}
