/*
 * Copyright 2000-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetbrains.rpc

import com.intellij.codeWithMe.ClientId
import com.intellij.concurrency.ConcurrentCollectionFactory
import com.intellij.openapi.diagnostic.logger
import org.jetbrains.concurrency.Promise
import org.jetbrains.concurrency.createError
import org.jetbrains.jsonProtocol.Request
import java.io.IOException
import java.util.*
import java.util.concurrent.CancellationException

interface MessageProcessor {
  fun cancelWaitingRequests()

  fun closed()

  fun <RESULT> send(message: Request<RESULT>): Promise<RESULT>
}

private val MESSAGE_MANAGER_LOG = logger<MessageProcessor>()

class MessageManager<REQUEST: Request<*>, INCOMING, INCOMING_WITH_SEQ : Any, SUCCESS>(private val handler: MessageManager.Handler<REQUEST, INCOMING, INCOMING_WITH_SEQ, SUCCESS>) : MessageManagerBase() {
  private val callbackMap = ConcurrentCollectionFactory.createConcurrentIntObjectMap<RequestCallback<SUCCESS>>()

  interface Handler<OUTGOING, INCOMING, INCOMING_WITH_SEQ : Any, SUCCESS> {
    fun getUpdatedSequence(message: OUTGOING): Int

    @Throws(IOException::class)
    fun write(message: OUTGOING): Boolean

    fun readIfHasSequence(incoming: INCOMING): INCOMING_WITH_SEQ?

    fun getSequence(incomingWithSeq: INCOMING_WITH_SEQ): Int = throw AbstractMethodError()

    fun getSequence(incomingWithSeq: INCOMING_WITH_SEQ, incoming: INCOMING): Int = getSequence(incomingWithSeq)

    fun acceptNonSequence(incoming: INCOMING)

    fun call(response: INCOMING_WITH_SEQ, callback: RequestCallback<SUCCESS>)
  }

  fun send(message: REQUEST, callback: RequestCallback<SUCCESS>) {
    if (rejectIfClosed(callback)) {
      message.buffer.release()
      return
    }

    val sequence = handler.getUpdatedSequence(message)
    callbackMap.put(sequence, decorateCallback(callback))

    val success: Boolean
    try {
      success = handler.write(message)
    }
    catch (e: CancellationException) {
      // Promise must be canceled regardless of the origin of the cancellation exception
      // ("rogue" CE or CE thrown because the current coroutine was canceled) because:
      // - we need to remove it from the callback map
      // - since the write operation wasn't finished, this promise isn't valid anymore
      cancelled(sequence)
      throw e
    } catch (e: Throwable) {
      try {
        failedToSend(sequence, message.methodName)
      }
      finally {
        MESSAGE_MANAGER_LOG.error("Failed to send", e)
      }
      return
    }

    if (!success) {
      failedToSend(sequence, message.methodName)
    }
  }

  private fun decorateCallback(callback: RequestCallback<SUCCESS>): RequestCallback<SUCCESS> {
    val currentId = ClientId.current
    return object : RequestCallback<SUCCESS> {
      override fun onSuccess(response: SUCCESS?, resultReader: ResultReader<SUCCESS>?) {
        ClientId.withClientId(currentId) {
          callback.onSuccess(response, resultReader)
        }
      }

      override fun onError(error: Throwable) {
        ClientId.withClientId(currentId) {
          callback.onError(error)
        }
      }

      override fun onCancel(error: Throwable?) {
        ClientId.withClientId(currentId) {
          callback.onCancel(error)
        }
      }
    }
  }

  private fun cancelled(sequence: Int) {
    callbackMap.remove(sequence)?.onCancel()
  }

  private fun failedToSend(sequence: Int, methodName: String) {
    callbackMap.remove(sequence)?.onError("Failed to send ($methodName)")
  }

  fun processIncoming(incomingParsed: INCOMING) {
    val commandResponse = handler.readIfHasSequence(incomingParsed)
    if (commandResponse == null) {
      if (closed) {
        // just ignore
        MESSAGE_MANAGER_LOG.info("Connection closed, ignore incoming")
      }
      else {
        handler.acceptNonSequence(incomingParsed)
      }
      return
    }

    val callback = getCallbackAndRemove(handler.getSequence(commandResponse, incomingParsed))
    if (rejectIfClosed(callback)) {
      return
    }

    try {
      handler.call(commandResponse, callback)
    }
    catch (e: Throwable) {
      callback.onError(e)
      MESSAGE_MANAGER_LOG.error("Failed to dispatch response to callback", e)
    }
  }

  fun getCallbackAndRemove(id: Int): RequestCallback<SUCCESS> = callbackMap.remove(id) ?: throw IllegalArgumentException("Cannot find callback with id $id")

  fun cancelWaitingRequests() {
    // we should call them in the order they have been submitted
    val map = callbackMap
    val keys = map.keys()
    Arrays.sort(keys)
    for (key in keys) {
      map.get(key)?.onCancel(createError(CONNECTION_CLOSED_MESSAGE))
    }
  }
}