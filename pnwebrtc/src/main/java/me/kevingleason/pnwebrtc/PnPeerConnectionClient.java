package me.kevingleason.pnwebrtc;

import android.util.Log;

import com.pubnub.api.Callback;
import com.pubnub.api.Pubnub;
import com.pubnub.api.PubnubError;
import com.pubnub.api.PubnubException;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.IceCandidate;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.SessionDescription;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * <p>
 *     Created by GleasonK on 7/20/15 for PubNub WebRTC Signaling.
 *     PubNub '15
 *     Boston College '16
 * </p>
 * {@link PnPeerConnectionClient} is used to manage peer connections.
 */
public class PnPeerConnectionClient {
    private SessionDescription localSdp  = null; // either offer or answer SDP
    private MediaStream localMediaStream = null;
    PeerConnectionFactory pcFactory;
    PnRTCListener mRtcListener;
    PnSignalingParams signalingParams;

    private Pubnub mPubNub;
    private PnRTCReceiver mSubscribeReceiver;
    private Map<String,PnAction> actionMap;
    private Map<String,PnPeer> peers;
    private String id;

    public PnPeerConnectionClient(Pubnub pubnub, PnSignalingParams signalingParams, PnRTCListener rtcListener){
        this.mPubNub = pubnub;
        this.signalingParams = signalingParams;
        this.mRtcListener = rtcListener;
        this.pcFactory = new PeerConnectionFactory(); // TODO: Check it allowed, else extra param
        this.peers = new HashMap<String, PnPeer>();
        init();
    }

    private void init(){
        this.actionMap = new HashMap<String, PnAction>();
        this.actionMap.put(CreateOfferAction.TRIGGER,     new CreateOfferAction());
        this.actionMap.put(CreateAnswerAction.TRIGGER,    new CreateAnswerAction());
        this.actionMap.put(SetRemoteSDPAction.TRIGGER,    new SetRemoteSDPAction());
        this.actionMap.put(AddIceCandidateAction.TRIGGER, new AddIceCandidateAction());
        this.actionMap.put(PnUserHangupAction.TRIGGER,    new PnUserHangupAction());
        this.actionMap.put(PnUserMessageAction.TRIGGER,   new PnUserMessageAction());
        mSubscribeReceiver = new PnRTCReceiver();
    }

    boolean connect(String myId){  // Todo: return success?
        if (localMediaStream==null){       // Not true for streaming?
            mRtcListener.onDebug(new PnRTCMessage("Need to add media stream before you can connect."));
            return false;
        }
        if (this.id != null){  // Prevent listening on multiple channels.
            mRtcListener.onDebug(new PnRTCMessage("Already listening on " + this.id + ". Cannot have multiple connections."));
            return false;
        }
        this.id = myId;
        subscribe(myId);
        return true;
    }

    public void setRTCListener(PnRTCListener listener){
        this.mRtcListener = listener;
    }

    private void subscribe(String channel){
        try {
            mPubNub.subscribe(channel, this.mSubscribeReceiver);
        } catch (PubnubException e){
            e.printStackTrace();
        }
    }

    public void setLocalMediaStream(MediaStream localStream){
        this.localMediaStream = localStream;
        mRtcListener.onLocalStream(localStream);
    }

    public MediaStream getLocalMediaStream(){
        return this.localMediaStream;
    }

    private PnPeer addPeer(String id) {
        PnPeer peer = new PnPeer(id, this);
        peers.put(id, peer);
        return peer;
    }

    PnPeer removePeer(String id) {
        PnPeer peer = peers.get(id);
        peer.pc.close();
        return peers.remove(peer.id);
    }

    List<PnPeer> getPeers(){
        return new ArrayList<PnPeer>(this.peers.values());
    }

    /**
     * Close connection (hangup) no a certain peer.
     * @param id PnPeer id to close connection with
     */
    public void closeConnection(String id){
        JSONObject packet = new JSONObject();
        try {
            if (!this.peers.containsKey(id)) return;
            PnPeer peer = this.peers.get(id);
            peer.hangup();
            packet.put(PnRTCMessage.JSON_HANGUP, true);
            transmitMessage(id, packet);
            mRtcListener.onPeerConnectionClosed(this.peers.get(id));
        } catch (JSONException e){
            e.printStackTrace();
        }
    }

    /**
     * Close connections (hangup) on all open connections.
     */
    public void closeAllConnections() {
        Iterator<String> peerIds = this.peers.keySet().iterator();
        while (peerIds.hasNext()) {
            closeConnection(peerIds.next());
        }
    }

    /**
     * Send SDP Offers/Answers nd ICE candidates to peers.
     * @param toID The id or "number" that you wish to transmit a message to.
     * @param packet The JSON data to be transmitted
     */
    void transmitMessage(String toID, JSONObject packet){
        if (this.id==null){ // Not logged in. Put an error in the debug cb.
            mRtcListener.onDebug(new PnRTCMessage("Cannot transmit before calling Client.connect"));
        }
        try {
            JSONObject message = new JSONObject();
            message.put(PnRTCMessage.JSON_PACKET, packet);
            message.put(PnRTCMessage.JSON_ID, ""); //Todo: session id, unused in js SDK?
            message.put(PnRTCMessage.JSON_NUMBER, this.id);
            this.mPubNub.publish(toID, message, new Callback() {  // Todo: reconsider callback.
                @Override
                public void successCallback(String channel, Object message, String timetoken) {
                    mRtcListener.onDebug(new PnRTCMessage((JSONObject)message));
                }

                @Override
                public void errorCallback(String channel, PubnubError error) {
                    mRtcListener.onDebug(new PnRTCMessage(error.errorObject));
                }
            });
        } catch (JSONException e){
            e.printStackTrace();
        }
    }

