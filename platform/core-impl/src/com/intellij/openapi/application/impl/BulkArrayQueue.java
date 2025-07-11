// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.application.impl;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.function.Predicate;

// array-backed queue with additional support for fast bulk inserts
@ApiStatus.Internal
public final class BulkArrayQueue<T> {
  // maintain wrap-around queue using these pointers into myQueue
  private int tail; // index at which the next element would be stored via enqueue()
  private int head; // index to a stored element to be returned by pollFirst(); if tail==head the queue is empty
  private Object[] myQueue = new Object[1024];

  public void enqueue(@NotNull T info) {
    int newTail = (tail + 1) % myQueue.length;
    if (newTail == head) {
      growAndUnwrap(0);
      newTail = tail+1;
    }
    myQueue[tail] = info;
    tail = newTail;
  }

  // grow the backing array to accommodate additional elements;
  // convert wrapped-around pointers to the conventional head<tail in the process
  // and allocate "reserveAtStart" empty slots before "head" to prepare for bulk insert
  private void growAndUnwrap(int reserveAtStart) {
    if (reserveAtStart < 0) throw new IllegalArgumentException("illegal argument: "+reserveAtStart);
    int oldCapacity = myQueue.length;
    int newCapacity = reserveAtStart + (oldCapacity < 100_000 ? oldCapacity * 2 : oldCapacity + (oldCapacity >> 1)); // overflow aware
    if (newCapacity <= oldCapacity + reserveAtStart) {
      throw new OutOfMemoryError("reserveAtStart: "+reserveAtStart+"; oldCapacity: "+oldCapacity);
    }
    Object[] newQueue = new Object[newCapacity];
    int firstChunkSize;
    if (head <= tail) {
      firstChunkSize = tail - head;
    }
    else {
      firstChunkSize = oldCapacity - head;
      System.arraycopy(myQueue, 0, newQueue, reserveAtStart + firstChunkSize, tail);
    }
    System.arraycopy(myQueue, head, newQueue, reserveAtStart, firstChunkSize);
    tail = size() + reserveAtStart;
    head = reserveAtStart;
    myQueue = newQueue;
  }


  public int size() {
    return head <= tail ? tail - head : tail + myQueue.length-head;
  }

  public int capacity() { return  myQueue.length; }

  public T pollFirst() {
    if (isEmpty()) return null;
    int nextHead = (head+1) % myQueue.length;
    T info = getAndNullize(head);
    head = nextHead;
    return info;
  }

  private @NotNull T getAndNullize(int head) {
    //noinspection unchecked
    T t = (T)myQueue[head];
    myQueue[head] = null;
    return t;
  }

  // insert all from "elements" before "head"
  public void bulkEnqueueFirst(@NotNull ObjectArrayList<? extends @NotNull T> elements) {
    int insertSize = elements.size();
    int oldCapacity = myQueue.length;
    int emptySpace = oldCapacity - size() - 1;
    if (insertSize > emptySpace) {
      growAndUnwrap(insertSize);
    }
    int secondChunkSize = head <= tail ? Math.min(insertSize, head) : Math.min(insertSize, head - tail - 1);
    int firstChunkSize = insertSize - secondChunkSize;

    head -= secondChunkSize;
    elements.getElements(firstChunkSize, myQueue, head, secondChunkSize);

    if (firstChunkSize != 0) {
      head = oldCapacity - firstChunkSize;
      elements.getElements(0, myQueue, head, firstChunkSize);
    }
  }

  void removeAll(@NotNull Predicate<? super T> shouldRemove) {
    int o;
    if (head <= tail) {
      // shift alive items in [head..tail) left to head
      o = head;
      for (int i=head; i<tail; i++) {
        T info = getAndNullize(i);
        if (!shouldRemove.test(info)) {
          myQueue[o++] = info;
        }
      }
    }
    else {
      // shift living items in [head..capacity) right
      o = myQueue.length;
      for (int i= myQueue.length-1; i>=head; i--) {
        T info = getAndNullize(i);
        if (!shouldRemove.test(info)) {
          myQueue[--o] = info;
        }
      }
      head = o % myQueue.length;
      // shift alive items in [0..tail) left to 0
      o = 0;
      for (int i=0; i<tail; i++) {
        T info = getAndNullize(i);
        if (!shouldRemove.test(info)) {
          myQueue[o++] = info;
        }
      }
    }
    tail = o;
  }

  boolean isEmpty() {
    return head == tail;
  }
}
