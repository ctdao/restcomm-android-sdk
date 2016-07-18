package org.mobicents.restcomm.android.client.sdk.SignalingClient;

import android.content.Context;
import android.gov.nist.javax.sip.ResponseEventExt;
import android.gov.nist.javax.sip.SipStackExt;
import android.gov.nist.javax.sip.clientauthutils.AuthenticationHelper;
import android.gov.nist.javax.sip.message.SIPMessage;
import android.javax.sip.ClientTransaction;
import android.javax.sip.DialogTerminatedEvent;
import android.javax.sip.IOExceptionEvent;
import android.javax.sip.ListeningPoint;
import android.javax.sip.ObjectInUseException;
import android.javax.sip.RequestEvent;
import android.javax.sip.ResponseEvent;
import android.javax.sip.ServerTransaction;
import android.javax.sip.SipException;
import android.javax.sip.SipFactory;
import android.javax.sip.SipListener;
import android.javax.sip.SipProvider;
import android.javax.sip.SipStack;
import android.javax.sip.TimeoutEvent;
import android.javax.sip.Transaction;
import android.javax.sip.TransactionTerminatedEvent;
import android.javax.sip.header.CSeqHeader;
import android.javax.sip.header.CallIdHeader;
import android.javax.sip.header.ViaHeader;
import android.javax.sip.message.Request;
import android.javax.sip.message.Response;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Environment;
import android.os.Handler;
import android.os.SystemClock;
import android.text.format.Formatter;

import org.apache.http.conn.util.InetAddressUtils;
import org.mobicents.restcomm.android.client.sdk.RCClient;
import org.mobicents.restcomm.android.client.sdk.RCDevice;
import org.mobicents.restcomm.android.client.sdk.RCDeviceListener;
import org.mobicents.restcomm.android.client.sdk.util.RCLogger;

import java.io.File;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;

class JainSipClient implements SipListener, NotificationManager.NotificationManagerListener {

   // Interface the JainSipClient listener needs to implement, to get events from us
   public interface JainSipClientListener {
      void onClientOpenedEvent(String id, RCDeviceListener.RCConnectivityStatus connectivityStatus, RCClient.ErrorCodes status, String text);  // on successful/unsuccessful register, onPrivateClientConnectorOpenedEvent

      void onClientErrorEvent(String id, RCClient.ErrorCodes status, String text);  // mostly on unsuccessful register, onPrivateClientConnectorOpenErrorEvent

      void onClientClosedEvent(String id, RCClient.ErrorCodes status, String text);  // on successful unregister, onPrivateClientConnectorClosedEvent

      void onClientReconfigureEvent(String id, RCClient.ErrorCodes status, String text);  // on successful register, onPrivateClientConnectorOpenedEvent

      void onClientConnectivityEvent(String id, RCDeviceListener.RCConnectivityStatus connectivityStatus);

      void onClientMessageArrivedEvent(String id, String peer, String messageText);

      void onClientMessageSentEvent(String id, RCClient.ErrorCodes status, String text);
   }

   public JainSipClientListener listener;
   JainSipMessageBuilder jainSipMessageBuilder;
   JainSipJobManager jainSipJobManager;
   NotificationManager notificationManager;
   Context androidContext;
   HashMap<String, Object> configuration;
   // any client context that is not configuration related, like the rport
   HashMap<String, Object> jainSipClientContext;
   //boolean clientConnected = false;
   boolean clientOpened = false;
   static final String TAG = "JainSipClient";
   // android handler token to identify registration refresh posts
   final int REGISTER_REFRESH_HANDLER_TOKEN = 1;
   Handler signalingHandler;
   final int DEFAULT_REGISTER_EXPIRY_PERIOD = 60;
   final int DEFAULT_LOCAL_SIP_PORT = 5090;
   // the registration refresh needs to happen sooner than expiry to make sure that the client has a registration at all times. Let's
   // set it to EXPIRY - 10 seconds. TODO: in the future we could randomize this so that for example it is between half the expiry
   // and full expiry (in this example, a random between [30, 60] seconds) to avoid having all android clients refreshing all at
   // the same time and stressing Restcomm. Actually this is how Sofia SIP in restcomm-ios-sdk does it by default.
   final int REGISTER_REFRESH_MINUS_INTERVAL = 10;

   // JAIN SIP entities
   public SipFactory jainSipFactory;
   public SipStack jainSipStack;
   public ListeningPoint jainSipListeningPoint;
   public SipProvider jainSipProvider;

   JainSipClient(Handler signalingHandler)
   {
      this.signalingHandler = signalingHandler;
   }

