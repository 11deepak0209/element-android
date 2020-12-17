/*
 * Copyright (c) 2020 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.app.features.call.webrtc

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import im.vector.app.ActiveSessionDataSource
import im.vector.app.core.services.BluetoothHeadsetReceiver
import im.vector.app.core.services.CallService
import im.vector.app.core.services.WiredHeadsetStateReceiver
import im.vector.app.features.call.CallAudioManager
import im.vector.app.features.call.VectorCallActivity
import im.vector.app.features.call.utils.EglUtils
import im.vector.app.push.fcm.FcmHelper
import kotlinx.coroutines.asCoroutineDispatcher
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.call.CallListener
import org.matrix.android.sdk.api.session.call.CallState
import org.matrix.android.sdk.api.session.call.MxCall
import org.matrix.android.sdk.api.session.room.model.call.CallAnswerContent
import org.matrix.android.sdk.api.session.room.model.call.CallCandidatesContent
import org.matrix.android.sdk.api.session.room.model.call.CallHangupContent
import org.matrix.android.sdk.api.session.room.model.call.CallInviteContent
import org.matrix.android.sdk.api.session.room.model.call.CallNegotiateContent
import org.matrix.android.sdk.api.session.room.model.call.CallRejectContent
import org.matrix.android.sdk.api.session.room.model.call.CallSelectAnswerContent
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.PeerConnectionFactory
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manage peerConnectionFactory & Peer connections outside of activity lifecycle to resist configuration changes
 * Use app context
 */
