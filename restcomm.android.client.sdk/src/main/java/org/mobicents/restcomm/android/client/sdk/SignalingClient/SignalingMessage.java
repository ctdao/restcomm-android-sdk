package org.mobicents.restcomm.android.client.sdk.SignalingClient;

import android.content.Context;

import org.mobicents.restcomm.android.client.sdk.RCClient;
import org.mobicents.restcomm.android.client.sdk.RCDeviceListener;

import java.util.HashMap;

// Structure signaling messages exchanged between UI and signaling thread
public class SignalingMessage {
   public enum MessageType {
      OPEN_REQUEST,
      OPEN_REPLY,
      CLOSE_REQUEST,
      CLOSE_REPLY,
      RECONFIGURE_REQUEST,
      RECONFIGURE_REPLY,
      ERROR_EVENT,
      CONNECTIVITY_EVENT,
      REGISTERING_EVENT,

      CALL_REQUEST,
      CALL_INCOMING_EVENT,
      CALL_OUTGOING_PEER_RINGING_EVENT,
      CALL_PEER_DISCONNECT_EVENT,
      CALL_OUTGOING_CONNECTED_EVENT,
      CALL_INCOMING_CONNECTED_EVENT,
      CALL_ERROR_EVENT,
      CALL_ACCEPT_REQUEST,
      CALL_DISCONNECT_REQUEST,
      CALL_LOCAL_DISCONNECT_EVENT,
      CALL_INCOMING_CANCELED_EVENT,
      CALL_SEND_DIGITS_REQUEST,
      CALL_SEND_DIGITS_EVENT,

      MESSAGE_REQUEST,
      MESSAGE_REPLY,
      MESSAGE_INCOMING_EVENT,

   }

   public String jobId;
   public MessageType type;
   public HashMap<String, Object> parameters;
   public Context androidContext;

   // result status and text
   public RCClient.ErrorCodes status;
   public String text;

   // additional fields per request
   public RCDeviceListener.RCConnectivityStatus connectivityStatus;
   // SDP
   public String sdp;
   // incoming messages
   public String messageText;
   public String peer;
   // DTMF digits
   public String dtmfDigits;
   // reason for hunging up a call (to be added as SIP Reason Header)
   public String reason;

   // let's enforce id and type, to make sure we always get them
   public SignalingMessage(String jobId, MessageType type)
   {
      this.jobId = jobId;
      this.type = type;
   }

   public void setParameters(HashMap<String, Object> parameters)
   {
      this.parameters = parameters;
   }

   public void setAndroidContext(Context androidContext)
   {
      this.androidContext = androidContext;
   }

}