   // -- Published API
   void open(String id, Context androidContext, HashMap<String, Object> configuration, JainSipClientListener listener)
   {
      RCLogger.i(TAG, "open(): " + configuration.toString());

      if (clientOpened) {
         listener.onClientOpenedEvent(id, notificationManager.getConnectivityStatus(), RCClient.ErrorCodes.ERROR_DEVICE_ALREADY_OPENED,
               RCClient.errorText(RCClient.ErrorCodes.ERROR_DEVICE_ALREADY_OPENED));
         return;
      }

      this.listener = listener;
      this.androidContext = androidContext;
      this.configuration = configuration;
      jainSipMessageBuilder = new JainSipMessageBuilder();
      jainSipJobManager = new JainSipJobManager(this);
      notificationManager = new NotificationManager(androidContext, signalingHandler, this);
      jainSipClientContext = new HashMap<String, Object>();

      jainSipFactory = SipFactory.getInstance();
      jainSipFactory.resetFactory();
      jainSipFactory.setPathName("android.gov.nist");

      Properties properties = new Properties();
      properties.setProperty("android.javax.sip.STACK_NAME", "androidSip");

      // Setup TLS even if currently we aren't using it, so that if user changes the setting later
      // the SIP stack is ready to support it
      String keystoreFilename = "restcomm-android.keystore";
      HashMap<String, String> securityParameters = SecurityHelper.generateKeystore(androidContext, keystoreFilename);
      SecurityHelper.setProperties(properties, securityParameters.get("keystore-path"), securityParameters.get("keystore-password"));

      if (configuration.containsKey(RCDevice.ParameterKeys.SIGNALING_JAIN_SIP_LOGGING_ENABLED) &&
            configuration.get(RCDevice.ParameterKeys.SIGNALING_JAIN_SIP_LOGGING_ENABLED) == true) {
         // You need 16 for logging traces. 32 for debug + traces.
         // Your code will limp at 32 but it is best for debugging.
         properties.setProperty("android.gov.nist.javax.sip.TRACE_LEVEL", "32");
         File downloadPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
         properties.setProperty("android.gov.nist.javax.sip.DEBUG_LOG", downloadPath.getAbsolutePath() + "/debug-jain.log");
         properties.setProperty("android.gov.nist.javax.sip.SERVER_LOG", downloadPath.getAbsolutePath() + "/server-jain.log");
      }

      try {
         jainSipStack = jainSipFactory.createSipStack(properties);
         jainSipJobManager.add(id, JainSipJob.Type.TYPE_OPEN, configuration);
      }
      catch (SipException e) {
         throw new RuntimeException(RCClient.errorText(RCClient.ErrorCodes.ERROR_SIGNALING_SIP_STACK_BOOTSTRAP));
         /*
         listener.onClientOpenedEvent(id, notificationManager.getConnectivityStatus(), RCClient.ErrorCodes.ERROR_SIGNALING_SIP_STACK_BOOTSTRAP,
               RCClient.errorText(RCClient.ErrorCodes.ERROR_SIGNALING_SIP_STACK_BOOTSTRAP));
         RCLogger.e(TAG, "open(): " + e.getMessage());
         e.printStackTrace();
         */
      }
   }

   void close(final String id)
   {
      RCLogger.v(TAG, "close(): " + id);

      if (clientOpened) {
         // cancel any pending scheduled registrations
         //signalingHandler.removeCallbacksAndMessages(REGISTER_REFRESH_HANDLER_TOKEN);

         // TODO: close any active calls
         //

         notificationManager.close();
         jainSipJobManager.removeAll();

         if (configuration.containsKey(RCDevice.ParameterKeys.SIGNALING_DOMAIN) && !configuration.get(RCDevice.ParameterKeys.SIGNALING_DOMAIN).equals("")) {
            // non registrar-less, we need to unregister and when done shutdown
            jainSipJobManager.add(id, JainSipJob.Type.TYPE_CLOSE, this.configuration);
         }
         else {
            // registrar-less, just shutdown and notify UI thread
            try {
               jainSipClientUnbind();
               jainSipClientStopStack();

               listener.onClientClosedEvent(id, RCClient.ErrorCodes.SUCCESS, RCClient.errorText(RCClient.ErrorCodes.SUCCESS));
            }
            catch (JainSipException e) {
               RCLogger.w(TAG, "close(): close failed: " + Arrays.toString(Thread.currentThread().getStackTrace()));
               listener.onClientClosedEvent(id, e.errorCode, e.errorText);
            }
         }
      }
      else {
         RCLogger.w(TAG, "close(): JAIN SIP client already closed, bailing");
         listener.onClientClosedEvent(id, RCClient.ErrorCodes.ERROR_SIGNALING_SIP_STACK_BOOTSTRAP, RCClient.errorText(RCClient.ErrorCodes.ERROR_SIGNALING_SIP_STACK_BOOTSTRAP));
      }
   }

