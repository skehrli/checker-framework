import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import org.checkerframework.checker.mustcall.qual.Owning;
import org.checkerframework.checker.mustcallonelements.qual.OwningCollection;

class OwningCollectionTransformers {
  private final int n = 10;
  private final String myHost = "";
  private final int myPort = 1;
  // :: error: owning.collection
  @Owning List<Socket> s;

  public void illegalOwningCollectionAssignment() {
    List<Socket> s = new ArrayList<Socket>();
    // this is a false positive, but we only allow assignment
    // of an @OwningCollection to a new Collection/Listay.
    // :: error: illegal.owningcollection.assignment
    @OwningCollection List<Socket> list = s;
  }

  // test that aliasing is not allowed.
  // public void illegalAliasing() {
  //   @OwningCollection List<Socket> list = new ArrayList<Socket>();
  //   // :: error: illegal.owningcollection.assignment
  //   @OwningCollection List<Socket> list2 = list;
  //   // :: error: illegal.aliasing
  //   List<Socket> list3 = list;
  // }

  public void checkListSetTransformer() {
    // calling obligations fulfilled due to call to OwnershipTaker
    @OwningCollection List<Socket> list = new ArrayList<Socket>();
    try {
      list.add(new Socket(myHost, myPort));

      // try to override an element while the list has open calling obligations
      // :: error: illegal.owningcollection.write
      list.set(0, new Socket(myHost, myPort));
    } catch (Exception e) {
    }

    // try to override an element while the list has open calling obligations
    // :: error: illegal.owningcollection.write
    list.set(0, null);

    // lose ownership and then try to set an element, to verify that the write is rejected
    // for the sake of being a write on a write-disabled reference.
    new OwnershipTaker(list);
    // :: error: assignment.without.ownership
    list.set(0, null);

    // verify that the ownership remains revoked after the call to set
    // :: error: argument.with.revoked.ownership
    // :: error: argument
    close(list);
  }

  public void unfulfilledAllocationLoop() {
    // :: error: unfulfilled.mustcallonelements.obligations
    @OwningCollection List<Socket> list = new ArrayList<Socket>();
    for (int i = 0; i < n; i++) {
      try {
        list.add(new Socket(myHost, myPort));
      } catch (Exception e) {
      }
    }
  }

  public void reassignOwningCollectionWithOpenObligations() {
    // try to trick the checker by closing the elements of the list
    // after reassigning it.
    // :: error: unfulfilled.mustcallonelements.obligations
    @OwningCollection List<Socket> list = new ArrayList<Socket>();
    for (int i = 0; i < n; i++) {
      try {
        list.add(new Socket(myHost, myPort));
      } catch (Exception e) {
      }
    }
    list = new ArrayList<Socket>();
    for (int i = 0; i < list.size(); i++) {
      try {
        list.get(i).close();
      } catch (Exception e) {
      }
    }
  }

  // test that opening and subsequent closing is allowed
  public void validClosing() {
    @OwningCollection List<Socket> list = new ArrayList<Socket>();
    for (int i = 0; i < n; i++) {
      try {
        list.add(new Socket(myHost, myPort));
      } catch (Exception e) {
      }
    }
    for (int i = 0; i < list.size(); i++) {
      try {
        list.get(i).close();
      } catch (Exception e) {
      }
    }
  }

  public void validDeallocationLoop() {
    @OwningCollection List<Socket> list = new ArrayList<Socket>();
    for (int i = 0; i < n; i++) {
      try {
        list.add(new Socket(myHost, myPort));
      } catch (Exception e) {
      }
    }
    // this deallocation loop is legal and should be pattern-matched
    for (int i = 0; i < list.size(); i++) {
      try {
        try {
          list.get(i).close();
        } catch (Exception e) {
        }
      } catch (Exception e) {
      }
    }
  }

  public void invalidDeallocationLoop() {
    // :: error: unfulfilled.mustcallonelements.obligations
    @OwningCollection List<Socket> list = new ArrayList<Socket>();
    for (int i = 0; i < n; i++) {
      try {
        list.add(new Socket(myHost, myPort));
      } catch (Exception e) {
      }
    }
    // this deallocation loop is illegal and is not pattern-matched
    for (int i = 0; i < list.size(); i++) {
      try {
        list.get(i).close();
        i++;
      } catch (Exception e) {
      }
    }
  }

