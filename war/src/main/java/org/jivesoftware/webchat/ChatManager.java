/**
 * $RCSfile$
 * $Revision: 29977 $
 * $Date: 2006-05-05 11:54:03 -0700 (Fri, 05 May 2006) $
 *
 * Copyright (C) 2003-2008 Jive Software. All rights reserved.
 *
 * This software is published under the terms of the GNU Public License (GPL),
 * a copy of which is included in this distribution, or a commercial license
 * agreement with Jive.
 */

package org.jivesoftware.webchat;

import org.jivesoftware.webchat.actions.WorkgroupStatus;
import org.jivesoftware.webchat.settings.ChatSettingsManager;
import org.jivesoftware.webchat.settings.ConnectionSettings;
import org.jivesoftware.webchat.util.WebLog;

import org.jivesoftware.smack.ConnectionListener;
import org.jivesoftware.smack.SmackException.NotConnectedException;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.tcp.XMPPTCPConnection;
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration;
import org.jivesoftware.smackx.muc.MultiUserChat;
import org.jxmpp.jid.EntityBareJid;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import javax.servlet.ServletContext;

/**
 * The ChatManager for the Web Chat Service. The ChatManager handles all ChatSessions,
 * connections, and settings handlers for the Web Chat Service.
 *
 * @author Derek DeMoro
 */
public final class ChatManager {
    private Map<String, ChatSession> sessions;
    private XMPPTCPConnection globalConnection;
    private ChatSettingsManager chatSettingsManager;

    /**
     * Chats that are closed but not removed are to be removed after this period.
     */
    private static final long MAXIMUM_STALE_SESSION_LENGTH_IN_MS = 30 * 60 * 1000;

    /**
     * The browser that is used in the webchat session polls regularly for new messages. When these polls do no longer
     * occur, the chat is said to be 'inactive'. The value defined here defines the amount of milliseconds of
     * inactiveness after which the chat is to be closed and removed.
     */
    private static final long MAXIMUM_INACTIVE_TIME_IN_MS = 60 * 1000;

    /**
     * The browser that is used in the webchat session polls regularly for new messages. When these polls do no longer
     * occur, the chat is said to be 'inactive'. The value defined here defines the amount of milliseconds of
     * inactiveness after which a warning message will be sent to the chat occupants (informing them of potential
     * connectivity problems).
     */
    private static final long INACTIVE_TIME_WARNING_IN_MS = 10 * 1000;

    private static final ChatManager singleton = new ChatManager();
    

    /**
     * Returns the singleton instance of <CODE>ChatManager</CODE>,
     * creating it if necessary.
     * <p/>
     *
     * @return the singleton instance of <Code>ChatManager</CODE>
     */
    public static ChatManager getInstance() {
        return singleton;
    }

    /**
     * Creates a new session manager.
     */
    private ChatManager() {

        new WorkgroupInitializer().initialize();

        sessions = Collections.synchronizedMap(new HashMap<>());

        // Setup timer to check for lingering sessions.
        final Timer timer = new Timer();

        final TimerTask closeSessionTask = new TimerTask() {
            public void run() {
                removeStaleChats();
            }
        };

        final long delayInMillis = 5 * 1000;
        final long periodInMillis = 1 * 1000;
        timer.schedule(closeSessionTask, delayInMillis, periodInMillis);
    }

    private void removeStaleChats() {
        final Iterator<ChatSession> chatSessions = new ArrayList<ChatSession>(getChatSessions()).iterator();
        final long now = System.currentTimeMillis();
        while (chatSessions.hasNext()) {
            final ChatSession chatSession = chatSessions.next();
            final long lastCheck = chatSession.getLastCheck();
            
            if (chatSession.isClosed()) {
                if (lastCheck < now - MAXIMUM_STALE_SESSION_LENGTH_IN_MS) {
                    removeChatSession(chatSession.getSessionID());
                }
            } else {
                if (lastCheck != 0) {
                    // If the last time the user check for new messages is greater than timeOut,
                    // then we can assume the user has closed to window and has not explicitly closed the connection.
                    if (now - lastCheck > MAXIMUM_INACTIVE_TIME_IN_MS) {

                        // Close Chat Session
                        chatSession.close();

                        // Remove from cache
                        removeChatSession(chatSession.getSessionID());
                    }
                    // Warn the users that the browser client appears to be unresponsive
                    else if (!chatSession.isInactivityWarningSent() && now - lastCheck > INACTIVE_TIME_WARNING_IN_MS) {
                        final MultiUserChat chat = chatSession.getGroupChat();
                        if (chat != null) {
                            final String inactivityInMs = Long.toString(now - lastCheck);
                            final String inactivityInSecs = inactivityInMs.substring(0, inactivityInMs.length()-3);
                            try {
                                final Message chatMessage = new Message();
                                chatMessage.setType(Message.Type.groupchat);
                                chatMessage.setBody("The webchat client connection appears to unstable. No data has been received in the last " + inactivityInSecs + " seconds.");

                                EntityBareJid room = chat.getRoom();
                                chatMessage.setTo(room);
                                chat.sendMessage(chatMessage);

                                chatSession.setInactivityWarningSent(true);
                            } catch (NotConnectedException | InterruptedException e) {
                                WebLog.logError("Error sending message:", e);
                            }
                        }
                    }
                } else {
                    // Handle case where the user never joins a conversation and leaves the queue.
                    if (!chatSession.isInQueue() && (now - chatSession.getCreatedTimestamp() > MAXIMUM_INACTIVE_TIME_IN_MS)) {

                        chatSession.close();

                        // Remove from cache
                        removeChatSession(chatSession.getSessionID());
                    }
                }
            }
        }
    }