   void reconfigure(String id, Context androidContext, HashMap<String, Object> parameters, JainSipClientListener listener)
   {
      RCLogger.i(TAG, "reconfigure(): " + parameters.toString());

      // check which parameters actually changed by comparing this.configuration with parameters
      HashMap<String, Object> modifiedParameters = JainSipConfiguration.modifiedParameters(this.configuration, parameters);

      if (modifiedParameters.size() == 0) {
         listener.onClientReconfigureEvent(id, RCClient.ErrorCodes.SUCCESS, RCClient.errorText(RCClient.ErrorCodes.SUCCESS));
         return;
      }

      HashMap<String, Object> oldParameters = new HashMap<String, Object>();
      oldParameters.putAll(configuration);

      // remember that the new parameters can be just a subset of the currently stored in configuration, so to update the current parameters we need
      // to merge them with the new (i.e. keep the old and replace any new keys with new values)
      configuration = JainSipConfiguration.mergeParameters(configuration, parameters);

      // Set the media parameters right away, since they are irrelevant to signaling
      if (modifiedParameters.containsKey(RCDevice.ParameterKeys.MEDIA_TURN_ENABLED)) {
         this.configuration.put(RCDevice.ParameterKeys.MEDIA_TURN_ENABLED, modifiedParameters.get(RCDevice.ParameterKeys.MEDIA_TURN_ENABLED));
      }
      if (modifiedParameters.containsKey(RCDevice.ParameterKeys.MEDIA_TURN_URL)) {
         this.configuration.put(RCDevice.ParameterKeys.MEDIA_TURN_URL, modifiedParameters.get(RCDevice.ParameterKeys.MEDIA_TURN_URL));
      }
      if (modifiedParameters.containsKey(RCDevice.ParameterKeys.MEDIA_TURN_USERNAME)) {
         this.configuration.put(RCDevice.ParameterKeys.MEDIA_TURN_USERNAME, modifiedParameters.get(RCDevice.ParameterKeys.MEDIA_TURN_USERNAME));
      }
      if (modifiedParameters.containsKey(RCDevice.ParameterKeys.MEDIA_TURN_PASSWORD)) {
         this.configuration.put(RCDevice.ParameterKeys.MEDIA_TURN_PASSWORD, modifiedParameters.get(RCDevice.ParameterKeys.MEDIA_TURN_PASSWORD));
      }

      HashMap<String, Object> multipleParameters = new HashMap<String, Object>();
      multipleParameters.put("old-parameters", oldParameters);
      multipleParameters.put("new-parameters", configuration);
      if (modifiedParameters.containsKey(RCDevice.ParameterKeys.SIGNALING_SECURE_ENABLED)) {
         // if signaling has changed we need to a. unregister from old using old creds, b. unbind, c. bind, d. register with new using new creds
         // start FSM and pass it both previous and current parameters
         jainSipJobManager.add(id, JainSipJob.Type.TYPE_RECONFIGURE_RELOAD_NETWORKING, multipleParameters);
      }
      else if (modifiedParameters.containsKey(RCDevice.ParameterKeys.SIGNALING_USERNAME) ||
            modifiedParameters.containsKey(RCDevice.ParameterKeys.SIGNALING_PASSWORD) ||
            modifiedParameters.containsKey(RCDevice.ParameterKeys.SIGNALING_DOMAIN)) {
         // if username, password or domain has changed we need to a. unregister from old using old creds and b. register with new using new creds
         // start FSM and pass it both previous and current parameters
         jainSipJobManager.add(id, JainSipJob.Type.TYPE_RECONFIGURE, multipleParameters);
      }
      else {
         listener.onClientReconfigureEvent(id, RCClient.ErrorCodes.SUCCESS, RCClient.errorText(RCClient.ErrorCodes.SUCCESS));
      }
   }

   void call(String jobId, HashMap<String, Object> parameters, JainSipCall.JainSipCallListener listener)
   {
      RCLogger.i(TAG, "call(): id: " + jobId + ", parameters: " + parameters.toString());

      if (!notificationManager.haveConnectivity()) {
         listener.onCallErrorEvent(jobId, RCClient.ErrorCodes.ERROR_NO_CONNECTIVITY, RCClient.errorText(RCClient.ErrorCodes.ERROR_NO_CONNECTIVITY));
         return;
      }

      JainSipCall jainSipCall = new JainSipCall(this, listener);
      jainSipCall.open(jobId, parameters);
   }

   void accept(String jobId, HashMap<String, Object> parameters, JainSipCall.JainSipCallListener listener)
   {
      RCLogger.i(TAG, "accept(): id: " + jobId + ", parameters: " + parameters.toString());

      if (!notificationManager.haveConnectivity()) {
         listener.onCallErrorEvent(jobId, RCClient.ErrorCodes.ERROR_NO_CONNECTIVITY, RCClient.errorText(RCClient.ErrorCodes.ERROR_NO_CONNECTIVITY));
         return;
      }

      JainSipJob jainSipJob = jainSipJobManager.get(jobId);
      if (jainSipJob == null) {
         throw new RuntimeException("Error accepting a call that doesn't exist in Job Manager");
      }
      jainSipJob.jainSipCall.accept(jainSipJob, parameters);
   }

   void hangup(String jobId, JainSipCall.JainSipCallListener listener)
   {
      RCLogger.i(TAG, "hangup(): id: " + jobId);

      if (!notificationManager.haveConnectivity()) {
         listener.onCallErrorEvent(jobId, RCClient.ErrorCodes.ERROR_NO_CONNECTIVITY, RCClient.errorText(RCClient.ErrorCodes.ERROR_NO_CONNECTIVITY));
         return;
      }

      JainSipJob jainSipJob = jainSipJobManager.get(jobId);
      jainSipJob.jainSipCall.disconnect(jainSipJob);
   }

   void sendDigits(String jobId, String digits)
   {
      RCLogger.i(TAG, "sendDigits(): id: " + jobId + ", digits: " + digits);

      JainSipJob jainSipJob = jainSipJobManager.get(jobId);
      jainSipJob.jainSipCall.sendDigits(jainSipJob, digits);
   }

   void sendMessage(String id, HashMap<String, Object> parameters)
   {
      RCLogger.i(TAG, "call(): id: " + id + ", parameters: " + parameters.toString());

      if (!notificationManager.haveConnectivity()) {
         listener.onClientMessageSentEvent(id, RCClient.ErrorCodes.ERROR_NO_CONNECTIVITY, RCClient.errorText(RCClient.ErrorCodes.ERROR_NO_CONNECTIVITY));
         return;
      }

      try {
         Transaction transaction = jainSipClientSendMessage(id, parameters);
         jainSipJobManager.add(id, JainSipJob.Type.TYPE_MESSAGE, transaction, parameters, null);
      }
      catch (JainSipException e) {
         listener.onClientMessageSentEvent(id, e.errorCode, e.errorText);
      }
   }

