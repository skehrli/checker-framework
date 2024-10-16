import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import org.checkerframework.checker.calledmethodsonelements.qual.EnsuresCalledMethodsOnElements;
import org.checkerframework.checker.mustcall.qual.InheritableMustCall;
import org.checkerframework.checker.mustcallonelements.qual.CollectionAlias;
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

    /*
     * This constructor tries to assign the field to a collection alias.
     * This would make the field also a write-disabled alias. This is illegal,
     * since we are not able to enforce the field respecting this.
     * A field has to be assigned to something it can get ownership over.
     *
     * Ignore int i, it is only there so that this constructor has a different signature
     * to the first one.
     */
    public OwnershipTaker(@CollectionAlias List<Socket> collection, int i) {
      // :: error: illegal.owningcollection.field.assignment
      this.collection = collection;
    }

    public void illegalOverwrite() {
      // field has possibly open obligation "close", cannot overwrite elements
      // :: error: unsafe.owningcollection.modification
      collection.set(0, null);
    }

    public void legalAdd() {
      // adding element to collection is legal,
      // since it doesn't overwrite elements
      collection.add(null);
    }

    /*
     * Cannot pass ownership of a field, since the field will still think its the owner.
     * Would result in two owners of the same underlying collection.
     */
    public void illegalConstructorInvocation() {
      // :: error: illegal.ownership.transfer
      new OwnershipTaker(this.collection).destruct();
    }

    /*
     * Cannot pass ownership of a field, since the field will still think its the owner.
     * Would result in two owners of the same underlying collection.
     */
    public void illegalMethodInvocation() {
      // :: error: illegal.ownership.transfer
      takeOwnership(this.collection);
    }

    /*
     * Cannot pass ownership of a field, since the field will still think its the owner.
     * Would result in two owners of the same underlying collection.
     */
    public void illegalAssignment() {
      // :: error: illegal.ownership.transfer
      @OwningCollection List<Socket> local = this.collection;
      for (Socket s : local) {
        try {
          s.close();
        } catch (Exception e) {
        }
      }
    }

    public void takeOwnership(@OwningCollection List<Socket> list) {
      new OwnershipTaker(list).destruct();
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
