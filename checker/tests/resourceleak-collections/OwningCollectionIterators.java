import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import org.checkerframework.checker.calledmethods.qual.*;
import org.checkerframework.checker.calledmethodsonelements.qual.*;
import org.checkerframework.checker.mustcall.qual.*;
import org.checkerframework.checker.mustcallonelements.qual.*;

class OwningCollectionIteratorTest {
  private final int n = 10;
  private final String myHost = "myHost";
  private final int myPort = 42;

  /*
   * The following method assigns an OwningCollection to the
   * return value of a method. Ensure this works as expected.
   */
  void assignEmptyList() {
    @OwningCollection List<Socket> sockets = new ArrayList<Socket>();
    // Iterator<Socket> iter = sockets.iterator();
  }
}