   // -- Internal APIs
   // Release JAIN networking facilities
   public void jainSipClientUnbind() throws JainSipException
   {
      RCLogger.w(TAG, "unbind()");
      if (jainSipListeningPoint != null) {
         try {
            jainSipProvider.removeSipListener(this);
            if (jainSipProvider.getListeningPoints().length > 1) {
               RCLogger.e(TAG, "unbind(): Listening Point count > 1: " + jainSipProvider.getListeningPoints().length);
            }
            jainSipStack.deleteSipProvider(jainSipProvider);
            jainSipStack.deleteListeningPoint(jainSipListeningPoint);

            jainSipListeningPoint = null;
         }
         catch (ObjectInUseException e) {
            RCLogger.e(TAG, "unbind(): " + e.getMessage());
            e.printStackTrace();
            throw new JainSipException(RCClient.ErrorCodes.ERROR_SIGNALING_UNHANDLED, RCClient.errorText(RCClient.ErrorCodes.ERROR_SIGNALING_UNHANDLED));
         }
      }
   }

   // Setup JAIN networking facilities
   public void jainSipClientBind(HashMap<String, Object> parameters) throws JainSipException
   {
      RCLogger.w(TAG, "bind()");
      if (jainSipListeningPoint == null) {
         if (!notificationManager.haveConnectivity()) {
            throw new JainSipException(RCClient.ErrorCodes.ERROR_NO_CONNECTIVITY,
                  RCClient.errorText(RCClient.ErrorCodes.ERROR_NO_CONNECTIVITY));
         }

         // new network interface is up, let's retrieve its ip address
         String transport = "tcp";
         if (JainSipConfiguration.getBoolean(configuration, RCDevice.ParameterKeys.SIGNALING_SECURE_ENABLED)) {
            transport = "tls";
         }

         Integer port = DEFAULT_LOCAL_SIP_PORT;
         if (parameters.containsKey(RCDevice.ParameterKeys.SIGNALING_LOCAL_PORT)) {
            port = (Integer) parameters.get(RCDevice.ParameterKeys.SIGNALING_LOCAL_PORT);
         }

         try {
            jainSipListeningPoint = jainSipStack.createListeningPoint(getIPAddress(true), port, transport);
            jainSipProvider = jainSipStack.createSipProvider(jainSipListeningPoint);
            jainSipProvider.addSipListener(this);
            jainSipMessageBuilder.initialize(jainSipFactory, jainSipProvider);
         }
         catch (SocketException e) {
            RCLogger.i(TAG, "register(): " + e.getMessage());
            e.printStackTrace();
            throw new JainSipException(RCClient.ErrorCodes.ERROR_SIGNALING_NETWORK_INTERFACE,
                  RCClient.errorText(RCClient.ErrorCodes.ERROR_SIGNALING_NETWORK_INTERFACE));
         }
         catch (Exception e) {
            RCLogger.e(TAG, "register(): " + e.getMessage());
            e.printStackTrace();
            throw new JainSipException(RCClient.ErrorCodes.ERROR_SIGNALING_NETWORK_BINDING,
                  RCClient.errorText(RCClient.ErrorCodes.ERROR_SIGNALING_NETWORK_BINDING));
         }
      }
      else {
         RCLogger.e(TAG, "jainSipBind(): Error: Listening point already instantiated");
      }
   }

   int jainSipClientStartStack(String id)
   {
      // TODO: Didn't have that in the past, hope it doesn't cause any issues
      try {
         jainSipStack.start();
         clientOpened = true;

      }
      catch (SipException e) {
         RCLogger.e(TAG, "jainSipStartStack(): " + e.getMessage());
         e.printStackTrace();
         return RCClient.ErrorCodes.ERROR_SIGNALING_SIP_STACK_BOOTSTRAP.ordinal();
      }
      return RCClient.ErrorCodes.SUCCESS.ordinal();
   }

   void jainSipClientStopStack()
   {
      jainSipStack.stop();
      jainSipMessageBuilder.shutdown();
      jainSipFactory.resetFactory();

        /*
        configuration = null;
        androidContext = null;
        listener = null;
        jainSipFsm = null;
        */

      clientOpened = false;
   }

   public ClientTransaction jainSipClientRegister(String id, final HashMap<String, Object> parameters) throws JainSipException
   {
      RCLogger.v(TAG, "jainSipRegister()");

      if (!notificationManager.haveConnectivity()) {
         throw new JainSipException(RCClient.ErrorCodes.ERROR_NO_CONNECTIVITY,
               RCClient.errorText(RCClient.ErrorCodes.ERROR_NO_CONNECTIVITY));
      }

      int expiry = DEFAULT_REGISTER_EXPIRY_PERIOD;
      if (parameters.containsKey("signaling-register-expiry") && !parameters.get("signaling-register-expiry").equals("")) {
         expiry = (Integer) parameters.get("signaling-register-expiry");
         if (expiry <= REGISTER_REFRESH_MINUS_INTERVAL) {
            RCLogger.w(TAG, "jainSipRegister(): Register expiry period too small, using default: " + DEFAULT_REGISTER_EXPIRY_PERIOD);
            expiry = DEFAULT_REGISTER_EXPIRY_PERIOD;
         }
      }

      ClientTransaction transaction = null;
      try {
         Request registerRequest = jainSipMessageBuilder.buildRegisterRequest(jainSipListeningPoint, expiry, parameters);
         if (registerRequest != null) {
            RCLogger.v(TAG, "jainSipRegister(): Sending SIP request: \n" + registerRequest.toString());

            // Remember that this might block waiting for DNS server
            transaction = this.jainSipProvider.getNewClientTransaction(registerRequest);
            transaction.sendRequest();
         }
      }
      catch (SipException e) {
         throw new JainSipException(RCClient.ErrorCodes.ERROR_SIGNALING_REGISTER_COULD_NOT_CONNECT, RCClient.errorText(RCClient.ErrorCodes.ERROR_SIGNALING_REGISTER_COULD_NOT_CONNECT));
      }

      // cancel any pending scheduled registrations (in case this is an on-demand registration and we end up posting to handler on top of the old)
      signalingHandler.removeCallbacksAndMessages(REGISTER_REFRESH_HANDLER_TOKEN);
      // schedule a registration update after 'registrationRefresh' seconds
      Runnable runnable = new Runnable() {
         @Override
         public void run()
         {
            jainSipJobManager.add(Long.toString(System.currentTimeMillis()), JainSipJob.Type.TYPE_REGISTER_REFRESH, parameters);
         }
      };
      signalingHandler.postAtTime(runnable, REGISTER_REFRESH_HANDLER_TOKEN, SystemClock.uptimeMillis() + (expiry - REGISTER_REFRESH_MINUS_INTERVAL) * 1000);

      return transaction;
   }

