import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import org.checkerframework.checker.calledmethodsonelements.qual.EnsuresCalledMethodsOnElements;
import org.checkerframework.checker.mustcall.qual.InheritableMustCall;
import org.checkerframework.checker.mustcallonelements.qual.OwningCollection;

class OwningCollectionFields {
  private final int n = 10;
  private final String myHost = "";
  private final int myPort = 1;

  @InheritableMustCall("destruct")
  class OwnershipTaker {
    @OwningCollection private final List<Socket> collection;

    // obligations for this field not fulfilled
    // :: error: unfulfilled.mustcallonelements.obligations
    @OwningCollection private final List<Socket> collection2 = new ArrayList<Socket>();

    public OwnershipTaker(@OwningCollection List<Socket> collection) {
      this.collection = collection;
    }

    public void illegalOverwrite() {
      // cannot overwrite elements of an @OwningCollection field
      // :: error: owningcollection.field.element.overwrite
      collection.set(0, null);
    }

    public void legalAdd() {
      // adding element to collection is legal,
      // since it doesn't overwrite elements
      collection.add(null);
    }

    @EnsuresCalledMethodsOnElements(
        value = "collection",
        methods = {"close"})
    public void destruct() {
      for (Socket s : collection) {
        try {
          s.close();
        } catch (Exception e) {
        }
      }
    }
  }
}