  public void invalidDeallocationLoop2() {
    // :: error: unfulfilled.mustcallonelements.obligations
    @OwningCollection List<Socket> list = new ArrayList<Socket>();
    for (int i = 0; i < n; i++) {
      try {
        list.add(new Socket(myHost, myPort));
      } catch (Exception e) {
      }
    }
    // this deallocation loop is illegal and is not pattern-matched
    for (int i = 0; i < list.size(); i++) {
      try {
        list.get(i).close();
      } catch (Exception e) {
      } finally {
        break;
      }
    }
  }

  public void validDeallocationLoop3() throws Exception {
    @OwningCollection List<Socket> list = new ArrayList<Socket>();
    for (int i = 0; i < n; i++) {
      try {
        list.add(new Socket(myHost, myPort));
      } catch (Exception e) {
      }
    }
    // this deallocation loop is legal, as we guarantee cmoe only
    // for normal termination
    for (int i = 0; i < list.size(); i++) {
      try {
        list.get(i).close();
      } catch (Exception e) {
        throw new Exception("this exception does not prevent a pattern-match");
      }
    }
  }

  public void invalidDeallocationLoop4() {
    // :: error: unfulfilled.mustcallonelements.obligations
    @OwningCollection List<Socket> list = new ArrayList<Socket>();
    for (int i = 0; i < n; i++) {
      try {
        list.add(new Socket(myHost, myPort));
      } catch (Exception e) {
      }
    }
    // this deallocation loop is illegal and is not pattern-matched
    for (int i = 0; i < list.size(); i++) {
      try {
        list.get(i).close();
      } catch (Exception e) {
      } finally {
        i += 2;
      }
    }
  }

  public void invalidDeallocationLoop5() {
    // :: error: unfulfilled.mustcallonelements.obligations
    @OwningCollection List<Socket> list = new ArrayList<Socket>();

    try {
      list.add(new Socket(myHost, myPort));
    } catch (Exception e) {
    }

    // this deallocation loop is illegal and is not pattern-matched
    for (int i = 0; i < list.size(); i++) {
      try {
        list.get(i).close();
      } catch (Exception e) {
        // this reassignment is never fulfilled
        // :: error: unfulfilled.mustcallonelements.obligations
        list = new ArrayList<Socket>();
      }
    }
  }

  public void invalidClosingAndReopening() {
    // :: error: unfulfilled.mustcallonelements.obligations
    @OwningCollection List<Socket> list = new ArrayList<Socket>();
    for (int i = 0; i < n; i++) {
      try {
        list.add(new Socket(myHost, myPort));
      } catch (Exception e) {
      }
    }
    for (int i = 0; i < list.size(); i++) {
      try {
        list.get(i).close();
      } catch (Exception e) {
      }
    }
    for (int i = 0; i < n; i++) {
      try {
        list.add(new Socket(myHost, myPort));
      } catch (Exception e) {
      }
    }
  }

  public void invalidReallocateElements() {
    // :: error: unfulfilled.mustcallonelements.obligations
    @OwningCollection List<Socket> list = new ArrayList<Socket>();
    for (int i = 0; i < n; i++) {
      try {
        list.add(new Socket(myHost, myPort));
      } catch (Exception e) {
      }
    }
    for (int i = 0; i < n; i++) {
      try {
        // :: error: illegal.owningcollection.write
        list.set(i, new Socket(myHost, myPort));
      } catch (Exception e) {
      }
    }
  }

  // test that passing ownership works
  public void validClosingByEnsuresCmoeMethod() {
    @OwningCollection List<Socket> list = new ArrayList<Socket>();
    for (int i = 0; i < n; i++) {
      try {
        list.add(new Socket(myHost, myPort));
      } catch (Exception e) {
      }
    }
    close(list);
  }

  public void close(@OwningCollection List<Socket> list) {
    for (int i = 0; i < list.size(); i++) {
      try {
        list.get(i).close();
      } catch (Exception e) {
      }
    }
  }

  static class OwnershipTaker {
    // private final Collection<Socket> collection;

    public OwnershipTaker(@OwningCollection List<Socket> collection) {
      for (Socket s : collection) {
        try {
          s.close();
        } catch (Exception e) {
        }
      }
      // this.collection = collection;
    }
  }
}