   public ClientTransaction jainSipClientUnregister(String id, final HashMap<String, Object> parameters) throws JainSipException
   {
      RCLogger.v(TAG, "jainSipUnregister()");

      if (!notificationManager.haveConnectivity()) {
         throw new JainSipException(RCClient.ErrorCodes.ERROR_NO_CONNECTIVITY,
               RCClient.errorText(RCClient.ErrorCodes.ERROR_NO_CONNECTIVITY));
      }

      ClientTransaction transaction = null;
      try {
         Request registerRequest = jainSipMessageBuilder.buildRegisterRequest(jainSipListeningPoint, 0, parameters);
         if (registerRequest != null) {
            RCLogger.v(TAG, "jainSipUnregister(): Sending SIP request: \n" + registerRequest.toString());

            // Remember that this might block waiting for DNS server
            transaction = this.jainSipProvider.getNewClientTransaction(registerRequest);
            transaction.sendRequest();
         }
      }
      catch (SipException e) {
         throw new JainSipException(RCClient.ErrorCodes.ERROR_SIGNALING_REGISTER_COULD_NOT_CONNECT,
               RCClient.errorText(RCClient.ErrorCodes.ERROR_SIGNALING_REGISTER_COULD_NOT_CONNECT));
      }

      // cancel any pending scheduled registrations
      signalingHandler.removeCallbacksAndMessages(REGISTER_REFRESH_HANDLER_TOKEN);

      return transaction;
   }

   public Transaction jainSipClientSendMessage(String id, final HashMap<String, Object> parameters) throws JainSipException
   {
      RCLogger.v(TAG, "jainSipClientSendMessage()");

      try {
         Request request = jainSipMessageBuilder.buildMessageRequest(id, (String) parameters.get("username"),
               (String) parameters.get("text-message"), jainSipListeningPoint, configuration);
         RCLogger.v(TAG, "jainSipClientSendMessage(): Sending SIP request: \n" + request.toString());

         ClientTransaction transaction = this.jainSipProvider.getNewClientTransaction(request);
         transaction.sendRequest();
         return transaction;
      }
      catch (SipException e) {
         throw new JainSipException(RCClient.ErrorCodes.ERROR_SIGNALING_MESSAGE_SEND_FAILED,
               RCClient.errorText(RCClient.ErrorCodes.ERROR_SIGNALING_MESSAGE_SEND_FAILED));
      }
   }

   // Notice that this is used both for registrations and calls
   public void jainSipAuthenticate(String id, JainSipJob jainSipJob, HashMap<String, Object> parameters, ResponseEventExt responseEventExt) throws JainSipException
   {
      try {
         AuthenticationHelper authenticationHelper = ((SipStackExt) jainSipStack).getAuthenticationHelper(
               new AccountManagerImpl((String) parameters.get(RCDevice.ParameterKeys.SIGNALING_USERNAME),
                     responseEventExt.getRemoteIpAddress(), (String) parameters.get(RCDevice.ParameterKeys.SIGNALING_PASSWORD)), jainSipMessageBuilder.getHeaderFactory());

         // we 're subtracting one since the first attempt has already taken place
         // (that way we are enforcing MAX_AUTH_ATTEMPTS at most)
         if (jainSipJob.shouldRetry()) {
            ClientTransaction authenticationTransaction = authenticationHelper.handleChallenge(responseEventExt.getResponse(),
                  (ClientTransaction) jainSipJob.transaction, jainSipProvider, 5, true);

            // update previous transaction with authenticationTransaction (remember that previous ended with 407 final response)
            //String authCallId = ((CallIdHeader) authenticationTransaction.getRequest().getHeader("Call-ID")).getCallId();
            jainSipJob.updateTransaction(authenticationTransaction);
            // TODO: not sure if this is needed. Auth doesn't change Call-Id, right?
            //jainSipJob.updateCallId(authCallId);
            RCLogger.v(TAG, "Sending SIP request: \n" + authenticationTransaction.getRequest().toString());
            authenticationTransaction.sendRequest();
            jainSipJob.increaseAuthAttempts();
         }
         else {
            throw new JainSipException(RCClient.ErrorCodes.ERROR_SIGNALING_REGISTER_AUTHENTICATION_MAX_RETRIES_REACHED,
                  RCClient.errorText(RCClient.ErrorCodes.ERROR_SIGNALING_REGISTER_AUTHENTICATION_MAX_RETRIES_REACHED));
         }
      }
      catch (Exception e) {
         throw new JainSipException(RCClient.ErrorCodes.ERROR_SIGNALING_UNHANDLED, RCClient.errorText(RCClient.ErrorCodes.ERROR_SIGNALING_UNHANDLED));
      }
   }

