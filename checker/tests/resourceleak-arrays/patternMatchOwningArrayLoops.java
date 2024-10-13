import java.net.Socket;
import org.checkerframework.checker.mustcall.qual.Owning;
import org.checkerframework.checker.mustcallonelements.qual.OwningCollection;

class PatternMatchOwningCollectionLoops {
  private final int n = 10;
  private final String myHost = "";
  private final int myPort = 1;
  // :: error: owning.collection
  @Owning Socket[] s;

  // @OwningCollection non-(1dArray/collection) is not allowed
  public void owningArrayNonArray() {
    // :: error: owningcollection.noncollection
    @OwningCollection Socket s;
    // :: error: owningcollection.noncollection
    @OwningCollection Socket[][] sMultiDimensional;
  }

  public void legalOwningCollectionAssignment() {
    Socket[] s = new Socket[n];
    // this is legal. A non-@OwningCollection cannot possibly have calling obligations,
    // so giving an @OwningCollection ownership over it is safe.
    @OwningCollection Socket[] arr = s;
  }

  /*
   * TODO in the future we would like to be able to have an arbitrary rhs in the
   * assignment in an allocating for loop. This is currently not easily achievable
   * since the pattern match code sits in the MustCallVisitor, which runs even before
   * the MustCall analysis and thus has no information about the mustcall type of the
   * rhs. We would have to move the pattern match code for this.
   */
  // public void checkAssignmentLoop() {
  //   // :: error: unfulfilled.mustcallonelements.obligations
  //   @OwningCollection Socket[] arr = new Socket[n];
  //   for (int i = 0; i < n; i++) {
  //     try {
  //       arr[i] = getSocket();
  //     } catch (Exception e) {
  //     }
  //   }
  // }

  public Socket getSocket() {
    try {
      return new Socket(myHost, myPort);
    } catch (Exception e) {
      return null;
    }
  }

  public void checkAssignment() {
    // :: error: unfulfilled.mustcallonelements.obligations
    @OwningCollection Socket[] arr = new Socket[n];

    // write is safe since arr has no open calling obligations
    try {
      arr[0] = getSocket();
    } catch (Exception e) {
    }
  }

  // test that declaring an @OwningCollection is alright
  public void illegalOwningCollectionElementAssignment() {
    // :: error: unfulfilled.mustcallonelements.obligations
    @OwningCollection Socket[] arr = new Socket[n];
    try {
      // since arr has no previous calling obligations, this write is safe
      arr[0] = new Socket(myHost, myPort);
    } catch (Exception e) {
    }

    try {
      // since arr now has open calling obligations, this write is not allowed,
      // since it could overwrite previous elements
      // :: error: illegal.owningcollection.overwrite
      arr[1] = new Socket(myHost, myPort);
    } catch (Exception e) {
    }
    // :: error: illegal.owningcollection.overwrite
    arr[0] = null;
  }

  public void unfulfilledAllocationLoop() {
    // :: error: unfulfilled.mustcallonelements.obligations
    @OwningCollection Socket[] arr = new Socket[n];
    for (int i = 0; i < n; i++) {
      try {
        arr[i] = new Socket(myHost, myPort);
      } catch (Exception e) {
      }
    }
  }

  /*
   * Check that fulfilling loop with null-check is also accepted
   */
  public void fulfillWithNullCheck() {
    @OwningCollection Socket[] arr = new Socket[n];
    for (int i = 0; i < n; i++) {
      try {
        arr[i] = new Socket(myHost, myPort);
      } catch (Exception e) {
      }
    }

    for (Socket s : arr) {
      if (s != null) {
        try {
          s.close();
        } catch (Exception e) {
        }
      }
    }
  }

  public void reassignOwningCollectionWithOpenObligations() {
    // try to trick the checker by closing the elements of the array
    // after reassigning it.
    // :: error: unfulfilled.mustcallonelements.obligations
    @OwningCollection Socket[] arr = new Socket[n];
    for (int i = 0; i < n; i++) {
      try {
        arr[i] = new Socket(myHost, myPort);
      } catch (Exception e) {
      }
    }
    arr = new Socket[n];
    for (int i = 0; i < n; i++) {
      try {
        arr[i].close();
      } catch (Exception e) {
      }
    }
  }

  // test that opening and subsequent closing is alright
  public void validClosing() {
    @OwningCollection Socket[] arr = new Socket[n];
    for (int i = 0; i < n; i++) {
      try {
        arr[i] = new Socket(myHost, myPort);
      } catch (Exception e) {
      }
    }
    for (int i = 0; i < n; i++) {
      try {
        arr[i].close();
      } catch (Exception e) {
      }
    }
  }

