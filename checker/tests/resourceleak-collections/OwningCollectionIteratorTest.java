import java.net.Socket;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.checkerframework.checker.calledmethods.qual.*;
import org.checkerframework.checker.collectionownership.qual.*;
import org.checkerframework.checker.mustcall.qual.*;

class OwningCollectionIteratorTest {
  private Iterator<Socket> iterator;
  private static final String myHost = "myHost";
  private static final int myPort = 42;

  // An in-depth example to explain how iterators over collections of resources are handled.
  void illegalIteratorRemovalExample(boolean b) {
    List<Socket> sockets = new ArrayList<Socket>();
    List<Socket> sockets2 = new ArrayList<Socket>();

    try {
      sockets.add(new Socket(myHost, myPort));
      sockets2.add(new Socket(myHost, myPort));
    } catch (Exception e) {
    }

    // at this point, both sockets and sockets2 are @OwningCollection

    /*
     * For Collection.iterator(), we first check whether the receiver collection
     * has open Mcoe obligations.
     * If there are open obligations, create an IteratorObligation for the temp-var
     * created for the Collection.iterator() call. IteratorObligation is a new subclass
     * of Obligation I've created specifically to track Iterators for OwningCollections.
     *
     * The receiver collection being a read-only alias is no problem as we will see soon,
     * since we enforce for any Iterator.remove() call, that its preceding Iterator.next()
     * call fulfilled its MustCall obligation.
     */
    Iterator<Socket> iter1 = sockets.iterator();
    Iterator<Socket> iter2 = sockets2.iterator();

    /*
     * When seeing an iter.next():
     * Get the IteratorObligation for the receiver iterator.
     * Create an obligation for the Iterator.next() temp-var. For this, I've created
     * another subclass of Obligation called IteratorNextObligation.
     * Add the IteratorNextObligation as a field to the IteratorObligation.
     * Also add the IteratorObligation as field to the IteratorNextObligation (two-
     * way pointer).
     * When the IteratorNextObligation goes out of scope (its last resource alias),
     * checkMustCall() is called in the consistency analysis. I've special cased it
     * in case the checked obligation is an instance of IteratorNextObligation. Namely,
     * it "caches" the corresponding cmStore and mcStore in the "parent" IteratorObligation.
     * The idea is that the IteratorNextObligation will only be checked if there is a call
     * to Iterator.remove() later.
     */
    try {
      iter1.next();
      // :: error: required.method.not.called
      iter2.next().close();

      if (b) {
        // swap to confuse the aliasing tracking
        Iterator<Socket> temp = iter1;
        iter1 = iter2;
        iter2 = temp;
      }

      /*
       * When we see an Iterator.remove(), get the IteratorObligation for iter1.
       * Check whether it has non-null cmStore and mcStore in its "cache" field.
       * If it has, it means the preceding Iterator.next() element already went
       * out of scope. In this case, call checkMustCall() with the cached stores.
       *
       * If there are no cached stores, it means the element returned by
       * Iterator.next() is still in scope. We flag the IteratorNextObligation,
       * so that when it goes out of scope, it will be checked (and not special cased
       * as described above).
       *
       * Here, checkMustCall() will report an error, since the CalledMethods type
       * of all resource aliases (only the temp-var here) of the IteratorNextObligation
       * are empty. We are in the first case, where the value returned by iter1.next()
       * already went out of scope and the stores at this program point were cached
       * to be checked at the point of an Iterator.remove() call.
       */
      iter1.remove();
    } catch (Exception e) {
    }

    close(sockets);
    close(sockets2);
  }

  /*
   * This method assigns an iterator, which has an IteratorObligation into a field.
   * This is to demonstrate that for any method call, we have to assume that an iterator
   * field has an IteratorObligation. If it had not, calling this method and then some other
   * which calls this.iterator.remove(), would unsoundly not report an error.
   * This method in itself is completely fine.
   */
  void assignOwningIteratorToField(@OwningCollection List<Socket> list) {
    this.iterator = list.iterator();
    close(list);
  }

  /*
   * As described above for assignOwningIteratortoField(), calling iterator.remove() on
   * a field without previous iterator.next() call (which has fulfilled obligations)
   * is unsafe.
   */
  void checkThatIteratorFieldHasObligation() {
    // :: error: unsafe.iterator.remove
    this.iterator.remove();
  }

  /*
   * The same holds for iterator parameters; there might have been a call to Iterator.next()
   * at the call-site, before the iterator was passed to the method. Thus, the call to
   * iter.remove() has no preceding call to iter.next() in this method and we need to
   * report an error.
   */
  void checkThatIteratorParamHasObligation(@OwningCollection Iterator<Socket> iter) {
    // :: error: unsafe.iterator.remove
    iter.remove();
  }

  /*
   * The element returned by iterator.next() has its obligations fulfilled, so the call to
   * iterator.remove() is legal here.
   */
  void legalIteratorFieldUsage() {
    try {
      this.iterator.next().close();
      this.iterator.remove();
    } catch (Exception e) {
    }
  }