   // -- SipListener events
   // Remember that SipListener events run in a separate thread created by JAIN SIP, which makes sharing of resources between our signaling thread and this
   // JAIN SIP thread a bit difficult. To avoid that let's do the actual handling of these events in the signaling thread.
   public void processRequest(final RequestEvent requestEvent)
   {
      Runnable runnable = new Runnable() {
         @Override
         public void run()
         {
            Request request = requestEvent.getRequest();
            RCLogger.v(TAG, "Received SIP request: \n" + request.toString());
            CallIdHeader callIdHeader = (CallIdHeader)request.getHeader("Call-ID");
            String callId = callIdHeader.getCallId();
            ServerTransaction serverTransaction = requestEvent.getServerTransaction();
            String method = request.getMethod();

            if (method.equals(Request.INVITE)) {
               // New INVITE, need to create new job
               JainSipCall jainSipCall = new JainSipCall(JainSipClient.this, (JainSipCall.JainSipCallListener)listener);
               // Remember, this is new dialog and hence serverTransaction is null
               JainSipJob jainSipJob = jainSipJobManager.add(callId, JainSipJob.Type.TYPE_CALL, null, null, jainSipCall);

               jainSipCall.processRequest(jainSipJob, requestEvent);
            }
            else if (method.equals(Request.MESSAGE)) {
               try {
                  if (serverTransaction == null) {
                     // no server transaction yet
                     serverTransaction = jainSipProvider.getNewServerTransaction(request);
                  }

                  Response response = jainSipMessageBuilder.buildResponse(Response.OK, request);
                  RCLogger.v(TAG, "Sending SIP response: \n" + response.toString());
                  serverTransaction.sendResponse(response);
                  String messageText = ((SIPMessage)request).getMessageContent();
                  listener.onClientMessageArrivedEvent(callId, ((SIPMessage)request).getFrom().getAddress().toString(), messageText);
               }
               catch (Exception e) {
                  RCLogger.e(TAG, "processRequest(): Error sending 200 OK to SIP Message: " + e.getMessage());
                  e.printStackTrace();
               }
            }
            else if (method.equals(Request.BYE) || method.equals(Request.CANCEL) || method.equals(Request.ACK)) {
               JainSipJob jainSipJob = jainSipJobManager.getByCallId(callId);
               if (jainSipJob == null) {
                  // no need to notify UI thread
                  RCLogger.e(TAG, "processRequest(): error, got request for unknown transaction job. Method: " + method);
                  return;
               }

               // forward to JainSipCall for processing
               jainSipJob.jainSipCall.processRequest(jainSipJob, requestEvent);
            }
         }
      };
      signalingHandler.post(runnable);
   }

