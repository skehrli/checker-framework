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

  void fieldWrapperClient() {
    @OwningCollection List<Socket> list = new ArrayList<Socket>();
    OwningCollectionFieldWrapper wrapper = new OwningCollectionFieldWrapper(list);

    try {
      // :: error: modification.without.ownership
      wrapper.socketList.add(new Socket(myHost, myPort));
    } catch (Exception e) {
    }

    // this is allowed
    List<Socket> alias = wrapper.socketList;

    // this is not allowed, since it would transfer ownership to lhs
    // :: error: illegal.ownership.transfer
    @OwningCollection List<Socket> illegalAlias = wrapper.socketList;

    // :: error: unfulfilled.mustcallonelements.obligations
    @OwningCollection List<Socket> owningAlias = wrapper.getField();
    try {
      // :: error: modification.without.ownership
      owningAlias.add(new Socket(myHost, myPort));
    } catch (Exception e) {
    }

    wrapper.destruct();
    // wrapper.reassignField();
  }

  @InheritableMustCall("destruct")
  class OwningCollectionFieldWrapper {
    final @OwningCollection List<Socket> socketList;

    public OwningCollectionFieldWrapper(@OwningCollection List<Socket> list) {
      this.socketList = list;
    }

    // this method is illegal. Cannot return an owning reference to field.
    @OwningCollection
    List<Socket> getOwningField() {
      // :: error: owningcollection.field.returned
      // :: error: return
      return this.socketList;
    }

    // this method is illegal. Cannot return an OwningCollection reference with an unannotated
    // return type.
    List<Socket> getUnannotatedField() {
      // :: error: owningcollection.return.value
      // :: error: return
      return this.socketList;
    }

    // this method is legal. Can return a non-owning reference to field.
    @CollectionAlias
    List<Socket> getField() {
      return this.socketList;
    }

    @EnsuresCalledMethodsOnElements(
        value = "socketList",
        methods = {"close"})
    public void destruct() {
      for (Socket s : socketList) {
        try {
          s.close();
        } catch (Exception e) {
        }
      }
    }

    // public void reassignFieldIllegally() {
    //   // maybe the call site knows that the obligations of field have been fulfilled.
    //   // If we force this method to have a @CreatesMustCallOnElementsFor("this") annotation,
    //   // at call-site,
    //   try {
    //     // :: error:
    //     socketList.add(new Socket(myHost, myPort));
    //   } catch (Exception e) {}
    // }

    // @CreatesMustCallOnElementsFor("this")
    // public void reassignField() {
    //   // maybe the call site knows that the obligations of field have been fulfilled.
    //   // If we force this method to have a @CreatesMustCallOnElementsFor("this") annotation,
    //   // at call-site,
    //   try {
    //     socketList.add(new Socket(myHost, myPort));
    //   } catch (Exception e) {}
    // }
  }

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