  public void validDeallocationLoop() {
    @OwningCollection Socket[] arr = new Socket[n];
    for (int i = 0; i < n; i++) {
      try {
        arr[i] = new Socket(myHost, myPort);
      } catch (Exception e) {
      }
    }
    // this deallocation loop is legal and should be pattern-matched
    for (int i = 0; i < n; i++) {
      try {
        try {
          arr[i].close();
        } catch (Exception e) {
        }
      } catch (Exception e) {
      }
    }
  }

  public void invalidDeallocationLoop() {
    // :: error: unfulfilled.mustcallonelements.obligations
    @OwningCollection Socket[] arr = new Socket[n];
    for (int i = 0; i < n; i++) {
      try {
        arr[i] = new Socket(myHost, myPort);
      } catch (Exception e) {
      }
    }
    // this deallocation loop is illegal and is not pattern-matched
    for (int i = 0; i < n; i++) {
      try {
        arr[i].close();
        i++;
      } catch (Exception e) {
      }
    }
  }

  public void invalidDeallocationLoop2() {
    // :: error: unfulfilled.mustcallonelements.obligations
    @OwningCollection Socket[] arr = new Socket[n];
    for (int i = 0; i < n; i++) {
      try {
        arr[i] = new Socket(myHost, myPort);
      } catch (Exception e) {
      }
    }
    // this deallocation loop is illegal and is not pattern-matched
    for (int i = 0; i < n; i++) {
      try {
        arr[i].close();
      } catch (Exception e) {
      } finally {
        break;
      }
    }
  }

  public void validDeallocationLoop3() throws Exception {
    @OwningCollection Socket[] arr = new Socket[n];
    for (int i = 0; i < n; i++) {
      try {
        arr[i] = new Socket(myHost, myPort);
      } catch (Exception e) {
      }
    }
    // this deallocation loop is legal, as we guarantee cmoe only
    // for normal termination
    for (int i = 0; i < n; i++) {
      try {
        arr[i].close();
      } catch (Exception e) {
        throw new Exception("this exception does not prevent a pattern-match");
      }
    }
  }

  public void invalidDeallocationLoop4() {
    // :: error: unfulfilled.mustcallonelements.obligations
    @OwningCollection Socket[] arr = new Socket[n];
    for (int i = 0; i < n; i++) {
      try {
        arr[i] = new Socket(myHost, myPort);
      } catch (Exception e) {
      }
    }
    // this deallocation loop is illegal and is not pattern-matched
    for (int i = 0; i < n; i++) {
      try {
        arr[i].close();
      } catch (Exception e) {
      } finally {
        i += 2;
      }
    }
  }

  // test that opening and subsequent closing is alright
  public void invalidClosingAndReopening() {
    // :: error: unfulfilled.mustcallonelements.obligations
    @OwningCollection Socket[] arr = new Socket[n];
    for (int i = 0; i < n; i++) {
      try {
        arr[i] = new Socket(myHost, myPort);
      } catch (Exception e) {
      }
    }
    for (int i = 0; i < n; i++) {
      try {
        arr[i].close();
      } catch (Exception e) {
      }
    }
    for (int i = 0; i < n; i++) {
      try {
        arr[i] = new Socket(myHost, myPort);
      } catch (Exception e) {
      }
    }
  }

  public void invalidReallocateElements() {
    // :: error: unfulfilled.mustcallonelements.obligations
    @OwningCollection Socket[] arr = new Socket[n];
    for (int i = 0; i < n; i++) {
      try {
        arr[i] = new Socket(myHost, myPort);
      } catch (Exception e) {
      }
    }
    for (int i = 0; i < n; i++) {
      try {
        // :: error: illegal.owningcollection.overwrite
        arr[i] = new Socket(myHost, myPort);
      } catch (Exception e) {
      }
    }
  }

  // test that passing ownership works
  public void validClosingByEnsuresCmoeMethod() {
    @OwningCollection Socket[] arr = new Socket[n];
    for (int i = 0; i < n; i++) {
      try {
        arr[i] = new Socket(myHost, myPort);
      } catch (Exception e) {
      }
    }
    close(arr);
  }

  public void close(@OwningCollection Socket[] arr) {
    for (int i = 0; i < n; i++) {
      try {
        arr[i].close();
      } catch (Exception e) {
      }
    }
  }
}