   public void processResponse(final ResponseEvent responseEvent)
   {
      Runnable runnable = new Runnable() {
         @Override
         public void run()
         {
            ResponseEventExt responseEventExt = (ResponseEventExt) responseEvent;
            Response response = responseEvent.getResponse();
            RCLogger.v(TAG, "Received SIP response: \n" + response.toString());

            //JainSipJob jainSipJob = jainSipJobManager.getByBranchId(responseEvent.getClientTransaction().getBranchId());
            JainSipJob jainSipJob = jainSipJobManager.getByCallId(((CallIdHeader)response.getHeader("Call-ID")).getCallId());
            if (jainSipJob == null) {
               RCLogger.e(TAG, "processResponse(): error, got response for unknown job");
               return;
            }

            CSeqHeader cseq = (CSeqHeader) response.getHeader(CSeqHeader.NAME);
            String method = cseq.getMethod();
            if (method.equals(Request.REGISTER)) {
               if (response.getStatusCode() == Response.PROXY_AUTHENTICATION_REQUIRED || response.getStatusCode() == Response.UNAUTHORIZED) {
                  jainSipJob.processFsm(jainSipJob.id, "auth-required", responseEventExt, RCClient.ErrorCodes.ERROR_SIGNALING_REGISTER_SERVICE_UNAVAILABLE,
                        RCClient.errorText(RCClient.ErrorCodes.ERROR_SIGNALING_REGISTER_SERVICE_UNAVAILABLE));
               }
               else if (response.getStatusCode() == Response.FORBIDDEN) {
                  jainSipJob.processFsm(jainSipJob.id, "register-failure", null, RCClient.ErrorCodes.ERROR_SIGNALING_REGISTER_AUTHENTICATION_FORBIDDEN,
                        RCClient.errorText(RCClient.ErrorCodes.ERROR_SIGNALING_REGISTER_AUTHENTICATION_FORBIDDEN));
               }
               else if (response.getStatusCode() == Response.SERVICE_UNAVAILABLE) {
                  jainSipJob.processFsm(jainSipJob.id, "register-failure", null, RCClient.ErrorCodes.ERROR_SIGNALING_REGISTER_SERVICE_UNAVAILABLE,
                        RCClient.errorText(RCClient.ErrorCodes.ERROR_SIGNALING_REGISTER_SERVICE_UNAVAILABLE));
               }
               else if (response.getStatusCode() == Response.OK) {
                  // register succeeded
                  ViaHeader viaHeader = (ViaHeader) response.getHeader(ViaHeader.NAME);
                  // keep around the Via received and rport parms so that we can populate the contact properly
                  if (viaHeader.getReceived() != null) {
                     jainSipClientContext.put("via-received", viaHeader.getReceived());
                  }
                  else {
                     jainSipClientContext.remove("via-received");
                  }

                  if (viaHeader.getRPort() != -1) {
                     jainSipClientContext.put("via-rport", viaHeader.getRPort());
                  }
                  else {
                     jainSipClientContext.remove("via-rport");
                  }

                  jainSipJob.processFsm(jainSipJob.id, "register-success", null, RCClient.ErrorCodes.SUCCESS, RCClient.errorText(RCClient.ErrorCodes.SUCCESS));
               }
            }
            else if (method.equals(Request.INVITE) || method.equals(Request.BYE) || method.equals(Request.CANCEL) ||
                  method.equals(Request.INFO)) {
               // forward to JainSipCall for processing
               jainSipJob.jainSipCall.processResponse(jainSipJob, responseEvent);
            }
            else if (method.equals(Request.MESSAGE)) {
               if (response.getStatusCode() == Response.PROXY_AUTHENTICATION_REQUIRED || response.getStatusCode() == Response.UNAUTHORIZED) {
                  try {
                     jainSipAuthenticate(jainSipJob.id, jainSipJob, configuration, responseEventExt);
                  }
                  catch (JainSipException e) {
                     listener.onClientMessageSentEvent(jainSipJob.id, e.errorCode, e.errorText);
                  }
               }
               else if (response.getStatusCode() == Response.OK) {
                  listener.onClientMessageSentEvent(jainSipJob.id, RCClient.ErrorCodes.SUCCESS,
                        RCClient.errorText(RCClient.ErrorCodes.SUCCESS));
               }
               else if (response.getStatusCode() == Response.FORBIDDEN) {
                  listener.onClientMessageSentEvent(jainSipJob.id, RCClient.ErrorCodes.ERROR_SIGNALING_REGISTER_AUTHENTICATION_FORBIDDEN,
                        RCClient.errorText(RCClient.ErrorCodes.ERROR_SIGNALING_REGISTER_AUTHENTICATION_FORBIDDEN));
               }
               else if (response.getStatusCode() == Response.SERVICE_UNAVAILABLE) {
                  listener.onClientMessageSentEvent(jainSipJob.id, RCClient.ErrorCodes.ERROR_SIGNALING_REGISTER_SERVICE_UNAVAILABLE,
                        RCClient.errorText(RCClient.ErrorCodes.ERROR_SIGNALING_REGISTER_SERVICE_UNAVAILABLE));
               }
            }
         }
      };
      signalingHandler.post(runnable);
   }

   public void processDialogTerminated(DialogTerminatedEvent dialogTerminatedEvent)
   {
      Runnable runnable = new Runnable() {
         @Override
         public void run()
         {
            // TODO:
         }
      };
      signalingHandler.post(runnable);

        /*
                RCLogger.i(TAG, "SipManager.processDialogTerminated: " + dialogTerminatedEvent.toString() + "\n" +
                        "\tdialog: " + dialogTerminatedEvent.getDialog().toString());
        */
   }

   public void processIOException(IOExceptionEvent exceptionEvent)
   {
      Runnable runnable = new Runnable() {
         @Override
         public void run()
         {
            // TODO:
         }
      };
      signalingHandler.post(runnable);

        /*
                RCLogger.e(TAG, "SipManager.processIOException: " + exceptionEvent.toString() + "\n" +
                        "\thost: " + exceptionEvent.getHost() + "\n" +
                        "\tport: " + exceptionEvent.getPort());
        */
   }

   public void processTransactionTerminated(TransactionTerminatedEvent transactionTerminatedEvent)
   {
      Runnable runnable = new Runnable() {
         @Override
         public void run()
         {
            // TODO:
         }
      };
      signalingHandler.post(runnable);

        /*
                RCLogger.i(TAG, "SipManager.processTransactionTerminated: " + transactionTerminatedEvent.toString() + "\n" +
                        "\tclient transaction: " + transactionTerminatedEvent.getClientTransaction() + "\n" +
                        "\tserver transaction: " + transactionTerminatedEvent.getServerTransaction() + "\n" +
                        "\tisServerTransaction: " + transactionTerminatedEvent.isServerTransaction());
        */
   }

