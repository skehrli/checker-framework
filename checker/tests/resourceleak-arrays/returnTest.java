import java.net.Socket;
import org.checkerframework.checker.calledmethods.qual.*;
import org.checkerframework.checker.calledmethodsonelements.qual.*;
import org.checkerframework.checker.mustcall.qual.*;
import org.checkerframework.checker.mustcallonelements.qual.*;

class ReturnTest {
  private final int n = 10;
  private final String myHost = "myHost";
  private final int myPort = 42;

  /*
   * Try to return an @OwningCollection with open obligations. Returning any @OwningCollection
   * is not allowed for this reason.
   */
  Socket @MustCallOnElements({"close", "foo"}) [] returnOwningCollection() {
    @OwningCollection Socket[] arr = new Socket[n];
    for (int i = 0; i < n; i++) {
      try {
        arr[i] = new Socket(myHost, myPort);
      } catch (Exception e) {
      }
    }
    // :: error: return.owningcollection
    return arr;
  }

  /*
   * Illustrate how allowing a return of an @OwningCollection is never sound.
   * This method does not throw an error. The only thing preventing this horrible
   * unsoundness is that returning @OwningCollection is forbidden.
   */
  void assignNonOwningCollectionToCollectionWithObligations() {
    Socket[] arr = returnOwningCollection();
  }

  // /*
  //  * Try returning a read-only alias, which is forbidden.
  //  */
  // void tryToReturnReadOnlyAlias() {
  //   @OwningCollection Socket[] arr = new Socket[n];
  //   for (int i = 0; i < n; i++) {
  //     try {
  //       arr[i] = new Socket(myHost, myPort);
  //     } catch (Exception e) {}
  //   }

  //   Socket[] roAlias = arr;

  //   // return type is expected to be MustCallOnElements({}), but is
  //   // MustCallOnElementsUnknown. Since this is not very helpful, we report
  //   // the additional warning that the returned value does not have ownership.
  //   // :: error: return.without.ownership
  //   // :: error: incompatible types: unexpected return value
  //   return roAlias;
  // }
}