    /**
     * Adds a new <code>ChatSession</code> associated with a particular uniqueID.
     *
     * @param chatID      the uniqueID.
     * @param chatSession the <code>ChatSession</code> associated with the uniqueID.
     */
    public void addChatSession(String chatID, ChatSession chatSession) {
        sessions.put(chatID, chatSession);
    }

    /**
     * Gets the <code>ChatSession</code> associated with a unique ID. In general, each user is
     * assigned one ChatSession per transaction.
     *
     * @param chatID the uniqueID of the user.
     * @return the ChatSession associated with the unique ID.
     */
    public ChatSession getChatSession(String chatID) {
        return sessions.get(chatID);
    }

    /**
     * Removes a <code>ChatSession</code> from the ChatManager.
     *
     * @param chatID the unique id associated with the ChatSession.
     * @return the ChatSession being removed.
     */
    public ChatSession removeChatSession(String chatID) {
        return sessions.remove(chatID);
    }

    /**
     * Returns a <code>Collection</code> of <code>ChatSessions</code>.
     *
     * @return <code>Collection</code> of <code>ChatSessions</code>
     */
    public Collection<ChatSession> getChatSessions() {
        return sessions.values();
    }

    /**
     * Returns the number of ChatSessions.
     *
     * @return the number of ChatSessions.
     */
    public int getNumberOfSessions() {
        return sessions.size();
    }

    /**
     * Close a particular <code>ChatSession</code>.
     *
     * @param chatID the uniqueID identifying the ChatSession.
     */
    public void closeChatSession(String chatID) {
        final ChatSession chatSession = getChatSession(chatID);
        if (chatSession != null) {
            chatSession.close();
            removeChatSession(chatID);
        }
    }

    /**
     * Removes all ChatSessions from the Manager.
     */
    public void destroyAllSessions() {
        Collection<ChatSession> chatSessions = getChatSessions();
        Iterator<ChatSession> iter = chatSessions.iterator();
        while (iter.hasNext()) {
            ChatSession chatSession = iter.next();
            chatSession.close();
        }
        sessions = new HashMap<>();
    }

    /**
     * Sets the Global Connection for the Web Client service.
     *
     * @param con the <code>XMPPConnection</code> for the Web Client Service.
     */
    public void setGlobalConnection(XMPPTCPConnection con) {
        globalConnection = con;

        WorkgroupStatus.initStatusListener();
    }

    /**
     * Gets the Global <code>XMPPTCPConnection</code> for the Web Client Service.
     *
     * @return the <code>XMPPTCPConnection</code> for the Web Client Service.
     */
    public XMPPTCPConnection getGlobalConnection() {
        return globalConnection;
    }

    /**
     * Sets the <code>ChatSettingsManager</code> for the Web Client Service. The
     * ChatSettingsManager allows for connection details such as host, port, and password
     * s needed to connect to the XMPP Server.
     *
     * @param chatSettingsManager the ChatSettingsManager.
     */
    public void setChatSettingsManager(ChatSettingsManager chatSettingsManager) {
        this.chatSettingsManager = chatSettingsManager;
    }

    /**
     * Gets the <code>ChatSettingsManager</code> for the Web Client Service. You would
     * call this if you needed information on how to connect to XMPP Server.
     *
     * @return the ChatSettingsManager.
     */
    public ChatSettingsManager getChatSettingsManager() {
        return chatSettingsManager;
    }


    /**
     * Connection Handling.
     * @param context 
     * @return 
     */
    public synchronized XMPPTCPConnection createConnection(final ServletContext context) {
      XMPPTCPConnection xmppConn = null;

        ConnectionSettings settings = chatSettingsManager.getSettings();
        if (settings == null) {
            return null;
        }

        boolean sslEnabled = settings.isSSLEnabled();
        String host = settings.getServerDomain();
        int port = settings.getPort();
        if (sslEnabled) {
            port = settings.getSSLPort();
        }

        // Initialize the XMPP connection
        try {

            XMPPTCPConnectionConfiguration.Builder config = XMPPTCPConnectionConfiguration.builder()
               .setSecurityMode(XMPPTCPConnectionConfiguration.SecurityMode.disabled)
               .setXmppDomain(host)
               .setHost(host)
               .setPort(port);
            
            config.performSaslAnonymousAuthentication();
            
            xmppConn = new XMPPTCPConnection(config.build());
            xmppConn.connect();

            // Login the presence bot user
            xmppConn.login();

            // Add Connection to Application Object
            setGlobalConnection(xmppConn);

            // Add a connection listener.
            xmppConn.addConnectionListener(new ConnectionListener() {
                @Override
                public void connectionClosed() {
                    context.log("Main Connection closed for some reason");
                }

                @Override
                public void connectionClosedOnError(Exception e) {
                    context.log("Connection closed on Error", e);
                }


                @Override
                public void reconnectingIn(int i) {

                }

                @Override
                public void reconnectionSuccessful() {

                }

                @Override
                public void reconnectionFailed(Exception exception) {

                }

                @Override
                public void connected(XMPPConnection connection) {
                  
                }

                @Override
                public void authenticated(XMPPConnection connection, boolean resumed) {
                  
                }
            });
        }
        catch (Exception e) {
            context.log("Error creating connection to server.", e);
            return null;
        }
        finally {
            // If creating the connection failed this time around, make sure it is cleaned up.
            if (xmppConn != null && !xmppConn.isAuthenticated() && xmppConn.isConnected()) {
                try {
                    xmppConn.disconnect();
                }
                catch (Exception e) {
                    context.log("Error disconnecting from server.", e);
                }
            }
        }
        return xmppConn;
    }

    public boolean isConnected() {
        XMPPConnection con = getGlobalConnection();
        return con != null && (con.isConnected() && con.isAuthenticated());
    }
}