   public void processTimeout(final TimeoutEvent timeoutEvent)
   {
      Runnable runnable = new Runnable() {
         @Override
         public void run()
         {
            Request request;
            Transaction transaction;
            if (timeoutEvent.isServerTransaction()) {
               request = timeoutEvent.getServerTransaction().getRequest();
               //transaction = timeoutEvent.getServerTransaction();
            }
            else {
               request = timeoutEvent.getClientTransaction().getRequest();
               //transaction = timeoutEvent.getClientTransaction();
            }

            RCLogger.i(TAG, "processTimeout(): method: " + request.getMethod() + " URI: " + request.getRequestURI());
            JainSipJob jainSipJob = jainSipJobManager.getByCallId(((CallIdHeader) request.getHeader("Call-ID")).getCallId());
            if (jainSipJob == null) {
               // transaction is not identified, just emit a log error; don't notify UI thread
               RCLogger.i(TAG, "processTimeout(): transaction not identified");
               return;
            }

            if (jainSipJob.type == JainSipJob.Type.TYPE_REGISTRATION) {
               // TODO: need to handle registration refreshes
               jainSipJob.processFsm(jainSipJob.id, "timeout", null, RCClient.ErrorCodes.ERROR_SIGNALING_REGISTER_TIMEOUT,
                     RCClient.errorText(RCClient.ErrorCodes.ERROR_SIGNALING_REGISTER_TIMEOUT));
               //jainSipJobManager.remove(callId);
            }
            else if (jainSipJob.type == JainSipJob.Type.TYPE_CALL) {
               // TODO: call JainSipCall.processTimeout()
            }
            else if (jainSipJob.type == JainSipJob.Type.TYPE_MESSAGE) {
               // TODO: call JainSipMessage.processTimeout()
            }

                /*
                RCLogger.i(TAG, "processTimeout(): method: " + request.getMethod() + " URI: " + request.getRequestURI());
                if (request.getMethod() == Request.INVITE) {
                    dispatchSipError(ISipEventListener.ErrorContext.ERROR_CONTEXT_CALL, RCClient.ErrorCodes.SIGNALLING_TIMEOUT,
                            "Timed out waiting on " + request.getMethod());
                }
                else {
                    dispatchSipError(ISipEventListener.ErrorContext.ERROR_CONTEXT_NON_CALL, RCClient.ErrorCodes.SIGNALLING_TIMEOUT,
                            "Timed out waiting on " + request.getMethod());
                }
                */
         }
      };
      signalingHandler.post(runnable);
   }

   // -- NotificationManagerListener events
   public void onConnectivityChange(NotificationManager.ConnectivityChange connectivityChange)
   {
      // No matter the connectivity change, cancel any pending scheduled registrations
      signalingHandler.removeCallbacksAndMessages(REGISTER_REFRESH_HANDLER_TOKEN);

      if (connectivityChange == NotificationManager.ConnectivityChange.OFFLINE) {
         try {
            jainSipClientUnbind();
            listener.onClientConnectivityEvent(Long.toString(System.currentTimeMillis()), RCDeviceListener.RCConnectivityStatus.RCConnectivityStatusNone);
         }
         catch (JainSipException e) {
            // let's notify the App regardless of the exception since we no longer have connectivity
            RCLogger.v(TAG, "onConnectivityChange: failed to unbind");
            listener.onClientConnectivityEvent(Long.toString(System.currentTimeMillis()), RCDeviceListener.RCConnectivityStatus.RCConnectivityStatusNone);
         }
      }
      else if (connectivityChange == NotificationManager.ConnectivityChange.OFFLINE_TO_WIFI ||
            connectivityChange == NotificationManager.ConnectivityChange.OFFLINE_TO_CELLULAR_DATA) {
         HashMap<String, Object> parameters = new HashMap<>(this.configuration);
         if (connectivityChange == NotificationManager.ConnectivityChange.OFFLINE_TO_WIFI) {
            parameters.put("connectivity-status", RCDeviceListener.RCConnectivityStatus.RCConnectivityStatusWiFi);
         }
         else {
            parameters.put("connectivity-status", RCDeviceListener.RCConnectivityStatus.RCConnectivityStatusWiFi);
         }
         jainSipJobManager.add(Long.toString(System.currentTimeMillis()), JainSipJob.Type.TYPE_START_NETWORKING, parameters);
      }
      else if (connectivityChange == NotificationManager.ConnectivityChange.CELLULAR_DATA_TO_WIFI ||
            connectivityChange == NotificationManager.ConnectivityChange.WIFI_TO_CELLULAR_DATA) {
         HashMap<String, Object> parameters = new HashMap<>(this.configuration);
         if (connectivityChange == NotificationManager.ConnectivityChange.CELLULAR_DATA_TO_WIFI) {
            parameters.put("connectivity-status", RCDeviceListener.RCConnectivityStatus.RCConnectivityStatusWiFi);
         }
         else {
            parameters.put("connectivity-status", RCDeviceListener.RCConnectivityStatus.RCConnectivityStatusCellular);
         }
         jainSipJobManager.add(Long.toString(System.currentTimeMillis()), JainSipJob.Type.TYPE_RELOAD_NETWORKING, parameters);
      }
   }

   // -- Helpers
   // TODO: Improve this, try to not depend on such low level facilities
   public String getIPAddress(boolean useIPv4) throws SocketException
   {
      RCLogger.i(TAG, "getIPAddress()");
      if (notificationManager.getConnectivityStatus() == RCDeviceListener.RCConnectivityStatus.RCConnectivityStatusWiFi) {
         WifiManager wifiMgr = (WifiManager) androidContext.getSystemService(Context.WIFI_SERVICE);
         WifiInfo wifiInfo = wifiMgr.getConnectionInfo();
         int ip = wifiInfo.getIpAddress();
         return Formatter.formatIpAddress(ip);
      }

      if (notificationManager.getConnectivityStatus() == RCDeviceListener.RCConnectivityStatus.RCConnectivityStatusCellular) {
         List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
         for (NetworkInterface intf : interfaces) {
            if (!intf.getName().matches("wlan.*")) {
               List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
               for (InetAddress addr : addrs) {
                  if (!addr.isLoopbackAddress()) {
                     String sAddr = addr.getHostAddress().toUpperCase();
                     boolean isIPv4 = InetAddressUtils.isIPv4Address(sAddr);
                     if (useIPv4) {
                        if (isIPv4)
                           return sAddr;
                     }
                     else {
                        if (!isIPv4) {
                           int delim = sAddr.indexOf('%'); // drop ip6 port
                           // suffix
                           return delim < 0 ? sAddr : sAddr.substring(0,
                                 delim);
                        }
                     }
                  }
               }
            }
         }
      }
      return "";
   }

}