@Singleton
class WebRtcCallManager @Inject constructor(
        private val context: Context,
        private val activeSessionDataSource: ActiveSessionDataSource
) : CallListener, LifecycleObserver {

    private val currentSession: Session?
        get() = activeSessionDataSource.currentValue?.orNull()

    interface CurrentCallListener {
        fun onCurrentCallChange(call: WebRtcCall?) {}
        fun onAudioDevicesChange() {}
    }

    private val currentCallsListeners = CopyOnWriteArrayList<CurrentCallListener>()
    fun addCurrentCallListener(listener: CurrentCallListener) {
        currentCallsListeners.add(listener)
    }

    fun removeCurrentCallListener(listener: CurrentCallListener) {
        currentCallsListeners.remove(listener)
    }

    val callAudioManager = CallAudioManager(context) {
        currentCallsListeners.forEach {
            tryOrNull { it.onAudioDevicesChange() }
        }
    }

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val dispatcher = executor.asCoroutineDispatcher()

    private val rootEglBase by lazy { EglUtils.rootEglBase }

    private var isInBackground: Boolean = true

    @OnLifecycleEvent(Lifecycle.Event.ON_RESUME)
    fun entersForeground() {
        isInBackground = false
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    fun entersBackground() {
        isInBackground = true
    }

    /**
     * The current call is the call we interacted with whatever his state (connected,resumed, held...)
     * As soon as we interact with an other call, it replaces this one and put it on held if not already.
     */
    var currentCall: AtomicReference<WebRtcCall?> = AtomicReference(null)
    private fun AtomicReference<WebRtcCall?>.setAndNotify(newValue: WebRtcCall?) {
        set(newValue)
        currentCallsListeners.forEach {
            tryOrNull { it.onCurrentCallChange(newValue) }
        }
    }

    private val callsByCallId = ConcurrentHashMap<String, WebRtcCall>()
    private val callsByRoomId = ConcurrentHashMap<String, MutableList<WebRtcCall>>()

    fun getCallById(callId: String): WebRtcCall? {
        return callsByCallId[callId]
    }

    fun getCallsByRoomId(roomId: String): List<WebRtcCall> {
        return callsByRoomId[roomId] ?: emptyList()
    }

    fun getCurrentCall(): WebRtcCall? {
        return currentCall.get()
    }

    fun getCalls(): List<WebRtcCall> {
        return callsByCallId.values.toList()
    }

    fun headSetButtonTapped() {
        Timber.v("## VOIP headSetButtonTapped")
        val call = currentCall.get() ?: return
        if (call.mxCall.state is CallState.LocalRinging) {
            // accept call
            call.acceptIncomingCall()
        }
        if (call.mxCall.state is CallState.Connected) {
            // end call?
            call.endCall()
        }
    }

    private fun createPeerConnectionFactoryIfNeeded() {
        if (peerConnectionFactory != null) return
        Timber.v("## VOIP createPeerConnectionFactory")
        val eglBaseContext = rootEglBase?.eglBaseContext ?: return Unit.also {
            Timber.e("## VOIP No EGL BASE")
        }

        Timber.v("## VOIP PeerConnectionFactory.initialize")
        PeerConnectionFactory.initialize(PeerConnectionFactory
                .InitializationOptions.builder(context.applicationContext)
                .createInitializationOptions()
        )

        val options = PeerConnectionFactory.Options()
        val defaultVideoEncoderFactory = DefaultVideoEncoderFactory(
                eglBaseContext,
                /* enableIntelVp8Encoder */
                true,
                /* enableH264HighProfile */
                true)
        val defaultVideoDecoderFactory = DefaultVideoDecoderFactory(eglBaseContext)
        Timber.v("## VOIP PeerConnectionFactory.createPeerConnectionFactory ...")
        peerConnectionFactory = PeerConnectionFactory.builder()
                .setOptions(options)
                .setVideoEncoderFactory(defaultVideoEncoderFactory)
                .setVideoDecoderFactory(defaultVideoDecoderFactory)
                .createPeerConnectionFactory()
    }

    private fun onCallActive(call: WebRtcCall) {
        Timber.v("## VOIP WebRtcPeerConnectionManager onCall active: ${call.mxCall.callId}")
        val currentCall = currentCall.get().takeIf { it != call }
        currentCall?.updateRemoteOnHold(onHold = true)
        this.currentCall.setAndNotify(call)
    }

    private fun onCallEnded(call: WebRtcCall) {
        Timber.v("## VOIP WebRtcPeerConnectionManager onCall ended: ${call.mxCall.callId}")
        CallService.onCallTerminated(context, call.callId)
        callAudioManager.stop()
        callsByCallId.remove(call.mxCall.callId)
        callsByRoomId[call.mxCall.roomId]?.remove(call)
        if (currentCall.get() == call) {
            val otherCall = getCalls().lastOrNull()
            currentCall.setAndNotify(otherCall)
        }
        // This must be done in this thread
        executor.execute {
            if (currentCall.get() == null) {
                Timber.v("## VOIP Dispose peerConnectionFactory as there is no need to keep one")
                peerConnectionFactory?.dispose()
                peerConnectionFactory = null
            }
            Timber.v("## VOIP WebRtcPeerConnectionManager close() executor done")
        }
    }

    fun startOutgoingCall(signalingRoomId: String, otherUserId: String, isVideoCall: Boolean) {
        Timber.v("## VOIP startOutgoingCall in room $signalingRoomId to $otherUserId isVideo $isVideoCall")
        if (getCallsByRoomId(signalingRoomId).isNotEmpty()) {
            Timber.w("## VOIP you already have a call in this room")
            return
        }
        if (currentCall.get() != null && currentCall.get()?.mxCall?.state !is CallState.Connected || getCalls().size >= 2) {
            Timber.w("## VOIP cannot start outgoing call")
            // Just ignore, maybe we could answer from other session?
            return
        }
        executor.execute {
            createPeerConnectionFactoryIfNeeded()
        }
        currentCall.get()?.updateRemoteOnHold(onHold = true)
        val mxCall = currentSession?.callSignalingService()?.createOutgoingCall(signalingRoomId, otherUserId, isVideoCall) ?: return
        val webRtcCall = createWebRtcCall(mxCall)
        currentCall.setAndNotify(webRtcCall)
        callAudioManager.startForCall(mxCall)

        CallService.onOutgoingCallRinging(
                context = context.applicationContext,
                callId = mxCall.callId)

        // start the activity now
        context.startActivity(VectorCallActivity.newIntent(context, mxCall, VectorCallActivity.OUTGOING_CREATED))
    }

    override fun onCallIceCandidateReceived(mxCall: MxCall, iceCandidatesContent: CallCandidatesContent) {
        Timber.v("## VOIP onCallIceCandidateReceived for call ${mxCall.callId}")
        val call = callsByCallId[iceCandidatesContent.callId]
                ?: return Unit.also {
                    Timber.w("onCallIceCandidateReceived for non active call? ${iceCandidatesContent.callId}")
                }
        call.onCallIceCandidateReceived(iceCandidatesContent)
    }

    private fun createWebRtcCall(mxCall: MxCall): WebRtcCall {
        val webRtcCall = WebRtcCall(
                mxCall = mxCall,
                callAudioManager = callAudioManager,
                rootEglBase = rootEglBase,
                context = context,
                dispatcher = dispatcher,
                peerConnectionFactoryProvider = {
                    createPeerConnectionFactoryIfNeeded()
                    peerConnectionFactory
                },
                sessionProvider = { currentSession },
                onCallBecomeActive = this::onCallActive,
                onCallEnded = this::onCallEnded
        )
        callsByCallId[mxCall.callId] = webRtcCall
        callsByRoomId.getOrPut(mxCall.roomId) { ArrayList(1) }
                .add(webRtcCall)
        return webRtcCall
    }

    fun endCallForRoom(roomId: String, originatedByMe: Boolean = true) {
        callsByRoomId[roomId]?.forEach { it.endCall(originatedByMe) }
    }

    fun onWiredDeviceEvent(event: WiredHeadsetStateReceiver.HeadsetPlugEvent) {
        Timber.v("## VOIP onWiredDeviceEvent $event")
        currentCall.get() ?: return
        // sometimes we received un-wanted unplugged...
        callAudioManager.wiredStateChange(event)
    }

    fun onWirelessDeviceEvent(event: BluetoothHeadsetReceiver.BTHeadsetPlugEvent) {
        Timber.v("## VOIP onWirelessDeviceEvent $event")
        callAudioManager.bluetoothStateChange(event.plugged)
    }

    override fun onCallInviteReceived(mxCall: MxCall, callInviteContent: CallInviteContent) {
        Timber.v("## VOIP onCallInviteReceived callId ${mxCall.callId}")
        if (getCallsByRoomId(mxCall.roomId).isNotEmpty()) {
            Timber.w("## VOIP you already have a call in this room")
            return
        }
        if ((currentCall.get() != null && currentCall.get()?.mxCall?.state !is CallState.Connected) || getCalls().size >= 2) {
            Timber.w("## VOIP receiving incoming call but cannot handle it")
            // Just ignore, maybe we could answer from other session?
            return
        }
        createWebRtcCall(mxCall).apply {
            offerSdp = callInviteContent.offer
        }
        callAudioManager.startForCall(mxCall)
        // Start background service with notification
        CallService.onIncomingCallRinging(
                context = context,
                callId = mxCall.callId,
                isInBackground = isInBackground
        )
        // If this is received while in background, the app will not sync,
        // and thus won't be able to received events. For example if the call is
        // accepted on an other session this device will continue ringing
        if (isInBackground) {
            if (FcmHelper.isPushSupported()) {
                // only for push version as fdroid version is already doing it?
                currentSession?.startAutomaticBackgroundSync(30, 0)
            } else {
                // Maybe increase sync freq? but how to set back to default values?
            }
        }
    }

    override fun onCallAnswerReceived(callAnswerContent: CallAnswerContent) {
        val call = callsByCallId[callAnswerContent.callId]
                ?: return Unit.also {
                    Timber.w("onCallAnswerReceived for non active call? ${callAnswerContent.callId}")
                }
        val mxCall = call.mxCall
        // Update service state
        CallService.onPendingCall(
                context = context,
                callId = mxCall.callId
        )
        call.onCallAnswerReceived(callAnswerContent)
    }

    override fun onCallHangupReceived(callHangupContent: CallHangupContent) {
        val call = callsByCallId[callHangupContent.callId]
                ?: return Unit.also {
                    Timber.w("onCallHangupReceived for non active call? ${callHangupContent.callId}")
                }
        call.endCall(false)
    }

    override fun onCallRejectReceived(callRejectContent: CallRejectContent) {
        val call = callsByCallId[callRejectContent.callId]
                ?: return Unit.also {
                    Timber.w("onCallRejectReceived for non active call? ${callRejectContent.callId}")
                }
        call.endCall(false)
    }

    override fun onCallSelectAnswerReceived(callSelectAnswerContent: CallSelectAnswerContent) {
        val call = callsByCallId[callSelectAnswerContent.callId]
                ?: return Unit.also {
                    Timber.w("onCallSelectAnswerReceived for non active call? ${callSelectAnswerContent.callId}")
                }
        val selectedPartyId = callSelectAnswerContent.selectedPartyId
        if (selectedPartyId != call.mxCall.ourPartyId) {
            Timber.i("Got select_answer for party ID $selectedPartyId: we are party ID ${call.mxCall.ourPartyId}.")
            // The other party has picked somebody else's answer
            call.endCall(false)
        }
    }

    override fun onCallNegotiateReceived(callNegotiateContent: CallNegotiateContent) {
        val call = callsByCallId[callNegotiateContent.callId]
                ?: return Unit.also {
                    Timber.w("onCallNegotiateReceived for non active call? ${callNegotiateContent.callId}")
                }
        call.onCallNegotiateReceived(callNegotiateContent)
    }

    override fun onCallManagedByOtherSession(callId: String) {
        Timber.v("## VOIP onCallManagedByOtherSession: $callId")
        val webRtcCall = callsByCallId.remove(callId)
        if (webRtcCall != null) {
            callsByRoomId[webRtcCall.mxCall.roomId]?.remove(webRtcCall)
        }
        // TODO: handle this properly
        CallService.onCallTerminated(context, callId)

        // did we start background sync? so we should stop it
        if (isInBackground) {
            if (FcmHelper.isPushSupported()) {
                currentSession?.stopAnyBackgroundSync()
            } else {
                // for fdroid we should not stop, it should continue syncing
                // maybe we should restore default timeout/delay though?
            }
        }
    }
}