  /*
   * ...and just like for fields, this is safe:
   */
  void safeIteratorParamAccess(@OwningCollection Iterator<Socket> iter) {
    try {
      iter.next().close();
      iter.remove();

      Socket s = iter.next();
      s.close();
      iter.remove();
    } catch (Exception e) {
    }
  }

  /*
   * Ownership of parameter collection passed on to returnOwningIterator(). However,
   * there could've been an exceptional exit before that. Hence, the obligations on
   * the parameter are not fulfilled.
   * The main task of this method is however to ensure proper handling of an iterator
   * field.
   */
  // :: error: unfulfilled.collection.obligations
  void iteratorField(@OwningCollection List<Socket> list) {
    try {
      // :: error: unsafe.iterator.remove
      this.iterator.remove();

      // :: error: required.method.not.called
      this.iterator.next();
      this.iterator.remove();

      // there is a false positive here for some reason. but it's sound, no big deal right now.
      // something about handling obligations for fields is weird.
      this.iterator.next().close();
      this.iterator.remove();

      // this is safe
      Socket s = this.iterator.next();
      this.iterator.remove();
      s.close();

      this.iterator = returnOwningIterator(list);
      // :: error: unsafe.iterator.remove
      this.iterator.remove();

      // :: error: required.method.not.called
      this.iterator.next();
      this.iterator.remove();

      // this is safe
      this.iterator.next().close();
      this.iterator.remove();

      // this is safe
      s = this.iterator.next();
      this.iterator.remove();
      s.close();
    } catch (Exception e) {
    }
  }

  void tryToCircumventIteratorObligation(@OwningCollection List<Socket> list) {
    // try to bypass iterator obligation by passing as method argument
    // error is reported in the called method
    takeOwningIterator(list.iterator());

    // try to bypass iterator obligation by getting iterator from method call
    // :: error: unsafe.iterator.remove
    returnOwningIterator(list).remove();
  }

  /*
   * If the passed iterator has an IteratorObligation and the parameter does not,
   * this method is illegal.
   * The sound rule is to give an IteratorObligation to any iterator method parameter,
   * which has a type-var with non-empty MustCall type.
   */
  void takeOwningIterator(@OwningCollection Iterator<Socket> iter) {
    // :: error: required.method.not.called
    iter.next();
    iter.remove();
  }

  /*
   * The iterator returned by list.iterator() has an IteratorObligation. At call-site,
   * we have to create an IteratorObligation for all method invocations that have a return
   * type resolving to an iterator with type-var that has MustCall obligations.
   * This method is completely fine, it just demonstrates that we must create such an obligation
   * at the call-site.
   */
  Iterator<Socket> returnOwningIterator(@OwningCollection List<Socket> list) {
    Iterator<Socket> iter = list.iterator();
    iter.next();
    close(list);
    return iter;
  }

  /*
   * The iterator returned by list.iterator() has no IteratorObligation, since
   * it iterates over an @OwningCollection without open calling obligations.
   * The method adds an element after getting the iterator. This results in a
   * ConcurrentModificationException the next time the iterator is accessed.
   * Therefore, we can safely return it without an error, since a call to
   * iterator.remove(), which would be unsafe, is guaranteed to throw an exception
   * anyways.
   * Moreover, adding elements to the collection after getting the iterator will lead
   * to an error for unfulfilled mcoe obligations on the collection.
   * Therefore, returning it is allowed and sound.
   */
  Iterator<Socket> returnModifiedOwningIterator(@OwningCollection List<Socket> list) {
    for (Socket s : list) {
      try {
        s.close();
      } catch (Exception e) {
      }
    }

    Iterator<Socket> iter = list.iterator();
    try {
      // :: error: unfulfilled.collection.obligations
      list.add(new Socket(myHost, myPort));
      return iter;
    } catch (Exception e) {
      return null;
    }
  }

  /*
   * Try to pass an iterator tracked by an obligation to an iterator parameter with
   * a component type that has no MustCall obligations (wildcard/generic with no upper bound).
   */
  void checkGenericAndWilcardParameters(
      @OwningCollection List<Socket> list, @OwningCollection List<Socket> list2) {
    // :: error: argument
    takeIteratorWithoutTypeArgUpperBound(returnOwningIterator(list));
    // :: error: argument
    // :: error: type.arguments.not.inferred
    takeIteratorWithoutTypeArgUpperBound2(returnOwningIterator(list2));
  }

  void takeIteratorWithTypeArgUpperBound(Iterator<? extends @MustCallUnknown Object> iter) {
    iter.next();
    iter.remove();
  }

  <E> void takeIteratorWithoutTypeArgUpperBound2(Iterator<E> iter) {
    iter.next();
    iter.remove();
  }

  /*
   * Fulfills obligation for owning socket list.
   */
  void close(@OwningCollection List<Socket> socketList) {
    for (Socket s : socketList) {
      try {
        s.close();
      } catch (Exception e) {
      }
    }
  }
}