    private interface PnAction{
        void execute(String peerId, JSONObject payload) throws JSONException;
    }

    private class CreateOfferAction implements PnAction{
        public static final String TRIGGER = "init";
        public void execute(String peerId, JSONObject payload) throws JSONException {
            Log.d("COAction","CreateOfferAction");
            PnPeer peer = peers.get(peerId);
            peer.setDialed(true);
            peer.setType(PnPeer.TYPE_ANSWER);
            peer.pc.createOffer(peer, signalingParams.pcConstraints);
        }
    }

    private class CreateAnswerAction implements PnAction{
        public static final String TRIGGER = "offer";
        public void execute(String peerId, JSONObject payload) throws JSONException {
            Log.d("CAAction","CreateAnswerAction");
            PnPeer peer = peers.get(peerId);
            peer.setType(PnPeer.TYPE_OFFER);
            peer.setStatus(PnPeer.STATUS_CONNECTED);
            SessionDescription sdp = new SessionDescription(
                    SessionDescription.Type.fromCanonicalForm(payload.getString("type")),
                    payload.getString("sdp")
            );
            peer.pc.setRemoteDescription(peer, sdp);
            peer.pc.createAnswer(peer, signalingParams.pcConstraints);
        }
    }

    private class SetRemoteSDPAction implements PnAction{
        public static final String TRIGGER = "answer";
        public void execute(String peerId, JSONObject payload) throws JSONException {
            Log.d("SRSAction","SetRemoteSDPAction");
            PnPeer peer = peers.get(peerId);
            SessionDescription sdp = new SessionDescription(
                    SessionDescription.Type.fromCanonicalForm(payload.getString("type")),
                    payload.getString("sdp")
            );
            peer.pc.setRemoteDescription(peer, sdp);
        }
    }

    private class AddIceCandidateAction implements PnAction{
        public static final String TRIGGER = "candidate";
        public void execute(String peerId, JSONObject payload) throws JSONException {
            Log.d("AICAction","AddIceCandidateAction");
            PeerConnection pc = peers.get(peerId).pc;
            if (pc.getRemoteDescription() != null) {
                IceCandidate candidate = new IceCandidate(
                        payload.getString("sdpMid"),
                        payload.getInt("sdpMLineIndex"),
                        payload.getString("candidate")
                );
                pc.addIceCandidate(candidate);
            }
        }
    }

    private class PnUserHangupAction implements PnAction{
        public static final String TRIGGER = PnRTCMessage.JSON_HANGUP;
        public void execute(String peerId, JSONObject payload) throws JSONException {
            Log.d("PnUserHangup","PnUserHangupAction");
            PnPeer peer = peers.get(peerId);
            peer.hangup();
            mRtcListener.onPeerConnectionClosed(peer);
            // Todo: Consider Callback?
        }
    }

    private class PnUserMessageAction implements PnAction{
        public static final String TRIGGER = PnRTCMessage.JSON_USERMSG;
        public void execute(String peerId, JSONObject payload) throws JSONException {
            Log.d("PnUserMessage","AddIceCandidateAction");
            PnPeer peer = peers.get(peerId);
            mRtcListener.onMessage(peer, payload);
        }
    }

    private class PnRTCReceiver extends Callback {

        @Override
        public void connectCallback(String channel, Object message) {
            mRtcListener.onDebug(new PnRTCMessage(((JSONArray) message).toString()));
            mRtcListener.onConnected(channel);
        }

        @Override
        public void successCallback(String channel, Object message) {
            if (!(message instanceof JSONObject)) return; // Ignore if not valid JSON.
            JSONObject jsonMessage = (JSONObject) message;
            mRtcListener.onDebug(new PnRTCMessage(jsonMessage));
            try {
                String peerId     = jsonMessage.getString(PnRTCMessage.JSON_NUMBER);
                JSONObject packet = jsonMessage.getJSONObject(PnRTCMessage.JSON_PACKET);
                PnPeer peer;
                if (!peers.containsKey(peerId)){
                    // Possibly threshold number of allowed users
                    peer = addPeer(peerId);
                    peer.pc.addStream(localMediaStream);
                } else {
                    peer = peers.get(peerId);
                }
                if (peer.getStatus().equals(PnPeer.STATUS_DISCONNECTED)) return; // Do nothing if disconnected.
                if (packet.has(PnRTCMessage.JSON_USERMSG)) {
                    actionMap.get(PnUserMessageAction.TRIGGER).execute(peerId,packet);
                    return;
                }
                if (packet.has(PnRTCMessage.JSON_HANGUP)){
                    actionMap.get(PnUserHangupAction.TRIGGER).execute(peerId,packet);
                    return;
                }
                if (packet.has(PnRTCMessage.JSON_THUMBNAIL)) {
                    return;   // No handler for thumbnail or hangup yet, will be separate controller callback
                }
                if (packet.has(PnRTCMessage.JSON_SDP)) {
                    if(!peer.received) {
                        peer.setReceived(true);
                        mRtcListener.onDebug(new PnRTCMessage("SDP - " + peer.toString()));
                        // Todo: reveivercb(peer);
                    }
                    String type = packet.getString(PnRTCMessage.JSON_TYPE);
                    actionMap.get(type).execute(peerId, packet);
                    return;
                }
                if (packet.has(PnRTCMessage.JSON_ICE)){
                    actionMap.get(AddIceCandidateAction.TRIGGER).execute(peerId,packet);
                    return;
                }
            } catch (JSONException e){
                e.printStackTrace();
            }
        }

        @Override
        public void errorCallback(String channel, PubnubError error) {
            super.errorCallback(channel, error);
        }

    }

}